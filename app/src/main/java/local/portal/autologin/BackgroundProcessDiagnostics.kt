package local.portal.autologin

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Numeric exit metadata only. Never export descriptions, traces or unrelated process data. */
internal object BackgroundProcessDiagnostics {
    fun recordExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30 || !DiagnosticLog.isEnabled()) return
        runCatching {
            val previous = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            DiagnosticLog.event("background.previous_exit_present", if (previous == null) 0 else 1)
            if (previous != null) {
                DiagnosticLog.event("background.previous_exit_reason", previous.reason)
                DiagnosticLog.event("background.previous_exit_importance", previous.importance)
                val age = ((System.currentTimeMillis() - previous.timestamp).coerceAtLeast(0) / 1000)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                DiagnosticLog.event("background.previous_exit_age_s", age)
            }
        }.onFailure { DiagnosticLog.failure(it) }
    }
}
