package local.portal.autologin

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/** App-private preferences with a stable snapshot shared by ordinary and module settings. */
internal class UnifiedPreferences internal constructor(
    private val backing: SharedPreferences, private val file: File,
    private val onCommitted: (Map<String, *>) -> Unit = {}
) : SharedPreferences by backing {
    var initializationFailed = false
        private set

    init {
        try {
            val data = if (file.exists()) PreferenceSnapshot.read(file) else backing.all
            val editor = backing.edit().clear()
            data.forEach { (key,value) -> when(value) {
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key,value as Set<String>) }
            } }
            if (!editor.commit()) throw IOException("preferences_mirror")
            PreferenceSnapshot.write(file,backing.all)
        } catch (_: Exception) { initializationFailed=true }
    }

    override fun edit(): SharedPreferences.Editor {
        // One editor instance must receive every put/remove and commit.
        val editor = backing.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?) = apply { editor.putString(key,value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { editor.putStringSet(key,values) }
            override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key,value) }
            override fun putInt(key: String?, value: Int) = apply { editor.putInt(key,value) }
            override fun putLong(key: String?, value: Long) = apply { editor.putLong(key,value) }
            override fun putFloat(key: String?, value: Float) = apply { editor.putFloat(key,value) }
            override fun remove(key: String?) = apply { editor.remove(key) }
            override fun clear() = apply { editor.clear() }
            override fun commit(): Boolean = synchronized(this@UnifiedPreferences) {
                if (!editor.commit()) return@synchronized false
                try {
                    PreferenceSnapshot.write(file,backing.all)
                    onCommitted(backing.all)
                    true
                }
                catch (_: IOException) { false }
            }
            // The app only applies small rejection-record removals; durability matters across mode changes.
            override fun apply() { commit() }
        }
    }

    companion object {
        private val stores = mutableMapOf<String,UnifiedPreferences>()
        @Synchronized
        fun open(context: Context, name: String, mode: Int = Context.MODE_PRIVATE): UnifiedPreferences {
            val app = context.applicationContext
            val file = File(app.filesDir,"unified-preferences/$name.properties")
            return stores.getOrPut(file.absolutePath) {
                val backing = app.getSharedPreferences(name,mode)
                UnifiedPreferences(backing,file) { values ->
                    if (name == "portal") ModuleBridge.localCommitted(values)
                }
            }
        }
    }
}
