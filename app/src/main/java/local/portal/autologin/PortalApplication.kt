package local.portal.autologin

import android.app.Application

class PortalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleBridge.initialize(this)
    }
}
