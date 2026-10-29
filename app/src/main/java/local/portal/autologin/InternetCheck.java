package local.portal.autologin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A finite, credential-free check. The caller must supply a transport bound to the selected Wi-Fi. */
final class InternetCheck {
    enum Result { ONLINE, CAPTIVE, UNAVAILABLE, CANCELLED }
    interface Transport { HttpURLConnection open(URL url) throws Exception; }
    interface Guard { boolean allowed(); }
    interface Trace { void event(String stage, int detail); }
    private static final String[] ENDPOINTS = {
        "https://connectivitycheck.platform.hicloud.com/generate_204",
        "https://www.baidu.com/robots.txt"
    };
    private final Transport transport;
    private final Guard guard;
    private final Trace trace;

    InternetCheck(Transport transport, Guard guard, Trace trace) {
        this.transport=transport; this.guard=guard; this.trace=trace;
    }

    Result run() {
        final long deadline=System.nanoTime()+12000000000L;
        boolean unavailable=false;
        for (int index=0; index<ENDPOINTS.length; index++) {
            if (!guard.allowed()) return Result.CANCELLED;
            if (System.nanoTime()>=deadline) return Result.UNAVAILABLE;
            HttpURLConnection connection=null;
            String phase="internet.endpoint"+index;
            try {
                connection=transport.open(new URL(ENDPOINTS[index]));
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache, no-store");
                connection.setRequestProperty("User-Agent", "PortalAutoConnectivity/1.0");
                int code=connection.getResponseCode();
                trace.event(phase+".http_status",code);
                if (!guard.allowed()) return Result.CANCELLED;
                // Never follow a portal redirect, accept a login page, or send saved credentials here.
                if ((code>=300 && code<400) || code==511) return Result.CAPTIVE;
                if (index==0 && code==204) {
                    try (InputStream in=connection.getInputStream()) {
                        if (in.read()==-1) continue;
                    }
                }
                if (index==1 && code==200) {
                    String body=readSmall(connection.getInputStream(),deadline).toLowerCase(Locale.ROOT);
                    if (body.contains("user-agent:") &&
                            (body.contains("disallow:") || body.contains("allow:")) &&
                            !body.contains("<html") && !body.contains("<form")) continue;
                }
                if (code==200 || code==204) return Result.CAPTIVE;
                unavailable=true;
            } catch (Exception ignored) {
                // Library exceptions can contain URLs. Export only the bounded stage code.
                trace.event(phase+".unavailable",0);
                unavailable=true;
            } finally {
                if (connection!=null) connection.disconnect();
            }
        }
        if (!guard.allowed()) return Result.CANCELLED;
        return unavailable ? Result.UNAVAILABLE : Result.ONLINE;
    }

    private static String readSmall(InputStream stream, long deadline) throws IOException {
        try (InputStream in=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024]; int count;
            while (out.size()<8192 && (count=in.read(buffer,0,Math.min(buffer.length,8192-out.size())))!=-1) {
                if (System.nanoTime()>=deadline) throw new IOException("check_timeout");
                out.write(buffer,0,count);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
