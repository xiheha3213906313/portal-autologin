package local.portal.autologin

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** Shared by Activity and service, including when the process starts without an Activity. */
internal data class PortalPreferences(val prefs: SharedPreferences, val moduleAvailable: Boolean) {
    val moduleActive: Boolean get() = moduleAvailable && DeviceCompatibility.supportsModule(Build.BRAND, Build.MANUFACTURER)
    companion object {
        fun open(context: Context): PortalPreferences {
            val app = context.applicationContext
            return PortalPreferences(UnifiedPreferences.open(app,"portal"), ModuleBridge.available)
        }
    }
}
