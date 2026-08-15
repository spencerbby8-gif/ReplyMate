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

    /** P-background-11: bounded shelf-life for a WAITING draft alert. Past this,
     *  replying is usually no longer relevant — the card retires itself instead
     *  of stacking forever in the shade. 12h covers the realistic "answer in the
     *  morning" case; the draft row itself stays in the app either way. */
    public static final long WAITING_TTL_MS = 12L * 3600_000L;
    /** A settled state line ("Sent ✓ / Copied") confirms, then clears itself. */
    public static final long SETTLED_TTL_MS = 120_000L;

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
        post(ctx, contactId, name, appLabel, draftText, draftId, cap, freshAlert,
            null, 0L, 0L);
    }

    /** P-background-11 full-context flavor: the alert must answer "what is the AI
     *  replying TO?" at a glance — the exact incoming message (with its time)
     *  sits above the generated draft (with its own time) in the expanded body,
     *  and the shade's when-stamp is the INCOMING message time, not the re-post
     *  time. The card expires itself after {@link #WAITING_TTL_MS} unactioned. */
    public static void post(Context ctx, long contactId, String name, String appLabel,
                            String draftText, long draftId, AssistantPlanner.Capability cap,
                            boolean freshAlert,
                            String incomingText, long incomingTs, long generatedAt) {
        post(ctx, contactId, name, appLabel, draftText, draftId, cap, freshAlert,
            incomingText, incomingTs, generatedAt, "Draft reply for ", "Replying to ");
    }

    /** P-intelligence-14: an AUTO FOLLOW-UP draft alert must never masquerade as a
     *  reply — its title and context label name exactly what it is. */
    public static void postFollowUp(Context ctx, long contactId, String name,
            String appLabel, String draftText, long draftId,
            AssistantPlanner.Capability cap, boolean freshAlert,
            String incomingText, long incomingTs, long generatedAt) {
        post(ctx, contactId, name, appLabel, draftText, draftId, cap, freshAlert,
            incomingText, incomingTs, generatedAt,
            "Follow-up idea for ", "Their latest message (you answered it) — from ");
    }

    /** P-intelligence-16b: caller-chosen title/context labels — group alerts carry
     *  the engagement verdict's salience ("Reply to Amara in …", "You could chime
     *  in — …") so an optional draft never masquerades as a directed reply. */
    public static void postWithLabels(Context ctx, long contactId, String name,
            String appLabel, String draftText, long draftId,
            AssistantPlanner.Capability cap, boolean freshAlert,
            String incomingText, long incomingTs, long generatedAt,
            String titleLabel, String contextLabel) {
        post(ctx, contactId, name, appLabel, draftText, draftId, cap, freshAlert,
            incomingText, incomingTs, generatedAt, titleLabel, contextLabel);
    }

    private static void post(Context ctx, long contactId, String name, String appLabel,
                            String draftText, long draftId, AssistantPlanner.Capability cap,
                            boolean freshAlert,
                            String incomingText, long incomingTs, long generatedAt,
                            String titleLabel, String contextLabel) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (freshAlert) {
            // Force a TRUE re-alert even if a settled card still sits in the shade:
            // cancel + re-post = new enqueue = pop + sound on the HIGH channel.
            nm.cancel(AssistantPlanner.notifTag(contactId), NOTIF_ID);
        }

        // P-background-12 hierarchy: THE DRAFT IS THE PRIMARY CONTENT — it leads
        // both the collapsed and expanded forms. The exact source message becomes
        // a clearly-labeled CONTEXT BLOCK below it (never interleaved, so the eye
        // never confuses who wrote what), with both times explicit.
        StringBuilder body = new StringBuilder();
        body.append(draftText);                                   // ① the AI reply
        if (generatedAt > 0) {
            body.append("\n— drafted ")
                .append(com.replymate.core.util.TimeFmt.dayTime(generatedAt)).append(" —");
        }
        if (incomingText != null && !incomingText.trim().isEmpty()) {
            body.append("\n\n· · · · · · · · · · · · · · ·\n")  // hard divider
                .append(contextLabel).append(name);             // ② its source
            if (incomingTs > 0) {
                body.append(" · ").append(com.replymate.core.util.TimeFmt.dayTime(incomingTs));
            }
            body.append(":\n“").append(incomingText.trim()).append("”");
        }
        body.append("\n\n").append(AssistantPlanner.caption(appLabel, cap));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_ID)
            : new Notification.Builder(ctx);
        brand(ctx, b)
            .setContentTitle(titleLabel + name
                + (appLabel == null || appLabel.isEmpty() ? "" : " · " + appLabel))
            .setContentText(draftText)
            .setStyle(new Notification.BigTextStyle()
                .bigText(body.toString())
                .setSummaryText(name))
            .setContentIntent(openPi(ctx, contactId))
            // P-background-8: a swipe-away is a signal — rejected draft + re-arm the alert.
            .setDeleteIntent(dismissPi(ctx, contactId, draftId))
            .setOnlyAlertOnce(!freshAlert)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(incomingTs > 0 ? incomingTs : System.currentTimeMillis());
        // setTimeoutAfter is API 26+; on 24–25 a waiting card simply stays until
        // replaced/dismissed (the per-conversation tag still prevents stacking).
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(WAITING_TTL_MS);

        for (AssistantPlanner.Btn btn : AssistantPlanner.buttonsFor(cap)) {
            // P-background-5: preview vs send stays split: tap-body →
            // ConversationActivity; APPROVE/COPY/REGEN → receiver.
            // P-intelligence-3: EDIT is INLINE — the action carries a RemoteInput so
            // the correction is typed right on the alert (no separate screen) and
            // lands in AssistantReceiver.ACTION_EDIT. Where the framework can't show
            // inline input it fires the SAME broadcast without results, and the
            // receiver falls back to the editor screen (the only safe fallback).
            android.app.PendingIntent pi;
            if (btn == AssistantPlanner.Btn.EDIT) {
                pi = editPi(ctx, contactId, name, appLabel, draftText, draftId, cap);
                Notification.Action.Builder ab =
                    new Notification.Action.Builder(null, label(btn), pi)
                        .addRemoteInput(new android.app.RemoteInput.Builder(
                            AssistantPlanner.EDIT_INPUT_KEY)
                            .setLabel("Edit the reply, then approve below").build());
                b.addAction(ab.build());
            } else {
                pi = btn == AssistantPlanner.Btn.OPEN
                    ? openPi(ctx, contactId)
                    : actionPi(ctx, btn, contactId, name, appLabel, draftText, draftId, cap);
                b.addAction(new Notification.Action.Builder(null, label(btn), pi).build());
            }
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
            .setAutoCancel(true)
            // P-background-11: the state line confirms (with its time), then
            // retires itself — old "Sent ✓ / Copied" cards never accrete.
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(SETTLED_TTL_MS);
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

    /** P-intelligence-3: the Edit action's PendingIntent is a BROADCAST to
     *  AssistantReceiver.ACTION_EDIT carrying the full draft context. It MUST be
     *  MUTABLE — the framework mutates the intent at tap time to attach the inline
     *  RemoteInput results (immutable pending intents silently drop them). Without
     *  results arriving (devices/layouts that can't present inline input) the
     *  receiver opens the editor screen as the documented fallback. */
    private static PendingIntent editPi(Context ctx, long contactId, String name,
                                        String appLabel, String draftText, long draftId,
                                        AssistantPlanner.Capability cap) {
        Intent i = new Intent(ctx, AssistantReceiver.class);
        i.setAction(AssistantReceiver.ACTION_EDIT);
        i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
        i.putExtra(AssistantReceiver.EXTRA_NAME, name == null ? "" : name);
        i.putExtra(AssistantReceiver.EXTRA_APP_LABEL, appLabel == null ? "" : appLabel);
        i.putExtra(AssistantReceiver.EXTRA_TEXT, draftText == null ? "" : draftText);
        i.putExtra(AssistantReceiver.EXTRA_DRAFT_ID, draftId);
        i.putExtra(AssistantReceiver.EXTRA_DIRECT, cap == AssistantPlanner.Capability.DIRECT);
        int rc = (int) (contactId & 0x7fffffff) * 10 + AssistantPlanner.Btn.EDIT.ordinal();
        return PendingIntent.getBroadcast(ctx, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_MUTABLE : 0));
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
