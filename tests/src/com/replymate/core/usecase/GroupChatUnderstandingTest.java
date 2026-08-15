package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.prompt.ComposeKind;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-15, topic 2: GROUP UNDERSTANDING in the prompt. When the
 *  capture-time fact says GROUP (persisted isGroup), the model is TOLD: the
 *  group's name, the members speaking (MessagingStyle sender attribution), and
 *  who the owner is — on the wire, through real generation, never inferred.
 *  1:1 prompts stay byte-identical; group memory stays scoped to the group. */
public final class GroupChatUnderstandingTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private LearningService learning;
    private com.replymate.core.memory.MemoryService memory;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        memory = new com.replymate.core.memory.MemoryService(
            new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memory);
    }

    private void seedGroup() {
        contacts.put(Fakes.contact(1, "Family group"));
        contacts.get(1).isGroup = true;
        Message m1 = Fakes.msg(1, Direction.INCOMING, "meeting moved to 3pm");
        m1.senderName = "Musa";
        messages.add(m1);
        Message m2 = Fakes.msg(1, Direction.INCOMING, "noted o, traffic bad");
        m2.senderName = "Chidi";
        messages.add(m2);
        messages.add(Fakes.msg(1, Direction.OUTGOING, "I'll be there before 3"));
        Message m3 = Fakes.msg(1, Direction.INCOMING, "so who is bringing drinks?");
        m3.senderName = "Musa";
        messages.add(m3);
    }

    /* 1 ─ the group context line rides the wire: name, members, owner identity. */
    @Test public void groupContextHitsTheWireThroughRealGeneration() {
        seedGroup();
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("I can grab drinks");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = svc.generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String sys = p.lastRequest.system;
        assertTrue(sys.contains("This is a GROUP chat called \"Family group\""));
        assertTrue("the members speaking are named",
            sys.contains("Members speaking here: Musa, Chidi"));
        assertTrue("the owner's own lines are marked as theirs", sys.contains("are YOURS"));
        assertTrue("the model may never speak for another member",
            sys.contains("never speak for another member"));
    }

    /* 2 ─ per-message attribution survives to the wire turns inside groups. */
    @Test public void memberAttributionRidesTheTurnsNotJustTheHeader() {
        seedGroup();
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);

        boolean musaSeen = false, chidiSeen = false;
        for (com.replymate.core.ai.Turn t : p.lastRequest.turns) {
            if (t.text.startsWith("Musa:")) musaSeen = true;
            if (t.text.startsWith("Chidi:")) chidiSeen = true;
        }
        assertTrue("Musa's lines are Musa's", musaSeen);
        assertTrue("Chidi's lines are Chidi's", chidiSeen);
    }

    /* 3 ─ 1:1 threads carry NO group line (byte-level absence, not soft wording). */
    @Test public void oneOnOneThreadsGetNoGroupLine() {
        contacts.put(Fakes.contact(1, "Ada"));
        Message m = Fakes.msg(1, Direction.INCOMING, "you dey?");
        m.senderName = "Ada";
        messages.add(m);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        assertFalse("a 1:1 chat must never claim group context",
            p.lastRequest.system.contains("GROUP chat"));
    }

    /* 4 ─ group memory stays scoped to the group (no 1:1 bleed, no cross-group bleed). */
    @Test public void groupMemoryIsSelfContained() {
        seedGroup();
        contacts.put(Fakes.contact(2, "Ada"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "you still coming tonight?"));
        memory.replacePinnedFacts(1, "GROUP-SECRET-FACT: mama's shop opens June 1st");
        memory.replacePinnedFacts(2, "ADA-SECRET: allergic to peanuts");
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        String groupSys = p.lastRequest.system;
        assertTrue(groupSys.contains("GROUP-SECRET-FACT: mama's shop opens June 1st"));
        assertFalse("Ada-only facts never enter the group's prompt",
            groupSys.contains("ADA-SECRET"));

        assertTrue(svc.generateForContact(2).ok);
        String adaSys = p.lastRequest.system;
        assertFalse("the group's facts never enter a 1:1 prompt",
            adaSys.contains("GROUP-SECRET-FACT"));
        assertTrue(adaSys.contains("ADA-SECRET: allergic to peanuts"));
        assertFalse(adaSys.contains("GROUP chat"));
    }

    /* 5 ─ intentional compose in a group carries the same group context. */
    @Test public void intentionalKindsAlsoKnowTheGroupContext() {
        seedGroup();
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("clarify draft");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> r = svc.composeForContact(1, ComposeKind.CLARIFY);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertTrue(p.lastRequest.system.contains("This is a GROUP chat called \"Family group\""));
        assertTrue("the clarification quotes the member-attributed latest message",
            p.lastRequest.task.text.contains("so who is bringing drinks?"));
    }
}
