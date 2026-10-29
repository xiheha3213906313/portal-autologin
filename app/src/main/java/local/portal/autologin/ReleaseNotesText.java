package local.portal.autologin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain app-facing changelog: preserve the wording and use only '-' for list formatting. */
final class ReleaseNotesText {
    private static final Pattern LINK=Pattern.compile("(!?)\\[([^\\]\\r\\n]*)\\]\\(([^\\s)]+)\\)");

    static String display(String markdown) {
        if(markdown==null || markdown.trim().isEmpty()) return "";
        StringBuilder text=new StringBuilder();
        boolean blank=true;
        for(String raw:markdown.replace("\r\n","\n").replace('\r','\n').split("\n",-1)) {
            String line=raw.trim();
            if(line.matches("(?:`{3,}|~{3,}).*") ||
                line.matches("(?:={3,}|-{3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})")) continue;
            line=line.replaceFirst("^(?:>[ \\t]+)+","");
            if(line.matches("#{1,6}[ \\t]+.*"))
                line=line.replaceFirst("^#{1,6}[ \\t]+","").replaceFirst("[ \\t]+#+$","");
            line=line.replaceFirst("^(?:[-+*]|[0-9]+[.)])[ \\t]+(?:\\[[ xX]\\][ \\t]+)?","- ");
            // Balanced emphasis only: keep identifiers such as private_account_presets and C#.
            for(int pass=0;pass<3;pass++) {
                line=line.replaceAll("(\\*{1,3}|~~)(?=\\S)(.+?\\S|\\S)\\1","$2");
                line=line.replaceAll("(?<![\\p{L}\\p{N}_])(_{1,3})(?=\\S)(.+?\\S|\\S)\\1(?![\\p{L}\\p{N}_])","$2");
            }
            line=line.replaceAll("`+([^`]+)`+","$1");
            Matcher links=LINK.matcher(line);
            StringBuffer plain=new StringBuffer();
            while(links.find()) {
                String label=links.group(2),url=links.group(3);
                String value=!links.group(1).isEmpty()?label:
                    (label.isEmpty() || label.equals(url)?url:label+"（"+url+"）");
                links.appendReplacement(plain,Matcher.quoteReplacement(value));
            }
            links.appendTail(plain);
            line=plain.toString().trim();
            if(line.isEmpty()) {
                if(!blank) {text.append('\n');blank=true;}
            } else {
                if(text.length()>0) text.append('\n');
                text.append(line);blank=false;
            }
        }
        return text.toString().trim();
    }
}
