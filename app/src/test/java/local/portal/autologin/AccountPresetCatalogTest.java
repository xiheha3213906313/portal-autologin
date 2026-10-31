package local.portal.autologin;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class AccountPresetCatalogTest {
    @Test public void additiveRootAndEntryFieldsAreIgnoredWithoutChangingCredentials() throws Exception {
        JSONObject root=new JSONObject(sample()).put("futureMetadata",new JSONObject().put("enabled",true));
        root.getJSONArray("accounts").getJSONObject(0).put("futureOptions",new JSONObject().put("mode","unknown"));
        List<SavedAccountBook.Account> accounts=AccountPresetCatalog.parse(root.toString());
        assertEquals(1,accounts.size());assertEquals(" a+&密 ",accounts.get(0).password);
    }
    @Test public void otherPortalWithSameUsernameAndDifferentFieldsDoesNotBreakCurrentPortal() throws Exception {
        JSONObject root=new JSONObject(sample());
        root.getJSONArray("accounts").put(new JSONObject().put("portalId","other-portal")
            .put("username","guest").put("password",42).put("label",JSONObject.NULL));
        root.getJSONArray("accounts").put(new JSONObject().put("portalId","sms-portal").put("phone",123));
        List<SavedAccountBook.Account> accounts=AccountPresetCatalog.parse(root.toString());
        assertEquals(1,accounts.size());assertEquals("guest",accounts.get(0).username);
    }
    @Test public void futureEntrySchemaIsSkippedBeforeCheckingLegacyRequiredFields() throws Exception {
        JSONObject root=new JSONObject(sample());
        root.getJSONArray("accounts").getJSONObject(0).put("portalId","bytedance").put("schemaVersion",1);
        root.getJSONArray("accounts").put(new JSONObject().put("portalId","bytedance")
            .put("schemaVersion",2).put("username","guest").put("newAuthentication",true));
        assertEquals(1,AccountPresetCatalog.parse(root.toString()).size());
    }
    @Test public void unrecognizedPortalIdentifiersNeverFallBackToTheCurrentPortal() throws Exception {
        for(Object id:Arrays.asList(JSONObject.NULL,17,"","BYTEDANCE",new JSONObject())) {
            JSONObject root=new JSONObject(sample());
            root.getJSONArray("accounts").put(new JSONObject().put("portalId",id));
            assertEquals(1,AccountPresetCatalog.parse(root.toString()).size());
        }
    }
    @Test public void unsupportedOnlyCatalogRetainsExistingPresetsInsteadOfWithdrawingThem() throws Exception {
        SavedAccountBook book=new SavedAccountBook();book.seed(Arrays.asList(new SavedAccountBook.Account("old","secret","旧配置")));
        String before=book.getAccounts().get(0).password;
        String[] catalogs={"{\"version\":1,\"accounts\":[{\"portalId\":\"other\"}]}",
            "{\"version\":1,\"accounts\":[{\"portalId\":\"bytedance\",\"schemaVersion\":2}]}",
            "{\"version\":1,\"accounts\":[null,42]}"};
        for(String catalog:catalogs) {
            try {book.replacePresets(AccountPresetCatalog.parse(catalog));fail();}
            catch(IOException expected){assertEquals("unsupported_presets",expected.getMessage());}
            assertEquals(before,book.getAccounts().get(0).password);
        }
    }
    @Test public void foreignEntriesDoNotConsumeTheCurrentPortalEntryLimit() throws Exception {
        JSONObject root=new JSONObject(sample());
        for(int i=0;i<201;i++)root.getJSONArray("accounts").put(new JSONObject().put("portalId","other"));
        assertEquals(1,AccountPresetCatalog.parse(root.toString()).size());
    }
    @Test public void cacheEncodingTagsLegacyEntriesWithoutAddingUnsupportedPortalData() throws Exception {
        List<SavedAccountBook.Account> legacy=AccountPresetCatalog.parse(sample());
        JSONObject encoded=new JSONObject(AccountPresetCatalog.encode(legacy));
        JSONObject entry=encoded.getJSONArray("accounts").getJSONObject(0);
        assertEquals("bytedance",entry.getString("portalId"));assertEquals(1,entry.getInt("schemaVersion"));
        assertEquals(legacy.get(0).password,AccountPresetCatalog.parse(encoded.toString()).get(0).password);
    }
    private static String sample() {
        return "{\"version\":1,\"accounts\":[{\"label\":\"测试地点\",\"username\":\" guest \",\"password\":\" a+&密 \"}]}";
    }
    private void invalid(String text) {
        try {AccountPresetCatalog.parse(text);fail();}catch(IOException expected) {
            assertEquals("invalid_presets",expected.getMessage());assertNull(expected.getCause());
        }
    }
    @Test public void validatedCatalogKeepsPasswordExactlyAndRoundTrips() throws Exception {
        List<SavedAccountBook.Account> accounts=AccountPresetCatalog.parse(sample());
        assertEquals(1,accounts.size());assertEquals("guest",accounts.get(0).username);
        assertEquals(" a+&密 ",accounts.get(0).password);assertEquals("测试地点",accounts.get(0).label);
        assertTrue(accounts.get(0).bundled);assertFalse(accounts.toString().contains(" a+&密 "));
        assertEquals(accounts.get(0).password,AccountPresetCatalog.parse(AccountPresetCatalog.encode(accounts)).get(0).password);
    }
    @Test public void invalidFieldsVersionAndDuplicateUsersRejectEntireCatalog() {
        invalid(sample()+" trailing-data");
        invalid(sample().replace("\"version\":1","\"version\":2"));
        invalid(sample().replace("\"version\":1","\"version\":\"1\""));
        invalid(sample().replace("\"label\":\"测试地点\"","\"label\":null"));
        invalid(sample().replace("测试地点","地点\\n下一行"));
        invalid(sample().replace("\" guest \"","123"));
        invalid(sample().replace(" a+&密 ",""));
        String item="{\"label\":\"甲\",\"username\":\"guest\",\"password\":\"pw\"}";
        invalid("{\"version\":1,\"accounts\":["+item+","+item+"]}");
        invalid("<html>PRIVATE_COOKIE</html>");
    }
    @Test public void emptyCatalogIsValidButOversizedCatalogIsRejected() throws Exception {
        assertTrue(AccountPresetCatalog.parse("{\"version\":1,\"accounts\":[]}").isEmpty());
        String item="{\"label\":\"甲\",\"username\":\"guest%d\",\"password\":\"pw\"}";
        List<String> rows=new ArrayList<>();
        for(int i=0;i<201;i++)rows.add(String.format(Locale.ROOT,item,i));
        invalid("{\"version\":1,\"accounts\":["+String.join(",",rows)+"]}");
    }
    private static class Fake extends HttpURLConnection {
        byte[] body;int code=200;boolean closed;
        Fake(URL url,byte[] body){super(url);this.body=body;}
        public void connect(){}public boolean usingProxy(){return false;}
        public void disconnect(){closed=true;}
        public int getResponseCode(){return code;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        public long getContentLengthLong(){return -1;}
    }
    @Test public void downloadUsesOnlyProjectHttpsGetAndNeverFollowsRedirects() throws Exception {
        List<Fake> opened=new ArrayList<>();
        List<SavedAccountBook.Account> accounts=new AccountPresetFetcher(url->{
            assertEquals("https",url.getProtocol());assertEquals("api.github.com",url.getHost());
            assertEquals("/repos/xiheha3213906313/portal-autologin/contents/release-assets/account-presets.json",url.getPath());
            assertTrue(url.getQuery().matches("ref=main&v=[0-9]+"));
            Fake f=new Fake(url,sample().getBytes(StandardCharsets.UTF_8));opened.add(f);return f;
        }).fetch();
        assertEquals(1,opened.size());Fake connection=opened.get(0);
        assertEquals("GET",connection.getRequestMethod());assertFalse(connection.getDoOutput());
        assertFalse(connection.getInstanceFollowRedirects());assertFalse(connection.getUseCaches());
        assertEquals("application/vnd.github.raw+json",connection.getRequestProperty("Accept"));
        assertEquals(5000,connection.getConnectTimeout());assertEquals(5000,connection.getReadTimeout());
        assertNull(connection.getRequestProperty("Authorization"));assertNull(connection.getRequestProperty("Cookie"));
        assertTrue(connection.closed);assertEquals("guest",accounts.get(0).username);
    }
    @Test public void redirectsLargeBodiesAndInvalidUtf8AreRejectedAndClosed() throws Exception {
        for(int mode=0;mode<3;mode++) {
            byte[] bytes=mode==1?new byte[AccountPresetCatalog.MAX_BYTES+1]:mode==2?new byte[]{(byte)0xc3,0x28}:new byte[0];
            Fake f=new Fake(new URL(ProjectLinks.ACCOUNT_PRESETS),bytes);if(mode==0)f.code=302;
            int[] calls={0};
            try {new AccountPresetFetcher(url->{calls[0]++;return f;}).fetch();fail();}catch(IOException expected){}
            assertEquals(1,calls[0]);
            assertTrue(f.closed);
        }
    }
}
