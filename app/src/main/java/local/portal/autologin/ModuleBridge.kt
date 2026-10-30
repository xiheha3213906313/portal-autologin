package local.portal.autologin

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** App-side framework binding. Local private preferences remain the source of truth. */
internal object ModuleBridge {
    var available by mutableStateOf(false); private set
    var syncFailed by mutableStateOf(false); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(1), { task -> Thread(task, "PortalConfigSync").apply { isDaemon = true } })
    private var initialized = false
    private var application: Context? = null
    private var source: UnifiedPreferences? = null
    private var service: XposedService? = null
    private var mirror: RemoteConfigMirror? = null
    private var queued = false
    private var revision = 0L
    private var snapshot = RemoteConfigMirror.snapshot(emptyMap<String, Any>())

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        application = context.applicationContext
        source = UnifiedPreferences.open(context.applicationContext, "portal")
        snapshot = RemoteConfigMirror.snapshot(source!!.all)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) { main.post {
                runCatching {
                    if (bound.apiVersion < 101 ||
                        bound.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) return@runCatching
                    if (service === bound && mirror != null) {
                        synchronize()
                        return@runCatching
                    }
                    val remote = bound.getRemotePreferences("portal")
                    service = bound
                    mirror = RemoteConfigMirror(remote)
                    available = true
                    syncFailed = false
                    revision++
                    DiagnosticLog.event("module.service_bound", bound.apiVersion)
                    // The process may have been restored by a Service with no Activity observing
                    // Compose state. Reconcile the background mode on the binding event itself.
                    application?.let(BackgroundLoginService::sync)
                    synchronize()
                }.onFailure { DiagnosticLog.failure(it) }
            } }
            override fun onServiceDied(dead: XposedService) { main.post {
                if (service === dead) {
                    service = null; mirror = null; available = false; syncFailed = false
                    DiagnosticLog.event("module.service_disconnected", 0)
                    application?.let(BackgroundLoginService::sync)
                }
            } }
        })
    }

    fun localCommitted(values: Map<String, *>) {
        val updated = RemoteConfigMirror.snapshot(values)
        onMain {
            if (updated != snapshot) { snapshot = updated; revision++; synchronize() }
        }
    }

    fun synchronize() {
        onMain {
            // Do not publish defaults when the durable local configuration could not be read.
            if (source?.initializationFailed == true) { syncFailed = available; return@onMain }
            val target = mirror ?: return@onMain
            if (queued) return@onMain
            queued = true
            val data = snapshot
            val requestRevision = revision
            worker.execute {
                val ok = runCatching { target.write(data) }.onFailure { DiagnosticLog.failure(it) }.getOrDefault(false)
                main.post {
                    queued = false
                    if (mirror === target) {
                        syncFailed = !ok
                        DiagnosticLog.event("module.config_sync", if (ok) 1 else 0)
                    }
                    // A save or a new binding arrived while the Binder write was running.
                    if (mirror != null && (mirror !== target || revision != requestRevision)) synchronize()
                }
            }
        }
    }

    fun recordState() {
        DiagnosticLog.event("module.service_available", if (available) 1 else 0)
        DiagnosticLog.event("module.config_sync_failed", if (syncFailed) 1 else 0)
        source?.let { saved ->
            val flags = (if (saved.getBoolean("enabled", false)) 1 else 0) or
                (if (saved.getBoolean("silent", false)) 2 else 0) or
                (if (saved.getString("username", "").isNullOrEmpty()) 0 else 4) or
                (if (saved.getString("password", "").isNullOrEmpty()) 0 else 8)
            DiagnosticLog.event("module.local_config_flags", flags)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
