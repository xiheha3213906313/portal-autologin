package local.portal.autologin;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class AccountPresetCatalogTest {
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
            assertEquals("https",url.getProtocol());assertEquals("raw.githubusercontent.com",url.getHost());
            assertEquals("/xiheha3213906313/portal-autologin/main/release-assets/account-presets.json",url.getPath());
            assertTrue(url.getQuery().matches("v=[0-9]+"));
            Fake f=new Fake(url,sample().getBytes(StandardCharsets.UTF_8));opened.add(f);return f;
        }).fetch();
        assertEquals(1,opened.size());Fake connection=opened.get(0);
        assertEquals("GET",connection.getRequestMethod());assertFalse(connection.getDoOutput());
        assertFalse(connection.getInstanceFollowRedirects());assertFalse(connection.getUseCaches());
        assertNull(connection.getRequestProperty("Authorization"));assertNull(connection.getRequestProperty("Cookie"));
        assertTrue(connection.closed);assertEquals("guest",accounts.get(0).username);
    }
    @Test public void redirectsLargeBodiesAndInvalidUtf8AreRejectedAndClosed() throws Exception {
        for(int mode=0;mode<3;mode++) {
            byte[] bytes=mode==1?new byte[AccountPresetCatalog.MAX_BYTES+1]:mode==2?new byte[]{(byte)0xc3,0x28}:new byte[0];
            Fake f=new Fake(new URL(ProjectLinks.ACCOUNT_PRESETS),bytes);if(mode==0)f.code=302;
            try {new AccountPresetFetcher(url->f).fetch();fail();}catch(IOException expected){}
            assertTrue(f.closed);
        }
    }
}
