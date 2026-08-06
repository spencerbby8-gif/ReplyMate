package com.replymate.app.listener;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.listener.DiagnosticsRing;
import com.replymate.core.listener.IngestCoordinator;
import com.replymate.core.listener.IngestReport;
import com.replymate.core.listener.ListenerFilter;
import com.replymate.core.listener.NotifEvent;
import java.util.List;

/** Official NotificationListenerService — READ-ONLY (blueprint P2).
 *  Parses WhatsApp/Telegram notifications via supported APIs, stores them locally,
 *  and schedules ReplyMate's own draft alerts. Nothing is ever sent back. */
public final class RmNotificationListener extends NotificationListenerService {

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        AppContainer c = ReplyMateApp.containerOf(this);
        if (c != null) {
            c.kv().put("listener.connected_at", String.valueOf(c.clock().now()));
            c.logger().i("NLS", "listener connected");
        }
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        AppContainer c = ReplyMateApp.containerOf(this);
        if (c != null) {
            c.kv().put("listener.disconnected_at", String.valueOf(c.clock().now()));
            c.logger().w("NLS", "listener disconnected");
        }
    }

    /** Developer diagnostics (P2 scope): every failure path below used to be INVISIBLE
     *  on device (logcat only). Count them and mirror a content-free line into the
     *  diagnostics ring so Settings → Diagnostics shows what really happened. */
    private static final String KV_PARSE_ERRORS = "listener.parse_errors";
    private static final String KV_UNPARSED = "listener.unparsed_total";

    private static void bump(AppContainer c, String key) {
        long cur;
        try { cur = Long.parseLong(c.kv().get(key, "0")); }
        catch (NumberFormatException nfe) { cur = 0; }
        c.kv().put(key, String.valueOf(cur + 1));
    }

    private static void ringLine(AppContainer c, String line) {
        c.kv().put(IngestCoordinator.KV_RING, DiagnosticsRing.append(
            c.kv().get(IngestCoordinator.KV_RING, ""), c.clock().now(), line));
    }

    @Override public void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null) return;
        final AppContainer c = ReplyMateApp.containerOf(this);
        if (c == null) return;
        // Callback runs on the service's main thread — move work off it.
        Tasks.bg(new Runnable() {
            @Override public void run() {
                try {
                    List<NotifEvent> events;
                    try {
                        events = NotifExtractor.extract(sbn);
                    } catch (RuntimeException parseFail) {
                        // Was a TOTAL, SILENT loss for this notification. Now counted +
                        // ring-recorded (content-free: package + exception class only).
                        bump(c, KV_PARSE_ERRORS);
                        ringLine(c, "parse error · " + pkgOf(sbn) + " · "
                            + parseFail.getClass().getSimpleName());
                        c.logger().e("NLS", "extract failed", parseFail);
                        return;
                    }
                    if (events.isEmpty()) {
                        // Watched app whose notification had nothing readable (calls,
                        // status/backup notices) — count only, keep ring noise-free.
                        if (ListenerFilter.channelForPackage(pkgOf(sbn)) != null) {
                            bump(c, KV_UNPARSED);
                        }
                        return;
                    }

                    IngestCoordinator engine = new IngestCoordinator(
                        c.contactService(), c.messages(), c.kv(), c.clock(), c.logger());
                    boolean watchWa = "1".equals(c.kv().get("watch.whatsapp", "1"));
                    boolean watchTg = "1".equals(c.kv().get("watch.telegram", "1"));
                    IngestReport rep = engine.handle(events, watchWa, watchTg);

                    for (final IngestReport.PingRequest ping : rep.pings) {
                        NotifierPings.schedule(getApplicationContext(), ping);
                    }
                } catch (RuntimeException e) {
                    bump(c, KV_PARSE_ERRORS);
                    ringLine(c, "ingest error · " + pkgOf(sbn) + " · "
                        + e.getClass().getSimpleName());
                    c.logger().e("NLS", "ingest failed", e);
                }
            }
        });
    }

    private static String pkgOf(StatusBarNotification sbn) {
        try { return sbn.getPackageName(); } catch (RuntimeException e) { return "?"; }
    }
}
