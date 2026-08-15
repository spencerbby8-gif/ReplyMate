package com.replymate.core.usecase;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;

/** P-intelligence-14 (owner mandate): AUTO FOLLOW-UP policy. When the per-contact
 *  "Auto follow-up draft" control is ON, ReplyMate may prepare ONE natural
 *  follow-up draft after an approved reply — through the exact same pipeline as
 *  everything else, landing as a draft that still needs explicit approval.
 *
 *  The mandate is "it must know when NOT to follow up" — so the decision is a
 *  PURE function, JVM-pinned case by case, never implied by happy paths:
 *    OFF            — the control is per-contact and defaults off; absent = off.
 *    PRIVATE/AI-OFF — private contacts and AI-disabled contacts never generate.
 *    THEIR TURN     — the newest usable message is THEIR incoming and it is not
 *                     the message the approved reply just answered (a quick-reply
 *                     sent through the source app never lands in our store, so
 *                     the approval's inReplyToId is the truth about what was
 *                     just answered — a message AFTER it means they wrote again
 *                     and a bump would be tone-deaf).
 *    DRAFT WAITING  — another generated draft already waits for approval; never
 *                     queue a distraction on top of it.
 *    BUMP ON A BUMP — the approved draft was itself an intentional compose
 *                     (follow-up/clarify/continue/opener): following up on a
 *                     follow-up is nagging by construction.
 *    ONCE PER REPLY — one follow-up per approved reply anchor, ever; a second
 *                     approval of the same draft (copy again) prepares nothing.
 *    NO ANCHOR      — nothing answered and nothing outgoing: nothing to bump. */
public final class FollowUpPolicy {

    public static final String REASON_OFF = "auto follow-up is off for this contact";
    public static final String REASON_PRIVATE = "private contact";
    public static final String REASON_AI_OFF = "AI replies are off for this contact";
    public static final String REASON_THEIR_TURN =
        "their newer message is still unanswered — that needs a reply, not a bump";
    public static final String REASON_DRAFT_WAITING =
        "a draft is already waiting for your approval";
    public static final String REASON_BUMP_ON_BUMP =
        "the approved draft was itself an intentional compose — never follow up on a follow-up";
    public static final String REASON_ONCE_PER_REPLY =
        "a follow-up for that reply was already prepared";
    public static final String REASON_NO_ANCHOR =
        "nothing answered and nothing of yours sent — nothing to follow up on";

    public static final class Verdict {
        public final boolean prepare;
        /** Human-auditable skip reason; "" when {@link #prepare}. */
        public final String skipped;
        private Verdict(boolean prepare, String skipped) {
            this.prepare = prepare;
            this.skipped = skipped;
        }
        static Verdict skip(String reason) { return new Verdict(false, reason); }
        static final Verdict PREPARE = new Verdict(true, "");
    }

    private FollowUpPolicy() { }

    /**
     * @param on                     per-contact auto-follow-up switch
     * @param privateMode/aiEnabled  contact gates
     * @param newestUsable           newest message with usable text (may be null)
     * @param lastOutgoingId         newest usable OUTGOING message id (may be null)
     * @param answeredId             the approved draft's inReplyToId (may be null)
     * @param approvedIsIntentional  the approved draft was a composed intention
     * @param generatedDraftWaiting  another GENERATED draft already waits
     * @param alreadyFollowedAnchor  kv-persisted anchor of the last prepared follow-up
     */
    public static Verdict decide(boolean on, boolean privateMode, boolean aiEnabled,
            Message newestUsable, Long lastOutgoingId, Long answeredId,
            boolean approvedIsIntentional, boolean generatedDraftWaiting,
            long alreadyFollowedAnchor) {
        if (!on) return Verdict.skip(REASON_OFF);
        if (privateMode) return Verdict.skip(REASON_PRIVATE);
        if (!aiEnabled) return Verdict.skip(REASON_AI_OFF);
        if (approvedIsIntentional) return Verdict.skip(REASON_BUMP_ON_BUMP);
        if (newestUsable != null && newestUsable.direction == Direction.INCOMING
                && (answeredId == null || newestUsable.id != answeredId.longValue())) {
            return Verdict.skip(REASON_THEIR_TURN);
        }
        if (generatedDraftWaiting) return Verdict.skip(REASON_DRAFT_WAITING);
        long anchor = anchorOf(answeredId, lastOutgoingId);
        if (anchor <= 0) return Verdict.skip(REASON_NO_ANCHOR);
        if (anchor == alreadyFollowedAnchor) return Verdict.skip(REASON_ONCE_PER_REPLY);
        return Verdict.PREPARE;
    }

    /** The dedupe anchor for one follow-up per approved reply: the answered
     *  message when there is one, else the owner's last outgoing. */
    public static long anchorOf(Long answeredId, Long lastOutgoingId) {
        if (answeredId != null && answeredId.longValue() > 0) return answeredId.longValue();
        return lastOutgoingId == null ? 0L : lastOutgoingId.longValue();
    }
}
