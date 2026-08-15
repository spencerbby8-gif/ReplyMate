package com.replymate.core.convo;

/** P-intelligence-16b: the engagement VERDICT for one evaluated conversation state.
 *  The whole point of the phase: ReplyMate must NOT answer just because a
 *  notification arrived — the classifier decides, with a human-readable reason
 *  that lands in diagnostics and the prompt-audit trail. */
public final class Engagement {

    public enum Verdict {
        /** Owner is clearly being addressed → draft now, target attached when known. */
        REPLY_REQUIRED,
        /** Worth a draft the owner may send (active member / open room question after wait). */
        REPLY_OPTIONAL,
        /** Too early to know — one deferred re-check, never a provider call now. */
        WAIT,
        /** Stay silent: nothing addressed to the owner. */
        NO_REPLY
    }

    public final Verdict verdict;
    public final String reason;          // stable machine label, e.g. MENTIONED, NOT_ADDRESSED
    public final ReplyTarget target;     // null when nothing is being answered specifically

    public Engagement(Verdict verdict, String reason, ReplyTarget target) {
        this.verdict = verdict;
        this.reason = reason == null ? "" : reason;
        this.target = target;
    }

    public boolean shouldGenerate() {
        return verdict == Verdict.REPLY_REQUIRED || verdict == Verdict.REPLY_OPTIONAL;
    }
}
