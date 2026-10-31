package local.portal.autologin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Opt-in, event-driven ordinary login. No automatic Activity launch or recurring polling. */
class BackgroundLoginService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val gate = BackgroundNetworkGate()
    private val networks = mutableMapOf<Network, Snapshot>()
    private lateinit var cm: ConnectivityManager
    private lateinit var prefs: SharedPreferences
    private lateinit var login: AppLoginController
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var watching: Network? = null
    private var filter = ""
    private var namesAvailable = false
    private var activeConfiguration: List<Any>? = null
    private var generation = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus = ""
    private var notificationReceiverRegistered = false
    private var restoreScheduled = false
    private var lastNotificationRestore = -3000L
    private val restoreNotification = Runnable {
        restoreScheduled = false
        DiagnosticLog.event("background.notification_restore_run", notificationRestoreState())
        // A dismissal is not permission to start a stopped service or authenticate again.
        if (canRestoreNotification() && !notificationPresent(this)) {
            runCatching {
                showStatus(lastStatus.ifEmpty { "等待连接 Wi-Fi" }, refresh = true)
                lastNotificationRestore = SystemClock.elapsedRealtime()
                DiagnosticLog.event("background.notification_restored", 1)
            }.onFailure { DiagnosticLog.failure(it) }
        }
    }
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NOTIFICATION_DISMISSED) return
            DiagnosticLog.event("background.notification_dismissed", if (running) 1 else 0)
            reconcileNotification(0)
        }
    }
    private class Snapshot(var caps: NetworkCapabilities? = null, var links: LinkProperties? = null,
                           var scheduled: Runnable? = null)
    private val stateListener: (AppLoginController.UiState) -> Unit = { state ->
        DiagnosticLog.event("background.controller_state", state.stage.ordinal)
        DiagnosticLog.event("background.controller_confirmed", if (state.internetConfirmed) 1 else 0)
        if (watching != null && login.checkedNetwork != watching) {
            // Foreground login takes priority. Revisit this connection after it finishes; the
            // controller's persisted submission guard prevents replaying an uncertain POST.
            gate.lost(watching!!.networkHandle)
            watching = null
            releaseWakeLock()
            showStatus("等待检查")
        }
        val network = watching
        val confirmed = state.stage == AppLoginController.Stage.SUCCESS && state.internetConfirmed
        // Fresh online proof may restore a previously failed connection. Only a failure from a
        // check actually owned by this service may consume its pending network event.
        if (network != null && login.checkedNetwork == network && (gate.isRunning(network.networkHandle) || confirmed)) {
            when {
                state.stage == AppLoginController.Stage.CONNECTING -> showStatus(
                    if (state.message == "正在确认联网状态") "确认联网中" else "登录中")
                state.stage == AppLoginController.Stage.SUCCESS && state.internetConfirmed -> {
                    gate.completed(network.networkHandle, true)
                    releaseWakeLock()
                    showStatus("已联网")
                }
                state.stage == AppLoginController.Stage.ERROR -> {
                    gate.completed(network.networkHandle, false)
                    releaseWakeLock()
                    showStatus(if (state.authenticationRejected) "登录失败" else "未确认联网")
                }
            }
        }
        if (state.stage == AppLoginController.Stage.ERROR ||
            (state.stage == AppLoginController.Stage.SUCCESS && state.internetConfirmed)) {
            val token = generation
            main.post { if (running && token == generation) networks.keys.toList().forEach(::schedule) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        DiagnosticLog.event("background.service_bound", if (running) 1 else 0)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.event("background.service_created", 0)
        cm = getSystemService(ConnectivityManager::class.java)
        login = AppLoginController.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "后台自动登录", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        runCatching {
            ContextCompat.registerReceiver(this, notificationReceiver, IntentFilter(NOTIFICATION_DISMISSED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            notificationReceiverRegistered = true
            DiagnosticLog.event("background.notification_receiver_ready", 1)
        }.onFailure {
            DiagnosticLog.event("background.notification_receiver_failed", 0)
            DiagnosticLog.failure(it)
        }
        login.addListener(stateListener)
        // Some OEM restores create a service and leave onStartCommand pending. Restore the
        // opted-in monitor here too; a later command takes the unchanged-configuration branch.
        DiagnosticLog.event("background.create_restore", 0)
        configureMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.event("background.start_command", if (intent == null) 0 else 1)
        DiagnosticLog.event("background.start_flags", flags)
        DiagnosticLog.event("background.start_id", startId)
        return configureMonitor()
    }

    private fun configureMonitor(): Int {
        val configuration = PortalPreferences.open(this)
        prefs = configuration.prefs
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        DiagnosticLog.event("background.configure_begin", if (running) 1 else 0)
        starting = false
        val readiness = startReadiness(this, configuration)
        DiagnosticLog.event("background.configure_readiness", readiness.ordinal)
        if (readiness != BackgroundStartPolicy.Status.READY) {
            notice = explanation(readiness)
            DiagnosticLog.event("background.start_blocked", readiness.ordinal)
            stopSelf()
            return START_NOT_STICKY
        }
        filter = prefs.getString("background_wifi", "").orEmpty()
        login.initialize(prefs, configuration.moduleActive)
        val readyForNames = filter.isBlank() || canReadWifiName(this)
        val configurationKey = listOf(prefs.getString("username", "").orEmpty(),
            prefs.getString("password", "").orEmpty(), filter, prefs.getBoolean("force_login", false))
        if (running && callback != null && activeConfiguration == configurationKey && namesAvailable == readyForNames) {
            // Returning from authorization settings must not reset connection guards or resubmit.
            DiagnosticLog.event("background.configure_unchanged", generation)
            showStatus(lastStatus.ifEmpty { "等待连接 Wi-Fi" }, refresh = true)
            return START_STICKY
        }
        namesAvailable = readyForNames
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (filter.isNotBlank() && namesAvailable && Build.VERSION.SDK_INT >= 29)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            DiagnosticLog.event("background.foreground_begin", types)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("等待连接 Wi-Fi"), types)
            else startForeground(NOTIFICATION, notification("等待连接 Wi-Fi"))
            DiagnosticLog.event("background.foreground_ready", types)
        } catch (e: Exception) {
            DiagnosticLog.event("background.foreground_failed", types)
            DiagnosticLog.failure(e)
            // A system restart may lack while-in-use permission. Keep matching paused until the
            // user reopens the app; never silently turn a named filter into an all-network rule.
            namesAvailable = false
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification("等待授权"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification("等待授权"))
            } catch (failure: Exception) {
                DiagnosticLog.event("background.foreground_fallback_failed", 0)
                DiagnosticLog.failure(failure)
                notice = "后台服务启动失败，请重新打开软件"
                stopSelf()
                return START_NOT_STICKY
            }
        }
        running = true
        DiagnosticLog.event("background.names_available", if (namesAvailable) 1 else 0)
        activeConfiguration = configurationKey
        notice = if (filter.isNotBlank() && !namesAvailable) "请打开软件，允许读取 Wi-Fi 名称" else null
        login.attachBackground()
        unregisterNetworks()
        releaseWakeLock()
        gate.clear()
        watching = null
        lastStatus = ""
        showStatus(if (notice != null) "等待授权" else "等待连接 Wi-Fi")
        try {
            callback = networkCallback()
            DiagnosticLog.event("background.callback_register_begin", generation)
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback!!, main)
            DiagnosticLog.event("background.callback_registered", generation)
            val existing = cm.allNetworks
            DiagnosticLog.event("background.scan_count", existing.size)
            for (network in existing) {
                DiagnosticLog.event("background.scan_network", network.hashCode())
                val caps = cm.getNetworkCapabilities(network) ?: continue
                DiagnosticLog.event("background.scan_transports", transportFlags(caps))
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                networks[network] = Snapshot(caps, cm.getLinkProperties(network))
                observeCapabilities(network, caps)
                schedule(network)
            }
            DiagnosticLog.event("background.started", if (filter.isBlank()) 0 else 1)
        } catch (e: Exception) {
            DiagnosticLog.event("background.monitor_failed", generation)
            DiagnosticLog.failure(e)
            notice = "无法监听 Wi-Fi，请重新打开软件"
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun networkCallback(): ConnectivityManager.NetworkCallback {
        val token = generation
        return if (Build.VERSION.SDK_INT >= 31 && filter.isNotBlank() && namesAvailable) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(n: Network) { if (token == generation) available(n) }
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { if (token == generation) capabilities(n, caps) }
                override fun onLinkPropertiesChanged(n: Network, links: LinkProperties) { if (token == generation) linkPropertiesChanged(n, links) }
                override fun onLost(n: Network) { if (token == generation) lost(n) }
            }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { if (token == generation) available(n) }
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { if (token == generation) capabilities(n, caps) }
            override fun onLinkPropertiesChanged(n: Network, links: LinkProperties) { if (token == generation) linkPropertiesChanged(n, links) }
            override fun onLost(n: Network) { if (token == generation) lost(n) }
        }
    }

    private fun available(network: Network) {
        networks.getOrPut(network) { Snapshot() }
        DiagnosticLog.event("background.network_id", network.hashCode())
        DiagnosticLog.event("background.network_available", 0)
        reconcileNotification(1)
        schedule(network)
    }
    private fun observeCapabilities(network: Network, caps: NetworkCapabilities) {
        val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        gate.observe(network.networkHandle, captive, validated)
        DiagnosticLog.event("background.network_capabilities", (if (validated) 2 else 0) or (if (captive) 1 else 0))
    }
    private fun capabilities(network: Network, caps: NetworkCapabilities) {
        val snapshot = networks.getOrPut(network) { Snapshot() }
        val wasValidated = snapshot.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        snapshot.caps = caps
        DiagnosticLog.event("background.network_id", network.hashCode())
        DiagnosticLog.event("background.network_transports", transportFlags(caps))
        observeCapabilities(network, caps)
        // Fresh system validation after a timeout may precede the app's final internet proof.
        // Recover the display with credential-free probes only, bounded across flag flapping.
        if (!wasValidated && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            watching == network && login.canBackgroundCheck(network) &&
            gate.beginOnlineRecovery(network.networkHandle, SystemClock.elapsedRealtime())) {
            DiagnosticLog.event("background.online_recovery", network.hashCode())
            acquireWakeLock()
            if (!login.backgroundVerifyOnly(network)) {
                gate.completed(network.networkHandle, false)
                releaseWakeLock()
            }
        }
        reconcileNotification(2)
        schedule(network)
    }
    private fun linkPropertiesChanged(network: Network, links: LinkProperties) {
        networks.getOrPut(network) { Snapshot() }.links = links
        DiagnosticLog.event("background.network_id", network.hashCode())
        DiagnosticLog.event("background.link_ready", (if (links.linkAddresses.isNotEmpty()) 1 else 0) or
            (if (links.dnsServers.isNotEmpty()) 2 else 0))
        reconcileNotification(3)
        schedule(network)
    }
    private fun lost(network: Network) {
        DiagnosticLog.event("background.network_id", network.hashCode())
        DiagnosticLog.event("background.network_lost", 0)
        networks.remove(network)?.scheduled?.let { main.removeCallbacks(it) }
        gate.lost(network.networkHandle)
        if (watching == network) {
            watching = null
            releaseWakeLock()
            showStatus("等待连接 Wi-Fi")
        }
    }

    private fun schedule(network: Network, delay: Long = 800) {
        val snapshot = networks[network] ?: return
        snapshot.scheduled?.let { main.removeCallbacks(it) }
        DiagnosticLog.event("background.check_scheduled", delay.toInt())
        snapshot.scheduled = Runnable { checkNetwork(network) }.also { main.postDelayed(it, delay) }
    }

    @Suppress("DEPRECATION")
    private fun ssid(network: Network, caps: NetworkCapabilities): String? {
        if (!namesAvailable || !canReadWifiName(this)) return null
        if (Build.VERSION.SDK_INT >= 31) return (caps.transportInfo as? WifiInfo)?.ssid
        // getConnectionInfo describes the primary Wi-Fi. Do not use it to identify a different
        // simultaneous Wi-Fi network on older Android versions.
        val wifiCount = cm.allNetworks.count { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifiCount != 1 && cm.activeNetwork != network) return null
        return applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
    }

    private fun checkNetwork(network: Network) {
        DiagnosticLog.event("background.check_network", network.hashCode())
        if (!prefs.getBoolean("background_login", false) || prefs.getString("background_wifi", "").orEmpty() != filter) {
            DiagnosticLog.event("background.config_changed_skip", 0); return
        }
        val liveCaps = cm.getNetworkCapabilities(network) ?: run { lost(network); return }
        val snapshot = networks[network] ?: return
        val caps = snapshot.caps ?: run { DiagnosticLog.event("background.waiting_capabilities", 0); return }
        val links = snapshot.links ?: run { DiagnosticLog.event("background.waiting_link", 0); return }
        if (!liveCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || liveCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !liveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            DiagnosticLog.event("background.network_unsuitable", 0); return
        }
        if (links.linkAddresses.isEmpty()) { DiagnosticLog.event("background.waiting_address", 0); return }
        if (links.dnsServers.isEmpty()) { DiagnosticLog.event("background.waiting_dns", 0); return }
        // Keep the callback's unredacted SSID, but use the current system capabilities for signals.
        gate.observe(network.networkHandle, liveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            liveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        val matches = runCatching { WifiNameMatcher.matches(filter, if (filter.isBlank()) null else ssid(network, caps)) }
            .onFailure { DiagnosticLog.failure(it) }.getOrDefault(false)
        if (!matches) {
            DiagnosticLog.event("background.filter_skipped", 0)
            if (watching == null) showStatus(if (filter.isNotBlank() && !canReadWifiName(this)) "等待授权" else "等待匹配 Wi-Fi")
            return
        }
        if (!login.canBackgroundCheck(network)) { DiagnosticLog.event("background.controller_wait", 0); return }
        val now = SystemClock.elapsedRealtime()
        val delay = gate.nextCheckDelay(network.networkHandle, now)
        if (delay > 0) {
            DiagnosticLog.event("background.recheck_deferred", delay.toInt())
            schedule(network, delay)
            return
        }
        val recheck = gate.wasChecked(network.networkHandle)
        if (!gate.begin(network.networkHandle, now, false)) {
            DiagnosticLog.event("background.already_checked", 0); return
        }
        watching = network
        acquireWakeLock()
        showStatus("检查中")
        DiagnosticLog.event("background.triggered", if (recheck) 1 else 0)
        if (login.backgroundCheck(network)) stateListener(login.state)
        else {
            gate.lost(network.networkHandle)
            watching = null
            releaseWakeLock()
            showStatus("等待连接 Wi-Fi")
        }
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed = PendingIntent.getBroadcast(this, 1,
            Intent(NOTIFICATION_DISMISSED).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_background_status)
            .setContentTitle("门户自动登录").setContentText(status).setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setAutoCancel(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    private fun showStatus(status: String, refresh: Boolean = false) {
        DiagnosticLog.event("background.notification_status", when (status) {
            "等待连接 Wi-Fi" -> 0; "等待授权" -> 1; "等待匹配 Wi-Fi" -> 2
            "检查中" -> 3; "登录中" -> 4; "已联网" -> 5; "登录失败" -> 6
            "确认联网中" -> 8; "未确认联网" -> 9; else -> 7
        })
        if (lastStatus == status && !refresh && notificationPresent(this)) return
        lastStatus = status
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.event("background.notification_blocked", 0); return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(status))
        DiagnosticLog.event("background.notification_posted", NOTIFICATION)
    }
    private fun notificationRestoreState(): Int = when {
        !running -> 1
        !::prefs.isInitialized -> 2
        callback == null -> 3
        startReadiness(this, PortalPreferences.open(this)) != BackgroundStartPolicy.Status.READY -> 4
        else -> 0
    }
    private fun canRestoreNotification(): Boolean = notificationRestoreState() == 0
    private fun reconcileNotification(source: Int) {
        val readiness = notificationRestoreState()
        if (readiness != 0) {
            DiagnosticLog.event("background.notification_restore_blocked", readiness); return
        }
        if (notificationPresent(this) || restoreScheduled) return
        DiagnosticLog.event("background.notification_missing", source)
        restoreScheduled = true
        // One bounded recovery per dismissal/network/task event, never a recurring monitor.
        val delay = maxOf(250L, lastNotificationRestore + 3000L - SystemClock.elapsedRealtime())
        DiagnosticLog.event("background.notification_restore_scheduled", delay.toInt())
        main.postDelayed(restoreNotification, delay)
    }
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PortalAuto:background-login").apply { setReferenceCounted(false); acquire(60000) }
            DiagnosticLog.event("background.wake_acquired", 60)
        }.onFailure { DiagnosticLog.failure(it) }
    }
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release(); DiagnosticLog.event("background.wake_released", 0) }
        wakeLock = null
    }
    private fun unregisterNetworks() {
        generation++
        DiagnosticLog.event("background.callback_unregister", generation)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        networks.values.forEach { it.scheduled?.let(main::removeCallbacks) }
        networks.clear()
    }
    override fun onDestroy() {
        DiagnosticLog.event("background.service_destroy_begin", if (running) 1 else 0)
        main.removeCallbacks(restoreNotification)
        restoreScheduled = false
        if (notificationReceiverRegistered) {
            runCatching { unregisterReceiver(notificationReceiver) }
            notificationReceiverRegistered = false
        }
        unregisterNetworks()
        login.removeListener(stateListener)
        login.detachBackground()
        releaseWakeLock()
        running = false
        starting = false
        if (notice == null) notice = "后台服务已停止，重新打开软件可恢复"
        stopForeground(STOP_FOREGROUND_REMOVE)
        DiagnosticLog.event("background.stopped", 0)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.event("background.task_removed", if (running) 1 else 0)
        reconcileNotification(4)
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        internal const val CHANNEL = "portal_background"
        private const val NOTIFICATION = 1001
        private const val NOTIFICATION_DISMISSED = "local.portal.autologin.NOTIFICATION_DISMISSED"
        var running by mutableStateOf(false)
            private set
        var notice by mutableStateOf<String?>(null)
            private set
        fun canReadWifiName(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        private var starting by mutableStateOf(false)
        fun stateText(): String = notice ?: when {
            running -> "后台服务运行中"
            starting -> "正在启动后台服务"
            else -> "后台服务未运行"
        }
        fun statusLabel(context: Context): String = BackgroundStartPolicy.label(
            startReadiness(context, PortalPreferences.open(context)), running)
        private fun startReadiness(context: Context, configuration: PortalPreferences): BackgroundStartPolicy.Status {
            val prefs = configuration.prefs
            return BackgroundStartPolicy.evaluate(configuration.moduleActive, prefs.getBoolean("background_login", false),
                !prefs.getString("username", "").isNullOrBlank() && !prefs.getString("password", "").isNullOrEmpty(),
                prefs.getString("background_wifi", ""), BackgroundAuthorization.fineLocation(context),
                BackgroundAuthorization.locationEnabled(context), BackgroundAuthorization.notificationsVisible(context))
        }
        private fun explanation(status: BackgroundStartPolicy.Status): String? = when (status) {
            BackgroundStartPolicy.Status.READY -> null
            BackgroundStartPolicy.Status.MODULE_ACTIVE -> "LSPosed 模式使用模块自动登录"
            BackgroundStartPolicy.Status.DISABLED -> "后台自动登录未开启"
            BackgroundStartPolicy.Status.MISSING_ACCOUNT -> "请先保存账号和密码"
            BackgroundStartPolicy.Status.INVALID_FILTER -> "请输入有效的 Wi-Fi 名称"
            BackgroundStartPolicy.Status.LOCATION_PERMISSION_REQUIRED -> "读取指定 Wi-Fi 需要精确定位权限"
            BackgroundStartPolicy.Status.LOCATION_DISABLED -> "读取指定 Wi-Fi 需要开启系统定位"
            BackgroundStartPolicy.Status.NOTIFICATION_REQUIRED -> "请允许通知并开启后台自动登录通知渠道"
        }
        fun recordState(context: Context) {
            val configuration = PortalPreferences.open(context)
            val prefs = configuration.prefs
            DiagnosticLog.event("background.readiness", startReadiness(context, configuration).ordinal)
            DiagnosticLog.event("background.module_active", if (configuration.moduleActive) 1 else 0)
            DiagnosticLog.event("background.config_enabled", if (prefs.getBoolean("background_login", false)) 1 else 0)
            DiagnosticLog.event("background.running", if (running) 1 else 0)
            DiagnosticLog.event("background.starting", if (starting) 1 else 0)
            DiagnosticLog.event("background.notification_visible", if (BackgroundAuthorization.notificationsVisible(context)) 1 else 0)
            DiagnosticLog.event("background.setup_completed", if (prefs.getBoolean("background_setup_completed", false)) 1 else 0)
            DiagnosticLog.event("background.notification_present", if (notificationPresent(context)) 1 else 0)
            DiagnosticLog.event("background.location_readable", if (canReadWifiName(context)) 1 else 0)
            DiagnosticLog.event("background.battery_exempt", if (BackgroundAuthorization.batteryExempt(context)) 1 else 0)
            BackgroundProcessDiagnostics.recordExit(context)
            runCatching {
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                val power = context.getSystemService(PowerManager::class.java)
                DiagnosticLog.event("background.system_restricted", if (manager.isBackgroundRestricted) 1 else 0)
                DiagnosticLog.event("background.power_save", if (power.isPowerSaveMode) 1 else 0)
                DiagnosticLog.event("background.device_idle", if (power.isDeviceIdleMode) 1 else 0)
                DiagnosticLog.event("background.data_saver", context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus)
            }.onFailure { DiagnosticLog.failure(it) }
        }
        private fun notificationPresent(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION }
        private fun transportFlags(caps: NetworkCapabilities): Int =
            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0) or
            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) 2 else 0) or
            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 4 else 0)
        fun sync(context: Context) {
            val configuration = PortalPreferences.open(context)
            DiagnosticLog.setEnabled(configuration.prefs.getBoolean("debug_log", false))
            val readiness = startReadiness(context, configuration)
            DiagnosticLog.event("background.sync", readiness.ordinal)
            if (readiness != BackgroundStartPolicy.Status.READY) {
                starting = false
                notice = explanation(readiness)
                context.stopService(Intent(context, BackgroundLoginService::class.java))
                return
            }
            notice = null
            starting = !running
            DiagnosticLog.event("background.start_requested", if (running) 1 else 0)
            try { context.startForegroundService(Intent(context, BackgroundLoginService::class.java)) }
            catch (e: Exception) {
                starting = false
                DiagnosticLog.failure(e)
                DiagnosticLog.event("background.start_failed", 0)
                notice = "后台服务启动失败，请重新打开软件"
            }
        }
    }
}
