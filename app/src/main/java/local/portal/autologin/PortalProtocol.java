package local.portal.autologin;

import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Protocol learned from the portal's submitFunc / CheckSubmit / SetRedirectUrl. */
public final class PortalProtocol {
    public static final String ORIGIN = "https://captiveportal-login.bytedance.net:1026";
    static final Set<String> FIELDS = new HashSet<>(Arrays.asList("username", "password", "RedirectUrl",
        "anonymous", "anonymousurl", "accesscode", "accesscode1", "checkbox", "checkbox1"));

    public static URL trusted(String value) throws Exception {
        URL u = new URL(value);
        if (!"https".equals(u.getProtocol()) || !"captiveportal-login.bytedance.net".equalsIgnoreCase(u.getHost()) ||
            u.getPort() != 1026 || u.getUserInfo() != null) throw new IllegalArgumentException("unexpected_origin");
        return u;
    }

    public static URL loginFrame(String html, URL landing) throws Exception {
        Document d = Jsoup.parse(html, landing.toString());
        Element frame = d.selectFirst("iframe#leftFrame, frame#leftFrame");
        if (frame == null || !frame.hasAttr("src")) throw new IllegalArgumentException("missing_frame");
        URL u = trusted(new URL(landing, frame.attr("src")).toString());
        if (!"/login.html".equals(u.getPath())) throw new IllegalArgumentException("unexpected_frame");
        return u;
    }

    private static boolean hidden(Element e) {
        for (Element p = e; p != null; p = p.parent()) {
            String style = p.attr("style").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (p.hasAttr("hidden") || style.contains("display:none") || style.contains("visibility:hidden")) return true;
        }
        return false;
    }

    public static String body(String html, URL frame, URL landing, String user, String pass) throws Exception {
        if (user.isEmpty() || pass.isEmpty() || user.length() > 65 || pass.length() > 128)
            throw new IllegalArgumentException("invalid_credentials_length");
        Document d = Jsoup.parse(html, frame.toString());
        Element f = d.selectFirst("form#LoginForm");
        if (f == null || !"post".equalsIgnoreCase(f.attr("method"))) throw new IllegalArgumentException("missing_form");
        URL action = trusted(new URL(frame, f.attr("action")).toString());
        if (!"/login".equals(action.getPath()) || action.getQuery() != null || f.hasAttr("enctype") &&
            !"application/x-www-form-urlencoded".equalsIgnoreCase(f.attr("enctype")))
            throw new IllegalArgumentException("unexpected_form");
        if (f.select("input[name=username][type=text]").size() != 1 ||
            f.select("input#password[name=password][type=password]").size() != 1)
            throw new IllegalArgumentException("unexpected_fields");
        Element button = f.selectFirst("input#sub2[type=submit]");
        if (button == null || button.hasAttr("disabled")) throw new IllegalArgumentException("disabled_button");
        LinkedHashMap<String,String> values = new LinkedHashMap<>();
        for (Element input : f.select("input[name]")) {
            if (input.hasAttr("disabled")) continue;
            String name = input.attr("name"), type = input.attr("type").toLowerCase(Locale.ROOT);
            if (Arrays.asList("submit", "button", "reset").contains(type)) continue;
            if (!FIELDS.contains(name) || values.containsKey(name)) throw new IllegalArgumentException("changed_fields");
            if (type.equals("checkbox") || type.equals("radio")) {
                if (!hidden(input)) throw new IllegalArgumentException("manual_checkbox");
                if (!input.hasAttr("checked")) continue;
            }
            if (name.equals("accesscode") && !hidden(input)) throw new IllegalArgumentException("manual_accesscode");
            values.put(name, input.hasAttr("value") ? input.attr("value") : type.equals("checkbox") ? "on" : "");
        }
        values.put("username", user); values.put("password", pass);
        String top = landing.toString();
        int index = top.indexOf("RedirectUrl=");
        if (index >= 0) values.put("RedirectUrl", top.substring(index+12, Math.min(top.length(),index+212)));
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String,String> e : values.entrySet()) {
            if (encoded.length() != 0) encoded.append('&');
            encoded.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return encoded.toString();
    }
}
