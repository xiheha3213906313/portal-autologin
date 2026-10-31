package local.portal.autologin

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** User-triggered catalog updates. Keeps no Activity and never edits the active login account. */
internal class AccountPresetUpdates private constructor(private val context: Context) {
    var fetching by mutableStateOf(false); private set
    var feedback by mutableStateOf<String?>(null); private set
    var revision by mutableStateOf(0L); private set
    private var feedbackId = 0L
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "PortalPresets").apply { isDaemon = true } })

    fun refresh() {
        if (fetching) return
        fetching = true; feedback = null
        DiagnosticLog.event("presets.fetch_started", 0)
        try { worker.execute {
            val response = runCatching {
                AccountPresetFetcher { url -> url.openConnection() as HttpURLConnection }.fetch()
            }
            main.post {
                val imported = response.fold(onSuccess = { presets ->
                    runCatching {
                        val store = AccountStore(context, PortalPreferences.open(context).prefs)
                        store.importPresets(presets).also { success ->
                            if (success) DiagnosticLog.event("presets.imported", presets.size)
                        }
                    }.onFailure { DiagnosticLog.failure(it) }.getOrDefault(false)
                }, onFailure = { DiagnosticLog.failure(it); false })
                if (imported) revision++
                complete(imported)
            }
        } } catch (failure: Exception) {
            DiagnosticLog.failure(failure); complete(false)
        }
    }
    private fun complete(success: Boolean) {
        fetching = false
        feedback = if (success) "已获取最新预设" else "获取失败，请重试"
        DiagnosticLog.event("presets.fetch_completed", if (success) 1 else 0)
        val id = ++feedbackId
        main.postDelayed({ if (feedbackId == id) feedback = null }, 1800)
    }
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AccountPresetUpdates? = null
        fun get(context: Context): AccountPresetUpdates = instance ?: synchronized(this) {
            instance ?: AccountPresetUpdates(context.applicationContext).also { instance = it }
        }
    }
}
