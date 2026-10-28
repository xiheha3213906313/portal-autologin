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
        assertFalse(b.contains("checkbox="));assertFalse(b.contains("Login="));
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
        public int getResponseCode(){return code;}
        public Map<String,List<String>> getHeaderFields(){return headers;}
        public String getHeaderField(String name){List<String> v=headers.get(name);return v==null?null:v.get(0);}
        public InputStream getInputStream(){return new ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public OutputStream getOutputStream(){return sent;}
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
}
