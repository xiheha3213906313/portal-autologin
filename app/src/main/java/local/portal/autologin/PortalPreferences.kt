package local.portal.autologin

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** Shared by Activity and service, including when the process starts without an Activity. */
internal data class PortalPreferences(val prefs: SharedPreferences, val moduleAvailable: Boolean) {
    val moduleActive: Boolean get() = moduleAvailable && DeviceCompatibility.supportsModule(Build.BRAND, Build.MANUFACTURER)
    companion object {
        private val availability = ModuleAvailability()
        @Suppress("DEPRECATION")
        fun open(context: Context): PortalPreferences {
            val app = context.applicationContext
            val moduleAvailable = availability.detect { app.getSharedPreferences("portal", Context.MODE_WORLD_READABLE) }
            val mode = if (moduleAvailable) Context.MODE_WORLD_READABLE else Context.MODE_PRIVATE
            return PortalPreferences(UnifiedPreferences.open(app,"portal",mode), moduleAvailable)
        }
    }
}
