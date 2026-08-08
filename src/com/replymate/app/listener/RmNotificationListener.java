package com.replymate.app.listener;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.listener.DiagnosticsRing;
import com.replymate.core.listener.IngestCoordinator;
import com.replymate.core.listener.IngestReport;
import com.replymate.core.listener.ListenerStats;
import com.replymate.core.listener.ParserRegistry;
import com.replymate.core.listener.RawNotif;
import com.replymate.core.model.Channel;
import java.util.EnumSet;
import java.util.Set;

/** Official NotificationListenerService — READ-ONLY (P3 provider architecture).
 *  Every notification flows through ONE pipeline:
 *      StatusBarNotification → NotifExtractor (dumb copy) → RawNotif
 *      → ParserRegistry.route (watch-gate FIRST, then per-app parser, stats)
 *      → IngestCoordinator (store/dedupe/ping aggregation)
 *  Nothing is ever sent back to any app. Unsupported or malformed notifications
 *  are ignored safely and counted, never crash the listener. */
public final class RmNotificationListener extends NotificationListenerService {

    /** Default-on channels preserve the shipped P2 behaviour (WhatsApp + Telegram).
     *  Every newly supported app starts OFF until the user enables it in
     *  Settings → Notification sources. */
    private static final Set<Channel> DEFAULTS_ON =
        EnumSet.of(Channel.WHATSAPP, Channel.TELEGRAM);

    /** Registry is stateless (parsers are pure) — one instance per process is fine. */
    private static final ParserRegistry REGISTRY = new ParserRegistry();

    /** Legacy aggregate counters (kept for the existing Diagnostics lines). */
    private static final String KV_PARSE_ERRORS = "listener.parse_errors";
    private static final String KV_UNPARSED = "listener.unparsed_total";

    /** P-background: the live, system-bound listener instance — the ONLY API that
     *  can see other apps' active notifications (NotificationManager's own
     *  getActiveNotifications returns ours only). Used by the assistant to
     *  re-resolve a quick-reply target at approve time. Null whenever the system
     *  isn't bound to us; every caller falls back honestly. */
    private static volatile RmNotificationListener ACTIVE;

    public static RmNotificationListener active() {
        return ACTIVE;
    }

    /** Live lookup of a still-posted notification by its sbn key; null if gone. */
    public StatusBarNotification findActive(String sbnKey) {
        if (sbnKey == null || sbnKey.isEmpty()) return null;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return null;
            for (StatusBarNotification s : all) {
                if (s != null && sbnKey.equals(s.getKey())) return s;
            }
        } catch (RuntimeException e) {
            // SecurityException if access was pulled mid-tap → honest fallback
        }
        return null;
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        ACTIVE = this;
        final AppContainer c = ReplyMateApp.containerOf(this);
        if (c == null) return;
        c.kv().put("listener.connected_at", String.valueOf(c.clock().now()));
        c.logger().i("NLS", "listener connected");
        // P-background-4 (restart self-heal): on every (re)bind, re-run the watched
        // pipeline once over everything still ACTIVE. Notifications posted while we
        // were dead (OEM kill, update, reboot) are caught up NOW instead of never.
        // Dedupe makes this idempotent: already-captured messages are skipped BEFORE
        // any ping/draft/job, so nothing re-alerts and nothing regenerates.
        Tasks.bg(new Runnable() {
            @Override public void run() {
                StatusBarNotification[] active;
                try {
                    active = getActiveNotifications();
                } catch (RuntimeException e) {
                    active = null;
                }
                reconcile(c, active);
            }
        });
    }

    /** Restart/re-bind catch-up (test seam): run a snapshot of still-active
     *  notifications through the SAME guarded pipeline as live arrivals. */
    static void reconcile(AppContainer c, StatusBarNotification[] active) {
        if (c == null || active == null) return;
        int seen = 0;
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            if (getPackageName(c).equals(pkgOf(sbn))) continue;   // never ingest our own
            seen++;
            process(sbn, c);
        }
        if (seen > 0) {
            ringLine(c, "listener (re)connected · reconciled " + seen + " active notif(s)");
        }
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (ACTIVE == this) ACTIVE = null;
        AppContainer c = ReplyMateApp.containerOf(this);
        if (c != null) {
            c.kv().put("listener.disconnected_at", String.valueOf(c.clock().now()));
            c.logger().w("NLS", "listener disconnected");
        }
    }

    @Override public void onDestroy() {
        if (ACTIVE == this) ACTIVE = null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null) return;
        final AppContainer c = ReplyMateApp.containerOf(this);
        if (c == null) return;
        // Callback runs on the service's main thread — move work off it.
        Tasks.bg(new Runnable() {
            @Override public void run() {
                process(sbn, c);
            }
        });
    }

    private static void process(StatusBarNotification sbn, AppContainer c) {
        final String pkg = pkgOf(sbn);
        try {
            RawNotif raw;
            try {
                raw = NotifExtractor.toRaw(sbn);
            } catch (RuntimeException boom) {
                bump(c, KV_PARSE_ERRORS);
                ringLine(c, "extract error · " + pkg + " · " + boom.getClass().getSimpleName());
                c.logger().e("NLS", "extract failed", boom);
                return;
            }
            if (raw == null) return;
            if (getPackageName(c).equals(pkg)) return;      // never ingest our own pings

            Set<Channel> enabled = ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON);
            ListenerStats stats = new ListenerStats(c.kv());
            ParserRegistry.Outcome out = REGISTRY.route(pkg, raw, enabled, stats);

            switch (out.kind) {
                case NOT_WATCHED:
                    return;                                  // silent by design
                case DISABLED:
                    return;                                  // drop BEFORE processing; stats recorded
                case IGNORED:
                    bump(c, KV_UNPARSED);                    // watched app, nothing readable/supported
                    return;
                case FAILED:
                    ringLine(c, "parse fail · " + pkg + " · " + safe(out.reason));
                    c.logger().w("NLS", "parser FAIL " + pkg + ": " + safe(out.reason));
                    return;
                case PARSED:
                    break;
                default:
                    return;
            }

            try {
                IngestCoordinator engine = new IngestCoordinator(
                    c.contactService(), c.messages(), c.kv(), c.clock(), c.logger());
                IngestReport rep = engine.handle(out.events, enabled);
                for (IngestReport.PingRequest ping : rep.pings) {
                    NotifierPings.schedule(c.app(), ping);
                    // P-background: remember where this conversation's quick-reply
                    // target lives, then debounce ONE background generation. The
                    // assistant never sends by itself — a human Approve is required.
                    com.replymate.app.assistant.AssistantTargetStore.save(
                        c.kv(), ping.contactId, raw, c.clock().now());
                    com.replymate.app.assistant.AssistantRunner.schedule(c, ping);
                }
            } catch (RuntimeException e) {
                bump(c, KV_PARSE_ERRORS);
                ringLine(c, "ingest error · " + pkg + " · " + e.getClass().getSimpleName());
                c.logger().e("NLS", "ingest failed", e);
            }
        } catch (RuntimeException e) {
            // Last-resort guard: the listener service itself must never crash.
            try {
                bump(c, KV_PARSE_ERRORS);
                ringLine(c, "pipeline error · " + pkg + " · " + e.getClass().getSimpleName());
                c.logger().e("NLS", "listener pipeline failure", e);
            } catch (RuntimeException ignored) { }
        } catch (Throwable t) {
            // P-background-4: even an Error (OEM-patched framework linkage failures)
            // must never silently kill a capture — count it and keep listening.
            try {
                bump(c, KV_PARSE_ERRORS);
                ringLine(c, "pipeline error · " + pkg + " · " + t.getClass().getSimpleName());
                c.logger().e("NLS", "listener pipeline failure (error)", t instanceof Exception ? (Exception) t : new Exception(t));
            } catch (RuntimeException ignored) { }
        }
    }

    private static String getPackageName(AppContainer c) {
        try { return c.app().getPackageName(); } catch (RuntimeException e) { return "com.replymate.app"; }
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "?" : s;
    }

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

    private static String pkgOf(StatusBarNotification sbn) {
        try { return sbn.getPackageName(); } catch (RuntimeException e) { return "?"; }
    }
}
