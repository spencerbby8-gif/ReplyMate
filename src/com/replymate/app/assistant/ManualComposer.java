package com.replymate.app.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.RmNotificationListener;
import com.replymate.core.listener.WatchedApps;
import com.replymate.core.model.Channel;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;

/** P-intelligence-3: the manual composer's WHAT-TO-DO logic with zero view deps, so
 *  the exact behavior is pinned by device-grade tests (Robo). The activity only
 *  renders the outcome. Rules (owner directive 4):
 *    - words are ALWAYS saved to the thread first (never lost);
 *    - manual-only contact ⇒ SAVED_DONE, no send claims;
 *    - a valid live/cached target ⇒ the text goes straight through the connected
 *      app's own quick-reply chain (same as Approve);
 *    - no usable target ⇒ words stay, text is copied, honest Copy + Open fallback —
 *      NEVER a fake "Sent". */
public final class ManualComposer {

    public enum Outcome { SAVED_ONLY, SENT_LIVE, SENT_CONVERSATION, SENT_CACHED, COPY_FALLBACK }

    public static final class Result {
        public final Outcome outcome;
        /** The connected app this contact came from (null → manual-only). */
        public final Channel origin;
        public final String appLabel;      // "" for manual-only contacts
        public final String reason;        // filled only for COPY_FALLBACK

        Result(Outcome outcome, Channel origin, String appLabel, String reason) {
            this.outcome = outcome;
            this.origin = origin;
            this.appLabel = appLabel == null ? "" : appLabel;
            this.reason = reason == null ? "" : reason;
        }
    }

    private ManualComposer() { }

    /** Save the owner-typed reply and deliver when a valid target exists.
     *  Never throws; never loses the words; never claims an undelivered send. */
    public static Result send(AppContainer c, long contactId, String text) {
        Message m = new Message();
        m.contactId = contactId;
        m.channel = Channel.MANUAL;
        m.direction = Direction.OUTGOING;
        m.body = text;
        m.sentAt = c.clock().now();
        m.source = Source.MANUAL;
        // P-intelligence-18 §3: the owner-typed row carries the conversation's
        // KNOWN identity (copied, never fabricated) so context/memory/thread
        // treat it exactly like a real sent message of THIS conversation.
        String[] ident =
            com.replymate.core.usecase.ManualEntryService
                .latestConversationIdentity(c.messages(), contactId);
        m.convId = ident[0];
        m.convTitle = ident[1];
        c.messages().insert(m);
        // P-intelligence-18 §3: learning parity — a manual send typed into
        // ReplyMate teaches a live draft exactly like one typed in the chat app
        // (same ManualSendLearner, same APPROVED/EDITED signal, same per-draft
        // dedupe marker — the listener path can never double-learn it).
        try {
            com.replymate.core.learning.ManualSendLearner.evaluate(
                c.contacts().get(contactId), c.messages(), c.drafts(),
                c.learningService(), c.kv(), c.clock());
        } catch (RuntimeException ignored) {
            // a learner hiccup must never hurt the send flow
        }

        Channel origin = null;
        for (ContactChannel ch : c.contacts().channelsByContact(contactId)) {
            if (ch.channel != null && ch.channel != Channel.MANUAL) origin = ch.channel;
        }
        if (origin == null) {
            return new Result(Outcome.SAVED_ONLY, null, "", "");
        }
        String app = WatchedApps.labelFor(origin);
        AssistantTargetStore.Target t = AssistantTargetStore.load(c.kv(), contactId);
        RmNotificationListener listener = RmNotificationListener.active();
        // P-intelligence-17: the answered message's own conversation identity
        // feeds the DeliveryGuard — a foreign target refuses instead of borrowing.
        Message latest = null;
        for (Message x : c.messages().lastMessages(contactId, 5)) {
            if (x.direction == Direction.INCOMING) latest = x;
        }
        DirectDelivery.Outcome out =
            DirectDelivery.deliver(c.app(), listener, t, app, text,
                WatchedApps.primaryPackageFor(origin),
                latest == null ? "" : latest.convId,
                latest == null ? "" : latest.convTitle);
        switch (out.how) {
            case LIVE:
                return new Result(Outcome.SENT_LIVE, origin, app, "");
            case CONVERSATION:
                return new Result(Outcome.SENT_CONVERSATION, origin, app, "");
            case CACHED:
                return new Result(Outcome.SENT_CACHED, origin, app, "");
            default:
                // honest fallback: words kept above; text copied for the paste run
                ClipboardManager cm = (ClipboardManager)
                    c.app().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("replymate reply", text));
                }
                return new Result(Outcome.COPY_FALLBACK, origin, app,
                    out.reason == null ? "no usable quick-reply target" : out.reason);
        }
    }
}
