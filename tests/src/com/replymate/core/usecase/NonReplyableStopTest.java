package com.replymate.core.usecase;

import com.replymate.core.listener.ItemClass;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: an item the ingest classifier stamped as ANNOUNCEMENT /
 *  BROADCAST / SERVICE / SYSTEM stops BEFORE research and long before the paid
 *  provider call — with the mandated explanation, zero drafts, zero provider
 *  calls. Stamped real messages (and honestly-unstamped pre-v9 rows) flow on. */
public final class NonReplyableStopTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        kv.put(ProfileService.KEY_NAME, "Spencer");
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        DraftService svc = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            profiles, drafts, new Fakes.UsageStoreFake(), gateway, Fakes.IDS,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
        svc.setConversationStateService(new ConversationStateService(kv, Fakes.FIXED_CLOCK));
        return svc;
    }

    private void seedContact(String name) {
        Contact c = Fakes.contact(1, name);
        c.relationshipType = "close friend";
        contacts.put(c);
    }

    private Message in(String cls, String body) {
        Message m = Fakes.msg(1, Direction.INCOMING, body);
        m.itemClass = cls;
        messages.add(m);
        return m;
    }

    private void assertStopped(String wire) {
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("nice one!", "great to hear");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);
        assertFalse("a " + wire + " item must not generate", r.ok);
        assertTrue(r.error, r.error.contains("can't be replied to from ReplyMate"));
        assertTrue(r.error, r.error.contains(wire.replace('_', ' ')));
        assertEquals("zero provider calls for a non-replyable item", 0, p.calls);
        assertTrue("zero drafts for a non-replyable item", drafts.saved.isEmpty());
    }

    @Test public void announcementStopsBeforeGenerationWithTheMandatedExplanation() {
        seedContact("Guild news");
        in(ItemClass.ANNOUNCEMENT.wire, "new rules are live, read them");
        assertStopped("announcement");
    }

    @Test public void serviceItemStopsToo() {
        seedContact("WhatsApp");
        in(ItemClass.SERVICE.wire, "messages you send are now secured");
        assertStopped("service");
    }

    @Test public void systemItemStopsToo() {
        seedContact("Amara");
        in(ItemClass.SYSTEM.wire, "Your security code with Amara changed.");
        assertStopped("system");
    }

    @Test public void aStampedRealMessageStillGenerates() {
        seedContact("Amara");
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did you land safe?"));
        in(ItemClass.REAL_1TO1.wire, "yes o! just dey settle");
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("nice one!", "great to hear");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
    }

    @Test public void anUnstampedPreV9RowStillGenerates() {
        seedContact("Amara");
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did you land safe?"));
        in("", "yes o! just dey settle");
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("nice one!", "great to hear");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
    }
}
