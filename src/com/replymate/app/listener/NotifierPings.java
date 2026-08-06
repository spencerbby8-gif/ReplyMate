package com.replymate.app.listener;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.replymate.app.R;
import com.replymate.app.ui.ConversationActivity;
import com.replymate.core.listener.BatchWindow;
import com.replymate.core.listener.IngestReport;
import java.util.HashMap;
import java.util.Map;

/** Posts ReplyMate's own quiet "new message" notifications, debounced per contact
 *  (BatchWindow) and permission-gated. READ-ONLY app: these pings are the only
 *  outward effect of the listener — tapping opens the draft screen. */
public final class NotifierPings {

    public static final String CHANNEL_ID = "rm_messages";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Long, Runnable> PENDING = new HashMap<Long, Runnable>();

    private NotifierPings() { }

    /** Create our notification channel once per process (26+). No-op below 26. */
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Message alerts", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("New messages ReplyMate captured for drafting (read-only)");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** Debounced schedule: collapses bursts into one ping per contact. */
    public static void schedule(final Context ctx, final IngestReport.PingRequest ping) {
        final long contactId = ping.contactId;
        Runnable previous;
        synchronized (PENDING) {
            previous = PENDING.remove(contactId);
        }
        if (previous != null) HANDLER.removeCallbacks(previous);

        Runnable post = new Runnable() {
            @Override public void run() {
                synchronized (PENDING) {
                    PENDING.remove(contactId);
                }
                postNow(ctx.getApplicationContext(), ping);
            }
        };
        synchronized (PENDING) {
            PENDING.put(contactId, post);
        }
        long due = BatchWindow.dueAt(ping.latestTs);
        HANDLER.postDelayed(post, BatchWindow.delayFrom(System.currentTimeMillis(), due));
    }

    private static void postNow(Context ctx, IngestReport.PingRequest ping) {
        if (!ListenerStatus.canPostNotifications(ctx)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(ctx, ConversationActivity.class);
        open.putExtra(ConversationActivity.EXTRA_CONTACT_ID, ping.contactId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
            ctx, (int) ping.contactId, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        Notification n = b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("New from " + ping.displayName)
            .setContentText(ping.snippet)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build();
        nm.notify((int) ping.contactId, n);
    }

    /** Cancel any scheduled (not yet shown) ping for a contact. */
    public static void cancelPending(long contactId) {
        Runnable r;
        synchronized (PENDING) {
            r = PENDING.remove(contactId);
        }
        if (r != null) HANDLER.removeCallbacks(r);
    }
}
