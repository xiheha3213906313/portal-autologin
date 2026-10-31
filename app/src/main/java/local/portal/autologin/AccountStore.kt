package local.portal.autologin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.FileNotFoundException

/** History stays separate from the active account shared with LSPosed. */
internal class AccountStore(context: Context, active: SharedPreferences) {
    private val storage = UnifiedPreferences.open(context,"saved_accounts")
    var initializationFailed = storage.initializationFailed
        private set
    val accounts: List<SavedAccountBook.Account> get() = read().accounts

    init {
        val candidate = read()
        var changed = candidate.migrateActive(active.getString("username", ""), active.getString("password", ""))
        try {
            val cached = storage.getString("remote_presets", null)
            if (cached != null) {
                changed = candidate.replacePresets(AccountPresetCatalog.parse(cached)) || changed
            } else {
                val text = context.assets.open("private-account-presets.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val items = JSONObject(text).getJSONArray("accounts")
                val presets = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    SavedAccountBook.Account(item.getString("username"), item.getString("password"), item.optString("label"))
                }
                // Drop obsolete seeded test entries if they were never downloaded. A genuine
                // downloaded catalog is handled above and remains available offline.
                changed = candidate.replacePresets(presets) || changed
            }
        } catch (_: FileNotFoundException) {
            if (storage.getString("remote_presets", null) == null)
                changed = candidate.replacePresets(emptyList()) || changed
        } catch (_: Exception) {
            initializationFailed = true
        }
        if (changed && !persist(candidate)) initializationFailed = true
    }

    fun remember(username: String, password: String): Boolean {
        val candidate = read()
        candidate.remember(username, password)
        return persist(candidate)
    }
    fun importPresets(presets: List<SavedAccountBook.Account>): Boolean {
        val encoded = AccountPresetCatalog.encode(presets)
        val previousBook = storage.getString("book", null)
        val previousCatalog = storage.getString("remote_presets", null)
        val candidate = read()
        candidate.replacePresets(presets)
        if (storage.edit().putString("book", candidate.encode()).putString("remote_presets", encoded).commit()) return true
        // A snapshot failure may happen after SharedPreferences changed in memory. Restore the
        // previous values as well, so a failed fetch is not presented as an imported catalog.
        val rollback = storage.edit()
        if (previousBook == null) rollback.remove("book") else rollback.putString("book", previousBook)
        if (previousCatalog == null) rollback.remove("remote_presets") else rollback.putString("remote_presets", previousCatalog)
        DiagnosticLog.event("presets.storage_rollback", if (rollback.commit()) 1 else 0)
        return false
    }
    fun remove(username: String): Boolean {
        val candidate = read()
        if (!candidate.remove(username)) return false
        return persist(candidate)
    }
    fun clear(): Boolean {
        val candidate = read()
        candidate.clear()
        return persist(candidate)
    }
    private fun persist(candidate: SavedAccountBook): Boolean {
        if (!storage.edit().putString("book", candidate.encode()).commit()) return false
        return true
    }
    private fun read() = SavedAccountBook.decode(storage.getString("book", ""))
}
