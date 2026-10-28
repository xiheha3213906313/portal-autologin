package local.portal.autologin;

import android.content.*;
import android.net.*;
import android.os.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import de.robv.android.xposed.*;

/** Event-driven observer in system_server. No main-thread HTTP or permanent polling. */
public final class SilentPortal {
    public static final String ACTION="local.portal.autologin.CONFIG_CHANGED";
    public static final String PERMISSION="local.portal.autologin.permission.CONFIGURE";
    public static final String LOG_CONFIG="local.portal.autologin.LOG_CONFIG";
    public static final String LOG_EXPORT="local.portal.autologin.LOG_EXPORT";
    public static final String LOG_CLEAR="local.portal.autologin.LOG_CLEAR";
    private static final String MODULE="local.portal.autologin";
    private static SilentPortal instance;
    private final ConnectivityManager cm;
    private final Handler handler;
    private int launchHookCount=-1;
    private final LinkedHashMap<Network,Attempt> attempts=new LinkedHashMap<>();
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
            XSharedPreferences p=new XSharedPreferences(MODULE,"portal");p.reload();
            DiagnosticLog.setEnabled(p.getBoolean("debug_log",false));
            c.user=p.getString("username","");c.pass=p.getString("password","");
            c.enabled=p.getBoolean("enabled",false) && p.getBoolean("silent",false) &&
                !c.user.isEmpty() && !c.pass.isEmpty();
        } catch (Throwable ignored) { c.enabled=false; }
        return c;
    }
    private static void log(String s) { XposedBridge.log("PortalAuto: silent "+s);DiagnosticLog.event(s,0); }
    private SilentPortal(Context context) {
        cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        HandlerThread thread=new HandlerThread("PortalNetworkObserver");thread.start();handler=new Handler(thread.getLooper());
    }
    public static synchronized void start(Context context) {
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
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent i) {
                config();
                if(LOG_EXPORT.equals(i.getAction())) {
                    setResultCode(-1);
                    synchronized(attempts) {
                        setResultData("observer=ready\nlaunch_hooks="+launchHookCount+"\nattempt_records="+attempts.size()+"\n"+DiagnosticLog.snapshot());
                    }
                    return;
                }
                if(LOG_CLEAR.equals(i.getAction())) {DiagnosticLog.clear();setResultCode(-1);return;}
                if(LOG_CONFIG.equals(i.getAction())) {DiagnosticLog.event("config.log_updated",0);return;}
                DiagnosticLog.event("config.saved",0);
                synchronized(attempts) {
                    for (Attempt a:attempts.values()) a.active=false;
                    attempts.clear();
                }
                scan();
            }
        },filter,PERMISSION,handler,Context.RECEIVER_EXPORTED);
        NetworkRequest request=new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(request,new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps) {
                DiagnosticLog.event("network.capabilities",(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)?1:0) |
                    (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?2:0));
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    synchronized(attempts) {
                        Attempt a=attempts.get(n);
                        if (a!=null && a.active) { a.active=false;log("validated"); }
                    }
                } else request(n);
            }
            @Override public void onLost(Network n) {
                DiagnosticLog.event("network.lost",0);
                synchronized(attempts) { Attempt a=attempts.remove(n);if(a!=null)a.active=false; }
            }
        },handler);
        hookPortalLaunch();
        handler.post(this::scan);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void scan() { try { DiagnosticLog.event("network.scan",0);for(Network n:cm.getAllNetworks()) request(n); } catch(Throwable e) {DiagnosticLog.failure(e);} }
    @android.annotation.SuppressLint("MissingPermission")
    private boolean captive(Network n) {
        try {
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            return c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
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
        log("attempt_started");
        try {
            new PortalHttpClient(url->(java.net.HttpURLConnection)n.openConnection(url),()->allowed(n,a),DiagnosticLog::event).login(c.user,c.pass);
            synchronized(attempts) {a.posted=true;}
            log("submitted_waiting_validation");
            requestValidation(n,a);
        } catch(PortalHttpClient.SubmissionUncertainException e) {
            // A closed response is not an auth rejection. Check the same network without reposting.
            DiagnosticLog.failure(e);
            DiagnosticLog.event("submission.uncertain_waiting_validation",0);
            requestValidation(n,a);
        } catch(Throwable e) {
            synchronized(attempts) {a.active=false;}
            log("fallback_"+e.getClass().getSimpleName());
            DiagnosticLog.failure(e);
        } finally {c.user=null;c.pass=null;}
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
            Object service=XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.ServiceManager",null),"getService","connectivity");
            if(service==null || service.getClass().getName().equals("android.os.BinderProxy")) {log("launch_hook_unavailable");return;}
            int count=0;
            for(Method m:service.getClass().getDeclaredMethods()) {
                if(!Arrays.asList("startCaptivePortalApp","startCaptivePortalAppInternal").contains(m.getName()) || m.getReturnType()!=void.class) continue;
                if(!Arrays.asList(m.getParameterTypes()).contains(Network.class)) continue;
                XposedBridge.hookMethod(m,new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {for(Object arg:p.args) if(arg instanceof Network && request((Network)arg)) {
                            DiagnosticLog.event("portal.launch_suppressed",0);p.setResult(null);return;
                        }}
                        catch(Throwable ignored) { /* Keep the system's normal login path. */ }
                    }
                });count++;
            }
            launchHookCount=count;log("launch_hooks_"+count);
        } catch(Throwable e) {log("launch_hook_unavailable_"+e.getClass().getSimpleName());}
    }
}
