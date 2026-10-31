package local.portal.autologin

import android.app.Application

class PortalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.attachJournal(java.io.File(filesDir, "diagnostics"))
        val prefs = PortalPreferences.open(this).prefs
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        DiagnosticLog.event("app.process_started", android.os.Process.myPid())
        DiagnosticLog.event("app.diagnostic_revision", 3)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagnosticLog.crash(error, thread == android.os.Looper.getMainLooper().thread) }
            previousHandler?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        ModuleBridge.initialize(this)
        BackgroundProcessDiagnostics.recordExit(this)
    }

    override fun onTrimMemory(level: Int) {
        DiagnosticLog.event("app.trim_memory", level)
        super.onTrimMemory(level)
    }
}
