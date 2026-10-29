package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.IOException;

public class ReleaseHistoryTest {
    private ReleaseHistory.Entry entry(String version,String notes) {
        return new ReleaseHistory.Entry(version,"## 更新内容\n"+notes+"\n## 安装与验证\nignore",false,false);
    }
    private ReleaseHistory.Page page(boolean more,ReleaseHistory.Entry... entries) {
        return new ReleaseHistory.Page(Arrays.asList(entries),more);
    }
    @Test public void gathersOnlyVersionsAfterInstalledThroughLatestInNumericDescendingOrder() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.2",entry("v1.10","latest"),n->page(false,
            entry("1.2","installed"),entry("1.9","middle"),entry("1.3","first"),entry("1.11","future"),entry("1.1","old")));
        assertTrue(r.complete);
        assertEquals("v1.10\nlatest\n\nv1.9\nmiddle\n\nv1.3\nfirst",r.notes);
    }
    @Test public void paginationContinuesPastOldVersionsAndIgnoresPublicationOrder() {
        List<Integer> requested=new ArrayList<>();
        ReleaseHistory.Result r=ReleaseHistory.collect("1.2",entry("1.4","newest"),n->{
            requested.add(n);return n==1?page(true,entry("1.1","old")):page(false,entry("1.3","late published"));
        });
        assertEquals(Arrays.asList(1,2),requested);assertTrue(r.complete);
        assertTrue(r.notes.contains("v1.3\nlate published"));assertFalse(r.notes.contains("v1.1"));
    }
    @Test public void draftsPrereleasesAndNonVersionTagsAreExcluded() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("1.4","latest"),n->page(false,
            new ReleaseHistory.Entry("1.3","draft",true,false),new ReleaseHistory.Entry("1.2","preview",false,true),
            entry("nightly","nightly"),entry("1.1-rc1","rc"),entry("1.1","stable")));
        assertTrue(r.complete);assertTrue(r.notes.contains("stable"));
        for(String text:Arrays.asList("draft","preview","nightly","rc"))assertFalse(r.notes.contains(text));
    }
    @Test public void equivalentVersionTagsAreDeduplicatedAndLatestMetadataWins() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("v1.3","authoritative"),n->page(false,
            entry("1.3.0","stale"),entry("1.2","first"),entry("v1.2.0","duplicate")));
        assertEquals("v1.3\nauthoritative\n\nv1.2\nfirst",r.notes);
    }
    @Test public void laterPageFailureRetainsFetchedNotesAndReportsIncompleteHistory() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("1.3","latest"),n->{
            if(n==2)throw new IOException("offline");return page(true,entry("1.2","loaded"));
        });
        assertFalse(r.complete);assertTrue(r.notes.contains("latest"));assertTrue(r.notes.contains("loaded"));
    }
    @Test public void firstPageFailureStillKeepsLatestNotes() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("1.3","latest"),n->{throw new IOException();});
        assertEquals("v1.3\nlatest",r.notes);assertFalse(r.complete);
    }
    @Test public void pageLimitIsBoundedAndNeverClaimsCompleteness() {
        int[] requests={0};
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("1.3","latest"),n->{requests[0]++;return page(true);});
        assertEquals(ReleaseHistory.MAX_PAGES,requests[0]);assertFalse(r.complete);
    }
    @Test public void alreadyCurrentSkipsHistoryRequests() {
        ReleaseHistory.Result r=ReleaseHistory.collect("1.3.0",entry("1.3","same"),n->{throw new AssertionError("unneeded request");});
        assertTrue(r.complete);assertEquals("same",r.notes);
    }
    @Test public void extractsFullSectionIncludingNestedHeadingsAndNormalizesLineEndings() {
        String body="## 安装包下载\r\nlink\r\n## 更新内容 ##\r\nfirst\r\n### 细节\r\nnested\r\n## 安装\r\nignore";
        assertEquals("first\n### 细节\nnested",ReleaseHistory.section(body));
    }
    @Test public void notesBeyondOldTwoThousandCharacterLimitArePreserved() {
        char[] chars=new char[3500];Arrays.fill(chars,'a');String notes=new String(chars)+"END";
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",entry("1.1",notes),n->page(false));
        assertTrue(r.notes.endsWith("END"));assertTrue(r.notes.length()>3500);
    }
    @Test public void oldPlainTextReleaseBodiesAndMissingNotesRemainReadable() {
        assertEquals("- old changes",ReleaseHistory.section("\n- old changes\n"));
        ReleaseHistory.Result r=ReleaseHistory.collect("1.0",new ReleaseHistory.Entry("1.2",null,false,false),
            n->page(false,entry("1.1","older changes")));
        assertTrue(r.notes.contains("v1.2\n此版本未提供更新说明"));assertTrue(r.notes.contains("older changes"));
    }
}
