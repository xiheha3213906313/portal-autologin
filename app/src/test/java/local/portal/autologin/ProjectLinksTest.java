package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.util.*;

public class ProjectLinksTest {
    @Test public void historyMetadataUsesOnlyExactProjectUrlsAndBoundedPages() {
        assertTrue(ProjectLinks.metadataUrl(ProjectLinks.LATEST_API));
        assertTrue(ProjectLinks.metadataUrl(ProjectLinks.releasesApi(1)));
        assertTrue(ProjectLinks.metadataUrl(ProjectLinks.releasesApi(ReleaseHistory.MAX_PAGES)));
        for(String value:Arrays.asList(ProjectLinks.LATEST_API+"?page=1",
            ProjectLinks.releasesApi(1).replace("api.github.com","evil.example"),
            ProjectLinks.releasesApi(1).replace("https:","http:"),
            ProjectLinks.releasesApi(1)+"&unexpected=true"))assertFalse(ProjectLinks.metadataUrl(value));
        for(int page:Arrays.asList(0,ReleaseHistory.MAX_PAGES+1)) {
            try{ProjectLinks.releasesApi(page);fail();}catch(IllegalArgumentException expected){}
        }
    }
    private Map<String,String> query(String url) throws Exception {
        Map<String,String> q=new HashMap<>();
        for(String part:new URI(url).getRawQuery().split("&")) {
            String[] pair=part.split("=",2);q.put(pair[0],URLDecoder.decode(pair[1],"UTF-8"));
        }
        return q;
    }
    @Test public void projectNameIsRequiredAndMustBeSingleLine() {
        for(String name:Arrays.asList("","   ","first\nsecond")) {
            try{ProjectLinks.issueDraft(name,"user","pass");fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void draftUsesOnlyOfficialIssuePageAndPreservesExactCredentials() throws Exception {
        String user="user+&=#甲",pass=" pass+&=#\\密 ";
        String url=ProjectLinks.issueDraft(" 项目甲 ",user,pass);
        URI uri=new URI(url);assertEquals("https",uri.getScheme());assertEquals("github.com",uri.getHost());
        assertEquals("/xiheha3213906313/portal-autologin/issues/new",uri.getPath());
        Map<String,String> q=query(url);assertEquals(2,q.size());assertEquals("[账号分享] 项目甲",q.get("title"));
        assertTrue(q.get("body").contains("\n"+user+"\n"));assertTrue(q.get("body").contains("\n"+pass+"\n"));
    }
    @Test public void embeddedMarkdownFencesDoNotBreakCredentialBlocks() throws Exception {
        String body=query(ProjectLinks.issueDraft("project","user","a```b")).get("body");
        assertTrue(body.contains("````\na```b\n````"));
    }
}
