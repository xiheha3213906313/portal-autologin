package local.portal.autologin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.FileNotFoundException

/** History stays separate from the active account shared with LSPosed. */
internal class AccountStore(context: Context, active: SharedPreferences) {
    private val storage = context.applicationContext.getSharedPreferences("saved_accounts", Context.MODE_PRIVATE)
    var initializationFailed = false
        private set
    val accounts: List<SavedAccountBook.Account> get() = read().accounts

    init {
        val candidate = read()
        var changed = candidate.migrateActive(active.getString("username", ""), active.getString("password", ""))
        try {
            val text = context.assets.open("private-account-presets.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val items = JSONObject(text).getJSONArray("accounts")
            val presets = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                SavedAccountBook.Account(item.getString("username"), item.getString("password"), item.optString("label"))
            }
            changed = candidate.seed(presets) || changed
        } catch (_: FileNotFoundException) {
            // Public builds have no bundled account presets.
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
