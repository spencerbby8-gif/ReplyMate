package com.replymate.app.assistant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.RmNotificationListener;
import com.replymate.app.platform.Tasks;
import com.replymate.core.assistant.AssistantEvent;
import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.model.Contact;
import com.replymate.core.model.DraftStatus;

/** P-background-2: the three notification buttons. Approve is the ONLY send path —
 *  it delivers the draft through the source app's own quick-reply contract
 *  (RemoteInput results → the action's PendingIntent), re-resolved LIVE from the
 *  status bar at tap time. Every failure leaves a structured AssistantEvent record
 *  (conversation / provider / model / alert id / stage / reason / action / fix).
 *  If the reply target is gone (dismissed notification, dead listener, deleted
 *  conversation, OEM weirdness) we never pretend: the draft is copied instead and
 *  the alert says exactly what happened. */
public final class AssistantReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND = "com.replymate.app.assistant.SEND";
    public static final String ACTION_COPY = "com.replymate.app.assistant.COPY";
    public static final String ACTION_REGEN = "com.replymate.app.assistant.REGEN";

    public static final String EXTRA_CONTACT_ID = "contactId";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_APP_LABEL = "appLabel";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_DRAFT_ID = "draftId";
    public static final String EXTRA_DIRECT = "direct";

    static String actionFor(AssistantPlanner.Btn btn) {
        switch (btn) {
            case APPROVE_SEND: return ACTION_SEND;
            case COPY:         return ACTION_COPY;
            default:           return ACTION_REGEN;
        }
    }

    @Override public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final long contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1);
        final String name = intent.getStringExtra(EXTRA_NAME);
        final String appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        final String text = intent.getStringExtra(EXTRA_TEXT);
        final long draftId = intent.getLongExtra(EXTRA_DRAFT_ID, -1);
        final AppContainer c = ReplyMateApp.containerOf(ctx);
        if (c == null || contactId < 0) return;

        final PendingResult pr = goAsync();   // official brief-bg-window for receivers
        Tasks.bg(new Runnable() {
            @Override public void run() {
                try {
                    if (ACTION_REGEN.equals(action)) {
                        AssistantRunner.regenerateNow(c, contactId, name);
                    } else if (ACTION_COPY.equals(action)) {
                        copyAndSettle(ctx, c, contactId, name, appLabel, text, draftId, null);
                    } else if (ACTION_SEND.equals(action)) {
                        approveAndSend(ctx, c, contactId, name, appLabel, text, draftId);
                    }
                } catch (RuntimeException e) {
                    AssistantNotifier.settled(ctx, contactId,
                        "ReplyMate hit a snag", "Couldn't finish that action ("
                            + e.getClass().getSimpleName() + ") — the draft is waiting"
                            + " in the conversation.", true);
                } finally {
                    pr.finish();
                }
            }
        });
    }

    /* ------------------------------------------------------------------ approve */

    private void approveAndSend(Context ctx, AppContainer c, long contactId, String name,
                                String appLabel, String text, long draftId) {
        String app = empty(appLabel) ? "the app" : appLabel;
        String who = empty(name) ? "#" + contactId : name;
        String tag = AssistantPlanner.notifTag(contactId);
        String why = null;
        String sbnKey = "";

        // Conversation deleted since the alert was posted? Nothing to reply into.
        Contact contact = c.contacts().get(contactId);
        if (contact == null) {
            AssistantDiag.record(c, contactId, who, tag, "",
                AssistantEvent.Stage.APPROVE_RESOLVE,
                "the conversation was deleted after this draft",
                "dropped the send, nothing copied", "re-add the contact if it was a mistake");
            AssistantNotifier.settled(ctx, contactId, "Can't send",
                "That conversation was removed from ReplyMate — the draft is gone with it.", false);
            AssistantNotifier.cancel(ctx, contactId);
            return;
        }

        RmNotificationListener listener = RmNotificationListener.active();
        AssistantTargetStore.Target t = AssistantTargetStore.load(c.kv(), contactId);
        sbnKey = t.sbnKey;
        if (listener == null) {
            why = "ReplyMate's link to the notification shade was recycled";
        } else if (!t.usable()) {
            why = "this notification never offered a quick-reply box";
        } else {
            StatusBarNotification sbn = listener.findActive(t.sbnKey);
            if (sbn == null) {
                why = "the original " + app + " notification was dismissed";
            } else {
                why = tryRemoteSend(ctx, sbn, t, text);
            }
        }

        if (why == null) {
            if (draftId > 0) c.drafts().updateStatus(draftId, DraftStatus.SENT);
            AssistantDiag.record(c, contactId, who, tag, sbnKey,
                AssistantEvent.Stage.REMOTE_SEND, "—",
                "approved text delivered through " + app + "'s quick-reply", "");
            AssistantNotifier.settled(ctx, contactId, "Sent ✓",
                "Delivered through " + app + "'s quick-reply as you approved:\n" + text,
                false);
        } else {
            AssistantDiag.record(c, contactId, who, tag, sbnKey,
                AssistantEvent.Stage.REMOTE_SEND, why,
                "fell back to clipboard copy (nothing was sent)",
                "open " + app + " and paste it, or wait for a new message");
            copyAndSettle(ctx, c, contactId, name, appLabel, text, draftId, why);
        }
    }

    /** Fire the source app's reply action with the approved text as RemoteInput
     *  results. Returns null on success, otherwise the honest failure reason. */
    private String tryRemoteSend(Context ctx, StatusBarNotification sbn,
                                 AssistantTargetStore.Target t, String text) {
        try {
            Notification n = sbn.getNotification();
            if (n == null || n.actions == null
                    || t.actionIndex < 0 || t.actionIndex >= n.actions.length) {
                return "the reply action moved — the notification layout changed";
            }
            Notification.Action action = n.actions[t.actionIndex];
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0 || action.actionIntent == null) {
                return "the reply action is no longer a text reply";
            }
            Bundle results = new Bundle();
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getResultKey() != null) {
                    results.putCharSequence(ri.getResultKey(), text);
                }
            }
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            action.actionIntent.send(ctx, 0, fillIn);
            return null;   // delivered — the source app treats it as a typed reply
        } catch (PendingIntent.CanceledException canceled) {
            return "the reply box expired (the app closed that notification)";
        } catch (RuntimeException e) {
            return "the app rejected the reply (" + e.getClass().getSimpleName() + ")";
        }
    }

    /* ------------------------------------------------------------------ copy */

    private void copyAndSettle(Context ctx, AppContainer c, long contactId, String name,
                               String appLabel, String text, long draftId, String why) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ReplyMate reply", text));
        }
        if (draftId > 0) c.drafts().updateStatus(draftId, DraftStatus.COPIED);
        String app = empty(appLabel) ? "the app" : appLabel;
        String who = empty(name) ? "#" + contactId : name;
        String line = (why == null)
            ? "Copied ✓ — paste it in " + app + " when you're ready."
            : "Couldn't quick-send (" + why + "). Copied instead ✓ — paste it in " + app + ".";
        if (why == null) {
            AssistantDiag.record(c, contactId, who, AssistantPlanner.notifTag(contactId), "",
                AssistantEvent.Stage.COPY_FALLBACK,
                "this app exposes no quick-reply box (by evidence)",
                "draft copied for manual paste", "");
        }
        AssistantNotifier.settled(ctx, contactId, "ReplyMate", line + "\n\n" + text, true);
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
