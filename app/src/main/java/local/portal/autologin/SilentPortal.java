package local.portal.autologin;

import android.content.*;
import android.net.*;
import android.os.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Event-driven observer in system_server. No main-thread HTTP or permanent polling. */
public final class SilentPortal {
    public static final String ACTION="local.portal.autologin.CONFIG_CHANGED";
    public static final String PERMISSION="local.portal.autologin.permission.CONFIGURE";
    public static final String LOG_CONFIG="local.portal.autologin.LOG_CONFIG";
    public static final String LOG_EXPORT="local.portal.autologin.LOG_EXPORT";
    public static final String LOG_CLEAR="local.portal.autologin.LOG_CLEAR";
    private static final String APP_BEGIN="local.portal.autologin.APP_LOGIN_BEGIN";
    private static final String APP_END="local.portal.autologin.APP_LOGIN_END";
    private static SilentPortal instance;
    private final ConnectivityManager cm;
    private final Handler handler;
    private int launchHookCount=-1;
    private long applicationLeaseUntil;
    // Hold the listener strongly: framework preference listeners may use weak references.
    private final SharedPreferences.OnSharedPreferenceChangeListener configListener;
    private final AtomicBoolean configChanged=new AtomicBoolean();
    private final Runnable configUpdate;
    private final LinkedHashMap<Network,Attempt> attempts=new LinkedHashMap<>();
    private final LinkedHashMap<Network,Integer> capabilityStates=new LinkedHashMap<>();
    private final ExecutorService worker=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"PortalAuth");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static class Config {
        boolean enabled; String user,pass;
    }
    private static class Attempt {
        final long start=SystemClock.elapsedRealtime();
        boolean active=true, posted;
    }
    private static Config config() {
        Config c=new Config();
        try {
            SharedPreferences p=ModuleRuntime.preferences();
            DiagnosticLog.setEnabled(p.getBoolean("debug_log",false));
            c.user=p.getString("username","");c.pass=p.getString("password","");
            c.enabled=p.getBoolean("enabled",false) && p.getBoolean("silent",false) &&
                !c.user.isEmpty() && !c.pass.isEmpty();
        } catch (Throwable ignored) { c.enabled=false; }
        return c;
    }
    private static void log(String s) { ModuleRuntime.log("silent "+s);DiagnosticLog.event(s,0); }
    private SilentPortal(Context context) {
        cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        HandlerThread thread=new HandlerThread("PortalNetworkObserver");thread.start();handler=new Handler(thread.getLooper());
        configUpdate=()->{
            config();
            if(configChanged.getAndSet(false)) configurationChanged();
        };
        configListener=(prefs,key)->{
            if(key==null || !"debug_log".equals(key)) configChanged.set(true);
            handler.removeCallbacks(configUpdate);
            handler.postDelayed(configUpdate,100);
        };
    }
    public static synchronized void start(Context context) {
        if (!DeviceCompatibility.supportsModule(Build.BRAND,Build.MANUFACTURER)) return;
        if (instance!=null) return;
        config();
        SilentPortal s=new SilentPortal(context);
        try { s.register(context);instance=s;log("observer_ready"); }
        catch (Throwable e) { s.handler.getLooper().quitSafely();s.worker.shutdownNow();log("observer_failed_"+e.getClass().getSimpleName()); }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void register(Context context) {
        IntentFilter filter=new IntentFilter(ACTION);
        filter.addAction(LOG_CONFIG);filter.addAction(LOG_EXPORT);filter.addAction(LOG_CLEAR);
        filter.addAction(APP_BEGIN);filter.addAction(APP_END);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent i) {
                config();
                if(APP_BEGIN.equals(i.getAction())) {
                    synchronized(attempts) {
                        Network requested=(Network)i.getParcelableExtra("network");
                        Attempt previous=attempts.get(requested);
                        if(previous!=null && previous.posted &&
                            SystemClock.elapsedRealtime()-previous.start<300000) {
                            setResultCode(11);return;
                        }
                        // A POST already started must finish; the app only waits for validation.
                        for(Attempt a:attempts.values()) if(a.active && a.posted) {
                            setResultCode(11);return;
                        }
                        applicationLeaseUntil=SystemClock.elapsedRealtime()+60000;
                        for(Attempt a:attempts.values()) a.active=false;
                    }
                    DiagnosticLog.event("app.claim_granted",0);setResultCode(10);return;
                }
                if(APP_END.equals(i.getAction())) {
                    synchronized(attempts) {
                        applicationLeaseUntil=0;
                        Network n=(Network)i.getParcelableExtra("network");
                        if(n!=null) {
                            Attempt a=new Attempt();a.active=false;a.posted=i.getBooleanExtra("submitted",false);
                            attempts.put(n,a);
                            if(attempts.size()>16) attempts.remove(attempts.keySet().iterator().next());
                        }
                    }
                    DiagnosticLog.event("app.claim_released",0);return;
                }
                if(LOG_EXPORT.equals(i.getAction())) {
                    setResultCode(-1);
                    synchronized(attempts) {
                        SharedPreferences saved=ModuleRuntime.preferences();
                        int flags=(saved.getBoolean("enabled",false)?1:0) |
                            (saved.getBoolean("silent",false)?2:0) |
                            (saved.getString("username","").isEmpty()?0:4) |
                            (saved.getString("password","").isEmpty()?0:8);
                        DiagnosticLog.event("config.native_flags",flags);
                        DiagnosticLog.event("network.active_vpn",activeVpn()?1:0);
                        setResultData("observer=ready\nlaunch_hooks="+launchHookCount+"\nattempt_records="+attempts.size()+"\n"+DiagnosticLog.snapshot());
                    }
                    return;
                }
                if(LOG_CLEAR.equals(i.getAction())) {DiagnosticLog.clear();setResultCode(-1);return;}
                if(LOG_CONFIG.equals(i.getAction())) {DiagnosticLog.event("config.log_updated",0);return;}
                DiagnosticLog.event("config.saved",0);
                synchronized(attempts) {
                    Iterator<Attempt> it=attempts.values().iterator();
                    while(it.hasNext()) {
                        Attempt a=it.next();
                        // Changing/saving configuration must not hide an already-started POST.
                        if(a.active && a.posted) continue;
                        a.active=false;it.remove();
                    }
                }
                scan();
            }
        },filter,PERMISSION,handler,Context.RECEIVER_EXPORTED);
        NetworkRequest request=new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(request,new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps) {
                if(!realWifi(caps)) return;
                int flags=(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)?1:0) |
                    (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?2:0);
                synchronized(attempts) {
                    Integer previous=capabilityStates.put(n,flags);
                    if(previous==null || previous!=flags) {
                        DiagnosticLog.event("network.id",n.hashCode());
                        DiagnosticLog.event("network.capabilities",flags);
                    }
                    if(capabilityStates.size()>16) capabilityStates.remove(capabilityStates.keySet().iterator().next());
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    synchronized(attempts) {
                        Attempt a=attempts.get(n);
                        if (a!=null && a.active) { a.active=false;log("validated"); }
                    }
                } else request(n);
            }
            @Override public void onLost(Network n) {
                DiagnosticLog.event("network.id",n.hashCode());
                DiagnosticLog.event("network.lost",0);
                synchronized(attempts) {
                    capabilityStates.remove(n);
                    Attempt a=attempts.remove(n);if(a!=null)a.active=false;
                }
            }
        },handler);
        hookPortalLaunch();
        ModuleRuntime.preferences().registerOnSharedPreferenceChangeListener(configListener);
        handler.post(this::scan);
    }
    private void configurationChanged() {
        DiagnosticLog.event("config.remote_updated",0);
        synchronized(attempts) {
            Iterator<Attempt> it=attempts.values().iterator();
            while(it.hasNext()) {
                Attempt a=it.next();
                if(a.active && a.posted) continue;
                a.active=false;it.remove();
            }
        }
        scan();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void scan() { try { DiagnosticLog.event("network.scan",0);for(Network n:cm.getAllNetworks()) request(n); } catch(Throwable e) {DiagnosticLog.failure(e);} }
    private static boolean realWifi(NetworkCapabilities caps) {
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private boolean activeVpn() {
        try {
            Network active=cm.getActiveNetwork();
            NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
            return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch(Throwable ignored) {return false;}
    }
    @android.annotation.SuppressLint("MissingPermission")
    private boolean captive(Network n) {
        try {
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            return realWifi(c) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) &&
                !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch(Throwable ignored) { return false; }
    }
    private boolean request(Network n) {
        if (!captive(n)) return false;
        DiagnosticLog.event("network.captive",1);
        Config c=config();
        if (!c.enabled) {DiagnosticLog.event("attempt.disabled_or_missing_config",0);return false;}
        final Attempt a;
        synchronized(attempts) {
            if(SystemClock.elapsedRealtime()<applicationLeaseUntil) return true;
            Attempt previous=attempts.get(n);
            long now=SystemClock.elapsedRealtime();
            if(previous!=null && now-previous.start<300000) {
                DiagnosticLog.event("attempt.cooldown",(int)((now-previous.start)/1000));
                return previous.active && now-previous.start<45000;
            }
            a=new Attempt();attempts.put(n,a);
            if(attempts.size()>16) {
                Iterator<Map.Entry<Network,Attempt>> it=attempts.entrySet().iterator();
                Map.Entry<Network,Attempt> first=it.next();first.getValue().active=false;it.remove();
            }
        }
        try { worker.execute(()->authenticate(n,a,c)); }
        catch(RejectedExecutionException e) { synchronized(attempts) {a.active=false;} DiagnosticLog.event("attempt.queue_full",0);return false; }
        // One deadline per authentication event, not a periodic monitor.
        handler.postDelayed(()->{
            synchronized(attempts) { if(a.active) {a.active=false;log("not_validated_fallback");} }
        },45000);
        return true;
    }
    private boolean allowed(Network n,Attempt a) {
        synchronized(attempts) { if(!a.active || attempts.get(n)!=a || SystemClock.elapsedRealtime()-a.start>=30000) return false; }
        return captive(n) && config().enabled;
    }
    @android.annotation.SuppressLint("MissingPermission") // Runs under the system_server UID.
    private void authenticate(Network n,Attempt a,Config c) {
        DiagnosticLog.event("attempt.network",n.hashCode());
        log("attempt_started");
        try {
            new PortalHttpClient(new NetworkPortalTransport(n),()->allowed(n,a),(stage,detail)->{
                DiagnosticLog.event(stage,detail);
                if("submit.write_begin".equals(stage)) synchronized(attempts) {
                    if(!a.active || SystemClock.elapsedRealtime()<applicationLeaseUntil)
                        throw new IllegalStateException("attempt_cancelled");
                    a.posted=true;
                }
            }).login(c.user,c.pass);
            synchronized(attempts) {a.posted=true;}
            log("submitted_waiting_validation");
            requestValidation(n,a);
            verifyInternet(n,a);
        } catch(PortalHttpClient.SubmissionUncertainException e) {
            // A closed response is not an auth rejection. Check the same network without reposting.
            DiagnosticLog.failure(e);
            DiagnosticLog.event("submission.uncertain_waiting_validation",0);
            requestValidation(n,a);
            verifyInternet(n,a);
        } catch(Throwable e) {
            synchronized(attempts) {
                a.active=false;
                if("login_form_returned".equals(e.getMessage()) || "authentication_rejected".equals(e.getMessage()))
                    a.posted=false;
            }
            log("fallback_"+e.getClass().getSimpleName());
            DiagnosticLog.failure(e);
        } finally {c.user=null;c.pass=null;}
    }
    @android.annotation.SuppressLint("MissingPermission") // Runs once on the authentication worker.
    private void verifyInternet(Network n,Attempt a) {
        DiagnosticLog.event("validation.internet_network",n.hashCode());
        InternetCheck.Result result=new InternetCheck(
            url->(java.net.HttpURLConnection)n.openConnection(url),
            ()->{
                synchronized(attempts) {
                    if(!a.active || attempts.get(n)!=a || SystemClock.elapsedRealtime()-a.start>=45000) return false;
                }
                try {return realWifi(cm.getNetworkCapabilities(n));}
                catch(Throwable ignored) {return false;}
            },(stage,detail)->DiagnosticLog.event("validation."+stage,detail)).run();
        DiagnosticLog.event("validation.internet_result",result.ordinal());
        // This is additional evidence, not permission to resend a POST or switch to mobile data.
        if(result==InternetCheck.Result.ONLINE) requestValidation(n,a);
    }
    @android.annotation.SuppressLint("MissingPermission") // Runs under the system_server UID.
    private void requestValidation(Network n,Attempt a) {
        synchronized(attempts) {if(attempts.get(n)!=a || !a.active)return;}
        try {
            cm.reportNetworkConnectivity(n,true);
            DiagnosticLog.event("validation.requested",0);
        } catch(Throwable e) {
            synchronized(attempts) {a.active=false;}
            DiagnosticLog.event("validation.request_failed",0);DiagnosticLog.failure(e);
        }
    }
    private void hookPortalLaunch() {
        try {
            Class<?> manager=Class.forName("android.os.ServiceManager");
            Method getService=manager.getDeclaredMethod("getService",String.class);getService.setAccessible(true);
            Object service=getService.invoke(null,"connectivity");
            if(service==null || service.getClass().getName().equals("android.os.BinderProxy")) {log("launch_hook_unavailable");return;}
            int count=0;
            for(Method m:service.getClass().getDeclaredMethods()) {
                if(!Arrays.asList("startCaptivePortalApp","startCaptivePortalAppInternal").contains(m.getName()) || m.getReturnType()!=void.class) continue;
                if(!Arrays.asList(m.getParameterTypes()).contains(Network.class)) continue;
                ModuleRuntime.framework().hook(m).intercept(chain -> {
                    try {for(Object arg:chain.getArgs()) if(arg instanceof Network && request((Network)arg)) {
                        DiagnosticLog.event("portal.launch_suppressed",0);return null;
                    }}
                    catch(Throwable ignored) { /* Keep the system's normal login path. */ }
                    return chain.proceed();
                });count++;
            }
            launchHookCount=count;log("launch_hooks_"+count);
        } catch(Throwable e) {log("launch_hook_unavailable_"+e.getClass().getSimpleName());}
    }
}
