package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** P-anr-startup: memory-first decision gate for NotificationListenerService
 *  requestRebind() — the caller thread NEVER touches SQLite.
 *
 *  Evidence (emulator run 41407382911, pulled procexitstore stack): the boot
 *  ANR chain was application-startup sync disk I/O — ReplyMateApp.onCreate →
 *  nudgeListenerRebind → ListenerRebind.request → RebindPolicy.due → KvDao.get
 *  → first SQLiteOpenHelper open → DbHelper.onConfigure → enableWriteAheadLogging
 *  (setJournalMode) → fdatasync — main thread in state=D until the framework
 *  recorded "executing service …RmNotificationListener" and later killed pid.
 *
 *  What this class changes: the throttle CHECK becomes a pure in-memory atomic
 *  read/CAS; the durable stamp (listener.rebind.last, still the shared 30s
 *  truth across process death) is read ONCE on the injected IO lane at seed
 *  time and written through asynchronously on the same lane. The rebind
 *  REQUEST itself is unaffected — this class decides only, it never calls the
 *  framework, so the request can still be issued synchronously inside the
 *  lifecycle callback that owns it (never deferred past onListenerDisconnected).
 *
 *  Semantics preserved:
 *    - at most one acquisition per 30s while the process lives (memory stamp);
 *    - durable stamp still lands in kv (async, ordered on the same lane);
 *    - a process restarted within the window stays throttled once the seed
 *      read lands (kv truth) — a cold, not-yet-seeded start is allowed ONCE
 *      per process (bounded: the stamp is claimed in memory immediately, and
 *      UNLOADED can never be re-entered), instead of doing a blocking kv read
 *      on the caller thread. Fail-open on skew/malformed data, as before. */
public final class RebindGate {

    /** Sentinel: durable stamp not yet read by this process. */
    private static final long UNLOADED = Long.MIN_VALUE;

    private final KvStore kv;
    private final Clock clock;
    private final Executor io;
    private final AtomicLong mem = new AtomicLong(UNLOADED);

    public RebindGate(KvStore kv, Clock clock, Executor io) {
        this.kv = kv;
        this.clock = clock;
        this.io = io;
    }

    /** Enqueue the one durable-state read on the IO lane. Runs there BEFORE any
     *  write-through (same single-thread lane ⇒ read-before-write ordering).
     *  Caller-thread safe: queues only, never reads kv here. */
    public void seedAsync() {
        io.execute(new Runnable() {
            @Override public void run() {
                if (mem.get() != UNLOADED) return;   // a request already staked a claim
                final long last;
                try {
                    last = RebindPolicy.lastAttempt(kv);
                } catch (RuntimeException boom) {
                    return;   // fail-open: stay UNLOADED; next tryAcquire decides due
                }
                mem.compareAndSet(UNLOADED, last);
            }
        });
    }

    /** IO-free throttle decision — safe on the main thread.
     *  true  → the caller MUST issue requestRebind now (window claimed in
     *          memory BEFORE the call, so a throwing OEM framework still
     *          leaves the bound — same stamp-before-call rule as P-LF §4);
     *  false → inside the shared 30s window. */
    public boolean tryAcquire() {
        final long now = clock.now();
        while (true) {
            long cur = mem.get();
            if (cur != UNLOADED && !RebindPolicy.due(cur, now)) return false;
            if (mem.compareAndSet(cur, now)) {
                persistAsync(now);
                return true;
            }
        }
    }

    private void persistAsync(final long stamp) {
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    kv.put(RebindPolicy.KV_LAST_ATTEMPT, String.valueOf(stamp));
                } catch (RuntimeException ignored) {
                    // the stamp must never crash the recovery it bounds
                }
            }
        });
    }

    /** Test seam: current in-memory stamp (0 when never loaded/claimed). */
    public long memStampForTest() {
        long v = mem.get();
        return v == UNLOADED ? 0L : v;
    }
}
