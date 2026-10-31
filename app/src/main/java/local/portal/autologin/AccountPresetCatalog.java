package local.portal.autologin;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Data only: downloaded presets never contain executable actions or alter active login settings. */
final class AccountPresetCatalog {
    static final int MAX_BYTES=256*1024,MAX_ACCOUNTS=200;
    static final String CURRENT_PORTAL="bytedance";
    static List<SavedAccountBook.Account> parse(String text) throws IOException {
        try {
            if(text==null || text.length()>MAX_BYTES)throw new IllegalArgumentException();
            JSONTokener tokens=new JSONTokener(text);
            Object parsed=tokens.nextValue();
            if(!(parsed instanceof JSONObject) || tokens.nextClean()!=0)throw new IllegalArgumentException();
            JSONObject root=(JSONObject)parsed;
            Object version=root.get("version");
            if(!(version instanceof Number) || ((Number)version).doubleValue()!=1)throw new IllegalArgumentException();
            JSONArray items=root.getJSONArray("accounts");
            ArrayList<SavedAccountBook.Account> accounts=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                // Dispatch before reading credentials: another portal may have different fields,
                // or use the same username. Neither must affect this portal's catalog.
                if(item==null || !supported(item))continue;
                String username=string(item,"username",4096).trim();
                String password=string(item,"password",4096);
                String label=string(item,"label",80).trim();
                if(username.isEmpty() || password.isEmpty() || label.isEmpty() || !seen.add(username))
                    throw new IllegalArgumentException();
                accounts.add(new SavedAccountBook.Account(username,password,label,true));
                if(accounts.size()>MAX_ACCOUNTS)throw new IllegalArgumentException();
            }
            // An explicit empty array is a withdrawal; an entirely unsupported catalog is not.
            // Do not turn a future-only response into a request to clear usable local presets.
            if(items.length()>0 && accounts.isEmpty())throw new IOException("unsupported_presets");
            return Collections.unmodifiableList(accounts);
        } catch(JSONException|IllegalArgumentException failure) {
            // JSON library messages can contain the actual account. Never propagate their text.
            throw new IOException("invalid_presets");
        }
    }
    private static boolean supported(JSONObject item) {
        if(item.has("portalId") && !CURRENT_PORTAL.equals(item.opt("portalId")))return false;
        if(!item.has("schemaVersion"))return true;
        Object version=item.opt("schemaVersion");
        return version instanceof Number && ((Number)version).doubleValue()==1;
    }
    private static String string(JSONObject item,String key,int maximum) throws JSONException {
        Object value=item.get(key);
        if(!(value instanceof String))throw new IllegalArgumentException();
        String text=(String)value;
        if(text.length()>maximum)throw new IllegalArgumentException();
        for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw new IllegalArgumentException();
        return text;
    }
    static String encode(List<SavedAccountBook.Account> accounts) throws IOException {
        try {
            JSONArray items=new JSONArray();
            for(SavedAccountBook.Account account:accounts)items.put(new JSONObject()
                .put("portalId",CURRENT_PORTAL).put("schemaVersion",1)
                .put("username",account.username).put("password",account.password).put("label",account.label));
            String result=new JSONObject().put("version",1).put("accounts",items).toString();
            parse(result);return result;
        } catch(JSONException failure){throw new IOException("invalid_presets");}
    }
}
