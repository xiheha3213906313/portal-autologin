package local.portal.autologin;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Process-local claims plus a persisted last submission; opening the UI never replays a POST. */
final class LoginAttempts {
    static final long SUBMISSION_COOLDOWN = 300000;
    static final long FAILURE_COOLDOWN = 15000;
    enum Decision { ALLOW, RUNNING, RECENT_SUBMISSION, RECENT_FAILURE }
    private static final class Entry {
        long time;
        boolean running = true, submitted;
        Entry(long now) { time = now; }
    }
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();

    synchronized Decision begin(long network, long now, long savedNetwork, long savedTime, boolean retry) {
        return begin(network,now,savedNetwork,savedTime,retry,false);
    }
    synchronized Decision begin(long network, long now, long savedNetwork, long savedTime, boolean retry, boolean force) {
        Entry e = entries.get(network);
        if (e != null && e.running) return Decision.RUNNING;
        if (!force && network == savedNetwork && recent(now, savedTime, SUBMISSION_COOLDOWN))
            return Decision.RECENT_SUBMISSION;
        if (!force && e != null && recent(now, e.time, e.submitted ? SUBMISSION_COOLDOWN : FAILURE_COOLDOWN)) {
            if (e.submitted) return Decision.RECENT_SUBMISSION;
            if (!retry) return Decision.RECENT_FAILURE;
        }
        entries.put(network, new Entry(now));
        if (entries.size() > 16) {
            Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Entry> oldest = it.next();
                if (!oldest.getValue().running) { it.remove(); break; }
            }
        }
        return Decision.ALLOW;
    }
    synchronized void submitted(long network, long now) {
        Entry e = entries.get(network);
        if (e != null) { e.submitted = true; e.time = now; }
    }
    synchronized void finish(long network, boolean definitelyRejected) {
        Entry e = entries.get(network);
        if (e != null) {
            e.running = false;
            if (definitelyRejected) e.submitted = false;
        }
    }
    static boolean recent(long now, long then, long duration) {
        return then >= 0 && now >= then && now - then < duration;
    }
}
