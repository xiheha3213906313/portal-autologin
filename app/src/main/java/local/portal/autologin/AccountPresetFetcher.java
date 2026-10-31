package local.portal.autologin;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A single bounded HTTPS GET. No account input, cookies, authentication or redirect following. */
final class AccountPresetFetcher {
    interface Transport {HttpURLConnection open(URL url) throws Exception;}
    private final Transport transport;
    AccountPresetFetcher(Transport transport){this.transport=transport;}
    List<SavedAccountBook.Account> fetch() throws Exception {
        URL url=new URL(ProjectLinks.ACCOUNT_PRESETS+"&v="+System.currentTimeMillis());
        HttpURLConnection connection=transport.open(url);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        try {
            connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            // Contents API returns the file body directly on api.github.com with this media type.
            connection.setRequestProperty("Accept","application/vnd.github.raw+json");
            connection.setRequestProperty("Cache-Control","no-cache, no-store");
            connection.setRequestProperty("User-Agent","PortalAutoPresets/1.0");
            int code=connection.getResponseCode();
            if(code!=200)throw new IOException("presets_unavailable");
            if(connection.getContentLengthLong()>AccountPresetCatalog.MAX_BYTES)throw new IOException("presets_size");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1) {
                    if(System.nanoTime()>=deadline || bytes.size()+count>AccountPresetCatalog.MAX_BYTES)
                        throw new IOException("presets_limit");
                    bytes.write(buffer,0,count);
                }
            }
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return AccountPresetCatalog.parse(text);
        } finally {connection.disconnect();}
    }
}
