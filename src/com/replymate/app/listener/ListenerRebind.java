package com.replymate.app.listener;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.listener.ListenerTrace;
import com.replymate.core.listener.RebindGate;
import com.replymate.core.listener.RebindPolicy;
import com.replymate.core.ports.KvStore;

/** P-listener-foundation §4: the ONE rebind mechanism (app layer). Every
 *  requestRebind in the app — disconnect recovery, process-start self-heal,
 *  any future explicit user action — goes through here so:
 *    (a) the stamped 30s throttle is shared; nothing can request more often
 *        than RebindPolicy.MIN_INTERVAL_MS no matter who calls next;
 *    (b) every attempt/skip/failure leaves an honest trace line with its
 *        reason, so a device report tells the story instead of guessing.
 *
 *  Officially sanctioned call sites per developer.android.com
 *  (NotificationListenerService reference): after onListenerDisconnected() and
 *  before onListenerConnected() — exactly our two callers. The request NEVER
 *  fabricates liveness: "listener bound right now" stays driven solely by the
 *  real onListenerConnected()/onListenerDisconnected() events, not by this
 *  request.
 *
 *  P-anr-startup (stack-proven ON DEVICE-CLASS EVIDENCE): the previous version
 *  ran RebindPolicy.due/stamp and ListenerTrace.line synchronously on the
 *  CALLING thread — on process start and inside the service lifecycle
 *  callbacks that is the MAIN thread, and the first kv read on a cold install
 *  opens SQLite and pays the journal_mode=WAL fsync right there
 *  (ApplicationExitInfo reason=6 ANR, frames fdatasync ← setJournalMode,
 *  three harness runs). Rebind decisions are now made by RebindGate — a
 *  process-local, I/O-free monitor — so the lifecycle callback NEVER touches
 *  SQLite and requestRebind keeps firing INSIDE its valid callback window
 *  (never deferred). The persisted kv stamp still bounds cross-process
 *  behaviour: the capture lane writes it after each claim and re-seeds the
 *  gate from it once per process start (off-main). See RebindGate's javadoc
 *  for the deliberate cold-start-vs-seed boundary and its worst case. */
public final class ListenerRebind {

    /** One per process; static so a broken container still leaves every caller
     *  bounded (the old unthrottled container-null escape hatch is no longer
     *  needed for safety — the gate needs no storage). */
    private static final RebindGate GATE = new RebindGate();

    private ListenerRebind() { }

    /** Warm path: open kv OFF the main thread (first open pays the WAL fsync)
     *  and seed the in-process gate from the persisted stamp. Called once per
     *  process start from the capture lane (ReplyMateApp.onCreate enqueues it
     *  before anything else). */
    public static void warmFromKv(KvStore kv) {
        GATE.seed(RebindPolicy.lastAttempt(kv));   // lastAttempt(null) → 0 → no-op
    }

    /** Best-effort rebind request. Never throws at the caller: framework/OEM
     *  rejections are traced, then swallowed (the listener must keep living
     *  through a broken binder). Decision is synchronous and I/O-free; the
     *  framework call itself happens on the calling thread — unchanged. */
    public static void request(final AppContainer c, Context ctx, String reason) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT < 24) return;    // requestRebind added at 24
            if (!GATE.tryClaim(System.currentTimeMillis())) {
                recordAsync(c, false, "rebind skipped · " + safe(reason)
                    + " · throttled (30s shared bound)");
                return;
            }
            NotificationListenerService.requestRebind(
                new ComponentName(ctx, RmNotificationListener.class));
            recordAsync(c, true, "rebind requested · " + safe(reason));
        } catch (Throwable t) {
            recordAsync(c, true, "rebind request failed · " + safe(reason) + " · "
                + t.getClass().getSimpleName());
        }
    }

    /** Mirror the verdict to the durable record — on the CAPTURE lane, never on
     *  the caller's thread. Lane ordering keeps the rebind lines chronological
     *  against the surrounding capture/trace lines. The persisted stamp moves
     *  ONLY when a request actually went out (same rule as before: a skip must
     *  not re-arm the window, and a throwing framework still leaves the stamp —
     *  the in-memory gate already claimed it atomically). */
    private static void recordAsync(final AppContainer c, final boolean stamp, final String line) {
        if (c == null) return;
        Tasks.ingest(new Runnable() {
            @Override public void run() {
                try {
                    if (stamp) RebindPolicy.stamp(c.kv(), c.clock());   // persisted shared record
                    ListenerTrace.line(c.kv(), c.clock(), line);
                } catch (Throwable ignored) {
                    // diagnostics must never crash the recovery they record
                }
            }
        });
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "?" : s;
    }
}
