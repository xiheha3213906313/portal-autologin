package local.portal.autologin;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.IOException;

public class DiagnosticLogTest {
    @Before public void reset() {DiagnosticLog.setEnabled(false);DiagnosticLog.clear();}
    @Test public void disabledByDefaultAndStopsRecording() {
        DiagnosticLog.event("test.before",1);assertFalse(DiagnosticLog.snapshot().contains("test.before"));
        DiagnosticLog.setEnabled(true);DiagnosticLog.event("test.active",2);
        DiagnosticLog.setEnabled(false);DiagnosticLog.event("test.after",3);
        String s=DiagnosticLog.snapshot();assertTrue(s.contains("test.active"));assertFalse(s.contains("test.after"));
    }
    @Test public void boundedHistoryKeepsLatestAndCanClear() {
        DiagnosticLog.setEnabled(true);
        for(int i=0;i<1000;i++)DiagnosticLog.event("stage.entry",i);
        String s=DiagnosticLog.snapshot();assertFalse(s.contains("stage.entry 0\n"));assertTrue(s.contains("stage.entry 999"));
        assertTrue(s.split("\n").length<=261);
        DiagnosticLog.clear();assertFalse(DiagnosticLog.snapshot().contains("stage.entry"));
    }
    @Test public void rejectsFreeTextAndNeverExportsExceptionMessages() {
        DiagnosticLog.setEnabled(true);
        DiagnosticLog.event("https://host/?token=PRIVATE",1);
        DiagnosticLog.failure(new IOException("password=PRIVATE cookie=PRIVATE",new IllegalArgumentException("missing_frame")));
        String s=DiagnosticLog.snapshot();assertFalse(s.contains("PRIVATE"));assertFalse(s.contains("https://"));
        assertTrue(s.contains("reason.missing_frame"));assertTrue(s.contains("error.ioexception"));
    }
}
