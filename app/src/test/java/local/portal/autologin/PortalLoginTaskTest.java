package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import static local.portal.autologin.PortalLoginTask.Outcome.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class PortalLoginTaskTest {
    private static class Fake extends HttpURLConnection {
        final int code;final String body;final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Fake(URL url,int code,String body){super(url);this.code=code;this.body=body;}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public int getResponseCode()throws IOException{return code;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
        public InputStream getErrorStream(){return getInputStream();}
        public OutputStream getOutputStream(){return sent;}
        public Map<String,List<String>> getHeaderFields(){return Collections.emptyMap();}
    }
    private PortalHttpClient client(List<Fake> requests,int postStatus,String postBody,boolean postEof) {
        return new PortalHttpClient(url->{
            Fake f;
            if(requests.isEmpty())f=new Fake(url,200,"<iframe id=leftFrame src=/login.html>");
            else if(requests.size()==1)f=new Fake(url,200,PortalProtocolTest.FORM);
            else if(postEof)f=new Fake(url,200,""){
                @Override public int getResponseCode()throws IOException{throw new EOFException();}
            };
            else f=new Fake(url,postStatus,postBody);
            requests.add(f);return f;
        },()->true);
    }
    @Test public void landingEofRequiresInternetCheckBeforeShowingFailureAndSendsNoCredentials() {
        List<Fake> requests=new ArrayList<>();
        PortalLoginTask.Result result=PortalLoginTask.run(new PortalHttpClient(url->{
            Fake f=new Fake(url,200,""){
                @Override public int getResponseCode()throws IOException{throw new IOException("closed",new EOFException());}
            };
            requests.add(f);return f;
        },()->true),"demo","pw");
        assertEquals(CHECK_INTERNET,result.outcome);assertNotNull(result.failure);
        assertFalse(result.failure instanceof PortalHttpClient.SubmissionUncertainException);
        assertEquals(1,requests.size());assertEquals("GET",requests.get(0).getRequestMethod());
        assertEquals(0,requests.get(0).sent.size());
    }
    @Test public void unavailablePortalAlsoChecksInternetWithoutPretendingCredentialsWereAccepted() {
        PortalLoginTask.Result result=PortalLoginTask.run(new PortalHttpClient(url->new Fake(url,503,""),()->true),"demo","pw");
        assertEquals(CHECK_INTERNET,result.outcome);assertEquals("page_unavailable",result.failure.getMessage());
    }
    @Test public void successfulPostStillRequiresIndependentInternetCheck() {
        List<Fake> requests=new ArrayList<>();
        PortalLoginTask.Result result=PortalLoginTask.run(client(requests,302,"Authentication Success",false),"demo","pw");
        assertEquals(CHECK_INTERNET,result.outcome);assertNull(result.failure);assertEquals(3,requests.size());
        assertTrue(requests.get(2).sent.size()>0);
    }
    @Test public void definiteHttpRejectionIsNotHiddenByAnAlreadyOnlineNetwork() {
        List<Fake> requests=new ArrayList<>();
        PortalLoginTask.Result result=PortalLoginTask.run(client(requests,403,"denied",false),"demo","pw");
        assertEquals(REJECTED,result.outcome);assertEquals("authentication_rejected",result.failure.getMessage());
        assertEquals(3,requests.size());
    }
    @Test public void returnedLoginFormRemainsAnExplicitAuthenticationFailure() {
        List<Fake> requests=new ArrayList<>();
        PortalLoginTask.Result result=PortalLoginTask.run(client(requests,200,PortalProtocolTest.FORM,false),"demo","pw");
        assertEquals(REJECTED,result.outcome);assertEquals("login_form_returned",result.failure.getMessage());
    }
    @Test public void responseEofAfterPostChecksInternetWithoutReplayingThePost() {
        List<Fake> requests=new ArrayList<>();
        PortalLoginTask.Result result=PortalLoginTask.run(client(requests,200,"",true),"demo","pw");
        assertEquals(CHECK_INTERNET,result.outcome);
        assertTrue(result.failure instanceof PortalHttpClient.SubmissionUncertainException);
        assertEquals(3,requests.size());assertEquals("POST",requests.get(2).getRequestMethod());
        assertTrue(requests.get(2).sent.size()>0);
    }
}
