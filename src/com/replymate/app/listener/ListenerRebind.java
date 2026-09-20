package com.replymate.app.listener;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import com.replymate.app.di.AppContainer;
import com.replymate.core.listener.ListenerTrace;
import com.replymate.core.listener.RebindPolicy;

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
 *  request. */
public final class ListenerRebind {

    private ListenerRebind() { }

    /** Best-effort rebind request. Never throws at the caller: framework/OEM
     *  rejections are traced, then swallowed (the listener must keep living
     *  through a broken binder). When the container is unavailable (container
     *  init failed) the request still goes out — unthrottled, since there is
     *  no kv to stamp — that path runs at most once per process start. */
    public static void request(AppContainer c, Context ctx, String reason) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT < 24) return;    // requestRebind added at 24
            if (c != null) {
                if (!RebindPolicy.due(c.kv(), c.clock())) {
                    ListenerTrace.line(c.kv(), c.clock(),
                        "rebind skipped · " + safe(reason) + " · throttled (30s shared bound)");
                    return;
                }
                RebindPolicy.stamp(c.kv(), c.clock());
            }
            NotificationListenerService.requestRebind(
                new ComponentName(ctx, RmNotificationListener.class));
            if (c != null) {
                ListenerTrace.line(c.kv(), c.clock(), "rebind requested · " + safe(reason));
            }
        } catch (Throwable t) {
            try {
                if (c != null) {
                    ListenerTrace.line(c.kv(), c.clock(), "rebind request failed · "
                        + safe(reason) + " · " + t.getClass().getSimpleName());
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "?" : s;
    }
}
