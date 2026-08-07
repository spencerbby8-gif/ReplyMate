package com.replymate.app.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.replymate.app.R;
import com.replymate.app.ui.ConversationActivity;
import com.replymate.core.assistant.AssistantPlanner;

/** P-background: posts the ReplyMate assistant notification — one PER CONVERSATION
 *  (stable tag), so Regenerate / a fresher draft replaces it in place instead of
 *  stacking. Buttons follow the verified capability of the source app:
 *  DIRECT → Approve & send / Regenerate / Open · NONE → Copy / Regenerate / Open
 *  with a caption that explains exactly why. */
public final class AssistantNotifier {

    public static final String CHANNEL_ID = "rm_assistant";
    public static final int NOTIF_ID = 7311;   // + contact-scoped tag = one per conversation

    private AssistantNotifier() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Reply assistant", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Draft replies generated in the background — you approve every send");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void post(Context ctx, long contactId, String name, String appLabel,
                            String draftText, long draftId, AssistantPlanner.Capability cap) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Reply ready for " + name)
            .setContentText(draftText)
            .setStyle(new Notification.BigTextStyle()
                .bigText(draftText + "\n\n" + AssistantPlanner.caption(appLabel, cap)))
            .setContentIntent(openPi(ctx, contactId))
            .setAutoCancel(true)
            .setShowWhen(true);

        for (AssistantPlanner.Btn btn : AssistantPlanner.buttonsFor(cap)) {
            b.addAction(new Notification.Action.Builder(null, label(btn),
                actionPi(ctx, btn, contactId, name, appLabel, draftText, draftId, cap)).build());
        }
        nm.notify(AssistantPlanner.notifTag(contactId), NOTIF_ID, b.build());
    }

    /** Replace the alert with a final one-line state (sent / copied / fallback). */
    public static void settled(Context ctx, long contactId, String title, String line,
                               boolean keepOpenButton) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(new Notification.BigTextStyle().bigText(line))
            .setContentIntent(openPi(ctx, contactId))
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

    private static String label(AssistantPlanner.Btn btn) {
        switch (btn) {
            case APPROVE_SEND: return "Approve & send";
            case COPY:         return "Copy";
            case REGENERATE:   return "Regenerate";
            default:           return "Open conversation";
        }
    }

    private static PendingIntent openPi(Context ctx, long contactId) {
        Intent open = new Intent(ctx, ConversationActivity.class);
        open.putExtra(ConversationActivity.EXTRA_CONTACT_ID, contactId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, (int) (contactId & 0x7fffffff) + 40000, open,
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
