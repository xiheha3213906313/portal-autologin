package local.portal.autologin

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class RemoteConfigMirrorTest {
    private fun snapshot(vararg values: Pair<String, Any>) = RemoteConfigMirror.snapshot(mapOf(*values))

    @Test fun onlyCurrentCredentialsAndModuleSwitchesAreShared() {
        val data = snapshot("username" to "guest", "password" to "secret", "enabled" to true,
            "silent" to true, "debug_log" to true, "accounts" to "private-history",
            "background_wifi" to "private-ssid", "background_login" to true,
            "submitted_network" to 123L, "background_setup_completed" to true)
        assertEquals(setOf("username", "password", "enabled", "silent", "debug_log"), data.keys)
        assertEquals("guest", data["username"])
        assertEquals("secret", data["password"])
        assertEquals(true, data["silent"])
    }

    @Test fun invalidTypesFailClosedWithoutPublishingObjects() {
        val data = snapshot("username" to 5, "password" to true, "enabled" to "true", "silent" to Any())
        assertEquals("", data["username"])
        assertEquals("", data["password"])
        assertEquals(false, data["enabled"])
        assertEquals(false, data["silent"])
    }

    @Test fun firstBindPublishesASingleCompleteConfiguration() {
        val store = RemoteStore()
        val data = snapshot("username" to "guest", "password" to "secret", "enabled" to true)
        assertTrue(RemoteConfigMirror(store).write(data))
        assertEquals(data, store.all)
        assertEquals(1, store.commits)
    }

    @Test fun reopeningWithTheSameConfigurationDoesNotReTriggerHooks() {
        val data = snapshot("username" to "guest", "enabled" to true)
        val store = RemoteStore(data.toMutableMap())
        assertTrue(RemoteConfigMirror(store).write(data))
        assertEquals(0, store.commits)
    }

    @Test fun logOnlyChangesDoNotRewriteCredentialOrAuthenticationKeys() {
        val data = snapshot("username" to "guest", "password" to "secret", "enabled" to true)
        val store = RemoteStore(data.toMutableMap())
        val mirror = RemoteConfigMirror(store)
        assertTrue(mirror.write(data + ("debug_log" to true)))
        assertEquals(setOf("debug_log"), store.written)
        assertTrue(store.removed.isEmpty())
    }

    @Test fun deletingConfigurationRemovesCredentialsAndDisablesTheModule() {
        val store = RemoteStore(snapshot("username" to "old", "password" to "secret", "enabled" to true,
            "silent" to true).toMutableMap())
        assertTrue(RemoteConfigMirror(store).write(snapshot()))
        assertEquals("", store.getString("username", null))
        assertEquals("", store.getString("password", null))
        assertFalse(store.getBoolean("enabled", true))
        assertFalse(store.getBoolean("silent", true))
    }

    @Test fun failedCommitIsRetriedEvenWhenRemoteCacheAlreadyChanged() {
        val store = RemoteStore()
        val mirror = RemoteConfigMirror(store)
        val data = snapshot("username" to "guest", "enabled" to true)
        store.failAfterUpdate = true
        assertFalse(mirror.write(data))
        assertEquals(data, store.all)
        store.failAfterUpdate = false
        assertTrue(mirror.write(data))
        assertEquals(2, store.commits)
        assertTrue(mirror.write(data))
        assertEquals(2, store.commits)
    }

    @Test fun revertingAfterUnknownCommitStillWritesThePreviousSnapshot() {
        val old = snapshot("username" to "old", "enabled" to true)
        val store = RemoteStore(old.toMutableMap())
        val mirror = RemoteConfigMirror(store)
        store.failAfterUpdate = true
        assertFalse(mirror.write(old + ("username" to "new")))
        store.failAfterUpdate = false
        assertTrue(mirror.write(old))
        assertEquals(2, store.commits)
        assertEquals(old, store.all)
    }

    @Test fun obsoleteRemoteKeysAreRemovedWithoutClearingUnchangedCredentials() {
        val data = snapshot("username" to "guest", "enabled" to true)
        val store = RemoteStore((data + ("old_history" to "obsolete")).toMutableMap())
        assertTrue(RemoteConfigMirror(store).write(data))
        assertEquals(setOf("old_history"), store.removed)
        assertTrue(store.written.isEmpty())
        assertEquals(data, store.all)
    }

    @Test fun thrownCommitDoesNotTreatTheOldSnapshotAsAcknowledged() {
        val old = snapshot("username" to "old", "enabled" to true)
        val store = RemoteStore(old.toMutableMap())
        val mirror = RemoteConfigMirror(store)
        store.throwAfterUpdate = true
        try {
            mirror.write(old + ("username" to "new"))
            fail("Expected a Binder-like failure")
        } catch (_: IllegalStateException) { }
        store.throwAfterUpdate = false
        assertTrue(mirror.write(old))
        assertEquals(2, store.commits)
        assertEquals(old, store.all)
    }

    private class RemoteStore(
        initial: MutableMap<String, Any> = mutableMapOf(),
        private val backing: SharedPreferences = UnifiedPreferencesTest.MemoryPreferences(initial)
    ) : SharedPreferences by backing {
        var commits = 0
        var failAfterUpdate = false
        var throwAfterUpdate = false
        val written = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?) = apply {
                    written += requireNotNull(key); editor.putString(key, value)
                }
                override fun putBoolean(key: String?, value: Boolean) = apply {
                    written += requireNotNull(key); editor.putBoolean(key, value)
                }
                override fun remove(key: String?) = apply {
                    removed += requireNotNull(key); editor.remove(key)
                }
                override fun clear(): SharedPreferences.Editor = error("Do not clear unchanged module settings")
                override fun commit(): Boolean {
                    commits++; editor.commit()
                    if (throwAfterUpdate) throw IllegalStateException("Binder-like failure")
                    return !failAfterUpdate
                }
            }
        }
    }
}
