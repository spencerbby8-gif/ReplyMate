package com.replymate.app.listener;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import com.replymate.app.di.AppContainer;

/** P-listener-foundation §4, P-anr-startup: the ONE rebind mechanism (app
 *  layer). Every requestRebind in the app — disconnect recovery, process-start
 *  self-heal, any future explicit user action — goes through here so:
 *    (a) the stamped 30s throttle is shared; nothing can request more often
 *        than RebindPolicy.MIN_INTERVAL_MS no matter who calls next;
 *    (b) every attempt/skip/failure leaves an honest trace line with its
 *        reason, so a device report tells the story instead of guessing.
 *
 *  P-anr-startup: the throttle check and every diagnostic write are now
 *  IO-free on the caller thread (RebindGate memory stamp + DiagQueue async
 *  writer) — the proven startup-ANR chain (onCreate → due() → first DB open →
 *  enableWriteAheadLogging → fdatasync on main, exit-info reason=6 ANR
 *  "executing service" in emulator runs 41386158198/41404621261/41407382911)
 *  is eliminated WITHOUT changing rebind semantics:
 *    - requestRebind is still issued SYNCHRONOUSLY inside the lifecycle
 *      callback that owns it — never deferred past its valid window
 *      (official rule: the call is safe pre-connect / post-disconnect);
 *    - stamp-before-call is preserved (memory claim happens before the
 *      framework call; durable write-through ordered on the diag lane);
 *    - ACTIVE liveness stays driven solely by real connect/disconnect events.
 *  When the container is unavailable (container init failed) the request still
 *  goes out — unthrottled, since there is no gate — at most once per process. */
public final class ListenerRebind {

    private ListenerRebind() { }

    public static void request(AppContainer c, Context ctx, String reason) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT < 24) return;    // requestRebind added at 24
            if (c != null && !c.rebindGate().tryAcquire()) {
                c.diagQueue().trace("rebind skipped · " + safe(reason)
                    + " · throttled (30s shared bound)");
                return;
            }
            NotificationListenerService.requestRebind(
                new ComponentName(ctx, RmNotificationListener.class));
            if (c != null) {
                c.diagQueue().trace("rebind requested · " + safe(reason));
            }
        } catch (Throwable t) {
            try {
                if (c != null) {
                    c.diagQueue().trace("rebind request failed · " + safe(reason)
                        + " · " + t.getClass().getSimpleName());
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "?" : s;
    }
}
