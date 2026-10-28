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
                       val successId: Long = 0)
    var state by mutableStateOf(UiState())
        private set
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2), { r -> Thread(r, "PortalAppAuth").apply { isDaemon = true } })
    private val attempts = LoginAttempts()
    private val history = context.getSharedPreferences("app_login", Context.MODE_PRIVATE)
    private lateinit var prefs: SharedPreferences
    private var moduleAvailable = false
    private var foreground = false
    private var current: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var successfulNetwork: Network? = null
    private var successSequence = 0L
    private var animatedSuccess = -1L

    private class Job(val network: Network) {
        @Volatile var active = true
        @Volatile var submitted = false
        @Volatile var credentialsWritten = false
        var bridgeClaimed = false
        var submittedAt = -1L
        var timeout: Runnable? = null
    }

    fun enterForeground(preferences: SharedPreferences, hasModule: Boolean) {
        prefs = preferences; moduleAvailable = hasModule; foreground = true
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        check(false)
    }
    fun leaveForeground() { foreground = false }
    fun saved() {
        DiagnosticLog.setEnabled(prefs.getBoolean("debug_log", false))
        current?.let { job -> synchronized(job) { if (!job.submitted) finish(job) } }
        check(true)
    }
    fun retry() = check(true)
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

    private fun check(retry: Boolean) {
        if (!configured()) { clear(); return }
        val network = runCatching { wifi() }.getOrNull()
        if (network == null) {
            current?.let { finish(it) }
            successfulNetwork = null
            state = UiState(Stage.ERROR, "请先连接 Wi-Fi")
            return
        }
        if (success(network)) return
        current?.let {
            if (it.network == network && it.active) return
            finish(it)
        }
        successfulNetwork = null
        val handle = network.networkHandle
        val sameBoot = history.getInt("submitted_boot", -2) == bootCount()
        val decision = attempts.begin(handle, SystemClock.elapsedRealtime(),
            history.getLong("submitted_network", -1),
            if (sameBoot) history.getLong("submitted_at", -1) else -1, retry)
        if (decision == LoginAttempts.Decision.RECENT_FAILURE) {
            state = UiState(Stage.ERROR, "暂时无法登录，请检查 Wi-Fi 后重试")
            return
        }
        if (decision == LoginAttempts.Decision.RUNNING) return
        val job = Job(network)
        current = job
        state = UiState(Stage.CONNECTING, "正在连接")
        DiagnosticLog.event("app.attempt_started", 0)
        try { observe(job) } catch (e: Exception) {
            DiagnosticLog.failure(e); fail(job, "暂时无法检查 Wi-Fi 状态"); return
        }
        if (current !== job || !job.active) return
        job.timeout = Runnable {
            if (current !== job || !job.active) return@Runnable
            if (!success(network)) fail(job,
                if (job.submitted) "尚未确认联网，请稍后重试" else "连接超时，请检查 Wi-Fi")
        }.also { main.postDelayed(it, if (decision == LoginAttempts.Decision.RECENT_SUBMISSION) 10000 else 45000) }
        if (decision == LoginAttempts.Decision.RECENT_SUBMISSION) {
            job.submitted = true
            state = UiState(Stage.CONNECTING, "正在确认联网状态")
            probe(network)
            return
        }
        claimModule(job) { authenticate(job) }
    }

    private fun observe(job: Job) {
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (current !== job || !job.active || n != job.network) return
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) success(n)
            }
            override fun onLost(n: Network) {
                if (current === job && job.active && n == job.network) fail(job, "Wi-Fi 已断开")
            }
        }
        callback = observer
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), observer, main)
        if (validated(job.network)) success(job.network)
    }

    private fun claimModule(job: Job, ready: () -> Unit) {
        if (current !== job || !job.active) return
        if (!moduleAvailable) {
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
                when (resultCode) {
                    BRIDGE_GRANTED -> { job.bridgeClaimed = true; ready() }
                    BRIDGE_BUSY -> {
                        runCatching { rememberSubmission(job) }.onFailure { DiagnosticLog.failure(it) }
                        state = UiState(Stage.CONNECTING, "正在确认联网状态")
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
                    job.active && !validated(job.network) && cm.getNetworkCapabilities(job.network) != null
                }, { stage, detail ->
                    DiagnosticLog.event("app.$stage", detail)
                    if (stage == "submit.write_begin") {
                        if (!job.active || validated(job.network)) throw IOException("attempt_cancelled")
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
                    if (success(job.network)) return@post
                    if (rejected) {
                        forgetRejectedSubmission(job)
                        fail(job, "登录未通过，请检查账号和密码", true)
                    } else if (job.submitted) submitted(job)
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
        if (success(job.network)) return
        state = UiState(Stage.CONNECTING, "正在确认联网状态")
        probe(job.network)
    }
    private fun probe(network: Network) {
        runCatching { cm.reportNetworkConnectivity(network, true) }.onFailure { DiagnosticLog.failure(it) }
    }
    private fun success(network: Network): Boolean {
        if (!validated(network)) return false
        val newlyAuthenticated = current?.let { it.network == network && it.credentialsWritten } == true
        current?.let { finish(it) }
        if (successfulNetwork == network && state.stage == Stage.SUCCESS) return true
        successfulNetwork = network
        state = UiState(Stage.SUCCESS, if (newlyAuthenticated) "登录成功" else "已联网", ++successSequence)
        DiagnosticLog.event("app.validated", if (newlyAuthenticated) 1 else 0)
        DiagnosticLog.event(if (newlyAuthenticated) "app.authenticated" else "app.already_online", 0)
        if (!foreground) DiagnosticLog.event("app.completed_in_background", 0)
        return true
    }
    private fun fail(job: Job, message: String, rejected: Boolean = false) {
        if (current !== job) return
        finish(job, rejected)
        state = UiState(Stage.ERROR, message)
    }
    private fun finish(job: Job, rejected: Boolean = false) = synchronized(job) {
        job.active = false
        job.timeout?.let { main.removeCallbacks(it) }
        attempts.finish(job.network.networkHandle, rejected)
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
