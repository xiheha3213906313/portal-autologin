package local.portal.autologin;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PortalHook implements IXposedHookLoadPackage {
    private static final String MODULE = "local.portal.autologin";
    private static final String ACTIVITY = "com.android.captiveportallogin.CaptivePortalLoginActivity";
    private static final Map<Activity, Session> sessions = new WeakHashMap<>();
    private static final Map<Class<?>, Method> evalMethods = new HashMap<>();
    private static long nextAttempt;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if ("android".equals(p.packageName) && ("android".equals(p.processName) || "system_server".equals(p.processName))) {
            try {
                Class<?> server=XposedHelpers.findClass("com.android.server.SystemServer",p.classLoader);
                XposedBridge.hookAllMethods(server,"startOtherServices",new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object thread=XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread",null),"currentActivityThread");
                            android.content.Context context=(android.content.Context)XposedHelpers.callMethod(thread,"getSystemContext");
                            SilentPortal.start(context);
                        } catch(Throwable e) {logError("silent_start",e);}
                    }
                });
            } catch(Throwable e) {logError("system_hook",e);}
            return;
        }
        if (!"com.android.browser".equals(p.packageName)) return;
        XposedBridge.log("PortalAuto: browser hook loaded");
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity)param.thisObject;
                if (!ACTIVITY.equals(a.getClass().getName())) return;
                try {
                    Session s = sessions.get(a);
                    if (s == null) { s = new Session(a); sessions.put(a, s); }
                    s.resume();
                } catch (Throwable e) { logError("resume", e); }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Session s = sessions.get((Activity)param.thisObject);
                if (s != null) s.pause();
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Session s = sessions.remove((Activity)param.thisObject);
                if (s != null) s.pause();
            }
        });
    }

    private static void logError(String where, Throwable e) {
        // Never log JS source, credentials, URL parameters or exception messages.
        XposedBridge.log("PortalAuto: " + where + " " + e.getClass().getSimpleName());
    }

    private static Method evaluator(View v) {
        Class<?> cls = v.getClass();
        if (evalMethods.containsKey(cls)) return evalMethods.get(cls);
        Method found = null;
        for (Method m : cls.getMethods()) {
            Class<?>[] args = m.getParameterTypes();
            if (m.getName().equals("evaluateJavascript") && args.length == 2 &&
                    args[0] == String.class && args[1].isInterface()) { found = m; break; }
        }
        evalMethods.put(cls, found);
        return found;
    }

    private static void collect(View v, ArrayList<View> out, int depth) {
        if (v == null || depth > 40 || !v.isShown()) return;
        if (evaluator(v) != null) { out.add(v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup)v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out, depth + 1);
        }
    }

    private static class Session implements Runnable {
        final Activity activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Set<String> messages = new HashSet<>();
        boolean active, attempting, busy;
        long deadline, busyAt;
        int viewIndex;
        String script;

        Session(Activity a) { activity = a; }

        void resume() {
            pause();
            try {
                XSharedPreferences prefs = new XSharedPreferences(MODULE, "portal");
                prefs.reload();
                if (!prefs.getBoolean("enabled", false)) {
                    XposedBridge.log("PortalAuto: disabled or configuration unavailable"); return;
                }
                // Silent mode controls the background attempt, not the foreground fallback.
                // Standard portal launches are gated in system_server while that attempt is active.
                String user = prefs.getString("username", "");
                String pass = prefs.getString("password", "");
                if (user.isEmpty() || pass.isEmpty()) return;
                script = LoginScript.CODE.replace("__FAST__", prefs.getBoolean("fast", true) ? "true" : "false")
                    .replace("__CREDENTIALS__", new JSONArray().put(user).put(pass).toString());
                active = true;
                deadline = SystemClock.elapsedRealtime() + 120000;
                handler.postDelayed(this, 150);
                XposedBridge.log("PortalAuto: portal resumed");
            } catch (Throwable e) { logError("configuration", e); }
        }

        void pause() {
            active = false; busy = false; script = null;
            handler.removeCallbacks(this);
        }

        // Lint reads the module manifest; this hook executes under the browser UID.
        // Its permission is checked below, and unavailable access returns false.
        @android.annotation.SuppressLint("MissingPermission")
        private boolean validated() {
            try {
                // This method runs in the browser process and uses its permission grant.
                if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
                Network n = activity.getIntent().getParcelableExtra("android.net.extra.NETWORK");
                if (n == null) return false;
                ConnectivityManager cm = (ConnectivityManager)activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            } catch (Throwable ignored) { return false; }
        }

        void message(String key, String text) {
            if (messages.add(key)) {
                XposedBridge.log("PortalAuto: " + key);
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
            }
        }

        void status(String value) {
            if (!active) return;
            if (value.equals("typing")) {
                if (!attempting) {
                    attempting = true;
                    nextAttempt = SystemClock.elapsedRealtime() + 300000;
                    message("typing", "正在自动填写，请保持登录页在前台");
                }
            } else if (value.equals("clicked")) {
                message("clicked", "已提交登录，等待 Wi-Fi 联网验证");
            } else if (value.startsWith("manual_") || value.equals("form_changed") ||
                       value.equals("input_cancelled") || value.equals("script_error") || value.equals("paused")) {
                message(value, "自动登录已暂停，请检查页面并手动完成登录");
                pause();
            }
        }

        @Override public void run() {
            if (!active || activity.isFinishing() || activity.isDestroyed()) { pause(); return; }
            long now = SystemClock.elapsedRealtime();
            if (validated()) {
                if (attempting) message("validated", "此 Wi-Fi 已通过系统联网验证");
                pause(); return;
            }
            if (now > deadline) { message("timeout", "未确认自动登录成功，请检查门户页面"); pause(); return; }
            if (!attempting && now < nextAttempt) {
                message("cooldown", "自动登录处于 5 分钟冷却期，可手动登录"); pause(); return;
            }
            if (busy && now - busyAt > 10000) {
                message("callback_timeout", "页面状态暂不可读，请查看联网状态；不会连续重试"); pause(); return;
            }
            if (!busy) {
                try {
                    ArrayList<View> views = new ArrayList<>();
                    collect(activity.getWindow().getDecorView(), views, 0);
                    if (!views.isEmpty()) {
                        View v = views.get((viewIndex++) % views.size());
                        Method m = evaluator(v);
                        Class<?> cb = m.getParameterTypes()[1];
                        Object callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, (proxy, method, args) -> {
                            if (method.getName().equals("onReceiveValue")) {
                                handler.post(() -> {
                                    busy = false;
                                    try {
                                        Object result = new JSONTokener(String.valueOf(args[0])).nextValue();
                                        if (result instanceof String) status((String)result);
                                    } catch (Throwable e) { logError("callback", e); }
                                });
                            } else if (method.getName().equals("toString")) return "PortalCallback";
                            else if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            else if (method.getName().equals("equals")) return proxy == args[0];
                            return null;
                        });
                        busy = true; busyAt = now;
                        m.invoke(v, script.replace("__ALLOW_NEW__", attempting ? "false" : "true"), callback);
                    }
                } catch (Throwable e) { logError("evaluate", e); pause(); return; }
            }
            if (active) handler.postDelayed(this, attempting ? 1000 : 400);
        }
    }
}
