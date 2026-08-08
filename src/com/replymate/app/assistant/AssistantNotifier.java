package com.replymate.app.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import com.replymate.app.R;
import com.replymate.app.ui.ConversationActivity;
import com.replymate.core.assistant.AssistantPlanner;

/** P-background: posts the ReplyMate assistant notification — one PER CONVERSATION
 *  (stable tag), so Regenerate / a fresher draft replaces it in place instead of
 *  stacking. Buttons follow the verified capability of the source app:
 *  DIRECT → Approve & send / Regenerate / Open · NONE → Copy / Regenerate / Open
 *  with a caption that explains exactly why.
 *
 *  P-background-8 (proper heads-up draft alerts, owner-verified on device):
 *   - NEW channel {@link #CHANNEL_ID} at IMPORTANCE_HIGH — the 1.4.6 channel was
 *     IMPORTANCE_DEFAULT, which makes heads-up IMPOSSIBLE, and channel importance
 *     is immutable after creation, so the old channel is deleted and replaced.
 *   - Message category + HIGH priority + public visibility (the pre-26 contract).
 *   - ReplyMate identity: dedicated monochrome small icon + the launcher icon as
 *     the large icon + the brand accent — it looks like OUR app, not a gray blob.
 *   - Fresh draft ⇒ alert (sound + pop). Burst updates / Regenerate refresh the
 *     SAME alert SILENTLY (onlyAlertOnce) — one audible pop per cycle, never a
 *     per-message rattlesnake. Swiping the alert away routes through
 *     AssistantReceiver.ACTION_DISMISS (learning signal + alert-flag reset). */
public final class AssistantNotifier {

    /** HIGH-importance channel id — deliberately NEW (importance can't be edited
     *  on an existing channel; the legacy DEFAULT channel is removed below). */
    public static final String CHANNEL_ID = "rm_assistant_heads_up";
    /** The 1.4.6-and-earlier channel (IMPORTANCE_DEFAULT — heads-up impossible). */
    public static final String LEGACY_CHANNEL_ID = "rm_assistant";
    public static final int NOTIF_ID = 7311;   // + contact-scoped tag = one per conversation

    private static final int BRAND = 0xFF0A84FF;   // ReplyMate accent

    private AssistantNotifier() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Reply assistant", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Draft replies pop over other apps so you can approve,"
                + " regenerate or open the chat right away — nothing ever sends by itself");
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
            // Retire the muted legacy channel (its stored importance would keep
            // silencing us; deleting is harmless when it never existed).
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        }
    }

    /** Fresh-first-alert overload (classic callers: always alert). */
    public static void post(Context ctx, long contactId, String name, String appLabel,
                            String draftText, long draftId, AssistantPlanner.Capability cap) {
        post(ctx, contactId, name, appLabel, draftText, draftId, cap, true);
    }

    public static void post(Context ctx, long contactId, String name, String appLabel,
                            String draftText, long draftId, AssistantPlanner.Capability cap,
                            boolean freshAlert) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (freshAlert) {
            // Force a TRUE re-alert even if a settled card still sits in the shade:
            // cancel + re-post = new enqueue = pop + sound on the HIGH channel.
            nm.cancel(AssistantPlanner.notifTag(contactId), NOTIF_ID);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        brand(ctx, b)
            .setContentTitle("Reply ready for " + name)
            .setContentText(draftText)
            .setStyle(new Notification.BigTextStyle()
                .bigText(draftText + "\n\n" + AssistantPlanner.caption(appLabel, cap)))
            .setContentIntent(openPi(ctx, contactId))
            // P-background-8: a swipe-away is a signal — rejected draft + re-arm the alert.
            .setDeleteIntent(dismissPi(ctx, contactId, draftId))
            .setOnlyAlertOnce(!freshAlert)
            .setAutoCancel(true)
            .setShowWhen(true);

        for (AssistantPlanner.Btn btn : AssistantPlanner.buttonsFor(cap)) {
            // P-background-5: preview vs send stays split: tap-body →
            // ConversationActivity; APPROVE/COPY/REGEN → receiver.
            // P-background-9: EDIT opens the in-app draft editor (3-action cap —
            // OPEN moved to the body tap, which needs no expansion).
            android.app.PendingIntent pi;
            if (btn == AssistantPlanner.Btn.EDIT) {
                pi = editPi(ctx, contactId, name, appLabel, draftText, draftId, cap);
            } else if (btn == AssistantPlanner.Btn.OPEN) {
                pi = openPi(ctx, contactId);
            } else {
                pi = actionPi(ctx, btn, contactId, name, appLabel, draftText, draftId, cap);
            }
            b.addAction(new Notification.Action.Builder(null, label(btn), pi).build());
        }
        nm.notify(AssistantPlanner.notifTag(contactId), NOTIF_ID, b.build());
    }

    /** Replace the alert with a final one-line state (sent / copied / fallback).
     *  Always SILENT (onlyAlertOnce) — a state line, not a new ask. */
    public static void settled(Context ctx, long contactId, String title, String line,
                               boolean keepOpenButton) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        brand(ctx, b)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(new Notification.BigTextStyle().bigText(line))
            .setContentIntent(openPi(ctx, contactId))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true);
        if (keepOpenButton) {
            b.addAction(new Notification.Action.Builder(null, "Open conversation",
                openPi(ctx, contactId)).build());
        }
        nm.notify(AssistantPlanner.notifTag(contactId), NOTIF_ID, b.build());
    }

    public static void cancel(Context ctx, long contactId) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(AssistantPlanner.notifTag(contactId), NOTIF_ID);
    }

    /** One identity for every assistant card: ReplyMate glyph + accent + msg class. */
    private static Notification.Builder brand(Context ctx, Notification.Builder b) {
        b.setSmallIcon(R.drawable.ic_stat_replymate)
         .setColor(BRAND)
         .setCategory(Notification.CATEGORY_MESSAGE)
         .setPriority(Notification.PRIORITY_HIGH)          // the pre-26 heads-up contract
         .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= 23) {
            b.setLargeIcon(Icon.createWithResource(
                // the large icon keeps our colored launcher mark beside the text
                ctx.getPackageName(), R.drawable.ic_launcher));
        }
        return b;
    }

    private static String label(AssistantPlanner.Btn btn) {
        switch (btn) {
            case APPROVE_SEND: return "Approve & send";
            case COPY:         return "Copy";
            case EDIT:         return "Edit";
            case REGENERATE:   return "Regenerate";
            default:           return "Open conversation";
        }
    }

    /** P-background-9: Edit opens the in-app editor with the full draft context. */
    private static PendingIntent editPi(Context ctx, long contactId, String name,
                                        String appLabel, String draftText, long draftId,
                                        AssistantPlanner.Capability cap) {
        Intent i = new Intent(ctx, com.replymate.app.ui.DraftEditActivity.class);
        i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
        i.putExtra(AssistantReceiver.EXTRA_NAME, name == null ? "" : name);
        i.putExtra(AssistantReceiver.EXTRA_APP_LABEL, appLabel == null ? "" : appLabel);
        i.putExtra(AssistantReceiver.EXTRA_TEXT, draftText == null ? "" : draftText);
        i.putExtra(AssistantReceiver.EXTRA_DRAFT_ID, draftId);
        i.putExtra(AssistantReceiver.EXTRA_DIRECT, cap == AssistantPlanner.Capability.DIRECT);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int rc = (int) (contactId & 0x7fffffff) * 10 + AssistantPlanner.Btn.EDIT.ordinal();
        return PendingIntent.getActivity(ctx, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static PendingIntent openPi(Context ctx, long contactId) {
        Intent open = new Intent(ctx, ConversationActivity.class);
        open.putExtra(ConversationActivity.EXTRA_CONTACT_ID, contactId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, (int) (contactId & 0x7fffffff) + 40000, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static PendingIntent dismissPi(Context ctx, long contactId, long draftId) {
        Intent i = new Intent(ctx, AssistantReceiver.class);
        i.setAction(AssistantReceiver.ACTION_DISMISS);
        i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
        i.putExtra(AssistantReceiver.EXTRA_DRAFT_ID, draftId);
        int rc = (int) (contactId & 0x7fffffff) * 10 + 9;
        return PendingIntent.getBroadcast(ctx, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private static PendingIntent actionPi(Context ctx, AssistantPlanner.Btn btn,
                                          long contactId, String name, String appLabel,
                                          String draftText, long draftId,
                                          AssistantPlanner.Capability cap) {
        Intent i = new Intent(ctx, AssistantReceiver.class);
        i.setAction(AssistantReceiver.actionFor(btn));
        i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
        i.putExtra(AssistantReceiver.EXTRA_NAME, name == null ? "" : name);
        i.putExtra(AssistantReceiver.EXTRA_APP_LABEL, appLabel == null ? "" : appLabel);
        i.putExtra(AssistantReceiver.EXTRA_TEXT, draftText == null ? "" : draftText);
        i.putExtra(AssistantReceiver.EXTRA_DRAFT_ID, draftId);
        i.putExtra(AssistantReceiver.EXTRA_DIRECT, cap == AssistantPlanner.Capability.DIRECT);
        int rc = (int) (contactId & 0x7fffffff) * 10 + btn.ordinal();
        return PendingIntent.getBroadcast(ctx, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }
}
