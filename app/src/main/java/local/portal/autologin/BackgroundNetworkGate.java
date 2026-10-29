package local.portal.autologin;

import java.util.LinkedHashMap;

/** Event deduplication only: no timer, scanning loop or automatic retry after failed authentication. */
final class BackgroundNetworkGate {
    private static final class Entry {
        boolean started,running,online,captive,validated,observed,pending;
        long lastAttempt;
    }
    private final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>();
    boolean wasChecked(long network) {Entry e=entries.get(network);return e!=null && e.started;}
    boolean isRunning(long network) {Entry e=entries.get(network);return e!=null && e.running;}
    private Entry entry(long network) {
        Entry e=entries.get(network);
        if(e==null) {
            e=new Entry();entries.put(network,e);
            if(entries.size()>16)entries.remove(entries.keySet().iterator().next());
        }
        return e;
    }
    void observe(long network,boolean captive,boolean validated) {
        Entry e=entry(network);
        // Capture changes when callbacks arrive, not after debounce has collapsed them.
        boolean changed=e.observed && ((e.validated && !validated) || (!e.captive && captive));
        if(changed && (e.online || e.running))e.pending=true;
        e.captive=captive;e.validated=validated;e.observed=true;
    }
    long nextCheckDelay(long network,long now) {
        Entry e=entries.get(network);
        if(e==null || !e.started)return 0;
        if(e.running || !e.pending || !e.online)return -1;
        // One deferred check for a real network event; no recurring connectivity polling.
        return Math.max(0,30000-(now-e.lastAttempt));
    }
    boolean begin(long network,long now,boolean explicitRetry) {
        Entry e=entry(network);
        if(e.running || (!explicitRetry && nextCheckDelay(network,now)!=0))return false;
        e.started=true;e.running=true;e.online=false;e.pending=false;e.lastAttempt=now;
        return true;
    }
    void completed(long network,boolean online) {
        Entry e=entries.get(network);
        if(e!=null){e.running=false;e.online=online;if(!online)e.pending=false;}
    }
    void lost(long network) {entries.remove(network);}
    void clear() {entries.clear();}
}
