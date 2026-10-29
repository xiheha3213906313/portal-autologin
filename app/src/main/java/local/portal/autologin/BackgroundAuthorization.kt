package local.portal.autologin

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** Only public, verifiable grants are reported as granted. OEM auto-start has no common API. */
internal object BackgroundAuthorization {
    fun requiredGranted(context: Context, needsWifiName: Boolean): Boolean =
        notificationsVisible(context) &&
            (!needsWifiName || (fineLocation(context) && locationEnabled(context)))
    fun notificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsVisible(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return notificationPermission(context) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(BackgroundLoginService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun fineLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun accessibilityEnabled(context: Context): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == ComponentName(context, LoginRecoveryService::class.java)
            }

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    fun notificationSettings(context: Context): List<Intent> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelBlocked = manager.getNotificationChannel(BackgroundLoginService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        return buildList {
            if (channelBlocked) add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, BackgroundLoginService.CHANNEL))
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(appDetails(context))
        }
    }

    fun accessibilitySettings(context: Context): List<Intent> = listOf(
        // Some systems expose a per-service page; fall back to the public list if unavailable.
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME,
            ComponentName(context, LoginRecoveryService::class.java).flattenToString()),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(context))

    fun batterySettings(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context))

    fun autoStartSettings(context: Context): List<Intent> {
        val family = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase(java.util.Locale.ROOT)
        val components = when {
            DeviceCompatibility.supportsModule(Build.BRAND, Build.MANUFACTURER) -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            "vivo" in family || "iqoo" in family -> listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            "oppo" in family || "realme" in family || "oneplus" in family -> listOf(
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity")
            "huawei" in family || "honor" in family -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            else -> emptyList()
        }
        return components.mapNotNull { ComponentName.unflattenFromString(it)?.let { component -> Intent().setComponent(component) } } + appDetails(context)
    }
}
