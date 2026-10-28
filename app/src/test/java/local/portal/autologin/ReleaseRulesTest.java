package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ReleaseRulesTest {
    private static final String REPO="https://github.com/xiheha3213906313/portal-autologin/";
    @Test public void comparesNumericVersionsInsteadOfText() {
        assertTrue(ReleaseRules.compareVersions("v1.10","1.9")>0);
        assertEquals(0,ReleaseRules.compareVersions("1.2.0","1.2"));
        assertTrue(ReleaseRules.compareVersions("1.1","1.2")<0);
    }
    @Test public void refusesMalformedVersionTags() {
        for(String v:Arrays.asList("v1.2-rc1","latest","1.2<script>","1.9999999999")) {
            try{ReleaseRules.compareVersions(v,"1.2");fail(v);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void allowsOnlyApksFromTheProject() {
        assertTrue(ReleaseRules.download(REPO+"releases/download/v1.3/app.apk"));
        for(String url:Arrays.asList(REPO+"releases/download/v1.3/app.zip",REPO.replace("https:","http:")+"blob/main/app.apk",
            "https://evil.example/app.apk","https://github.com/other/repo/blob/main/app.apk",
            "https://user@github.com/xiheha3213906313/portal-autologin/blob/main/app.apk",
            REPO+"blob/main/../app.apk"))assertFalse(url,ReleaseRules.download(url));
    }
    @Test public void supportsExistingReleaseBodyDownloadsWithoutAssets() {
        String blob=REPO+"blob/85be2b5/release-assets/v1.1/portal-autologin-1.1.apk?raw=true";
        String result=ReleaseRules.markdownDownload("[source](https://evil.example/app.apk) [APK]("+blob+")");
        assertEquals("https://raw.githubusercontent.com/xiheha3213906313/portal-autologin/85be2b5/release-assets/v1.1/portal-autologin-1.1.apk",result);
    }
    @Test public void permitsOnlyHttpsGithubDownloadRedirects() {
        assertTrue(ReleaseRules.redirect("https://release-assets.githubusercontent.com/signed.apk?token=test"));
        assertTrue(ReleaseRules.redirect("https://objects.githubusercontent.com/file"));
        assertFalse(ReleaseRules.redirect("http://github.com/file"));
        assertFalse(ReleaseRules.redirect("https://release-assets.githubusercontent.com.evil.example/file"));
    }
    @Test public void releasePageMustBelongToThisRepository() {
        assertTrue(ReleaseRules.releasePage(REPO+"releases/tag/v1.3"));
        assertFalse(ReleaseRules.releasePage("https://github.com/other/repo/releases/tag/v1.3"));
    }
}
