package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class PortalProtocolTest {
    static final String ROOT=PortalProtocol.ORIGIN;
    static final String FORM="<form id=LoginForm method=post action=/login>"+
        "<input name=username type=text><input id=password name=password type=password>"+
        "<input name=RedirectUrl type=hidden value='original&amp;value'>"+
        "<input name=anonymous type=hidden value=0>"+
        "<div style='display: none'><input type=password name=accesscode id=accesscode>"+
        "<input type=checkbox name=checkbox><input type=checkbox name=checkbox1></div>"+
        "<input id=sub2 type=submit name=Login value=Login></form>";
    private String body(String html) throws Exception {
        return PortalProtocol.body(html,new URL(ROOT+"/login.html"),new URL(ROOT+"/index.html"),"demo","a +&密");
    }
    @Test public void preservesHiddenFieldsAndEncodesPassword() throws Exception {
        String b=body(FORM);
        assertTrue(b.contains("username=demo")); assertTrue(b.contains("password=a+%2B%26%E5%AF%86"));
        assertTrue(b.contains("RedirectUrl=original%26value")); assertTrue(b.contains("anonymous=0"));
        assertTrue(b.contains("checkbox=on"));assertTrue(b.contains("checkbox1=on"));assertFalse(b.contains("Login="));
    }
    @Test public void matchesSetRedirectUrlWithoutDecoding() throws Exception {
        String b=PortalProtocol.body(FORM,new URL(ROOT+"/login.html"),new URL(ROOT+"/index.html?RedirectUrl=http%3A%2F%2Fexample.org"),"demo","pw");
        assertTrue(b.contains("RedirectUrl=http%253A%252F%252Fexample.org"));
    }
    @Test public void rejectsOriginsAndChangedForms() throws Exception {
        for(String u:Arrays.asList("http://captiveportal-login.bytedance.net:1026/login",ROOT.replace(":1026","")+"/login", "https://other.example:1026/login", "https://user@captiveportal-login.bytedance.net:1026/login")) {
            try {PortalProtocol.trusted(u);fail(u);} catch(IllegalArgumentException expected){}
        }
        for(String html:Arrays.asList(FORM.replace("action=/login","action=https://other.example/login"),
            FORM.replace("display: none","display:block"),FORM.replace("type=submit","type=button"),
            FORM.replace("</form>","<input name=unknown value=test></form>"))) {
            try {body(html);fail("must reject changed or additional conditions");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void frameMustBeExpectedAndSameOrigin() throws Exception {
        assertEquals(ROOT+"/login.html",PortalProtocol.loginFrame("<iframe id=leftFrame src=/login.html>",new URL(ROOT+"/index.html")).toString());
        try{PortalProtocol.loginFrame("<iframe id=leftFrame src=https://example.org/login.html>",new URL(ROOT+"/index.html"));fail();}catch(IllegalArgumentException expected){}
    }
    private static class Fake extends HttpURLConnection {
        final String html;final int code; final Map<String,List<String>> headers=new HashMap<>();
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Fake(URL u,String html,int code){super(u);this.html=html;this.code=code;}
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public int getResponseCode() throws IOException{return code;}
        public Map<String,List<String>> getHeaderFields(){return headers;}
        public String getHeaderField(String name){List<String> v=headers.get(name);return v==null?null:v.get(0);}
        public InputStream getInputStream(){return new ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public OutputStream getOutputStream() throws IOException{return sent;}
    }
    @Test public void getsFreshPagesCarriesCookiesAndPostsOnlyOnce() throws Exception {
        List<Fake> seen=new ArrayList<>();
        PortalHttpClient client=new PortalHttpClient(u->{
            Fake f=new Fake(u,seen.isEmpty()?"<iframe id=leftFrame src=/login.html>":seen.size()==1?FORM:"Authentication Success",200);
            if(seen.isEmpty())f.headers.put("Set-Cookie",Arrays.asList("sid=fresh; Path=/; Secure"));
            seen.add(f);return f;
        },()->true);
        client.login("demo","pw");
        assertEquals(3,seen.size());assertEquals("POST",seen.get(2).getRequestMethod());
        assertTrue(seen.get(2).getRequestProperty("Cookie").contains("sid=fresh"));
        assertTrue(seen.get(2).sent.toString("UTF-8").contains("password=pw"));
        for(Fake f:seen)assertEquals("keep-alive",f.getRequestProperty("Connection"));
        assertEquals("same-origin",seen.get(2).getRequestProperty("Sec-Fetch-Site"));
        assertEquals("navigate",seen.get(2).getRequestProperty("Sec-Fetch-Mode"));
        assertEquals("document",seen.get(2).getRequestProperty("Sec-Fetch-Dest"));
        assertTrue(seen.get(2).getRequestProperty("User-Agent").contains("Chrome/135.0.7049.79"));
        assertEquals("?1",seen.get(2).getRequestProperty("sec-ch-ua-mobile"));
    }
    @Test public void neverFollowsRedirectToOtherOriginOrPostsAfterCancellation() throws Exception {
        List<Fake> seen=new ArrayList<>();
        try {
            new PortalHttpClient(u->{Fake f=new Fake(u,"",302);f.headers.put("Location",Arrays.asList("https://example.org/login"));seen.add(f);return f;},()->true).login("demo","pw");fail();
        }catch(IllegalArgumentException expected){}
        assertEquals(1,seen.size());seen.clear();
        try {
            new PortalHttpClient(u->{Fake f=new Fake(u,seen.isEmpty()?"<iframe id=leftFrame src=/login.html>":FORM,200);seen.add(f);return f;},()->seen.size()<2).login("demo","pw");fail();
        }catch(IllegalStateException expected){}
        assertEquals(2,seen.size());assertEquals(0,seen.get(1).sent.size());
    }
    @Test public void traceIdentifiesFailedPhaseWithoutLeakingNetworkDetails() throws Exception {
        List<String> log=new ArrayList<>();
        try {
            new PortalHttpClient(u->new Fake(u,"not a portal",200),()->true,(s,n)->log.add(s+" "+n)).login("private-user","private-pass");
            fail();
        } catch(IllegalArgumentException expected){}
        assertTrue(log.contains("landing.http_status 200"));assertTrue(log.contains("landing.parse_frame 0"));
        String text=log.toString();assertFalse(text.contains("private-user"));assertFalse(text.contains("private-pass"));
        assertFalse(text.contains("https://"));assertFalse(text.contains("submit.write_begin"));
    }
    @Test public void postResponseEofIsUncertainAndNeverReplayed() throws Exception {
        List<Fake> seen=new ArrayList<>();List<String> trace=new ArrayList<>();
        try {
            new PortalHttpClient(u->{
                Fake f=seen.size()<2?new Fake(u,seen.isEmpty()?"<iframe id=leftFrame src=/login.html>":FORM,200):
                    new Fake(u,"",200){@Override public int getResponseCode() throws IOException{throw new IOException("private-url",new EOFException("private-token"));}};
                seen.add(f);return f;
            },()->true,(s,n)->trace.add(s)).login("demo","pw");fail();
        }catch(PortalHttpClient.SubmissionUncertainException expected) {
            assertEquals("submission_response_unavailable",expected.getMessage());
        }
        assertEquals(3,seen.size());assertEquals("POST",seen.get(2).getRequestMethod());
        assertTrue(seen.get(2).sent.size()>0);assertTrue(trace.contains("submit.write_complete"));
        assertTrue(trace.contains("submit.outcome_unknown"));assertFalse(trace.contains("submit.http_status"));
        assertFalse(trace.toString().contains("private-"));
    }
    @Test public void failureBeforeOpeningPostStreamIsNotReportedAsSent() throws Exception {
        List<Fake> seen=new ArrayList<>();List<String> trace=new ArrayList<>();
        try {
            new PortalHttpClient(u->{
                Fake f=seen.size()<2?new Fake(u,seen.isEmpty()?"<iframe id=leftFrame src=/login.html>":FORM,200):
                    new Fake(u,"",200){@Override public OutputStream getOutputStream() throws IOException{throw new javax.net.ssl.SSLHandshakeException("private-cert-detail");}};
                seen.add(f);return f;
            },()->true,(s,n)->trace.add(s)).login("demo","pw");fail();
        }catch(javax.net.ssl.SSLHandshakeException expected){}
        assertEquals(3,seen.size());assertEquals(0,seen.get(2).sent.size());
        assertFalse(trace.contains("submit.outcome_unknown"));
        assertFalse(trace.contains("submit.write_complete"));
    }
    @Test public void hiddenFlagsUseFreshPageValuesButVisibleTermsAreNeverAccepted() throws Exception {
        String html=FORM.replace("name=checkbox>","name=checkbox value=flag-a>").replace("name=checkbox1>","name=checkbox1 value=flag-b>");
        String b=body(html);assertTrue(b.contains("checkbox=flag-a"));assertTrue(b.contains("checkbox1=flag-b"));
        String visible=html.replace("type=password name=accesscode","style='display:none' type=password name=accesscode")
            .replace("display: none","display:block");
        for(String form:Arrays.asList(visible,visible.replace("type=checkbox","type=checkbox checked"))) {
            try {body(form);fail();}
            catch(IllegalArgumentException expected){assertEquals("manual_checkbox",expected.getMessage());}
        }
    }
    @Test public void traceContainsOnlyCookieCountsAndHiddenFieldPresence() throws Exception {
        List<Fake> seen=new ArrayList<>();List<String> trace=new ArrayList<>();
        new PortalHttpClient(u->{
            Fake f=new Fake(u,seen.isEmpty()?"<iframe id=leftFrame src=/login.html>":seen.size()==1?FORM:"Authentication Success",200);
            if(seen.isEmpty())f.headers.put("Set-Cookie",Arrays.asList("sid=PRIVATE_COOKIE; Path=/; Secure"));
            seen.add(f);return f;
        },()->true,(s,n)->trace.add(s+" "+n)).login("private-user","private-password");
        assertTrue(trace.contains("submit.cookie_count 1"));assertTrue(trace.contains("form.field.checkbox 1"));
        assertTrue(trace.contains("form.field.checkbox1 1"));assertTrue(trace.contains("form.field.accesscode 0"));
        assertFalse(trace.toString().contains("PRIVATE_COOKIE"));assertFalse(trace.toString().contains("private-password"));
    }

    @Test public void dedicatedPostAvoidsTheReadPoolAndKeepsCookiesAndFormData() throws Exception {
        List<Fake> reads=new ArrayList<>();List<Fake> posts=new ArrayList<>();
        PortalHttpClient.Transport transport=new PortalHttpClient.Transport() {
            public HttpURLConnection open(URL u) {
                if(reads.size()>=2) throw new AssertionError("POST must not reuse the read pool");
                Fake f=new Fake(u,reads.isEmpty()?"<iframe id=leftFrame src=/login.html>":FORM,200);
                if(reads.isEmpty()) f.headers.put("Set-Cookie",Arrays.asList("sid=fresh; Path=/; Secure"));
                reads.add(f);return f;
            }
            public HttpURLConnection openForSubmission(URL u) {
                Fake f=new Fake(u,"",302);posts.add(f);return f;
            }
        };
        new PortalHttpClient(transport,()->true).login("demo","a +&密");
        assertEquals(2,reads.size());assertEquals(1,posts.size());
        Fake post=posts.get(0);
        assertEquals("POST",post.getRequestMethod());
        assertTrue(post.getRequestProperty("Cookie").contains("sid=fresh"));
        assertEquals(body(FORM),post.sent.toString("UTF-8"));
        assertEquals("keep-alive",post.getRequestProperty("Connection"));
        assertEquals(ROOT,post.getRequestProperty("Origin"));
        assertFalse(post.getInstanceFollowRedirects());
    }

    @Test public void uncertainDedicatedPostIsNeverReplayedOrMovedToTheReadPool() throws Exception {
        int[] reads={0},posts={0};List<Fake> sent=new ArrayList<>();
        PortalHttpClient.Transport transport=new PortalHttpClient.Transport() {
            public HttpURLConnection open(URL u) {
                if(++reads[0]>2) throw new AssertionError("No fallback submission on the read pool");
                return new Fake(u,reads[0]==1?"<iframe id=leftFrame src=/login.html>":FORM,200);
            }
            public HttpURLConnection openForSubmission(URL u) {
                posts[0]++;
                Fake f=new Fake(u,"",200) {
                    @Override public int getResponseCode() throws IOException {throw new EOFException();}
                };
                sent.add(f);return f;
            }
        };
        try {new PortalHttpClient(transport,()->true).login("demo","pw");fail();}
        catch(PortalHttpClient.SubmissionUncertainException expected) { }
        assertEquals(2,reads[0]);assertEquals(1,posts[0]);assertTrue(sent.get(0).sent.size()>0);
    }

    @Test public void failureOpeningDedicatedPostDoesNotFallBackToAnotherTransport() throws Exception {
        int[] reads={0},posts={0};
        PortalHttpClient.Transport transport=new PortalHttpClient.Transport() {
            public HttpURLConnection open(URL u) {
                if(++reads[0]>2) throw new AssertionError("No retry through the read pool");
                return new Fake(u,reads[0]==1?"<iframe id=leftFrame src=/login.html>":FORM,200);
            }
            public HttpURLConnection openForSubmission(URL u) throws IOException {
                posts[0]++;throw new javax.net.ssl.SSLHandshakeException("handshake_failed");
            }
        };
        try {new PortalHttpClient(transport,()->true).login("demo","pw");fail();}
        catch(javax.net.ssl.SSLHandshakeException expected) { }
        assertEquals(2,reads[0]);assertEquals(1,posts[0]);
    }
}
