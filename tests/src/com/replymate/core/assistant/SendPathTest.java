package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17R: the approve-time resolution order, pinned —
 *  live original → UNIQUE same-conversation re-post (a changed notification
 *  key/id never blocks delivery) → conversation-bound cached token (also the
 *  answer to ambiguity — never a guessed live target) → honest failure. */
public final class SendPathTest {

    @Test public void liveOriginalFiresItsOwnAction() {
        assertEquals(SendPath.Plan.LIVE_ORIGINAL,
            SendPath.plan(true, 0, false));
        assertEquals(SendPath.Plan.LIVE_ORIGINAL,
            SendPath.plan(true, 2, true));   // live original wins even over ambiguity
    }

    @Test public void sameChatRepostWithChangedKeyRebindsToItsCurrentAction() {
        // owner regression #2: the app re-posted the SAME conversation under a new
        // notification key — exactly one strict identity match ⇒ rebind and send.
        assertEquals(SendPath.Plan.REBIND_REPOST,
            SendPath.plan(false, 1, true));
        assertEquals(SendPath.Plan.REBIND_REPOST,
            SendPath.plan(false, 1, false));
    }

    @Test public void dismissedNotificationUsesTheCachedConversationBoundToken() {
        // owner regression #3: notification dismissed, nothing re-posted — the
        // cached reply PendingIntent (conversation-bound by the source app) sends.
        assertEquals(SendPath.Plan.CACHED_TOKEN,
            SendPath.plan(false, 0, true));
    }

    @Test public void ambiguousIdentityMatchesNeverGuessALiveTarget() {
        // owner #7 posture: two live notifications match the stored identity
        // (e.g. two same-name chats) — rebind would be a guess; prefer the
        // conversation-bound cached token, else fail honestly.
        assertEquals(SendPath.Plan.CACHED_TOKEN,
            SendPath.plan(false, 2, true));
        assertEquals(SendPath.Plan.HONEST_FAIL,
            SendPath.plan(false, 2, false));
    }

    @Test public void nothingLiveAndNothingCachedFailsHonestly() {
        assertEquals(SendPath.Plan.HONEST_FAIL,
            SendPath.plan(false, 0, false));
    }
}
