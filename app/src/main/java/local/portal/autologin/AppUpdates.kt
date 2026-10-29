package local.portal.autologin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Startup/manual release checks and bounded APK downloads; no account data or auth headers. */
internal class AppUpdates private constructor(private val context: Context) {
    data class Release(val version: String, val notes: String, val page: String, val apk: String?)
    data class CheckResult(val available: Boolean, val message: String)
    var checking by mutableStateOf(false); private set
    var release by mutableStateOf<Release?>(null); private set
    var checkResult by mutableStateOf<CheckResult?>(null); private set
    var downloading by mutableStateOf(false); private set
    var progress by mutableStateOf(-1); private set
    var ready by mutableStateOf<File?>(null); private set
    var downloadError by mutableStateOf<String?>(null); private set
    private var installationPromptPending = false
    private var retryAutomaticOnConnection = false
    private var automaticFailed = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2), { r -> Thread(r, "PortalUpdates").apply { isDaemon = true } })

    fun consumeResult(): CheckResult? = checkResult.also { checkResult = null }

    // Keep the completion event until a foreground Activity can display it, then consume once.
    fun consumeInstallationPrompt(): Boolean {
        if (ready == null || !installationPromptPending) return false
        installationPromptPending = false
        return true
    }

    fun hasUpdate(currentVersion: String): Boolean = release?.let {
        ReleaseRules.compareVersions(it.version, currentVersion) > 0
    } == true

    fun checkOnStart(currentVersion: String) {
        if (checking || downloading || ready != null) return
        retryAutomaticOnConnection = true
        check(currentVersion, automatic = true)
    }

    fun retryOnConnection(currentVersion: String) {
        if (!retryAutomaticOnConnection || checking || !automaticFailed) return
        retryAutomaticOnConnection = false
        check(currentVersion, automatic = true)
    }

    fun check(currentVersion: String, automatic: Boolean = false) {
        if (checking || downloading || (automatic && ready != null)) return
        if (!automatic) {
            retryAutomaticOnConnection = false
            checkResult = null; ready = null; installationPromptPending = false; downloadError = null
        }
        automaticFailed = false
        checking = true
        worker.execute {
            try {
                val json = JSONObject(metadata())
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw IOException("invalid_release")
                val tag = json.getString("tag_name")
                val newer = ReleaseRules.compareVersions(tag, currentVersion) > 0
                val page = json.getString("html_url")
                if (!ReleaseRules.releasePage(page)) throw IOException("invalid_release")
                val body = json.optString("body")
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    val url = item.optString("browser_download_url")
                    val name = item.optString("name").lowercase()
                    if (name.endsWith(".apk") && !name.contains("unsigned") && !name.contains("debug") && ReleaseRules.download(url)) {
                        apk = ReleaseRules.normalizeDownload(url); break
                    }
                }
                if (apk == null) apk = ReleaseRules.markdownDownload(body)
                val notes = body.substringAfter("## 更新内容", body).substringBefore("\n## ").trim().take(2000)
                val item = Release(tag.removePrefix("v").removePrefix("V"), notes, page, apk)
                main.post {
                    release = item; automaticFailed = false; checking = false
                    if (!automatic) checkResult = CheckResult(newer, if (newer) "发现新版本" else "已是最新版本")
                }
            } catch (_: Exception) {
                main.post {
                    automaticFailed = automatic; checking = false
                    if (!automatic) checkResult = CheckResult(false, "检查更新失败，请稍后重试")
                }
            }
        }
    }

    private fun connection(initial: String, metadata: Boolean = false): HttpURLConnection {
        if (metadata) require(initial == ProjectLinks.LATEST_API) else require(ReleaseRules.download(initial))
        var url = URL(initial)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "PortalAuto/${DiagnosticLog.VERSION}")
            c.setRequestProperty("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream")
            if (!metadata) c.setRequestProperty("Accept-Encoding", "identity")
            if (metadata) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code in 300..399) {
                val location = c.getHeaderField("Location"); c.disconnect()
                val next = URL(url, location ?: throw IOException("invalid_redirect"))
                if (!ReleaseRules.redirect(next.toString())) throw IOException("invalid_redirect")
                url = next
            } else {
                if (code != 200) { c.disconnect(); throw IOException("http_failed") }
                return c
            }
        }
        throw IOException("redirect_limit")
    }

    private fun metadata(): String {
        val c = connection(ProjectLinks.LATEST_API, true)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 1048576 || System.nanoTime() > deadline) throw IOException("response_limit")
                    out.write(buffer, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }

    fun download() {
        val info = release ?: return
        val url = info.apk ?: return
        if (downloading) return
        downloading = true; progress = -1; ready = null; installationPromptPending = false; downloadError = null
        worker.execute {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "download.part.apk")
            try {
                val c = connection(url)
                try {
                    val total = c.contentLengthLong
                    val limit = 50L * 1024 * 1024
                    if (total > limit) throw IOException("size_limit")
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
                    var bytes = 0L; var last = -1
                    c.inputStream.use { input -> FileOutputStream(part).use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n
                            if (bytes > limit || System.nanoTime() > deadline) throw IOException("download_limit")
                            output.write(buffer, 0, n)
                            if (total > 0) {
                                val percent = (bytes * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != last) { last = percent; main.post { progress = percent } }
                            }
                        }
                    } }
                    if (bytes == 0L || total >= 0 && bytes != total) throw IOException("incomplete_download")
                } finally { c.disconnect() }
                verify(part, info.version)
                val file = File(dir, "update.apk")
                if (file.exists() && !file.delete() || !part.renameTo(file)) throw IOException("file_failed")
                main.post { installationPromptPending = true; ready = file; progress = 100; downloading = false }
            } catch (_: Exception) {
                part.delete()
                main.post { downloading = false; downloadError = "下载或校验失败，请重试或使用浏览器" }
            }
        }
    }

    private fun verify(file: File, expectedVersion: String) {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("invalid_package")
        if (candidate.packageName != context.packageName || candidate.longVersionCode <= installed.longVersionCode ||
            ReleaseRules.compareVersions(candidate.versionName, expectedVersion) != 0) throw IOException("invalid_version")
        fun certificates(info: android.content.pm.PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }
            ?.toSet() ?: emptySet()
        val current = certificates(installed)
        if (current.isEmpty() || current != certificates(candidate)) throw IOException("signature_mismatch")
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppUpdates? = null
        fun get(context: Context): AppUpdates = instance ?: synchronized(this) {
            instance ?: AppUpdates(context.applicationContext).also { instance = it }
        }
    }
}
