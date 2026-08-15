package com.replymate.core.assistant;

import com.replymate.core.listener.ConversationMatch;

/** P-intelligence-17: THE SEND-INTEGRITY GUARD. Before ANY approved text is fired
 *  through a captured reply target (live action, re-posted action, cached
 *  PendingIntent — every flavor), the target's identity is compared against the
 *  CONVERSATION IDENTITY OF THE MESSAGE BEING ANSWERED (schema v9 message row).
 *  A mismatch REFUSES with a human reason — approved text is never sent into a
 *  different conversation. Pure + test-pinned.
 *
 *  Tiers (strongest first, mirroring ConversationMatch):
 *    1. different package ⇒ REFUSE_DIFFERENT_APP
 *    2. the answered message carries conversation identity (v9+ notification of
 *       Discord/Slack-style multi-channel apps always does) ⇒ ConversationMatch
 *       decides: ALLOW(verified) or REFUSE_DIFFERENT_CONVERSATION
 *    3. message without identity (1:1 rows; pre-v9 rows) ⇒ ALLOW_UNVERIFIED —
 *       structurally safe because a target is captured per contact, so a 1:1
 *       contact's target can only belong to that same 1:1 chat; the ledger marks
 *       the verification level honestly. Never a silent "verified".
 */
public final class DeliveryGuard {

    public enum Verdict {
        ALLOW,                 // identity-verified same conversation
        ALLOW_UNVERIFIED,      // no identity on the message side — contact-scoped allow
        REFUSE_DIFFERENT_APP,
        REFUSE_DIFFERENT_CONVERSATION,
        REFUSE_NOT_USABLE
    }

    public static final class Decision {
        public final Verdict verdict;
        public final String reason;          // human reason, mandatory on REFUSE_*
        Decision(Verdict v, String r) { verdict = v; reason = r == null ? "" : r; }
        public boolean allowed() {
            return verdict == Verdict.ALLOW || verdict == Verdict.ALLOW_UNVERIFIED;
        }
        public boolean verified() { return verdict == Verdict.ALLOW; }
    }

    private DeliveryGuard() { }

    /**
     * @param usable   the captured target passed its own usability check
     * @param pkgT/convIdT/convTitleT/titleT  captured TARGET identity
     * @param pkgM/convIdM/convTitleM         ANSWERED MESSAGE identity (schema v9)
     */
    public static Decision check(boolean usable,
                                 String pkgT, String convIdT, String convTitleT, String titleT,
                                 String pkgM, String convIdM, String convTitleM) {
        if (!usable) {
            return new Decision(Verdict.REFUSE_NOT_USABLE,
                "no usable reply target survived for this message");
        }
        if (nonEmpty(pkgM) && !pkgM.equals(pkgT == null ? "" : pkgT)) {
            return new Decision(Verdict.REFUSE_DIFFERENT_APP,
                "the reply target belongs to a different app than the message being"
                + " answered — ReplyMate never borrows another app's reply action");
        }
        boolean messageIdentified =
            ConversationMatch.identifiable(convIdM, convTitleM, "");
        if (messageIdentified) {
            // P-intelligence-17R: the MESSAGE side is stamped by the PARSERS, whose
            // convention is convTitle := firstNonBlank(EXTRA_CONVERSATION_TITLE,
            // EXTRA_TITLE) — a 1:1 chat's row carries the partner NAME in convTitle.
            // The captured TARGET keeps the raw fields separate (convTitle raw,
            // title = EXTRA_TITLE). Hardcoding the message-side title to "" made
            // every title-only identified chat refuse ("not meant for that chat" —
            // the on-device 1.6.6 regression). Feed the stamped title into the
            // match's title tier so a legit known chat verifies; the tiers stay
            // decisive (different native ids still refuse even when titles collide).
            boolean same = ConversationMatch.same(
                pkgT, convIdT, convTitleT, titleT,
                pkgM, convIdM, convTitleM, convTitleM);
            if (!same) {
                return new Decision(Verdict.REFUSE_DIFFERENT_CONVERSATION,
                    "the captured reply action belongs to a DIFFERENT conversation"
                    + " than the message being answered — ReplyMate never borrows"
                    + " another notification's reply target");
            }
            return new Decision(Verdict.ALLOW, "");
        }
        return new Decision(Verdict.ALLOW_UNVERIFIED,
            "the answered message carries no conversation identity (1:1 or pre-v9 row)"
            + " — allow by contact scope, unverified at conversation level");
    }

    private static boolean nonEmpty(String s) { return s != null && !s.trim().isEmpty(); }
}
