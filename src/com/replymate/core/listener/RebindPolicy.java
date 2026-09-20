package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;

/** P-listener-foundation §4: pure throttle for NotificationListenerService
 *  requestRebind attempts. Official semantics (developer.android.com reference
 *  for NotificationListenerService, read 2026-09-20): after
 *  onListenerDisconnected() "You will not receive any events after this call,
 *  and may only call requestRebind(ComponentName) at this time"; and
 *  requestRebind(ComponentName) is "the only one that is safe to call before
 *  onListenerConnected() or after onListenerDisconnected()" (static, API 24+,
 *  no-op for listeners that were never granted access).
 *
 *  Asking for a rebind from BOTH the disconnect callback and process start is
 *  therefore correct and intended; the only app-side hazard is asking too
 *  often — a flapping OEM binder could otherwise spin. MIN_INTERVAL_MS is the
 *  bound: at most one request per window, shared by every caller through this
 *  stamped kv value, so competing rebind mechanisms are structurally
 *  impossible regardless of who calls next. */
public final class RebindPolicy {

    /** Choke-point stamp of the last request (ms epoch), shared by ALL callers. */
    public static final String KV_LAST_ATTEMPT = "listener.rebind.last";

    /** At most one rebind request per 30s across all callers — fast enough that
     *  a ColorOS unbind self-heals inside the same minute, bounded enough that
     *  even a reconnect↔disconnect flapping system cannot spin a loop. */
    public static final long MIN_INTERVAL_MS = 30_000L;

    private RebindPolicy() { }

    /** True when a new rebind request may go out now. Clock-went-backward skew
     *  (>60s) also fails open — recovery is the point; the stamp re-arms
     *  immediately so openness cannot become a storm either. */
    public static boolean due(KvStore kv, Clock clock) {
        long last = lastAttempt(kv);
        long now = clock.now();
        return last <= 0L || now - last >= MIN_INTERVAL_MS || now < last - 60_000L;
    }

    /** Record that a request is about to go out (call BEFORE the API call, so a
     *  throwing OEM framework still leaves the bounded stamp). */
    public static void stamp(KvStore kv, Clock clock) {
        try {
            kv.put(KV_LAST_ATTEMPT, String.valueOf(clock.now()));
        } catch (RuntimeException ignored) {
            // the stamp must never crash the recovery it bounds
        }
    }

    /** Last attempt timestamp, 0 when never (or unparsable — fail open). */
    public static long lastAttempt(KvStore kv) {
        if (kv == null) return 0L;
        try {
            return Long.parseLong(kv.get(KV_LAST_ATTEMPT, "0"));
        } catch (NumberFormatException nfe) {
            return 0L;
        }
    }
}
