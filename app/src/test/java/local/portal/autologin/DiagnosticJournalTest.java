package local.portal.autologin;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class DiagnosticJournalTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static String line(int value){return "2026-10-31 09:00:00.000 test.stage "+value;}
    @After public void detach(){DiagnosticLog.attachJournal(null);DiagnosticLog.setEnabled(false);DiagnosticLog.clear();}

    @Test public void restartReadsCompletedRecordsAndRotationRemainsBounded() throws Exception {
        File dir=temp.newFolder();DiagnosticJournal journal=new DiagnosticJournal(dir,512);
        for(int i=0;i<200;i++)journal.append(line(i));
        String saved=new DiagnosticJournal(dir,512).read();
        assertTrue(saved.contains(line(199)));assertFalse(saved.contains(line(0)));
        File[] segments=dir.listFiles();assertNotNull(segments);assertEquals(2,segments.length);
        for(File segment:segments)assertTrue(segment.length()<=512);
    }

    @Test public void partialLastWriteIsDiscardedWithoutLosingPriorOrNextRecord() throws Exception {
        File dir=temp.newFolder();new DiagnosticJournal(dir).append(line(1));
        Files.write(new File(dir,"application.log").toPath(),"partial-private-value".getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND);
        DiagnosticJournal restored=new DiagnosticJournal(dir);
        assertEquals(line(1)+"\n",restored.read());
        restored.append(line(2));
        assertEquals(line(1)+"\n"+line(2)+"\n",restored.read());
    }

    @Test public void clearingRemovesCurrentAndPreviousAcrossAnotherRestart() throws Exception {
        File dir=temp.newFolder();DiagnosticJournal journal=new DiagnosticJournal(dir,256);
        for(int i=0;i<30;i++)journal.append(line(i));
        journal.clear();assertEquals("",new DiagnosticJournal(dir,256).read());
        journal.append(line(40));assertEquals(line(40)+"\n",new DiagnosticJournal(dir,256).read());
    }

    @Test public void diskAndExportRejectArbitraryMessagesAndDisabledLoggingDoesNotGrow() throws Exception {
        File dir=temp.newFolder();DiagnosticLog.attachJournal(dir);
        DiagnosticLog.setEnabled(true);
        DiagnosticLog.event("https://host/?password=PRIVATE",1);
        DiagnosticLog.failure(new IOException("password=PRIVATE cookie=PRIVATE"));
        DiagnosticLog.event("test.valid",42);
        DiagnosticLog.setEnabled(false);
        String before=new DiagnosticJournal(dir).read();
        DiagnosticLog.event("test.disabled",5);
        assertEquals(before,new DiagnosticJournal(dir).read());
        DiagnosticLog.attachJournal(dir);String exported=DiagnosticLog.snapshot();
        assertTrue(exported.contains("test.valid 42"));assertTrue(exported.contains("error.ioexception"));
        assertFalse(exported.contains("PRIVATE"));assertFalse(exported.contains("test.disabled"));
    }

    @Test public void journalFailureCannotBreakAuthenticationOrEraseMemoryFallback() throws Exception {
        File blocked=temp.newFile();DiagnosticLog.attachJournal(blocked);DiagnosticLog.setEnabled(true);
        DiagnosticLog.event("test.important",7);
        String snapshot=DiagnosticLog.snapshot();
        assertTrue(snapshot.contains("persistence=error"));assertTrue(snapshot.contains("test.important 7"));
    }

    @Test public void processHaltWithoutShutdownHooksRetainsLastStageAndSanitizedCrash() throws Exception {
        File dir=temp.newFolder();
        String separator=File.pathSeparator;
        String classpath=new File(DiagnosticLog.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()+
            separator+new File(CrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),
            "-cp",classpath,CrashProbe.class.getName(),dir.getPath()).redirectErrorStream(true)
            .redirectOutput(new File(temp.getRoot(),"child-output.txt")).start();
        try {
            assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(17,child.exitValue());
        } finally {child.destroyForcibly();}
        String saved=new DiagnosticJournal(dir).read();
        assertTrue(saved.contains("test.before_halt 71"));assertTrue(saved.contains("crash.uncaught 1"));
        assertTrue(saved.contains("crash.frame.backgroundloginservice.onstartcommand 123"));
        assertFalse(saved.contains("PRIVATE"));
    }

    public static class CrashProbe {
        public static void main(String[] args) {
            DiagnosticLog.attachJournal(new File(args[0]));DiagnosticLog.setEnabled(true);
            DiagnosticLog.event("test.before_halt",71);
            RuntimeException failure=new RuntimeException("PRIVATE_COOKIE_AND_PASSWORD");
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "local.portal.autologin.BackgroundLoginService","onStartCommand","BackgroundLoginService.kt",123)});
            DiagnosticLog.crash(failure,true);
            Runtime.getRuntime().halt(17);
        }
    }
}
