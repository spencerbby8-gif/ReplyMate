package com.replymate.core.learning;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.ports.DraftStore;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.prompt.PromptBuilder;
import com.replymate.core.util.Clock;
import java.util.List;

/** P-intelligence-1 (learn from manually sent replies): when the owner answers a chat
 *  THEMSELVES inside the messaging app, that outgoing text shows up in later
 *  MessagingStyle history and lands in our store as OUTGOING. If an untouched,
 *  still-fresh generated draft predates it, the owner demonstrably chose their own
 *  words over the draft — that is the strongest "edited/corrected" signal available:
 *
 *    identical words  → APPROVED ("sent-manually-same-words")
 *    different words  → EDITED  ("manual:<classifyEdit tokens>")
 *
 *  Honesty rules (all unit-pinned):
 *    - only drafts still in GENERATED status qualify (a draft the owner already
 *      copied/edited/liked via ReplyMate recorded its signal the normal way);
 *    - the draft must PREDATE the manual send and be fresh (≤ 72h) — ancient drafts
 *      never absorb unrelated later messages;
 *    - one draft learns at most once (kv marker per draft), and only the NEWEST
 *      qualifying draft learns per outgoing message;
 *    - placeholder/media outgoing rows are ignored (no words to compare);
 *    - the recording still passes through LearningService.record, so the standard
 *      gate (private / memory off / learning off / paused) is honored — the
 *      dedupe marker is set regardless so a silent draft is never re-evaluated. */
public final class ManualSendLearner {

    /** A draft older than this relative to the manual send is considered unrelated. */
    public static final long MAX_AGE_MS = 72L * 60L * 60L * 1000L;

    /** What happened — so the caller can log honestly. */
    public enum Outcome {
        LEARNED_APPROVED,   // same words sent by hand
        LEARNED_EDITED,     // owner's own words differed — correction learned
        NO_LIVE_DRAFT,      // nothing fresh+untouched predated this send
        PLACEHOLDER,        // outgoing carried no readable words
        ALREADY_LEARNED     // only candidate draft already consumed this channel
    }

    public static final class Result {
        public final Outcome outcome;
        public final String detail;     // classifyEdit tokens / ""
        public final long draftId;      // 0 when none
        public Result(Outcome outcome, String detail, long draftId) {
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
            this.draftId = draftId;
        }
    }

    private ManualSendLearner() { }

    public static String markerKey(long draftId) {
        return "manual.learned." + draftId;
    }

    /** Evaluate ONE contact: find the newest OUTGOING message in the hot window and
     *  learn from it if it was a manual answer to a live draft. Idempotent. */
    public static Result evaluate(Contact contact, MessageStore messages,
                                  DraftStore drafts, LearningService learning,
                                  KvStore kv, Clock clock) {
        if (contact == null) return new Result(Outcome.NO_LIVE_DRAFT, "", 0);

        Message newestOutgoing = null;
        List<Message> thread = messages.lastMessages(contact.id, 12);
        for (Message m : thread) {   // oldest-first → loop ends at the newest
            if (m != null && m.direction == Direction.OUTGOING) newestOutgoing = m;
        }
        if (newestOutgoing == null) return new Result(Outcome.NO_LIVE_DRAFT, "", 0);
        if (!PromptBuilder.usableText(newestOutgoing.body)) {
            return new Result(Outcome.PLACEHOLDER, "", 0);
        }
        String sent = newestOutgoing.body.trim();

        List<Draft> recent = drafts.byContact(contact.id, 25);   // newest-first
        for (Draft d : recent) {
            if (d == null) continue;
            if (d.status != DraftStatus.GENERATED) continue;          // already consumed
            if (d.createdAt <= 0 || d.createdAt > newestOutgoing.sentAt) continue; // must predate
            if (newestOutgoing.sentAt - d.createdAt > MAX_AGE_MS) continue;        // stale
            if ("1".equals(kv.get(markerKey(d.id), "0"))) {
                return new Result(Outcome.ALREADY_LEARNED, "", d.id);
            }
            kv.put(markerKey(d.id), "1");
            boolean same = sent.equals(d.replyText == null ? "" : d.replyText.trim());
            if (same) {
                learning.record(contact, StyleSignal.Kind.APPROVED,
                    "sent-manually-same-words", Long.valueOf(d.id));
                return new Result(Outcome.LEARNED_APPROVED, "", d.id);
            }
            String tokens = LearningEngine.classifyEdit(d.replyText, sent);
            learning.record(contact, StyleSignal.Kind.EDITED, "manual:" + tokens,
                Long.valueOf(d.id));
            return new Result(Outcome.LEARNED_EDITED, tokens, d.id);
        }
        return new Result(Outcome.NO_LIVE_DRAFT, "", 0);
    }
}
