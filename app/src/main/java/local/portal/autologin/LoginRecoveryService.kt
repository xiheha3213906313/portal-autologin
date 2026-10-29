package local.portal.autologin

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Optional recovery on system binding only. No window events, screen access, gestures or polling. */
class LoginRecoveryService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagnosticLog.event("background.recovery_connected", 0)
        // Do not reconfigure an already running service or reset its per-network attempt guard.
        if (!BackgroundLoginService.running) BackgroundLoginService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
