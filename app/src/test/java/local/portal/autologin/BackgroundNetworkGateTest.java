package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundNetworkGateTest {
    @Test public void validationAfterUncertainSubmissionAllowsProbesWithoutClearingPostProtection() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        LoginAttempts attempts=new LoginAttempts();
        gate.observe(10,true,false);assertTrue(gate.begin(10,1000,false));
        assertEquals(LoginAttempts.Decision.ALLOW,attempts.begin(10,1000,-1,-1,false));
        attempts.submitted(10,1100);attempts.finish(10,false);gate.completed(10,false);
        gate.observe(10,false,true);
        assertTrue(gate.beginOnlineRecovery(10,75000));
        assertFalse(gate.begin(10,75001,false));
        assertEquals(LoginAttempts.Decision.RECENT_SUBMISSION,attempts.begin(10,75001,10,1100,false));
        gate.completed(10,true);assertFalse(gate.beginOnlineRecovery(10,76000));
    }
    @Test public void repeatedValidationSignalsCannotLoopFailedVerification() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,true,false);gate.begin(10,1000,false);gate.completed(10,false);
        gate.observe(10,false,true);assertTrue(gate.beginOnlineRecovery(10,2000));
        assertFalse(gate.beginOnlineRecovery(10,2100));gate.completed(10,false);
        gate.observe(10,true,false);gate.observe(10,false,true);
        assertFalse(gate.beginOnlineRecovery(10,3000));
        assertFalse(gate.begin(10,32000,false));
        assertTrue(gate.beginOnlineRecovery(10,32000));
    }
    @Test public void onlineOrLostNetworkDoesNotStartAnUnrelatedRecovery() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        assertFalse(gate.beginOnlineRecovery(10,1000));
        gate.observe(10,false,true);gate.begin(10,1000,false);gate.completed(10,true);
        assertFalse(gate.beginOnlineRecovery(10,2000));gate.lost(10);
        assertFalse(gate.beginOnlineRecovery(10,40000));
    }
    private static void startOnline(BackgroundNetworkGate gate, long network) {
        gate.observe(network,false,true);
        assertTrue(gate.begin(network,1000,false));
        gate.completed(network,true);
    }
    @Test public void repeatedCapabilitiesAndLinkEventsShareOneAttempt() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,false,true);
        assertTrue(gate.begin(10,1000,false));
        assertTrue(gate.isRunning(10));
        assertFalse(gate.begin(10,1200,false));
        assertFalse(gate.begin(10,1300,true));
        gate.completed(10,true);
        assertFalse(gate.isRunning(10));
        assertFalse(gate.begin(10,32000,false));
        assertEquals(-1,gate.nextCheckDelay(10,32000));
    }
    @Test public void failedAuthenticationIsNotRepeatedByCaptiveFlagFlapping() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,true,false);
        assertTrue(gate.begin(10,1000,false));gate.completed(10,false);
        gate.observe(10,false,true);
        assertFalse(gate.begin(10,32000,false));
        gate.observe(10,true,false);
        assertFalse(gate.begin(10,33000,false));
    }
    @Test public void reconnectionAllowsAnotherAttempt() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,true,false);
        gate.begin(10,1000,false);gate.completed(10,false);gate.lost(10);
        gate.observe(10,true,false);
        assertTrue(gate.begin(10,2000,false));
        gate.observe(11,true,false);
        assertTrue(gate.begin(11,2100,false));
    }
    @Test public void portalReturningAfterSuccessfulConnectionCanAuthenticateAgain() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,true,false);
        assertTrue(gate.begin(10,32000,false));
        gate.completed(10,false);
        assertFalse(gate.begin(10,33000,false));
    }
    @Test public void losingValidationWithoutCaptiveFlagStillChecksAgain() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,false,false);
        assertFalse(gate.isRunning(10));
        assertTrue(gate.begin(10,32000,false));
    }
    @Test public void quickCapabilityRecoveryDoesNotEraseThePendingCheck() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,false,false);
        gate.observe(10,false,true);
        gate.completed(10,true); // Another foreground success must not erase the captured change.
        assertTrue(gate.begin(10,32000,false));
        gate.completed(10,true);
        assertFalse(gate.begin(10,33000,false));
    }
    @Test public void eventWithinCooldownIsDeferredRatherThanForgotten() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,true,false);
        assertEquals(29000,gate.nextCheckDelay(10,2000));
        assertFalse(gate.begin(10,2000,false));
        gate.observe(10,true,false);
        assertEquals(27000,gate.nextCheckDelay(10,4000));
        assertTrue(gate.begin(10,31000,false));
        assertEquals(-1,gate.nextCheckDelay(10,32000));
    }
    @Test public void changesDuringAnInFlightCheckWaitForItsResult() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,false,true);gate.begin(10,1000,false);
        gate.observe(10,false,false);
        assertEquals(-1,gate.nextCheckDelay(10,32000));
        gate.completed(10,true);
        assertTrue(gate.begin(10,32000,false));
    }
    @Test public void failedInFlightCheckDiscardsPendingAutomaticRetry() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,false,true);gate.begin(10,1000,false);
        gate.observe(10,true,false);gate.completed(10,false);
        assertEquals(-1,gate.nextCheckDelay(10,32000));
        assertFalse(gate.begin(10,32000,false));
    }
    @Test public void freshForegroundOnlineProofRestoresMonitoringAfterManualRecovery() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,true,false);gate.begin(10,1000,false);gate.completed(10,false);
        gate.observe(10,false,true);
        gate.completed(10,true);
        gate.observe(10,false,false);
        assertTrue(gate.begin(10,32000,false));
    }
    @Test public void rapidPortalChangesCannotCreateARequestLoop() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,true,false);
        assertFalse(gate.begin(10,2000,false));
        gate.observe(10,false,true);
        assertFalse(gate.begin(10,2100,false));
        gate.observe(10,true,false);
        assertFalse(gate.begin(10,2200,false));
        assertTrue(gate.begin(10,31000,false));
        gate.completed(10,true);
        assertFalse(gate.begin(10,61000,false));
    }
    @Test public void explicitRetryAndSavedConfigurationCanResetFailure() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        gate.observe(10,true,false);gate.begin(10,1000,false);gate.completed(10,false);
        assertTrue(gate.begin(10,2000,true));
        gate.completed(10,false);gate.clear();
        assertFalse(gate.wasChecked(10));assertTrue(gate.begin(10,3000,false));
    }
    @Test public void lostNetworkDoesNotLeaveADeferredCheckBehind() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();startOnline(gate,10);
        gate.observe(10,false,false);
        assertEquals(29000,gate.nextCheckDelay(10,2000));
        gate.lost(10);
        assertFalse(gate.wasChecked(10));
        assertTrue(gate.begin(10,3000,false));
    }
    @Test public void oldNetworksAreBounded() {
        BackgroundNetworkGate gate=new BackgroundNetworkGate();
        for(int i=0;i<20;i++){gate.observe(i,false,true);gate.begin(i,i*1000,false);gate.completed(i,true);}
        assertFalse(gate.wasChecked(0));assertTrue(gate.wasChecked(19));
    }
}
