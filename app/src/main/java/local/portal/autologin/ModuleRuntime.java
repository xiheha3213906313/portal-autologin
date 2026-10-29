package local.portal.autologin;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;

/** Only loaded by the framework in scoped host processes, never by the ordinary app. */
final class ModuleRuntime {
    private static XposedInterface framework;
    private static SharedPreferences preferences;

    static void install(XposedInterface value) { framework=value; }
    static XposedInterface framework() {
        if(framework==null) throw new IllegalStateException("module_not_loaded");
        return framework;
    }
    static synchronized SharedPreferences preferences() {
        if(preferences==null) preferences=framework().getRemotePreferences("portal");
        return preferences;
    }
    static void log(String message) { framework().log(Log.INFO,"PortalAuto",message); }
}
