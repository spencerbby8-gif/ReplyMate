package com.replymate.core.usecase;

import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: the GROUP ENGAGEMENT GATE inside DraftService — a group
 *  draft is never generated merely because a notification arrived. WAIT/NO_REPLY
 *  cost zero provider calls, optional/required flow through, 1:1 is untouched,
 *  and the classic gates (private / AI-off) still win first. */
public final class GroupEngagementGateTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private ConversationStateService convoStates;
    private long now;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        convoStates = new ConversationStateService(kv, Fakes.FIXED_CLOCK);
        now = Fakes.FIXED_CLOCK.now();
        kv.put(ProfileService.KEY_NAME, "Spencer");
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        DraftService svc = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            profiles, drafts, new Fakes.UsageStoreFake(), gateway, Fakes.IDS,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
        svc.setConversationStateService(convoStates);
        return svc;
    }

    private void seedGroup(String name) {
        contacts.put(Fakes.contact(1, name));
        contacts.get(1).isGroup = true;
    }

    private Message in(String sender, String body, long ts) {
        Message m = Fakes.msg(1, Direction.INCOMING, body);
        m.senderName = sender;
        m.sentAt = ts;
        messages.add(m);
        return m;
    }

    private Message out(String body, long ts) {
        Message m = Fakes.msg(1, Direction.OUTGOING, body);
        m.sentAt = ts;
        messages.add(m);
        return m;
    }

    @Test public void mentionGeneratesAndMarksTheVerdict() {
        seedGroup("Family group");
        in("Musa", "match tickets are out", now - 60_000);
        in("Chidi", "Spencer are you still coming?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("count me in");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
        assertTrue(convoStates.lastFor(1).startsWith("REPLY_REQUIRED|MENTIONED|Chidi"));
    }

    @Test public void burstNotAddressingTheOwnerStaysSilentAtZeroCost() {
        seedGroup("Family group");
        in("Musa", "the report is finally out", now - 60_000);
        in("Chidi", "reading it tonight", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("unused");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error, r.error.startsWith(
            DraftService.ENGAGEMENT_SKIP_PREFIX + "NO_REPLY:NOT_ADDRESSED"));
        assertEquals("zero provider calls for a silent verdict", 0, p.calls);
        assertTrue(drafts.byContact(1, 5).isEmpty());
    }

    @Test public void freshRoomQuestionWaitsBeforeSpending() {
        seedGroup("Family group");
        in("Musa", "who has the charger?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("unused");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error.startsWith(
            DraftService.ENGAGEMENT_SKIP_PREFIX + "WAIT:ROOM_QUESTION_FRESH"));
        assertEquals(0, p.calls);
    }

    @Test public void roomQuestionAfterTheWaitBecomesAnOptionalDraft() {
        seedGroup("Family group");
        out("morning fam", now - 3_600_000);
        Message q = in("Musa", "who has the charger?", now - 5_000);
        // the runner already waited once for THIS exact content
        String hash = AssistantPlanner.hashOf(q.body + "|" + q.sentAt + "|" + q.id);
        convoStates.markWaited(1, hash);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("I dropped it at yours");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
        assertTrue(convoStates.lastFor(1).startsWith("REPLY_OPTIONAL|ROOM_QUESTION|Musa"));
    }

    @Test public void activeMemberGetsAnOptionalJoinInDraft() {
        seedGroup("Family group");
        out("that match was wild", now - 600_000);
        in("Musa", "next match is saturday morning", now - 60_000);
        in("Chidi", "hope the pitch is dry this time", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("coming");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
        assertTrue(convoStates.lastFor(1).startsWith("REPLY_OPTIONAL|ACTIVE_MEMBER|"));
    }

    @Test public void directChatsBypassTheGateEntirely() {
        contacts.put(Fakes.contact(2, "Ada"));
        Message m = Fakes.msg(2, Direction.INCOMING, "the report is finally out");
        m.senderName = "Ada";
        m.sentAt = now - 5_000;
        messages.add(m);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(2);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
    }

    @Test public void classicGatesStillWinBeforeEngagement() {
        seedGroup("Locked group");
        contacts.get(1).privateMode = true;
        in("Musa", "Spencer are you there?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("unused");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.generateForContact(1);
        assertFalse(r.ok);
        assertFalse(r.error.contains(DraftService.ENGAGEMENT_SKIP_PREFIX));
        assertEquals(0, p.calls);

        contacts.get(1).privateMode = false;
        contacts.get(1).aiEnabled = false;
        Result<DraftOutcome> r2 = svc.generateForContact(1);
        assertFalse(r2.ok);
        assertFalse(r2.error.contains(DraftService.ENGAGEMENT_SKIP_PREFIX));
        assertEquals(0, p.calls);
    }
}
