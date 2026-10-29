package local.portal.autologin;

import java.text.Normalizer;
import java.util.Locale;

/** Exact matching after normalizing case, width, separators and punctuation. No substring matching. */
final class WifiNameMatcher {
    static String normalize(String value) {
        if(value==null)return "";
        String text=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result=new StringBuilder();
        for(int offset=0;offset<text.length();) {
            int code=text.codePointAt(offset);offset+=Character.charCount(code);
            int type=Character.getType(code);
            if(Character.isLetterOrDigit(code) || type==Character.NON_SPACING_MARK ||
                type==Character.COMBINING_SPACING_MARK)result.appendCodePoint(code);
        }
        return result.toString();
    }
    static boolean validFilter(String filter) {
        return blank(filter) || !normalize(filter).isEmpty();
    }
    static boolean matches(String filter,String ssid) {
        if(blank(filter))return true;
        if(ssid==null)return false;
        String name=ssid.trim();
        if(name.length()>=2 && name.startsWith("\"") && name.endsWith("\""))name=name.substring(1,name.length()-1);
        if(name.isEmpty() || "<unknown ssid>".equalsIgnoreCase(name))return false;
        String wanted=normalize(filter);
        return !wanted.isEmpty() && wanted.equals(normalize(name));
    }
    private static boolean blank(String value) {
        return value==null || Normalizer.normalize(value,Normalizer.Form.NFKC).trim().isEmpty();
    }
}
