package local.portal.autologin;

import java.util.*;
import java.util.regex.*;

/** Version-filtered release notes, independent of Android and GitHub's publication ordering. */
final class ReleaseHistory {
    static final int MAX_PAGES=20;
    static final int PAGE_SIZE=100;
    static final class Entry {
        final String version,body;
        final boolean draft,prerelease;
        Entry(String version,String body,boolean draft,boolean prerelease) {
            this.version=version;this.body=body;this.draft=draft;this.prerelease=prerelease;
        }
    }
    static final class Page {
        final List<Entry> entries;
        final boolean hasNext;
        Page(List<Entry> entries,boolean hasNext) {this.entries=entries;this.hasNext=hasNext;}
    }
    interface Pages { Page load(int number) throws Exception; }
    static final class Result {
        final String notes;
        final boolean complete;
        Result(String notes,boolean complete) {this.notes=notes;this.complete=complete;}
    }

    static Result collect(String current,Entry latest,Pages pages) {
        // Keep /latest authoritative for both the upper version bound and its own notes.
        TreeMap<String,Entry> selected=new TreeMap<>((a,b)->ReleaseRules.compareVersions(b,a));
        if (ReleaseRules.compareVersions(latest.version,current)<=0)
            return new Result(section(latest.body),true);
        selected.put(latest.version,latest);
        boolean complete=false;
        try {
            for(int number=1;number<=MAX_PAGES;number++) {
                Page page=pages.load(number);
                for(Entry entry:page.entries) {
                    if(entry.draft || entry.prerelease)continue;
                    try {
                        if(ReleaseRules.compareVersions(entry.version,current)>0 &&
                            ReleaseRules.compareVersions(entry.version,latest.version)<=0)
                            selected.putIfAbsent(entry.version,entry);
                    } catch(IllegalArgumentException ignored) { /* Non-version tags are not app updates. */ }
                }
                // Do not stop at an older version: GitHub orders releases by publication, not version.
                if(!page.hasNext) {complete=true;break;}
            }
        } catch(Exception ignored) { /* Retain available notes and the usable latest download. */ }
        StringBuilder text=new StringBuilder();
        for(Entry entry:selected.values()) {
            if(text.length()>0)text.append("\n\n");
            text.append('v').append(entry.version.trim().replaceFirst("^[vV]","")).append('\n');
            String body=section(entry.body);
            text.append(body.isEmpty()?"此版本未提供更新说明":body);
        }
        return new Result(text.toString(),complete);
    }

    static String section(String body) {
        if(body==null)return "";
        body=body.replace("\r\n","\n").replace('\r','\n');
        Matcher headings=Pattern.compile("(?m)^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$").matcher(body);
        int start=-1,level=0;
        while(headings.find()) {
            String title=headings.group(2).replaceFirst("[ \\t]+#+$","").trim();
            if(start<0) {
                if(Arrays.asList("更新内容","更新日志","更新说明","更新").contains(title)) {
                    start=headings.end();level=headings.group(1).length();
                }
            } else if(headings.group(1).length()<=level) {
                return body.substring(start,headings.start()).trim();
            }
        }
        return (start<0?body:body.substring(start)).trim();
    }
}
