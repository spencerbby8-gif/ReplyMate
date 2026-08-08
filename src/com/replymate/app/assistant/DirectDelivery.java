package com.replymate.app.assistant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.replymate.app.listener.RmNotificationListener;

/** P-intelligence-3: the SHARED mechanics of delivering approved text through the
 *  source app's own quick-reply contract. Extracted — byte-equivalent — from
 *  AssistantReceiver's send chain so the manual composer (ConversationActivity)
 *  delivers through the exact same proven path as the notification Approve button.
 *  Every method returns null on success or the honest human reason on failure;
 *  callers decide what to show and what to ledger. */
public final class DirectDelivery {

    /** Delivery outcome for {@link #deliver}. */
    public enum How { LIVE, CONVERSATION, CACHED, FAILED }

    /** Result of a full delivery attempt. how==FAILED ⇒ reason explains honestly. */
    public static final class Outcome {
        public final How how;
        public final String reason;      // null unless FAILED
        Outcome(How how, String reason) { this.how = how; this.reason = reason; }
    }

    private DirectDelivery() { }

    /** Resolve the captured action on a live notification (key-matched via
     *  ReplyActionResolver). */
    public static Notification.Action resolveAction(Notification n,
                                                    AssistantTargetStore.Target t) {
        if (n == null || t == null || t.actionIndex < 0) return null;
        return ReplyActionResolver.selectAnySurface(n, t.source, t.resultKey, t.actionIndex);
    }

    /** Fill the resolved action's RemoteInputs with {@code text} and fire the app's
     *  own PendingIntent. null on success, else the honest failure reason. */
    public static String fillAndFire(Context ctx, Notification.Action action, String text) {
        try {
            RemoteInput[] inputs = action == null ? null : action.getRemoteInputs();
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

    /** Fire the CACHED reply PendingIntent with the official RemoteInput results
     *  structure rebuilt from the stored key (dismissal-proof send). */
    public static String fireCached(Context ctx, AssistantTargetStore.Target t,
                                    String app, String text) {
        PendingIntent pi = AssistantTargetStore.readCachedPi(t);
        if (pi == null) {
            return "no cached reply target survived either";
        }
        try {
            RemoteInput[] inputs = ReplyActionResolver.rebuiltInputs(t.resultKey);
            Bundle results = new Bundle();
            results.putCharSequence(t.resultKey, text);
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            pi.send(ctx, 0, fillIn);
            return null;   // cached target fired — handed to the app unwatched
        } catch (PendingIntent.CanceledException gone) {
            return app + " closed that reply box (the cached target expired too)";
        } catch (RuntimeException e) {
            return "the cached reply target failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Full dismissal-safe chain (same order as the Approve button): live sbn →
     *  re-posted same-conversation sbn → cached PendingIntent. Used by the manual
     *  composer; the notification Approve flow keeps its own ledgered inline copy. */
    public static Outcome deliver(Context ctx, RmNotificationListener listener,
                                  AssistantTargetStore.Target t, String app, String text) {
        if (t == null || !t.usable()) {
            return new Outcome(How.FAILED, "this notification never offered a quick-reply box");
        }
        StatusBarNotification sbn = listener == null ? null : listener.findActive(t.sbnKey);
        if (sbn != null) {
            String why = fillAndFire(ctx, resolveAction(sbn.getNotification(), t), text);
            if (why == null) return new Outcome(How.LIVE, null);
            return new Outcome(How.FAILED, why);
        }
        StatusBarNotification reposted = listener == null
            ? null : AssistantTargetStore.findConversationMatch(
                t, listener.safeActiveNotifications());
        if (reposted != null) {
            String why = fillAndFire(ctx, resolveAction(reposted.getNotification(), t), text);
            if (why == null) return new Outcome(How.CONVERSATION, null);
            // fall through to the cached target below
        }
        String why = fireCached(ctx, t, app, text);
        return why == null ? new Outcome(How.CACHED, null) : new Outcome(How.FAILED, why);
    }
}
