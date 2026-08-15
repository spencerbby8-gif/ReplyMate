package com.replymate.core.convo;

import com.replymate.core.understanding.BurstSignals;

/** P-intelligence-16b: the ENGAGEMENT CLASSIFIER — decides, BEFORE any provider
 *  call, whether this conversation state deserves a draft at all:
 *    REPLY_REQUIRED / REPLY_OPTIONAL / WAIT / NO_REPLY.
 *  Fully deterministic and test-pinned; every verdict carries a stable reason
 *  string for diagnostics. Honesty contract:
 *   - a target is attached ONLY on explicit evidence (owner named, owner quoted-
 *     by-conversation-shape, open room question) — never invented;
 *   - owner-name matching is exact word-boundary matching of the profile name
 *     tokens (no fuzzy guessing); when the owner set no name, mention-detection
 *     simply cannot fire and the classifier says why;
 *   - 1:1 chats are ALWAYS addressed to the owner → REQUIRED with the newest
 *     line as target (this formalizes the long-standing newest-message behavior
 *     instead of changing it). */
public final class EngagementClassifier {

    /** A room question stops being "fresh" after this — then the wait is over. */
    public static final long QUESTION_FRESH_MS = 90_000L;
    /** Owner counts as an ACTIVE member of the group within this recency. */
    public static final long ACTIVE_MEMBER_MS = 72L * 3600_000L;
    /** A question only "answers the owner's message" in the same conversational
     *  beat — an hour-old line of yours followed by a question is a room
     *  question, not a reply to you. */
    public static final long REPLY_WINDOW_MS = 30L * 60_000L;
    public static final int MAX_OWNER_TOKENS = 3;

    private EngagementClassifier() { }

    public static Engagement evaluate(ConversationState st, boolean waitExhausted) {
        if (st == null) return new Engagement(Engagement.Verdict.NO_REPLY, "EMPTY", null);

        // ---- 1:1: direct messages always address the owner (legacy behavior). ----
        if (!st.isGroup) {
            ConversationState.Line newest = st.newestLine();
            ReplyTarget t = newest == null ? null : new ReplyTarget(
                st.conversationId, newest.senderStableId, newest.senderLabel,
                newest.notifKey, newest.text, newest.tsMs,
                ReplyTarget.Confidence.MEDIUM, "direct chat");
            return new Engagement(Engagement.Verdict.REPLY_REQUIRED, "DIRECT_CHAT", t);
        }

        if (st.burst.isEmpty()) {
            return new Engagement(Engagement.Verdict.NO_REPLY, "EMPTY", null);
        }

        // ---- owner addressed by NAME anywhere in the burst (newest mention wins). ----
        String[] tokens = ownerTokens(st.ownerName);
        ConversationState.Line mention = null;
        if (tokens.length > 0) {
            for (ConversationState.Line l : st.burst) {
                if (mentionsName(l.text, tokens)) mention = l;   // loop ends at newest
            }
        }
        if (mention != null) {
            return new Engagement(Engagement.Verdict.REPLY_REQUIRED, "MENTIONED",
                new ReplyTarget(st.conversationId, mention.senderStableId, mention.senderLabel,
                    mention.notifKey, mention.text, mention.tsMs,
                    ReplyTarget.Confidence.HIGH, "mentioned you by name"));
        }

        ConversationState.Line newest = st.newestLine();
        boolean fillerOnly = true;
        for (ConversationState.Line l : st.burst) {
            if (!BurstSignals.isFiller(l.text)) { fillerOnly = false; break; }
        }

        // ---- pure filler ping with no name: wait once, then stay silent. A '?' in
        //  filler ("you there?") is still filler — checked BEFORE question logic. ----
        if (fillerOnly) {
            return waitExhausted
                ? new Engagement(Engagement.Verdict.NO_REPLY, "FILLER_ONLY", null)
                : new Engagement(Engagement.Verdict.WAIT, "FILLER_ONLY", null);
        }

        // ---- a question following the owner's own message answers THEM → us. ----
        ConversationState.Line question = null;
        for (ConversationState.Line l : st.burst) {
            if (l.text.indexOf('?') >= 0) question = l;          // newest question
        }
        if (question != null && st.lastOutgoingAt > 0
                && question.tsMs >= st.lastOutgoingAt
                && question.tsMs - st.lastOutgoingAt <= REPLY_WINDOW_MS
                && st.burst.size() <= 2 && !fillerOnly) {
            return new Engagement(Engagement.Verdict.REPLY_REQUIRED, "REPLIED_TO_YOURS",
                new ReplyTarget(st.conversationId, question.senderStableId, question.senderLabel,
                    question.notifKey, question.text, question.tsMs,
                    ReplyTarget.Confidence.MEDIUM,
                    "they asked right after your message — likely addressed to you"));
        }

        // ---- open question to the room: wait for the room first, then optional. ----
        if (question != null) {
            long age = Math.max(0L, st.evaluatedAtMs - question.tsMs);
            if (!waitExhausted && age < QUESTION_FRESH_MS) {
                return new Engagement(Engagement.Verdict.WAIT, "ROOM_QUESTION_FRESH", null);
            }
            return new Engagement(Engagement.Verdict.REPLY_OPTIONAL, "ROOM_QUESTION",
                new ReplyTarget(st.conversationId, question.senderStableId, question.senderLabel,
                    question.notifKey, question.text, question.tsMs,
                    ReplyTarget.Confidence.MEDIUM, "open question to the group"));
        }

        // ---- substantive traffic not aimed at the owner. ----
        boolean ownerActive = st.lastOutgoingAt > 0
            && (st.evaluatedAtMs - st.lastOutgoingAt) <= ACTIVE_MEMBER_MS;
        if (ownerActive) {
            return new Engagement(Engagement.Verdict.REPLY_OPTIONAL, "ACTIVE_MEMBER", null);
        }
        return new Engagement(Engagement.Verdict.NO_REPLY, "NOT_ADDRESSED", null);
    }

    /** Owner name tokens worth matching (≥3 chars, up to 3). Empty name ⇒ no tokens —
     *  mention detection is honestly impossible without a name, never fuzzy. */
    public static String[] ownerTokens(String ownerName) {
        if (ownerName == null) return new String[0];
        String[] raw = ownerName.trim().toLowerCase().split("[^a-z0-9']+");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String t : raw) {
            if (t.length() >= 3 && !out.contains(t)) out.add(t);
            if (out.size() >= MAX_OWNER_TOKENS) break;
        }
        return out.toArray(new String[0]);
    }

    /** Whole-word/"@word" match of ANY owner token (case-insensitive). */
    public static boolean mentionsName(String text, String[] tokens) {
        if (text == null || tokens == null || tokens.length == 0) return false;
        String lower = " " + text.toLowerCase().replaceAll("[^a-z0-9'@]+", " ") + " ";
        for (String t : tokens) {
            if (lower.contains(" " + t + " ") || lower.contains(" @" + t + " ")) return true;
        }
        return false;
    }
}
