package com.replymate.core.assistant;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-7: the catch-up sweep's eligibility rule is EXACTLY the live
 *  schedule path's rule — a conversation is retried only for a real incoming
 *  message that never produced an alerted draft. Already-answered, outgoing and
 *  empty states are never re-driven (no duplicate alerts, no paid re-generation). */
public final class AssistantCatchUpTest {

    private static Message msg(Direction dir, String body, long sentAt, long id) {
        Message m = new Message();
        m.contactId = 7L;
        m.direction = dir;
        m.body = body;
        m.sentAt = sentAt;
        m.id = id;
        m.source = Source.LISTENER;
        return m;
    }

    private static String hashOf(Message m) {
        return AssistantPlanner.hashOf(m.body + "|" + m.sentAt + "|" + m.id);
    }

    @Test public void unansweredIncomingNeedsReply() {
        Message m = msg(Direction.INCOMING, "you there?", 1000L, 5L);
        assertTrue("no done-hash ⇒ still owed a reply",
            AssistantPlanner.needsReply(m, ""));
    }

    @Test public void alreadyAnsweredIncomingNeverRetries() {
        Message m = msg(Direction.INCOMING, "you there?", 1000L, 5L);
        assertFalse("hash marked done after generation ⇒ never re-alerted",
            AssistantPlanner.needsReply(m, hashOf(m)));
    }

    @Test public void aNewerMessageAlwaysRetries() {
        Message old = msg(Direction.INCOMING, "you there?", 1000L, 5L);
        Message newer = msg(Direction.INCOMING, "hello??", 2000L, 6L);
        assertTrue(AssistantPlanner.needsReply(newer, hashOf(old)));
    }

    @Test public void outgoingLatestNeverRetries() {
        Message m = msg(Direction.OUTGOING, "on my way", 2000L, 6L);
        assertFalse(AssistantPlanner.needsReply(m, ""));
    }

    @Test public void nullMessageIsSafe() {
        assertFalse(AssistantPlanner.needsReply(null, ""));
    }
}
