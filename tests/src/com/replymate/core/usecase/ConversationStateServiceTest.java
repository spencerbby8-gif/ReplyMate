package com.replymate.core.usecase;

import com.replymate.core.convo.ConversationState;
import com.replymate.core.convo.Engagement;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: ConversationStateService builds the platform-agnostic state
 *  from STORED rows (participants/burst/topic persisted per conversation), derives
 *  the wait/skip markers, and keeps every conversation isolated. */
public final class ConversationStateServiceTest {

    private Fakes.KvStoreFake kv;
    private ConversationStateService svc;
    private long now;

    @Before public void setUp() {
        kv = new Fakes.KvStoreFake();
        svc = new ConversationStateService(kv, Fakes.FIXED_CLOCK);
        now = Fakes.FIXED_CLOCK.now();
    }

    private static Contact groupContact(long id, String name) {
        Contact c = Fakes.contact(id, name);
        c.isGroup = true;
        return c;
    }

    private static Message in(long cid, String sender, String key, String body, long ts) {
        Message m = Fakes.msg(cid, Direction.INCOMING, body);
        m.senderName = sender;
        m.senderKey = key;
        m.sentAt = ts;
        return m;
    }

    private static Message out(long cid, String body, long ts) {
        Message m = Fakes.msg(cid, Direction.OUTGOING, body);
        m.sentAt = ts;
        return m;
    }

    @Test public void burstIsEverythingSinceTheOwnersLastReplyAttributed() {
        Contact c = groupContact(1, "Family");
        List<Message> t = new ArrayList<Message>();
        t.add(in(1, "Musa", "m1", "old line before my reply", now - 500_000));
        t.add(out(1, "I'll be there before 3", now - 400_000));
        t.add(in(1, "Musa", "m1", "meeting moved to 3pm", now - 300_000));
        t.add(in(1, "Chidi", "c2", "noted o, traffic bad", now - 200_000));
        ConversationState st = svc.build(c, t, "Spencer");
        assertEquals(2, st.burst.size());
        assertEquals("Musa", st.burst.get(0).senderLabel);
        assertEquals("Chidi", st.burst.get(1).senderLabel);
        assertEquals("I'll be there before 3", st.lastOutgoingText);
        assertTrue(st.isGroup);
        assertEquals("Family", st.groupTitle);
    }

    @Test public void participantsAreLearnedAndPersistAcrossInstances() {
        Contact c = groupContact(1, "Family");
        List<Message> t = new ArrayList<Message>();
        t.add(in(1, "234801", "person-1", "first", now - 300_000));
        t.add(in(1, "Amara", "person-1", "second", now - 200_000));  // same key, refined name
        ConversationState st = svc.build(c, t, "Spencer");
        assertEquals("Amara", st.participants.labelFor("k:person-1"));
        ConversationStateService again = new ConversationStateService(kv, Fakes.FIXED_CLOCK);
        ConversationState st2 = again.build(c, new ArrayList<Message>(), "Spencer");
        assertEquals("the registry survives process death (kv)", "Amara",
            st2.participants.labelFor("k:person-1"));
    }

    @Test public void topicIsTrackedAndAChangeNamesThePreviousTopic() {
        Contact c = groupContact(1, "Family");
        List<Message> t1 = new ArrayList<Message>();
        t1.add(in(1, "Musa", "m1", "match tickets are out, get your match tickets", now - 50_000));
        t1.add(in(1, "Chidi", "c2", "match tickets done, stadium next", now - 40_000));
        ConversationState st1 = svc.build(c, t1, "Spencer");
        assertEquals("match, tickets", st1.topic);
        assertEquals("", st1.previousTopic);

        List<Message> t2 = new ArrayList<Message>();
        t2.add(in(1, "Musa", "m1", "dinner plans changed, sushi instead", now - 10_000));
        t2.add(in(1, "Chidi", "c2", "sushi dinner confirmed for friday", now - 5_000));
        ConversationState st2 = svc.build(c, t2, "Spencer");
        assertTrue(st2.topic.contains("dinner") || st2.topic.contains("sushi"));
        assertEquals("a topic change keeps the old one visible",
            "match, tickets", st2.previousTopic);
    }

    @Test public void newestLineNarrowsToAnActiveSubtopic() {
        Contact c = groupContact(1, "Family");
        List<Message> t = new ArrayList<Message>();
        t.add(in(1, "Musa", "m1", "match tickets are out", now - 30_000));
        t.add(in(1, "Chidi", "c2", "ticketing site is crashing though", now - 20_000));
        ConversationState st = svc.build(c, t, "Spencer");
        assertFalse(st.topic.isEmpty());
        assertEquals("ticketing", st.subtopic);
    }

    @Test public void waitMarkerExhaustsOnlyForTheSameContent() {
        svc.markWaited(7, "hashA");
        Contact c = groupContact(7, "Family");
        List<Message> t = new ArrayList<Message>();
        t.add(in(7, "Musa", "m1", "who has the charger?", now - 5000));
        ConversationStateService.Evaluation fresh = svc.evaluate(c, t, "Spencer", "hashB");
        assertEquals(Engagement.Verdict.WAIT, fresh.engagement.verdict);
        ConversationStateService.Evaluation same = svc.evaluate(c, t, "Spencer", "hashA");
        assertEquals(Engagement.Verdict.REPLY_OPTIONAL, same.engagement.verdict);
    }

    @Test public void skipMarkerKeepsTheSameContentQuiet() {
        assertNull(svc.skippedFor(3, "h1"));
        svc.markSkip(3, "h1", "NO_REPLY:NOT_ADDRESSED");
        assertEquals("NO_REPLY:NOT_ADDRESSED", svc.skippedFor(3, "h1"));
        assertNull(svc.skippedFor(3, "other"));
    }

    @Test public void conversationsStayIsolated() {
        Contact g1 = groupContact(1, "Family");
        Contact g2 = groupContact(2, "Work squad");
        List<Message> t1 = new ArrayList<Message>();
        t1.add(in(1, "Musa", "m1", "match tickets moved", now - 30_000));
        List<Message> t2 = new ArrayList<Message>();
        t2.add(in(2, "Ada", "a1", "deploy freeze starts monday", now - 30_000));
        ConversationState s1 = svc.build(g1, t1, "Spencer");
        ConversationState s2 = svc.build(g2, t2, "Spencer");
        assertEquals(1, s1.participants.size());
        assertEquals(1, s2.participants.size());
        assertNotEquals(s1.topic, s2.topic);
        assertTrue(kv.get(ConversationStateService.KV_REG + 1, "").contains("m1"));
        assertFalse(kv.get(ConversationStateService.KV_REG + 2, "").contains("m1"));
    }
}
