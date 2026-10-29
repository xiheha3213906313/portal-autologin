package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import static local.portal.autologin.BackgroundStartPolicy.Status.*;

public class BackgroundStartPolicyTest {
    @Test public void permissionsDoNotEnableAnUnsavedSwitch() {
        assertEquals(DISABLED, BackgroundStartPolicy.evaluate(false, false, true, "Guest", true, true, true));
    }
    @Test public void emptyFilterNeedsNoLocationGrant() {
        assertEquals(READY, BackgroundStartPolicy.evaluate(false, true, true, "", false, false, true));
        assertEquals(READY, BackgroundStartPolicy.evaluate(false, true, true, "　 ", false, false, true));
    }
    @Test public void accountMustHaveBeenSaved() {
        assertEquals(MISSING_ACCOUNT, BackgroundStartPolicy.evaluate(false, true, false, "", true, true, true));
    }
    @Test public void namedWifiRequiresPreciseLocation() {
        assertEquals(LOCATION_PERMISSION_REQUIRED, BackgroundStartPolicy.evaluate(false, true, true, "Guest", false, true, true));
    }
    @Test public void locationSwitchIsADistinctPrerequisite() {
        assertEquals(LOCATION_DISABLED, BackgroundStartPolicy.evaluate(false, true, true, "Guest", true, false, true));
        assertEquals(READY, BackgroundStartPolicy.evaluate(false, true, true, "Guest", true, true, true));
    }
    @Test public void invalidNameMustNotBecomeMatchAll() {
        assertEquals(INVALID_FILTER, BackgroundStartPolicy.evaluate(false, true, true, "-_*", true, true, true));
    }
    @Test public void supportedModuleKeepsOrdinaryBackgroundServiceOff() {
        assertEquals(MODULE_ACTIVE, BackgroundStartPolicy.evaluate(true, true, true, "", true, true, true));
    }
    @Test public void backgroundRequiresNotificationDisplayEvenWithoutWifiName() {
        assertEquals(NOTIFICATION_REQUIRED, BackgroundStartPolicy.evaluate(false, true, true, "", false, false, false));
    }
    @Test public void grantingNotificationsChangesUnauthorizedToReadyWithoutOnboardingAcknowledgement() {
        BackgroundStartPolicy.Status before = BackgroundStartPolicy.evaluate(false, true, true, "", false, false, false);
        assertEquals("未授权", BackgroundStartPolicy.label(before, false));
        BackgroundStartPolicy.Status after = BackgroundStartPolicy.evaluate(false, true, true, "", false, false, true);
        assertEquals(READY, after);
        assertEquals("运行中", BackgroundStartPolicy.label(after, true));
    }
    @Test public void missingAccountTakesPriorityOverMissingAuthorization() {
        assertEquals(MISSING_ACCOUNT, BackgroundStartPolicy.evaluate(false, true, false, "", false, false, false));
    }
    @Test public void titleLabelsDistinguishConfigurationAndGrantsFromRuntime() {
        assertEquals("未配置", BackgroundStartPolicy.label(MISSING_ACCOUNT, false));
        assertEquals("未配置", BackgroundStartPolicy.label(INVALID_FILTER, false));
        for (BackgroundStartPolicy.Status status : new BackgroundStartPolicy.Status[]{LOCATION_PERMISSION_REQUIRED,
                LOCATION_DISABLED, NOTIFICATION_REQUIRED}) {
            assertEquals("未授权", BackgroundStartPolicy.label(status, false));
            assertEquals("未授权", BackgroundStartPolicy.label(status, true));
        }
        assertEquals("未运行", BackgroundStartPolicy.label(READY, false));
        assertEquals("运行中", BackgroundStartPolicy.label(READY, true));
    }
}
