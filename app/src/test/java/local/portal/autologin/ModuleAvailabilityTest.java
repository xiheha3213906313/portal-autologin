package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModuleAvailabilityTest {
    private static final class Preferences {
        boolean cached;
        int publicOpens;
        void openPublic() {
            publicOpens++;
            // Android may return an existing preferences instance before checking the mode.
            if (!cached) throw new SecurityException("World readable mode denied");
        }
        void openPrivate() { cached = true; }
    }

    @Test public void ordinaryModeDoesNotBecomeModuleModeAfterPrivatePreferencesAreCached() {
        Preferences preferences = new Preferences();
        ModuleAvailability availability = new ModuleAvailability();
        assertFalse(availability.detect(preferences::openPublic));
        preferences.openPrivate();
        assertFalse(availability.detect(preferences::openPublic));
        assertFalse(availability.detect(preferences::openPublic));
        assertEquals(1, preferences.publicOpens);
    }

    @Test public void supportedModuleRemainsAvailableForActivityAndService() {
        ModuleAvailability availability = new ModuleAvailability();
        assertTrue(availability.detect(() -> {}));
        assertTrue(availability.detect(() -> { throw new SecurityException(); }));
    }

    @Test public void unrelatedInitializationFaultIsNotSilentlyTreatedAsOrdinaryMode() {
        ModuleAvailability availability = new ModuleAvailability();
        try {
            availability.detect(() -> { throw new IllegalStateException(); });
            fail("Expected initialization failure");
        } catch (IllegalStateException expected) { }
        assertTrue(availability.detect(() -> {}));
    }
}
