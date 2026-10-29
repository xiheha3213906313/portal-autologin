package local.portal.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Ordinary application process only. No Xposed classes, root, service, or permanent observer. */
class AppLoginController private constructor(private val context: Context) {
    enum class Stage { CONFIGURATION, CONNECTING, SUCCESS, ERROR }
    data class UiState(val stage: Stage = Stage.CONFIGURATION, val message: String = "",
                       val successId: Long = 0, val detail: String = "",
                       val offerForceLogin: Boolean = false, val recommendForceLogin: Boolean = false,
                       val internetConfirmed: Boolean = false)
    var state by mutableStateOf(UiState())
        private set
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2), { r -> Thread(r, "PortalAppAuth").apply { isDaemon = true } })
    private val verifier = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2), { r -> Thread(r, "PortalInternetCheck").apply { isDaemon = true } })
    private val attempts = LoginAttempts()
    var forceLoginEnabled by mutableStateOf(false)
        private set
    val ordinaryMode: Boolean get() = !moduleAvailable
    private val history = UnifiedPreferences.open(context,"app_login")
    private lateinit var prefs: SharedPreferences
    private var moduleAvailable = false
    private var foreground = false
    private var current: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var successfulNetwork: Network? = null
    private var successSequence = 0L
    private var animatedSuccess = -1L

    private class Job(val network: Network, val force: Boolean = false,
                      val verificationOnly: Boolean = false) {
        @Volatile var active = true
        @Volatile var submitted = false
        @Volatile var credentialsWritten = false
        var checkingInternet = false
        var internetChecks = 0
        var verificationRetry: Runnable? = null
        var authenticationFinished = false
        var bridgeClaimed = false
        var submittedAt = -1L
        var timeout: Runnable? = null
    }

    fun enterForeground(preferences: SharedPreferences, hasModule: Boolean) {
        prefs = preferences; moduleAvailable = hasModule; foreground = true
        forceLoginEnabled = prefs.getBoolean("force_login", false)
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        check(false)
    }
    fun leaveForeground() { foreground = false }
    fun saved() {
        forceLoginEnabled = prefs.getBoolean("force_login", false)
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        current?.let { job -> synchronized(job) { if (!job.submitted) finish(job) } }
        check(true)
    }
    fun retry() = check(true)
    fun forceLogin() = check(true, oneShotForce = true)
    fun updateForceLogin(value: Boolean) {
        if (moduleAvailable) return
        if (prefs.edit().putBoolean("force_login", value).commit()) {
            forceLoginEnabled = value
            DiagnosticLog.event("app.force_setting", if (value) 1 else 0)
        } else state = state.copy(detail = "设置保存失败，请重试")
    }
    fun isWifiOnline(): Boolean = runCatching { wifi()?.let { validated(it) } == true }.getOrDefault(false)
    fun consumeSuccessAnimation(id: Long): Boolean {
        if (animatedSuccess == id) return false
        animatedSuccess = id
        return true
    }
    fun clear() {
        current?.let { finish(it) }
        successfulNetwork = null
        state = UiState()
    }

    private fun configured() = !prefs.getString("username", "").isNullOrBlank() &&
        !prefs.getString("password", "").isNullOrEmpty()

    private fun wifi(): Network? {
        fun suitable(n: Network) = cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        cm.activeNetwork?.let { if (suitable(it)) return it }
        val all = cm.allNetworks.filter(::suitable)
        return all.firstOrNull { cm.getNetworkCapabilities(it)?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true } ?: all.firstOrNull()
    }
    private fun validated(n: Network): Boolean = cm.getNetworkCapabilities(n)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

    private fun connected(n: Network): Boolean = cm.getNetworkCapabilities(n)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } == true

    private fun check(retry: Boolean, oneShotForce: Boolean = false) {
        if (!configured()) { clear(); return }
        val network = runCatching { wifi() }.getOrNull()
        if (network == null) {
            current?.let { finish(it) }
            successfulNetwork = null
            state = UiState(Stage.ERROR, "请先连接 Wi-Fi")
            DiagnosticLog.event("app.no_wifi", 0)
            return
        }
        val force = ordinaryMode && (oneShotForce || forceLoginEnabled)
        current?.let {
            // A forced click may replace a passive check, but never a running authentication.
            if (it.network == network && it.active && !(force && it.verificationOnly)) return
            finish(it)
        }
        if (!force && validated(network)) {
            val job = Job(network, verificationOnly = true)
            current = job
            showOnline(job, confirmed = false)
            DiagnosticLog.event("app.system_validated", 1)
            if (!startObservation(job, 20000)) return
            verifyInternet(job)
            return
        }
        successfulNetwork = null
        val handle = network.networkHandle
        val sameBoot = history.getInt("submitted_boot", -2) == bootCount()
        val decision = attempts.begin(handle, SystemClock.elapsedRealtime(),
            history.getLong("submitted_network", -1),
            if (sameBoot) history.getLong("submitted_at", -1) else -1, retry, force)
        DiagnosticLog.event("app.attempt_decision", decision.ordinal)
        if (decision == LoginAttempts.Decision.RECENT_FAILURE) {
            state = UiState(Stage.ERROR, "暂时无法登录，请检查 Wi-Fi 后重试", offerForceLogin = ordinaryMode)
            return
        }
        if (decision == LoginAttempts.Decision.RUNNING) return
        val job = Job(network, force)
        current = job
        state = UiState(Stage.CONNECTING, if (force) "正在强制登录" else "正在连接")
        DiagnosticLog.event("app.attempt_started", if (force) 1 else 0)
        if (!startObservation(job, if (decision == LoginAttempts.Decision.RECENT_SUBMISSION) 20000 else 45000)) return
        if (decision == LoginAttempts.Decision.RECENT_SUBMISSION) {
            job.submitted = true
            job.authenticationFinished = true
            state = UiState(Stage.CONNECTING, "正在确认联网状态")
            verifyInternet(job)
            probe(network)
            return
        }
        claimModule(job) { authenticate(job) }
    }

    private fun startObservation(job: Job, timeoutMs: Long): Boolean {
        try { observe(job) } catch (e: Exception) {
            DiagnosticLog.failure(e); fail(job, "暂时无法检查 Wi-Fi 状态"); return false
        }
        job.timeout = Runnable {
            if (current !== job || !job.active) return@Runnable
            DiagnosticLog.event("app.attempt_timeout", if (job.submitted) 1 else 0)
            verificationFailed(job, "暂时无法确认当前 Wi-Fi 的互联网连接")
        }.also { main.postDelayed(it, timeoutMs) }
        return true
    }

    private fun observe(job: Job) {
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (current !== job || !job.active || n != job.network) return
                DiagnosticLog.event("app.network_capabilities",
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) or
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) 1 else 0))
                // A cached VALIDATED bit must never finish a forced POST or prove internet access.
                if (job.authenticationFinished || job.verificationOnly) verifyInternet(job)
            }
            override fun onLost(n: Network) {
                if (current === job && job.active && n == job.network) fail(job, "Wi-Fi 已断开")
            }
        }
        callback = observer
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), observer, main)
    }

    private fun verifyInternet(job: Job) {
        if (current !== job || !job.active || job.checkingInternet) return
        job.verificationRetry?.let { main.removeCallbacks(it) }
        job.verificationRetry = null
        job.checkingInternet = true
        job.internetChecks++
        DiagnosticLog.event("app.internet.started", if (job.verificationOnly) 0 else 1)
        try { verifier.execute {
            val result = InternetCheck({ url -> job.network.openConnection(url) as HttpURLConnection }, {
                job.active && connected(job.network)
            }, { stage, detail -> DiagnosticLog.event("app.$stage", detail) }).run()
            main.post {
                if (current !== job || !job.active) return@post
                job.checkingInternet = false
                DiagnosticLog.event("app.internet.result", result.ordinal)
                // Never apply a completed check to a different Wi-Fi after a network switch.
                if (!connected(job.network) || wifi() != job.network) {
                    fail(job, "Wi-Fi 已切换，请重试")
                } else if (job.submitted && result != InternetCheck.Result.ONLINE &&
                    result != InternetCheck.Result.CANCELLED && job.internetChecks < 3) {
                    // Gateways can release traffic shortly after the POST. Three bounded checks,
                    // never another POST or a permanent connectivity poll.
                    job.verificationRetry = Runnable { verifyInternet(job) }.also {
                        main.postDelayed(it, if (job.internetChecks == 1) 1000 else 2500)
                    }
                } else when (result) {
                    InternetCheck.Result.ONLINE -> showOnline(job, confirmed = true)
                    InternetCheck.Result.CAPTIVE -> verificationFailed(job, "当前 Wi-Fi 仍需认证或访问受限")
                    InternetCheck.Result.UNAVAILABLE -> verificationFailed(job, "暂时无法确认当前 Wi-Fi 的互联网连接")
                    else -> fail(job, "Wi-Fi 已断开")
                }
            }
        } } catch (e: Exception) {
            DiagnosticLog.failure(e)
            verificationFailed(job, "暂时无法检查互联网连接，请重试")
        }
    }

    private fun verificationFailed(job: Job, detail: String) {
        if (current !== job) return
        val submitted = job.submitted
        finish(job)
        successfulNetwork = null
        state = UiState(Stage.ERROR, if (submitted) "登录失败" else "未进行登录",
            detail = detail, offerForceLogin = ordinaryMode && connected(job.network))
    }

    private fun claimModule(job: Job, ready: () -> Unit) {
        if (current !== job || !job.active) return
        if (!moduleAvailable) {
            DiagnosticLog.event("app.module_not_required", 0)
            ready(); return
        }
        context.sendOrderedBroadcast(Intent(APP_BEGIN).setPackage("android")
            .putExtra("network", job.network), null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (current !== job || !job.active) {
                    if (resultCode == BRIDGE_GRANTED) context.sendBroadcast(
                        Intent(APP_END).setPackage("android").putExtra("network", job.network))
                    return
                }
                DiagnosticLog.event("app.module_claim_result", resultCode)
                when (resultCode) {
                    BRIDGE_GRANTED -> { job.bridgeClaimed = true; ready() }
                    BRIDGE_BUSY -> {
                        runCatching { rememberSubmission(job) }.onFailure { DiagnosticLog.failure(it) }
                        job.authenticationFinished = true
                        state = UiState(Stage.CONNECTING, "正在确认联网状态")
                        verifyInternet(job)
                        probe(job.network)
                    }
                    else -> {
                        if (prefs.getBoolean("enabled", false) && prefs.getBoolean("silent", false))
                            fail(job, "请重启手机，加载更新后的模块")
                        else ready()
                    }
                }
            }
        }, main, 0, null, null)
    }

    private fun authenticate(job: Job) {
        if (current !== job || !job.active) return
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        try { worker.execute {
            try {
                PortalHttpClient({ url -> job.network.openConnection(url) as HttpURLConnection }, {
                    job.active && (job.force || !validated(job.network)) && connected(job.network)
                }, { stage, detail ->
                    DiagnosticLog.event("app.$stage", detail)
                    if (stage == "submit.write_begin") {
                        if (!job.active || (!job.force && validated(job.network))) throw IOException("attempt_cancelled")
                        rememberSubmission(job)
                    }
                    if (stage == "submit.write_complete") job.credentialsWritten = true
                }).login(user, pass)
                main.post { submitted(job) }
            } catch (_: PortalHttpClient.SubmissionUncertainException) {
                main.post { submitted(job) }
            } catch (e: Exception) {
                DiagnosticLog.failure(e)
                val rejected = e.message == "login_form_returned" || e.message == "authentication_rejected"
                main.post {
                    if (current !== job || !job.active) return@post
                    if (rejected) {
                        forgetRejectedSubmission(job)
                        fail(job, "登录未通过，请检查账号和密码", true)
                    } else if (job.submitted || (!job.force && validated(job.network))) submitted(job)
                    else fail(job, "暂时无法登录，请检查 Wi-Fi 后重试")
                }
            }
        } } catch (e: Exception) {
            DiagnosticLog.failure(e); fail(job, "暂时无法登录，请稍后重试")
        }
    }

    private fun bootCount() = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    private fun rememberSubmission(job: Job) = synchronized(job) {
        // Commit before writing credentials, including across application-process restarts.
        if (!job.active) throw IOException("attempt_cancelled")
        val now = SystemClock.elapsedRealtime()
        if (!history.edit().putLong("submitted_network", job.network.networkHandle)
                .putLong("submitted_at", now).putInt("submitted_boot", bootCount()).commit())
            throw IOException("submission_record_failed")
        job.submittedAt = now
        job.submitted = true
        attempts.submitted(job.network.networkHandle, now)
    }
    private fun forgetRejectedSubmission(job: Job) {
        if (history.getLong("submitted_network", -1) == job.network.networkHandle &&
            history.getLong("submitted_at", -1) == job.submittedAt) {
            history.edit().remove("submitted_network").remove("submitted_at").apply()
        }
    }
    private fun submitted(job: Job) {
        if (current !== job || !job.active) return
        job.authenticationFinished = true
        state = UiState(Stage.CONNECTING, "正在确认联网状态")
        verifyInternet(job)
        probe(job.network)
    }
    private fun probe(network: Network) {
        runCatching { cm.reportNetworkConnectivity(network, true) }.onFailure { DiagnosticLog.failure(it) }
    }
    private fun showOnline(job: Job, confirmed: Boolean) {
        if (current !== job || !job.active) return
        val newlyAuthenticated = confirmed && job.credentialsWritten
        val recommend = ordinaryMode && ((job.force && newlyAuthenticated) ||
            (successfulNetwork == job.network && state.recommendForceLogin))
        val sequence = if (successfulNetwork == job.network && state.stage == Stage.SUCCESS)
            state.successId else ++successSequence
        successfulNetwork = job.network
        state = UiState(Stage.SUCCESS, if (newlyAuthenticated && !job.force) "登录成功" else "已联网",
            sequence, recommendForceLogin = recommend, internetConfirmed = confirmed)
        DiagnosticLog.event(if (confirmed) "app.internet_confirmed" else "app.already_online", 0)
        if (newlyAuthenticated) DiagnosticLog.event("app.authenticated", if (job.force) 1 else 0)
        if (confirmed) {
            finish(job)
            if (!foreground) DiagnosticLog.event("app.completed_in_background", 0)
        }
    }
    private fun fail(job: Job, message: String, rejected: Boolean = false) {
        if (current !== job) return
        finish(job, rejected)
        state = UiState(Stage.ERROR, message, offerForceLogin = ordinaryMode && connected(job.network))
    }
    private fun finish(job: Job, rejected: Boolean = false) = synchronized(job) {
        job.active = false
        job.timeout?.let { main.removeCallbacks(it) }
        job.verificationRetry?.let { main.removeCallbacks(it) }
        if (!job.verificationOnly) attempts.finish(job.network.networkHandle, rejected)
        if (job.bridgeClaimed) context.sendBroadcast(Intent(APP_END).setPackage("android")
            .putExtra("network", job.network).putExtra("submitted", job.submitted && !rejected))
        if (current === job) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = null; current = null
        }
    }

    companion object {
        const val APP_BEGIN = "local.portal.autologin.APP_LOGIN_BEGIN"
        const val APP_END = "local.portal.autologin.APP_LOGIN_END"
        const val BRIDGE_GRANTED = 10
        const val BRIDGE_BUSY = 11
        // The constructor is private and get() always retains applicationContext, never an Activity.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppLoginController? = null
        fun get(context: Context): AppLoginController = instance ?: synchronized(this) {
            instance ?: AppLoginController(context.applicationContext).also { instance = it }
        }
    }
}
