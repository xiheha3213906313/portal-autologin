package local.portal.autologin

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UnifiedPreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.root,"preferences.properties")

    @Test fun configurationAndHistorySurviveBothModeChanges() {
        val normal = UnifiedPreferences(MemoryPreferences(),file())
        assertTrue(normal.edit().putString("username","test-user").putString("password","test-secret")
            .putBoolean("enabled",true).putString("book","saved-history").commit())
        val moduleMirror = MemoryPreferences(mutableMapOf("username" to "stale"))
        val module = UnifiedPreferences(moduleMirror,file())
        assertEquals("test-user",module.getString("username",null))
        assertEquals("saved-history",module.getString("book",null))
        assertTrue(moduleMirror.getBoolean("enabled",false))
        assertTrue(module.edit().putString("password","updated-secret").commit())
        val normalAgain=UnifiedPreferences(MemoryPreferences(),file())
        assertEquals("updated-secret",normalAgain.getString("password",null))
    }

    @Test fun removedOrClearedDataDoesNotReturnFromAnOldMirror() {
        val prefs=UnifiedPreferences(MemoryPreferences(),file())
        assertTrue(prefs.edit().putString("username","test-user").putString("password","test-secret").commit())
        prefs.edit().remove("password").apply()
        val old=MemoryPreferences(mutableMapOf("password" to "stale-secret"))
        val reopened=UnifiedPreferences(old,file())
        assertFalse(reopened.contains("password"))
        assertTrue(reopened.edit().clear().commit())
        assertTrue(UnifiedPreferences(MemoryPreferences(mutableMapOf("username" to "stale")),file()).all.isEmpty())
    }

    @Test fun preservesTypesAndUnicodeAcrossProcessReopen() {
        val values=mapOf<String,Any>("账号" to "name\n=雪", "enabled" to true,
            "boot" to 3, "submitted_at" to 1234567890123L, "ratio" to .5f, "set" to setOf("a\nb","中文"))
        PreferenceSnapshot.write(file(),values)
        assertEquals(values,PreferenceSnapshot.read(file()))
        val reopened=UnifiedPreferences(MemoryPreferences(),file())
        assertEquals(1234567890123L,reopened.getLong("submitted_at",-1))
        assertEquals(3,reopened.getInt("boot",-1))
    }

    @Test fun failedNativeCommitDoesNotReplaceStableData() {
        val mirror=MemoryPreferences()
        val prefs=UnifiedPreferences(mirror,file())
        assertTrue(prefs.edit().putString("username","saved").commit())
        mirror.failCommit=true
        assertFalse(prefs.edit().putString("username","unsaved").commit())
        assertEquals("saved",PreferenceSnapshot.read(file())["username"])
    }

    @Test fun corruptSnapshotIsReportedAndPreserved() {
        file().writeText("format=unknown\n")
        val prefs=UnifiedPreferences(MemoryPreferences(mutableMapOf("username" to "mirror")),file())
        assertTrue(prefs.initializationFailed)
        assertEquals("mirror",prefs.getString("username",null))
        assertEquals("format=unknown\n",file().readText())
    }

    @Test fun invalidReplacementKeepsLastAtomicSnapshot() {
        PreferenceSnapshot.write(file(),mapOf("username" to "saved"))
        try { PreferenceSnapshot.write(file(),mapOf("invalid" to Any())); fail() }
        catch (_: IOException) { }
        assertEquals("saved",PreferenceSnapshot.read(file())["username"])
        assertFalse(temp.root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun remoteNotificationSeesOnlyDurablyCommittedLocalSnapshot() {
        val notifications = mutableListOf<Map<String, *>>()
        val backing = MemoryPreferences()
        val local = file()
        val prefs = UnifiedPreferences(backing, local) { values ->
            assertEquals(PreferenceSnapshot.read(local), values)
            notifications += values.toMap()
        }
        assertTrue(prefs.edit().putString("username", "guest").putString("password", "secret").commit())
        assertEquals(1, notifications.size)
        assertEquals("secret", notifications.single()["password"])
        backing.failCommit = true
        assertFalse(prefs.edit().putString("username", "unsaved").commit())
        assertEquals(1, notifications.size)
        backing.failCommit = false
        assertTrue(prefs.edit().clear().commit())
        assertTrue(notifications.last().isEmpty())
    }

    @Test fun failedSnapshotWriteDoesNotPublishAnUndurableConfiguration() {
        var notified = false
        val local = file()
        val prefs = UnifiedPreferences(MemoryPreferences(), local) { notified = true }
        assertTrue(local.delete())
        assertTrue(local.mkdir())
        assertFalse(prefs.edit().putString("username", "unsaved").commit())
        assertFalse(notified)
    }

    internal class MemoryPreferences(private val values: MutableMap<String,Any> = mutableMapOf()) : SharedPreferences {
        var failCommit=false
        override fun getAll(): MutableMap<String,*> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes=mutableMapOf<String,Any?>()
            private var clear=false
            override fun putString(key: String?, value: String?) = apply { changes[requireNotNull(key)]=value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { changes[requireNotNull(key)]=values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { changes[requireNotNull(key)]=value }
            override fun putLong(key: String?, value: Long) = apply { changes[requireNotNull(key)]=value }
            override fun putFloat(key: String?, value: Float) = apply { changes[requireNotNull(key)]=value }
            override fun putBoolean(key: String?, value: Boolean) = apply { changes[requireNotNull(key)]=value }
            override fun remove(key: String?) = apply { changes[requireNotNull(key)]=null }
            override fun clear() = apply { clear=true }
            override fun commit(): Boolean {
                if (failCommit) return false
                if (clear) values.clear()
                changes.forEach { (key,value) -> if(value==null) values.remove(key) else values[key]=value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
