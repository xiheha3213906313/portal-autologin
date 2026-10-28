package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import static local.portal.autologin.LoginAttempts.Decision.*;

public class LoginAttemptsTest {
    @Test public void repeatedForegroundEntriesShareOneActiveAttempt() {
        LoginAttempts a=new LoginAttempts();
        assertEquals(ALLOW,a.begin(10,1000,-1,-1,false));
        assertEquals(RUNNING,a.begin(10,1100,-1,-1,false));
        assertEquals(RUNNING,a.begin(10,1200,-1,-1,true));
    }
    @Test public void submittedPostIsNotReplayedEvenByRetryButton() {
        LoginAttempts a=new LoginAttempts();a.begin(10,1000,-1,-1,false);
        a.submitted(10,1200);a.finish(10,false);
        assertEquals(RECENT_SUBMISSION,a.begin(10,1500,-1,-1,false));
        assertEquals(RECENT_SUBMISSION,a.begin(10,2000,-1,-1,true));
        assertEquals(ALLOW,a.begin(10,301200,-1,-1,false));
    }
    @Test public void processRestartStillHonorsPersistedSubmission() {
        LoginAttempts freshProcess=new LoginAttempts();
        assertEquals(RECENT_SUBMISSION,freshProcess.begin(10,4000,10,2000,false));
        assertEquals(RECENT_SUBMISSION,freshProcess.begin(10,5000,10,2000,true));
        assertEquals(ALLOW,freshProcess.begin(11,5000,10,2000,false));
    }
    @Test public void failureBeforeSubmissionCanBeRetriedExplicitly() {
        LoginAttempts a=new LoginAttempts();a.begin(10,1000,-1,-1,false);a.finish(10,false);
        assertEquals(RECENT_FAILURE,a.begin(10,1500,-1,-1,false));
        assertEquals(ALLOW,a.begin(10,2000,-1,-1,true));
    }
    @Test public void correctedCredentialsCanRetryAfterDefiniteRejection() {
        LoginAttempts a=new LoginAttempts();a.begin(10,1000,-1,-1,false);
        a.submitted(10,1200);a.finish(10,true);
        assertEquals(ALLOW,a.begin(10,1500,-1,-1,true));
    }
    @Test public void expiredFailureCanRetryOnNextForegroundVisit() {
        LoginAttempts a=new LoginAttempts();a.begin(10,1000,-1,-1,false);a.finish(10,false);
        assertEquals(ALLOW,a.begin(10,16000,-1,-1,false));
    }
    @Test public void earlierUptimeDoesNotExtendCooldownAfterReboot() {
        LoginAttempts a=new LoginAttempts();
        assertEquals(ALLOW,a.begin(10,1000,10,100000,false));
        assertFalse(LoginAttempts.recent(1000,-1,300000));
    }
    @Test public void networkChangeKeepsOldSubmissionGuard() {
        LoginAttempts a=new LoginAttempts();a.begin(10,1000,-1,-1,false);
        a.submitted(10,1200);a.finish(10,false);
        assertEquals(ALLOW,a.begin(11,1500,-1,-1,false));a.finish(11,false);
        assertEquals(RECENT_SUBMISSION,a.begin(10,2000,-1,-1,false));
    }
}
