package local.portal.autologin;

import java.text.SimpleDateFormat;
import java.util.*;

/** Bounded in-memory trace. Only stage tokens, integer counters and allowlisted faults. */
public final class DiagnosticLog {
    public static final String VERSION="0.4.2";
    private static boolean enabled;
    private static final ArrayDeque<String> lines=new ArrayDeque<>();
    private static final Set<String> REASONS=new HashSet<>(Arrays.asList(
        "unexpected_origin","missing_frame","unexpected_frame","invalid_credentials_length",
        "missing_form","unexpected_form","unexpected_fields","disabled_button","changed_fields",
        "manual_checkbox","manual_accesscode","attempt_cancelled","response_limit","page_unavailable",
        "redirect_limit","unexpected_login_page","authentication_rejected","login_form_returned","submission_response_unavailable"));
    public static synchronized void setEnabled(boolean value) {
        if(enabled==value)return;
        enabled=value;append(value?"logging.enabled":"logging.disabled");
    }
    public static synchronized void event(String stage,int detail) {
        if(!enabled || stage==null || !stage.matches("[a-z0-9_.:-]{1,96}"))return;
        append(stage+" "+detail);
    }
    private static void append(String value) {
        if(lines.size()>=256)lines.removeFirst();
        lines.addLast(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date())+" "+value);
    }
    public static synchronized void clear() {
        lines.clear();if(enabled)append("logging.cleared");
    }
    public static synchronized String snapshot() {
        return "PortalAuto "+VERSION+"\nlogging="+(enabled?"on":"off")+
            "\nOnly stage codes and counters; no credentials, cookies, URLs or response bodies.\n"+
            String.join("\n",lines)+"\n";
    }
    public static void failure(Throwable e) {
        // Exception messages from network libraries may contain sensitive URLs: never export them.
        for(int i=0;e!=null && i<5;i++,e=e.getCause()) {
            String name=e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z0-9_]{1,64}"))name="exception";
            event("error."+name,i);
            String message=e.getMessage();
            if(REASONS.contains(message))event("reason."+message,0);
        }
    }
}
