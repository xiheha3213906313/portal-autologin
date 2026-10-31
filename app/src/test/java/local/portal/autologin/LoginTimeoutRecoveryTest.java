package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoginTimeoutRecoveryTest {
    @Test public void delayedSubmittedJobGetsOnlyOneFinalVerificationWindow() {
        LoginTimeoutRecovery recovery=new LoginTimeoutRecovery();
        assertTrue(recovery.begin(true,true));
        assertFalse(recovery.begin(true,true));
        assertFalse(recovery.begin(true,true));
    }
    @Test public void unsubmittedTimeoutNeverAuthorizesThisRecoveryPath() {
        LoginTimeoutRecovery recovery=new LoginTimeoutRecovery();
        assertFalse(recovery.begin(false,true));
        assertTrue(recovery.begin(true,true));
    }
    @Test public void disconnectedNetworkCannotConsumeOrStartVerification() {
        LoginTimeoutRecovery recovery=new LoginTimeoutRecovery();
        assertFalse(recovery.begin(true,false));
        assertTrue(recovery.begin(true,true));
    }
}
