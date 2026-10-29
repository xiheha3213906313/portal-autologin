package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import static local.portal.autologin.InternetCheck.Result.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InternetCheckTest {
    private static final String ROBOTS="User-agent: *\nDisallow: /private\n";
    private static class Fake extends HttpURLConnection {
        final int code; final String body; boolean closed;
        Fake(URL url,int code,String body) { super(url);this.code=code;this.body=body; }
        public void connect() {} public void disconnect() { closed=true; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() throws IOException { return code; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
    }
    @Test public void checksBothHttpsEndpointsWithoutCredentialsRedirectsOrCachedResponses() {
        List<Fake> seen=new ArrayList<>();
        InternetCheck check=new InternetCheck(url->{
            Fake f=new Fake(url,seen.isEmpty()?204:200,seen.isEmpty()?"":ROBOTS);
            seen.add(f);return f;
        },()->true,(s,n)->{});
        assertEquals(ONLINE,check.run());assertEquals(2,seen.size());
        for(Fake f:seen) {
            assertEquals("https",f.getURL().getProtocol());
            assertEquals("GET",f.getRequestMethod());assertFalse(f.getDoOutput());
            assertFalse(f.getInstanceFollowRedirects());assertFalse(f.getUseCaches());
            assertNull(f.getRequestProperty("Cookie"));assertNull(f.getRequestProperty("Authorization"));
            assertTrue(f.closed);assertTrue(f.getConnectTimeout()<=3000);assertTrue(f.getReadTimeout()<=3000);
        }
        assertNotEquals(seen.get(0).getURL().getHost(),seen.get(1).getURL().getHost());
    }
    @Test public void systemProbeWhitelistedButOrdinaryPageIsLoginDoesNotMeanOnline() {
        int[] calls={0};
        assertEquals(CAPTIVE,new InternetCheck(url->++calls[0]==1?new Fake(url,204,""):
            new Fake(url,200,"<html><form id=LoginForm>User-agent: x Disallow: /</form></html>"),
            ()->true,(s,n)->{}).run());
        assertEquals(2,calls[0]);
    }
    @Test public void portalRedirectIsNotFollowed() {
        int[] calls={0};
        assertEquals(CAPTIVE,new InternetCheck(url->{calls[0]++;return new Fake(url,302,"");},
            ()->true,(s,n)->{}).run());
        assertEquals(1,calls[0]);
    }
    @Test public void oneReachableEndpointDoesNotHideDnsOrTlsFailureOfTheOther() {
        int[] calls={0};List<String> trace=new ArrayList<>();
        assertEquals(UNAVAILABLE,new InternetCheck(url->{
            if(++calls[0]==1)throw new javax.net.ssl.SSLHandshakeException("PRIVATE_TOKEN");
            return new Fake(url,200,ROBOTS);
        },()->true,(s,n)->trace.add(s)).run());
        assertEquals(2,calls[0]);assertFalse(trace.toString().contains("PRIVATE_TOKEN"));
    }
    @Test public void timeoutsAreNotMistakenForOnlineOrAuthenticationRejection() {
        assertEquals(UNAVAILABLE,new InternetCheck(url->{throw new SocketTimeoutException();},
            ()->true,(s,n)->{}).run());
    }
    @Test public void changingNetworkCancelsAnOtherwiseSuccessfulCheck() {
        boolean[] connected={true};
        assertEquals(CANCELLED,new InternetCheck(url->{connected[0]=false;return new Fake(url,204,"");},
            ()->connected[0],(s,n)->{}).run());
    }
    @Test public void ordinaryPageMustHaveExpectedContentAndAReal200Response() {
        for(int code:Arrays.asList(200,204,500)) {
            int[] calls={0};
            InternetCheck.Result result=new InternetCheck(url->++calls[0]==1?new Fake(url,204,""):
                new Fake(url,code,""),()->true,(s,n)->{}).run();
            assertNotEquals(ONLINE,result);
        }
    }
    @Test public void failedNetworkNeverOpensAnUnboundFallbackConnection() {
        int[] calls={0};
        assertEquals(UNAVAILABLE,new InternetCheck(url->{calls[0]++;throw new UnknownHostException();},
            ()->true,(s,n)->{}).run());
        assertEquals(2,calls[0]);
    }
}
