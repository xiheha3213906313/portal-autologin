package local.portal.autologin;

import java.net.URI;
import java.util.Locale;
import java.util.regex.*;

final class ReleaseRules {
    private static final String REPO="/xiheha3213906313/portal-autologin/";
    static int compareVersions(String a,String b) {
        int[] x=version(a),y=version(b);
        for(int i=0;i<Math.max(x.length,y.length);i++) {
            int c=Integer.compare(i<x.length?x[i]:0,i<y.length?y[i]:0);if(c!=0)return c;
        }
        return 0;
    }
    private static int[] version(String s) {
        if(s==null)throw new IllegalArgumentException("invalid_version");
        s=s.trim().replaceFirst("^[vV]","");
        if(!s.matches("[0-9]{1,9}(\\.[0-9]{1,9}){1,3}"))throw new IllegalArgumentException("invalid_version");
        String[] parts=s.split("\\.");int[] n=new int[parts.length];
        for(int i=0;i<n.length;i++)n[i]=Integer.parseInt(parts[i]);return n;
    }
    private static URI secure(String value) {
        try {
            URI u=new URI(value);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null ||
                u.getPort()!=-1 && u.getPort()!=443 || u.getRawFragment()!=null || u.getPath().contains(".."))return null;
            return u;
        } catch(Exception e){return null;}
    }
    static boolean releasePage(String value) {
        URI u=secure(value);return u!=null && "github.com".equalsIgnoreCase(u.getHost()) &&
            u.getPath().startsWith(REPO+"releases/tag/");
    }
    static boolean download(String value) {
        URI u=secure(value);if(u==null)return false;
        String host=u.getHost().toLowerCase(Locale.ROOT),path=u.getPath();
        if(!path.toLowerCase(Locale.ROOT).endsWith(".apk"))return false;
        return host.equals("github.com") && (path.startsWith(REPO+"releases/download/") || path.startsWith(REPO+"blob/")) ||
            host.equals("raw.githubusercontent.com") && path.startsWith(REPO);
    }
    static String normalizeDownload(String value) {
        if(!download(value))throw new IllegalArgumentException("invalid_download");
        URI u=URI.create(value);
        if("github.com".equalsIgnoreCase(u.getHost()) && u.getPath().startsWith(REPO+"blob/"))
            return "https://raw.githubusercontent.com"+REPO+u.getRawPath().substring((REPO+"blob/").length());
        return value;
    }
    static boolean redirect(String value) {
        URI u=secure(value);if(u==null)return false;
        String h=u.getHost().toLowerCase(Locale.ROOT);
        return h.equals("github.com") || h.equals("raw.githubusercontent.com") ||
            h.equals("release-assets.githubusercontent.com") || h.equals("objects.githubusercontent.com");
    }
    static String markdownDownload(String body) {
        Matcher m=Pattern.compile("https://[^\\s)<>\\\"']+").matcher(body==null?"":body);
        while(m.find())if(download(m.group()))return normalizeDownload(m.group());return null;
    }
}
