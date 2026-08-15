package com.replymate.core.assistant;

/** P-intelligence-17R: the ORDER for resolving an approved send, pure + JVM-pinned
 *  so the Android shell (AssistantReceiver) stays a thin executor:
 *
 *    1. LIVE_ORIGINAL — the captured notification is still active: fire its own
 *       action (strongest, watchable).
 *    2. REBIND_REPOST — the original is gone and EXACTLY ONE live notification
 *       matches the stored conversation identity (strict ConversationMatch):
 *       rebind to the current valid action for that SAME conversation (the app
 *       re-posted the chat under a new sbn key). A changed notification key/id
 *       alone never blocks delivery.
 *    3. CACHED_TOKEN — zero matches OR an AMBIGUOUS count (≥2): never guess a
 *       live target. The cached reply PendingIntent is conversation-bound BY THE
 *       SOURCE APP (official guidance keys reply PIs per conversation); firing it
 *       can only ever reach the conversation whose notification carried it. If
 *       the app canceled it, the send fails honestly — never into another chat.
 *    4. HONEST_FAIL — no live, no unique rebind, no cached token: explain.
 *
 *  The DeliveryGuard verdict stays a SEPARATE, earlier gate: a guard refusal
 *  short-circuits every plan here. */
public final class SendPath {

    public enum Plan { LIVE_ORIGINAL, REBIND_REPOST, CACHED_TOKEN, HONEST_FAIL }

    private SendPath() { }

    /**
     * @param originalLive            the captured sbn key resolves to an active notification
     * @param sameConversationMatches live notifications matching the stored identity (strict)
     * @param cachedAvailable         a cached reply PendingIntent exists for THIS conversation
     */
    public static Plan plan(boolean originalLive, int sameConversationMatches,
                            boolean cachedAvailable) {
        if (originalLive) return Plan.LIVE_ORIGINAL;
        if (sameConversationMatches == 1) return Plan.REBIND_REPOST;
        // 0 = no re-post live; ≥2 = ambiguous — both fall to the conversation-bound
        // cached token before any honest failure (never a guessed live target).
        return cachedAvailable ? Plan.CACHED_TOKEN : Plan.HONEST_FAIL;
    }
}
