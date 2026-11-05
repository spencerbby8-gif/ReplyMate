package com.pingposter;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** HARNESS-ONLY notification poster. Driven by `am start` extras:
 *
 *    --es shape  wa-11 | wa-burst | wa-group | wa-noaction | wa-withaction
 *                | wa-selfstatus | dc-channel | dc-nohistory | plain | clear
 *                | rebind-rm  (no post: cross-app requestRebind attempt on
 *                            ReplyMate's listener, result logged to PP)
 *    --ei nid    notification id for this post (distinct per scenario)
 *
 *  Every shape is assembled from the OFFICIAL documented MessagingStyle /
 *  Notification APIs (developer.android.com) and posted through the REAL
 *  NotificationManager — ReplyMate's listener receives genuine framework
 *  delivery events. The SHAPE is synthetic and labelled so everywhere;
 *  this is REAL-platform-delivery evidence, never claimed as genuine
 *  WhatsApp/Discord app integration. */
public final class MainActivity extends Activity {

    private static final String TAG = "PP";
    private static final String CHANNEL = "pp.test";
    private static final long T0 = 1_700_000_000_000L;   // arbitrary epoch base

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        String shape = getIntent().getStringExtra("shape");
        int nid = getIntent().getIntExtra("nid", 1001);
        if (shape == null || shape.isEmpty()) shape = "plain";
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            ensureChannel(nm);
            if ("clear".equals(shape)) {
                nm.cancelAll();
                Log.i(TAG, "posted shape=clear id=0");
                finish();
                return;
            }
            if ("rebind-rm".equals(shape)) {
                // fix 12: the ONLY documented trigger for a disconnect/reconnect
                // cycle while notification access STAYS granted is
                // NotificationListenerService.requestRebind itself (NMS unbinds
                // then rebinds the component). Whether a THIRD-PARTY caller may
                // drive it for another app's component is exactly what the
                // harness needs to learn empirically — log the outcome either way.
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        new android.content.ComponentName("com.replymate.app",
                            "com.replymate.app.listener.RmNotificationListener"));
                    Log.i(TAG, "rebind-rm result=accepted (call returned)");
                } catch (Throwable t) {
                    Log.i(TAG, "rebind-rm result=rejected ex=" + t.getClass().getName()
                        + " msg=" + String.valueOf(t.getMessage()));
                }
                finish();
                return;
            }
            Notification n = build(shape);
            nm.notify(null, nid, n);
            Log.i(TAG, "posted shape=" + shape + " id=" + nid
                + " pkg=" + getPackageName() + " ts=" + System.currentTimeMillis());
        } catch (RuntimeException boom) {
            Log.e(TAG, "post failed shape=" + shape + ": " + boom.getClass().getSimpleName(), boom);
        }
        finish();
    }

    private void ensureChannel(NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "PingPoster test",
            NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);
    }

    private Notification build(String shape) {
        long now = System.currentTimeMillis();
        switch (shape) {
            case "wa-11":
                // SYNTHETIC WhatsApp-1:1-shaped: MessagingStyle, one sender, std Reply.
                return msg("Ada", null, false, now,
                    new String[][]{{"Ada", "ping from pingposter, 1:1", "" + T0}},
                    true, Notification.CATEGORY_MESSAGE);
            case "wa-burst":
                // SYNTHETIC burst: three messages, one sender.
                return msg("Ada", null, false, now,
                    new String[][]{{"Ada", "first of burst", "" + T0},
                        {"Ada", "second of burst", "" + (T0 + 1000)},
                        {"Ada", "third of burst", "" + (T0 + 2000)}},
                    true, Notification.CATEGORY_MESSAGE);
            case "wa-group":
                // SYNTHETIC WhatsApp-group-shaped: convTitle, 2 senders, Reply action.
                return msg("Weekend crew", "Weekend crew", true, now,
                    new String[][]{{"Ada", "plan stands", "" + T0},
                        {"Bim", "latest line", "" + (T0 + 1000)}},
                    true, Notification.CATEGORY_MESSAGE);
            case "wa-noaction":
                // First post WITHOUT the Reply action (late-capability scenario).
                return msg("Ada", null, false, now,
                    new String[][]{{"Ada", "no reply action yet", "" + T0}},
                    false, Notification.CATEGORY_MESSAGE);
            case "wa-withaction":
                // Re-post of the same conversation WITH a Reply action present.
                return msg("Ada", null, false, now,
                    new String[][]{{"Ada", "no reply action yet", "" + T0},
                        {"Ada", "now the reply action appears", "" + (T0 + 1000)}},
                    true, Notification.CATEGORY_MESSAGE);
            case "wa-selfstatus":
                // SYNTHETIC self-status (backup-style housekeeping card).
                return selfStatus();
            case "dc-channel":
                // SYNTHETIC Discord-server-channel-shaped: convTitle, 2 senders.
                return msg("#general", "#general", true, now,
                    new String[][]{{"Ada", "squad update", "" + T0},
                        {"Bim", "ack", "" + (T0 + 1000)}},
                    true, Notification.CATEGORY_MESSAGE);
            case "dc-nohistory":
                // SYNTHETIC Discord announcement-shaped post: no MessagingStyle,
                // no actions.
                return plain("PingPoster-Announce", "server notice, no history",
                    Notification.CATEGORY_SOCIAL);
            default:
                // Minimal generic control notification.
                return plain("PingPoster", "control notification", Notification.CATEGORY_EVENT);
        }
    }

    /** MessagingStyle post: title/convTitle/group flag + entries + optional
     *  free-form RemoteInput "Reply" action (standard surface). No content
     *  string matters — ReplyMate counts structure; everything here is a
     *  synthetic placeholder. */
    private Notification msg(String title, String convTitle, boolean group, long now,
                             String[][] entries, boolean withReply, String category) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(entries[entries.length - 1][1])
            .setCategory(category)
            .setWhen(now);

        Notification.MessagingStyle style =
            new Notification.MessagingStyle("Device Owner");
        if (convTitle != null) style.setConversationTitle(convTitle);
        style.setGroupConversation(group);
        for (String[] e : entries) {
            style.addMessage(e[1], Long.parseLong(e[2]), e[0]);
        }
        b.setStyle(style);
        b.setShortcutId(title + "-" + (group ? "g" : "1"));   // conversation id surface
        if (withReply) {
            RemoteInput ri = new RemoteInput.Builder("key_pp_reply")
                .setLabel("Reply").build();
            // API 31+ enforcement (NotificationManagerService.
            // checkDisqualifyingFeatures): "PendingIntents attached to actions
            // with remote inputs must be mutable" — the system fills the reply
            // text into this Intent at runtime. IMMUTABLE here posts NOTHING
            // (IllegalArgumentException from NMS); proven by harness run
            // 41336986254 on API 34.
            PendingIntent pi = PendingIntent.getActivity(this, nid0(nidSeed()),
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(ri).build());
        }
        return b.build();
    }

    private Notification selfStatus() {
        // ongoing + progress-bar housekeeping shape, app-labelled, no messages
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PingPoster")
            .setContentText("Backing up messages …")
            .setProgress(100, 40, false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private Notification plain(String title, String text, String category) {
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(category)
            .build();
    }

    private int nidSeed() { return getIntent().getIntExtra("nid", 1001); }
    private static int nid0(int seed) { return seed; }
}
