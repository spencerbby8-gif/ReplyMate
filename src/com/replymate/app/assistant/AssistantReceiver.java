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
import com.replymate.core.assistant.AssistantLearning;
import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;

/** P-background-2: the notification buttons. Approve is the ONLY send path — it
 *  delivers the draft through the source app's own quick-reply contract
 *  (RemoteInput results → the action's PendingIntent), re-resolved from the
 *  status bar at tap time. Every failure leaves a structured AssistantEvent record
 *  (conversation / provider / model / alert id / stage / reason / action / fix).
 *
 *  P-background-8 approve order (owner's blocking audit, dismissal-hardened):
 *    1. the ORIGINAL notification still live (exact sbn key) → send there;
 *    2. it was dismissed, but the SAME conversation is live again under a NEW key
 *       (strict conversationId/title identity match) → resolve the reply action on
 *       the FRESH notification, send, and adopt its geometry;
 *    3. else the CACHED reply PendingIntent (system token) → fire it; the settled
 *       card says plainly that delivery couldn't be watched (no fake certainty);
 *    4. else honest copy fallback — draft preserved, never a fake "Sent ✓".
 *  P-background-8 learning: approve/copy/regenerate/dismiss all record the SAME
 *  signal kinds as the manual screen (AssistantLearning), per-contact gated. */
public final class AssistantReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND = "com.replymate.app.assistant.SEND";
    public static final String ACTION_COPY = "com.replymate.app.assistant.COPY";
    public static final String ACTION_REGEN = "com.replymate.app.assistant.REGEN";
    public static final String ACTION_DISMISS = "com.replymate.app.assistant.DISMISS";
    /** P-intelligence-3: inline draft editing — the Edit action carries a RemoteInput;
     *  the typed correction arrives as results under AssistantPlanner.EDIT_INPUT_KEY. */
    public static final String ACTION_EDIT = "com.replymate.app.assistant.EDIT";

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
                        // P-background-8 learning: the Regenerate button = "another take".
                        Contact contact = c.contacts().get(contactId);
                        AssistantLearning.onRegenerate(c.learningService(), contact);
                        AssistantRunner.regenerateNow(c, contactId, name);
                    } else if (ACTION_COPY.equals(action)) {
                        copyAndSettle(ctx, c, contactId, name, appLabel, text, draftId, null);
                    } else if (ACTION_SEND.equals(action)) {
                        approveAndSend(ctx, c, contactId, name, appLabel, text, draftId);
                    } else if (ACTION_EDIT.equals(action)) {
                        applyInlineEdit(ctx, c, contactId, name, appLabel, text, draftId,
                            intent);
                    } else if (ACTION_DISMISS.equals(action)) {
                        noteDismissed(c, contactId, draftId);
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

    /* ---------------------------------------------------------------- inline edit */

    /** P-intelligence-3: the user typed a correction directly on the draft alert.
     *  It NEVER approves by itself — the edited text becomes the pending draft and
     *  the SAME alert is re-posted (silently) with Approve & send / Regenerate
     *  intact, so the human still decides the send. Learning records exactly what
     *  the manual screen records for an edit (EDITED + classifyEdit tokens).
     *  Fallback: an action tap WITHOUT inline results (device/layout can't present
     *  inline input) opens the editor screen — the only other safe path. */
    private void applyInlineEdit(Context ctx, AppContainer c, long contactId, String name,
                                 String appLabel, String text, long draftId, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence cs = results == null
            ? null : results.getCharSequence(AssistantPlanner.EDIT_INPUT_KEY);
        String edited = cs == null ? "" : cs.toString().trim();
        String who = empty(name) ? "#" + contactId : name;
        String tag = AssistantPlanner.notifTag(contactId);

        if (edited.isEmpty()) {
            // No inline text arrived → the only safe fallback: the editor screen,
            // with the full draft context (pre-inline-edit behavior preserved).
            AssistantDiag.record(c, contactId, who, tag, "",
                AssistantEvent.Stage.NOTIFY,
                "edit tapped but no inline text arrived (inline input unsupported here)",
                "opening the in-app editor instead", "");
            Intent open = new Intent(ctx, com.replymate.app.ui.DraftEditActivity.class);
            open.putExtra(EXTRA_CONTACT_ID, contactId);
            open.putExtra(EXTRA_NAME, name == null ? "" : name);
            open.putExtra(EXTRA_APP_LABEL, appLabel == null ? "" : appLabel);
            open.putExtra(EXTRA_TEXT, text == null ? "" : text);
            open.putExtra(EXTRA_DRAFT_ID, draftId);
            open.putExtra(EXTRA_DIRECT,
                intent != null && intent.getBooleanExtra(EXTRA_DIRECT, false));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(open);
            return;
        }

        String oldText = text == null ? "" : text;
        if (draftId > 0 && !edited.equals(oldText.trim())) {
            c.drafts().updateText(draftId, edited);
            c.drafts().updateStatus(draftId, DraftStatus.EDITED);
            Contact contact = c.contacts().get(contactId);
            // exact manual-screen parity: editing carries the richest style signal
            c.learningService().record(contact,
                com.replymate.core.model.StyleSignal.Kind.EDITED,
                com.replymate.core.learning.LearningEngine.classifyEdit(oldText, edited),
                Long.valueOf(draftId));
        }
        AssistantDiag.record(c, contactId, who, tag, "",
            AssistantEvent.Stage.NOTIFY,
            edited.equals(oldText.trim())
                ? "inline edit submitted the draft's own words (no change)"
                : "draft corrected inline on the alert",
            "edited draft re-posted — still waiting for a human Approve;"
                + " nothing sends by itself", "");

        // The action tap auto-canceled the alert — re-post it SILENTLY with the
        // edited text as the pending draft (same cycle: no new heads-up pop).
        boolean direct = intent != null && intent.getBooleanExtra(EXTRA_DIRECT, false);
        // P-background-11: the re-posted card keeps the full "replying to <exact
        // message> · time" context — the edit flow must not drop it.
        String inText = null; long inTs = 0L;
        java.util.List<Message> lastOne = c.messages().lastMessages(contactId, 5);
        for (Message m : lastOne) {
            if (m.direction == Direction.INCOMING && (inText == null || m.sentAt >= inTs)) {
                inText = m.body; inTs = m.sentAt;
            }
        }
        AssistantNotifier.post(ctx, contactId,
            empty(name) ? who : name, appLabel, edited, draftId,
            direct ? AssistantPlanner.Capability.DIRECT : AssistantPlanner.Capability.NONE,
            false, inText, inTs, System.currentTimeMillis());
    }

    /* ----------------------------------------------------------------- dismiss */

    /** ANY dismissal of the draft card (swipe OR the auto-cancel that follows an
     *  action/body tap) re-arms the heads-up flag so the NEXT genuinely new burst
     *  pops again. Deliberately NOT a learning signal: auto-cancel fires this same
     *  delete-intent on Approve/Copy/Open taps, so a swipe can never be told apart
     *  from an action tap — recording a "rejection" here would corrupt learning
     *  with false positives. True rejections come from the conversation screen's
     *  Delete button, which has unambiguous provenance. No UI. */
    private void noteDismissed(AppContainer c, long contactId, long draftId) {
        c.kv().put(AssistantPlanner.alertedKvKey(contactId), "0");
        AssistantDiag.record(c, contactId, "#" + contactId,
            AssistantPlanner.notifTag(contactId), "",
            AssistantEvent.Stage.NOTIFY,
            "the draft alert left the shade (dismissed or replaced by an action)",
            "heads-up re-armed for the next genuinely new burst"
                + " (not a rejection signal — a swipe and a button tap look identical here)",
            "");
    }

    /* ------------------------------------------------------------------ approve */

    /** Send flavor — decides how decisive the settled card is allowed to sound. */
    private static final int HOW_LIVE = 0;         // original notification, observed after
    private static final int HOW_CONVERSATION = 1; // re-posted same conversation, live
    private static final int HOW_CACHED = 3;       // cached PendingIntent, unwatchable

    private void approveAndSend(Context ctx, AppContainer c, long contactId, String name,
                                String appLabel, String text, long draftId) {
        String app = empty(appLabel) ? "the app" : appLabel;
        String who = empty(name) ? "#" + contactId : name;
        String tag = AssistantPlanner.notifTag(contactId);
        String why = null;
        String sbnKey = "";
        int how = HOW_LIVE;

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
        if (!t.usable()) {
            why = "this notification never offered a quick-reply box";
        } else {
            StatusBarNotification sbn = listener == null ? null : listener.findActive(t.sbnKey);
            if (sbn != null) {
                why = tryRemoteSend(ctx, c, contactId, who, tag, sbn, t, text);
            } else {
                // The original sbn key is gone (dismissed/churned). TWO more honest
                // attempts before any fallback (P-background-8 ordering):
                String liveMissReason = listener == null
                    ? "ReplyMate's link to the notification shade was recycled"
                    : "the original " + app + " notification was dismissed";

                // (2) SAME conversation, re-posted under a NEW key → fresh send target.
                StatusBarNotification reposted = listener == null
                    ? null : AssistantTargetStore.findConversationMatch(
                        t, listener.safeActiveNotifications());
                if (reposted != null) {
                    String whyReposted = tryRemoteSend(ctx, c, contactId, who, tag,
                        reposted, t, text);
                    if (whyReposted == null) {
                        how = HOW_CONVERSATION;
                        // Adopt the fresher geometry + cache its PI for next time.
                        adoptLiveTarget(c, contactId, reposted);
                        sbnKey = reposted.getKey();
                    } else {
                        // the re-post didn't pan out — the cached target is next
                        AssistantDiag.record(c, contactId, who, tag, t.sbnKey,
                            AssistantEvent.Stage.APPROVE_RESOLVE,
                            "a re-posted same-conversation notification was found but unusable: "
                                + whyReposted,
                            "moving on to the cached reply target", "");
                    }
                }
                if (how != HOW_CONVERSATION) {
                    // (3) CACHED reply PendingIntent — fired with the official results
                    //     wire; success is ledgered as UNWATCHED (no fake certainty).
                    why = tryCachedSend(ctx, c, contactId, who, tag, app, t, text,
                        liveMissReason);
                    if (why == null) how = HOW_CACHED;
                }
            }
        }

        // This draft cycle is over either way — the NEXT fresh burst may pop again.
        c.kv().put(AssistantPlanner.alertedKvKey(contactId), "0");

        if (why == null) {
            if (draftId > 0) c.drafts().updateStatus(draftId, DraftStatus.SENT);
            AssistantLearning.onQuickSent(c.learningService(), contact,
                draftId > 0 ? Long.valueOf(draftId) : null);
            String handoff = how == HOW_CACHED
                ? "fired through " + app + "'s cached quick-reply target (unwatched)"
                : how == HOW_CONVERSATION
                    ? "delivered through " + app + "'s quick-reply on the re-posted notification"
                    : "approved text delivered through " + app + "'s quick-reply";
            AssistantDiag.record(c, contactId, who, tag, sbnKey,
                AssistantEvent.Stage.REMOTE_SEND, "—", handoff, "");
            if (how == HOW_CACHED) {
                AssistantNotifier.settled(ctx, contactId, "Sent ✓ via cached target",
                    "ReplyMate fired " + app + "'s saved reply action with your text —"
                        + " the original alert was already gone, so ReplyMate couldn't"
                        + " watch it land. Check the chat if you want to be sure:\n" + text,
                    true);
            } else {
                AssistantNotifier.settled(ctx, contactId, "Sent ✓",
                    "Delivered through " + app + "'s quick-reply as you approved:\n" + text,
                    false);
            }
        } else {
            AssistantDiag.record(c, contactId, who, tag, sbnKey,
                AssistantEvent.Stage.REMOTE_SEND, why,
                "fell back to clipboard copy (nothing was sent)",
                "open " + app + " and paste it, or wait for a new message");
            copyAndSettle(ctx, c, contactId, name, appLabel, text, draftId, why);
        }
    }

    /** Adopt a re-posted notification as the new live target (geometry + cache). */
    private void adoptLiveTarget(AppContainer c, long contactId, StatusBarNotification sbn) {
        try {
            com.replymate.core.listener.RawNotif raw =
                com.replymate.app.listener.NotifExtractor.toRaw(sbn);
            if (raw == null) return;
            if (AssistantPlanner.directAction(raw.actions) == null) return;
            AssistantTargetStore.save(c.kv(), contactId, raw, System.currentTimeMillis());
            AssistantTargetStore.cachePendingIntent(c.kv(), contactId, sbn);
        } catch (RuntimeException ignored) {
            // adoption is opportunistic — the send itself already succeeded
        }
    }

    /** Fire the source app's reply action with the approved text as RemoteInput
     *  results. Resolves the action in the SAME documented list it was captured
     *  from — matched by the EXACT stored result key (layout drift only re-orders
     *  actions; the key is stable), with either surface accepted if the app moved
     *  it. Every stage is ledgered (owner's P-background-5 audit list). Returns null
     *  on success, otherwise the honest failure reason. */
    private String tryRemoteSend(Context ctx, AppContainer c, long contactId, String who,
                                 String tag, StatusBarNotification sbn,
                                 AssistantTargetStore.Target t, String text) {
        try {
            Notification n = sbn.getNotification();
            Notification.Action action = resolveAction(n, t);
            if (action == null) {
                return "the reply action moved — the notification layout changed";
            }
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0 || action.actionIntent == null) {
                return "the reply action is no longer a text reply";
            }
            Bundle results = new Bundle();
            StringBuilder keys = new StringBuilder();
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getResultKey() != null) {
                    results.putCharSequence(ri.getResultKey(), text);
                    if (keys.length() > 0) keys.append(',');
                    keys.append(ri.getResultKey());
                }
            }
            boolean sameKey = sbn.getKey() != null && sbn.getKey().equals(t.sbnKey);
            AssistantDiag.record(c, contactId, who, tag, t.sbnKey,
                AssistantEvent.Stage.APPROVE_RESOLVE,
                "resolved '" + String.valueOf(action.title) + "' on "
                    + (sameKey ? "the original notification"
                               : "a RE-POSTED copy of this conversation")
                    + " key-match=" + t.resultKey,
                "filling RemoteInput results [" + keys + "] and firing the app's own PendingIntent",
                "");
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            action.actionIntent.send(ctx, 0, fillIn);
            notePostSendObservation(c, contactId, who, tag, sbn.getKey());
            return null;   // delivered — the source app treats it as a typed reply
        } catch (PendingIntent.CanceledException canceled) {
            return "the reply box expired (the app closed that notification)";
        } catch (RuntimeException e) {
            return "the app rejected the reply (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Best-effort corroboration that the source app consumed the reply: shortly
     *  after the send, re-check whether the original notification closed itself.
     *  Recorded as an OBSERVATION, never as proof. */
    private void notePostSendObservation(final AppContainer c, final long contactId,
                                         final String who, final String tag,
                                         final String sbnKey) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                Tasks.bg(new Runnable() {
                    @Override public void run() {
                        RmNotificationListener l = RmNotificationListener.active();
                        String note;
                        String meaning;
                        if (l == null) {
                            note = "post-send check: shade link gone, can't re-check";
                            meaning = "check the chat to confirm it landed";
                        } else if (l.findActive(sbnKey) == null) {
                            note = "post-send check: source notification closed itself";
                            meaning = "strong sign the app accepted the reply";
                        } else {
                            note = "post-send check: source notification still visible";
                            meaning = "some apps keep it; check the chat to confirm";
                        }
                        AssistantDiag.record(c, contactId, who, tag, sbnKey,
                            AssistantEvent.Stage.REMOTE_SEND, note, meaning, "");
                    }
                });
            }
        }, 1500);
    }

    /** P-background-7: dismissal-proof send — the original notification is gone,
     *  so fire the CACHED reply PendingIntent with the official RemoteInput results
     *  structure rebuilt from the stored key. If the app canceled/cleared that
     *  target (or none was cached), return the honest reason — the caller keeps the
     *  draft and offers Copy/Open. Never claims a send that didn't fire. */
    private String tryCachedSend(Context ctx, AppContainer c, long contactId, String who,
                                 String tag, String app, AssistantTargetStore.Target t,
                                 String text, String liveMissReason) {
        AssistantTargetStore.CachedPi cached = AssistantTargetStore.readCachedPi(t);
        if (cached == null || cached.pi == null) {
            return liveMissReason + " — no cached reply target survived either";
        }
        try {
            RemoteInput[] inputs = ReplyActionResolver.rebuiltInputs(t.resultKey);
            Bundle results = new Bundle();
            results.putCharSequence(t.resultKey, text);
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            String flavor = cached.inMemory
                ? "the kept live reply token (survives dismissal while ReplyMate runs)"
                : AssistantTargetStore.restartedSinceCache(t)
                    ? "the stored reply target — captured BEFORE a ReplyMate restart,"
                        + " so it may already be stale"
                    : "the stored reply target (this ReplyMate restart kept no live token)";
            AssistantDiag.record(c, contactId, who, tag, t.sbnKey,
                AssistantEvent.Stage.APPROVE_RESOLVE, liveMissReason,
                "sending via " + app + "'s cached reply PendingIntent through " + flavor
                    + " (result key " + t.resultKey
                    + ") — delivery can't be watched after dismissal", "");
            cached.pi.send(ctx, 0, fillIn);
            return null;   // cached target fired — handed to the app unwatched
        } catch (PendingIntent.CanceledException gone) {
            return app + (cached.inMemory
                ? " closed that reply box itself (the saved target was canceled —"
                    + " only the app can do that, never a plain dismissal)"
                : " closed that reply box (the cached target expired too)");
        } catch (RuntimeException e) {
            return "the cached reply target failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Resolve the captured action on a live notification — shared key-matched logic
     *  (ReplyActionResolver); kept as a thin named seam for existing regression tests. */
    private Notification.Action resolveAction(Notification n,
                                              AssistantTargetStore.Target t) {
        if (t == null || t.actionIndex < 0) return null;
        return ReplyActionResolver.selectAnySurface(n, t.source, t.resultKey, t.actionIndex);
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
        c.kv().put(AssistantPlanner.alertedKvKey(contactId), "0");
        if (why == null) {
            // a straight Copy tap = as-is approval (manual-screen parity)
            Contact contact = c.contacts().get(contactId);
            AssistantLearning.onCopied(c.learningService(), contact,
                draftId > 0 ? Long.valueOf(draftId) : null);
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
