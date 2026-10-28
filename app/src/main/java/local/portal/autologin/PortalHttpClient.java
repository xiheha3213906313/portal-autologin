package local.portal.autologin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No WebView, global proxy changes, certificate bypass, or global cookie store. */
public final class PortalHttpClient {
    public interface Transport { HttpURLConnection open(URL url) throws Exception; }
    public interface Guard { boolean allowed(); }
    public interface Trace { void event(String stage,int detail); }
    private final Transport transport;
    private final Guard guard;
    private final Trace trace;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final long deadline = System.nanoTime() + 30000000000L;
    public PortalHttpClient(Transport transport, Guard guard) { this(transport,guard,(stage,detail)->{}); }
    public PortalHttpClient(Transport transport,Guard guard,Trace trace) { this.transport=transport;this.guard=guard;this.trace=trace; }
    private void check() {
        if (!guard.allowed() || System.nanoTime() >= deadline) throw new IllegalStateException("attempt_cancelled");
    }
    private static class Page {
        final URL url; final int code; final String body, redirect;
        Page(URL url,int code,String body,String redirect) { this.url=url;this.code=code;this.body=body;this.redirect=redirect; }
    }
    private Page request(URL url, String body, URL referer,String phase) throws Exception {
        check(); PortalProtocol.trusted(url.toString());
        trace.event(phase+".open",0);
        HttpURLConnection c=transport.open(url);
        try {
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            for (Map.Entry<String,List<String>> h:cookies.get(url.toURI(),Collections.emptyMap()).entrySet())
                c.setRequestProperty(h.getKey(),String.join("; ",h.getValue()));
            if (referer != null) c.setRequestProperty("Referer",referer.toString());
            if (body != null) {
                check();
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Origin",PortalProtocol.ORIGIN);
                c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                byte[] data=body.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(data.length);
                trace.event(phase+".write_begin",0);
                try (OutputStream out=c.getOutputStream()) { out.write(data); }
                trace.event(phase+".write_complete",0);
            }
            int code=c.getResponseCode();
            trace.event(phase+".http_status",code);
            cookies.put(url.toURI(),c.getHeaderFields());
            String redirect=c.getHeaderField("Location");
            InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if (stream != null) try (InputStream in=stream) {
                byte[] buffer=new byte[8192]; int n;
                while ((n=in.read(buffer))!=-1) {
                    if (System.nanoTime()>=deadline || bytes.size()+n>1048576) throw new IOException("response_limit");
                    bytes.write(buffer,0,n);
                }
            }
            trace.event(phase+".body_bytes",bytes.size());
            return new Page(url,code,bytes.toString("UTF-8"),redirect);
        } finally { c.disconnect(); }
    }
    private Page get(URL u, URL referer,String phase) throws Exception {
        for (int i=0;i<4;i++) {
            Page p=request(u,null,referer,phase);
            if (p.code>=300 && p.code<400 && p.redirect!=null) {
                trace.event(phase+".redirect",i+1);
                u=PortalProtocol.trusted(new URL(u,p.redirect).toString()); continue;
            }
            if (p.code!=200) throw new IOException("page_unavailable");
            return p;
        }
        throw new IOException("redirect_limit");
    }
    /** One POST only; response is a hint, system validation decides connectivity. */
    public void login(String user,String pass) throws Exception {
        try {
            Page landing=get(new URL(PortalProtocol.ORIGIN+"/index.html"),null,"landing");
            trace.event("landing.parse_frame",0);
            URL frame=PortalProtocol.loginFrame(landing.body,landing.url);
            Page page=get(frame,landing.url,"frame");
            if (!"/login.html".equals(page.url.getPath())) throw new IOException("unexpected_login_page");
            trace.event("form.parse_begin",0);
            String encoded=PortalProtocol.body(page.body,page.url,landing.url,user,pass);
            trace.event("form.ready",0);
            check();
            Page result=request(new URL(PortalProtocol.ORIGIN+"/login"),encoded,page.url,"submit");
            trace.event("submit.success_hint",result.body.contains("Authentication Success") || result.body.contains("认证成功")?1:0);
            trace.event("submit.already_online_hint",result.body.contains("User Has Online") || result.body.contains("用户已在线")?1:0);
            if (result.code>=400) throw new IOException("authentication_rejected");
            if (org.jsoup.Jsoup.parse(result.body).selectFirst("form#LoginForm") != null)
                throw new IOException("login_form_returned");
        } finally { cookies.getCookieStore().removeAll(); }
    }
}
