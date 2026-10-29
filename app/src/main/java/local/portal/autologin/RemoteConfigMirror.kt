package local.portal.autologin

import android.content.SharedPreferences

/** Share only the current module configuration, never account history or background permissions. */
internal class RemoteConfigMirror(private val remote: SharedPreferences) {
    private var committed: Map<String, *> = remote.all.toMap()
    private var uncertain = false

    fun write(values: Map<String, Any>): Boolean {
        try {
            return writeSnapshot(values)
        } catch (e: RuntimeException) {
            // A Binder failure may throw after the remote-side transaction has taken effect.
            uncertain = true
            throw e
        }
    }

    private fun writeSnapshot(values: Map<String, Any>): Boolean {
        if (!uncertain && values == committed) return true
        val edit = remote.edit()
        // Do not trigger authentication again on an unchanged app reopen or a log-only change.
        ((committed.keys + remote.all.keys) - values.keys).forEach(edit::remove)
        values.forEach { (key, value) ->
            if (!uncertain && committed[key] == value) return@forEach
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                else -> error("Unexpected module configuration type")
            }
        }
        // RemotePreferences updates its in-process cache before the Binder commit. Compare only
        // our last successful write, so a failed commit is retried even if that cache looks current.
        if (!edit.commit()) { uncertain = true; return false }
        committed = values.toMap()
        uncertain = false
        return true
    }

    companion object {
        fun snapshot(values: Map<String, *>): Map<String, Any> = mapOf(
            "username" to (values["username"] as? String ?: ""),
            "password" to (values["password"] as? String ?: ""),
            "enabled" to (values["enabled"] as? Boolean ?: false),
            "silent" to (values["silent"] as? Boolean ?: false),
            "debug_log" to (values["debug_log"] as? Boolean ?: false)
        )
    }
}
