package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** P-anr-startup: single-lane, queue-backed writer for DIAGNOSTIC kv keys —
 *  the listener trace ring, the legacy diagnostics ring, and lifecycle scalars
 *  (listener.connected_at / listener.disconnected_at).
 *
 *  Why: NLS lifecycle callbacks (onListenerConnected / onListenerDisconnected)
 *  and Application.onCreate run on the MAIN thread, where synchronous SQLite
 *  is the proven startup-ANR path (fdatasync under setJournalMode — see
 *  RebindGate's class doc for the pulled stack). Every diagnostic write now
 *  lands here: callers enqueue (never blocking, never IO); a single injected
 *  IO lane drains in exact enqueue order.
 *
 *  Properties relied on elsewhere:
 *    - listener.trace gets ONE writer lane ⇒ the ring's read-modify-write can
 *      no longer be lost-updated between the callback thread and the ingest
 *      lane (previously a real cross-thread race);
 *    - timestamps are captured at ENQUEUE time, so line order is event order
 *      even across producer threads;
 *    - a throwing kv never escapes the drain (diagnostics must never hurt
 *      capture, and must never crash the listener);
 *    - diagnostic-only scope: message/contact data durability is unchanged
 *      (that path keeps its own synchronous writes on background lanes).
 *    - async means a process death between enqueue and drain can cost the last
 *      few DIAGNOSTIC lines. Accepted: this is a diagnostic lens, not a log —
 *      capture boundary data that matters (stored messages) is unaffected. */
public final class DiagQueue {

    private final KvStore kv;
    private final Clock clock;
    private final Executor io;
    private final ConcurrentLinkedQueue<Runnable> pending =
        new ConcurrentLinkedQueue<Runnable>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    private final Runnable drain = new Runnable() {
        @Override public void run() {
            try {
                Runnable r;
                while ((r = pending.poll()) != null) {
                    try { r.run(); }
                    catch (RuntimeException ignored) { /* diagnostics never crash */ }
                }
            } finally {
                scheduled.set(false);
                // a producer may have slipped in between the last poll and the
                // reset — re-arm so nothing waits forever
                if (!pending.isEmpty()) schedule();
            }
        }
    };

    public DiagQueue(KvStore kv, Clock clock, Executor io) {
        this.kv = kv;
        this.clock = clock;
        this.io = io;
    }

    /** Scalar diagnostic put (e.g. listener.connected_at). Async, ordered. */
    public void put(final String key, final String value) {
        enqueue(new Runnable() {
            @Override public void run() { kv.put(key, value); }
        });
    }

    /** One bounded listener-trace line; ts stamped at enqueue (event time). */
    public void trace(final String line) {
        if (line == null) return;
        final long ts = clock.now();
        enqueue(new Runnable() {
            @Override public void run() {
                kv.put(ListenerTrace.KV_TRACE,
                    DiagnosticsRing.append(kv.get(ListenerTrace.KV_TRACE, ""), ts, line));
            }
        });
    }

    /** One legacy diagnostics-ring line for key KV_RING-style rings. */
    public void ring(final String key, final String line) {
        if (key == null || line == null) return;
        final long ts = clock.now();
        enqueue(new Runnable() {
            @Override public void run() {
                kv.put(key, DiagnosticsRing.append(kv.get(key, ""), ts, line));
            }
        });
    }

    /** Test seam: number of not-yet-drained operations. */
    public int pendingForTest() { return pending.size(); }

    private void enqueue(Runnable task) {
        pending.add(task);
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            io.execute(drain);
        }
    }
}
