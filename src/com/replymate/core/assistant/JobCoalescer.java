package com.replymate.core.assistant;

import java.util.HashMap;
import java.util.Map;

/** P-background-2: exactly-once job identity per key (contact). Every new schedule
 *  for the same key SUPersedes the older token — the older job can then never run,
 *  and an in-flight older job must check isCurrent() BEFORE doing expensive work
 *  (the provider call) and before posting any user-visible result.
 *  Pure JVM so the guarantee is unit-pinned, not assumed. */
public final class JobCoalescer {

    private final Map<Long, Long> currentByKey = new HashMap<Long, Long>();
    private long seq;

    /** Register a new job attempt for key; returns its token. Any previous token
     *  for this key is now obsolete (cancelled automatically by identity). */
    public synchronized long begin(long key) {
        long token = ++seq;
        currentByKey.put(Long.valueOf(key), Long.valueOf(token));
        return token;
    }

    /** Only the LATEST token for a key may proceed. */
    public synchronized boolean isCurrent(long key, long token) {
        Long current = currentByKey.get(Long.valueOf(key));
        return current != null && current.longValue() == token;
    }

    /** Job done — clears only if it is still the current one (a newer job for the
     *  same key must survive). */
    public synchronized void finish(long key, long token) {
        if (isCurrent(key, token)) {
            currentByKey.remove(Long.valueOf(key));
        }
    }

    public synchronized int pendingCount() {
        return currentByKey.size();
    }
}
