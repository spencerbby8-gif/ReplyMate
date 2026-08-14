package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.EnumSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-13: group chats are OPT-IN, never globally blocked. Drives the
 *  REAL stack (ParserRegistry → IngestCoordinator) with the policy in kv:
 *    - default OFF: a group card stays silent (the P-bg-7 contract stands);
 *    - master ON: a real group message is captured, attributed per member, and
 *      PINGS (⇒ a draft can generate) under its own group-namespaced contact;
 *    - per-app override beats the master both ways;
 *    - 1:1 behavior is byte-identical in every mode. */
public final class GroupOptInTest {

    private ParserRegistry registry;

    private static final class Run {
        ParserRegistry.Outcome out;
        IngestReport rep;
        Fakes.ContactStoreFake contacts;
        Fakes.MessageStoreFake messages;
        Fakes.KvStoreFake kv;
    }

    @Before public void setUp() {
        registry = new ParserRegistry();
    }

    private Run drive(RawNotif raw, String... kvPairs) {
        Run r = new Run();
        r.contacts = new Fakes.ContactStoreFake();
        r.messages = new Fakes.MessageStoreFake();
        r.kv = new Fakes.KvStoreFake();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) r.kv.put(kvPairs[i], kvPairs[i + 1]);
        IngestCoordinator engine = new IngestCoordinator(
            new ContactService(r.contacts, Fakes.FIXED_CLOCK), r.messages, r.kv,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
        r.out = registry.route(raw.packageName, raw, EnumSet.allOf(Channel.class),
            new ListenerStats(r.kv));
        if (r.out.kind == ParserRegistry.OutcomeKind.PARSED) {
            r.rep = engine.handle(r.out.events, null);
        }
        return r;
    }

    @Test public void defaultOffKeepsGroupsSilent() {
        Run r = drive(ParserFixtures.styleGroup("com.whatsapp", "Market women",
            "Nkem", "who has the match tickets?"));
        assertNotNull(r.rep);
        assertEquals(0, r.rep.stored);
        assertEquals(0, r.rep.pings.size());
        assertEquals(0, r.contacts.all().size());
    }

    @Test public void masterOnCapturesAttributesAndPingsTheGroup() {
        Run r = drive(ParserFixtures.styleGroup("com.whatsapp", "Market women",
            "Nkem", "who has the match tickets?"),
            GroupPolicy.KV_GLOBAL, "1");
        assertEquals(ParserRegistry.OutcomeKind.PARSED, r.out.kind);
        assertEquals(1, r.rep.stored);
        assertEquals("an enabled group pings like a 1:1 (⇒ one draft job)",
            1, r.rep.pings.size());
        assertEquals(1, r.contacts.all().size());

        Contact group = r.contacts.all().get(0);
        assertEquals("Market women", group.displayName);
        List<Message> rows = r.messages.lastMessages(group.id, 5);
        assertEquals(1, rows.size());
        assertEquals("member attribution rides the row (schema v6)",
            "Nkem", rows.get(0).senderName);
        assertEquals("who has the match tickets?", rows.get(0).body);
    }

    @Test public void perAppOverrideBeatsTheMasterOnBothSides() {
        // master OFF, WhatsApp explicitly ON
        Run wa = drive(ParserFixtures.styleGroup("com.whatsapp", "Devs hangout",
            "Emeka", "deploy done, check am"), GroupPolicy.keyFor(Channel.WHATSAPP), "1");
        assertEquals(1, wa.rep.stored);
        assertEquals(1, wa.rep.pings.size());
        Run tg = drive(ParserFixtures.styleGroup("org.telegram.messenger",
            "Family gist", "Ada", "sunday lunch don set"),
            GroupPolicy.keyFor(Channel.WHATSAPP), "1");
        assertEquals("Telegram inherits the OFF master", 0,
            tg.rep == null ? 0 : tg.rep.stored);

        // master ON, Telegram explicitly OFF
        Run wa2 = drive(ParserFixtures.styleGroup("com.whatsapp", "Devs hangout",
            "Emeka", "deploy don land"),
            GroupPolicy.KV_GLOBAL, "1",
            GroupPolicy.keyFor(Channel.TELEGRAM), "0");
        assertEquals(1, wa2.rep.stored);
        Run tg2 = drive(ParserFixtures.styleGroup("org.telegram.messenger",
            "Family gist", "Ada", "sunday lunch don set"),
            GroupPolicy.KV_GLOBAL, "1",
            GroupPolicy.keyFor(Channel.TELEGRAM), "0");
        assertEquals(0, tg2.rep == null ? 0 : tg2.rep.stored);
        assertEquals(0, tg2.contacts.all().size());
    }

    @Test public void enabledGroupGeneratesWithMemberAttribution() {
        Run r = drive(ParserFixtures.styleGroup("com.whatsapp", "Market women",
            "Nkem", "who has the match tickets?"),
            GroupPolicy.KV_GLOBAL, "1");
        assertEquals(1, r.contacts.all().size());
        final Contact group = r.contacts.all().get(0);

        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("I never see am o");
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(signals, new Fakes.KvStoreFake());
        com.replymate.core.usecase.DraftService svc =
            new com.replymate.core.usecase.DraftService(r.contacts, r.messages,
                new Fakes.StyleStoreFake(),
                new com.replymate.core.usecase.ProfileService(kv),
                new Fakes.DraftStoreFake(), new Fakes.UsageStoreFake(),
                new Fakes.GatewayFake(provider), Fakes.IDS, Fakes.FIXED_CLOCK,
                Fakes.NOOP_LOG, Fakes.styleService(settings, learning), learning,
                new com.replymate.core.memory.MemoryService(
                    new Fakes.MemoryStoreFake(), r.messages, kv, Fakes.FIXED_CLOCK));

        com.replymate.core.util.Result<com.replymate.core.usecase.DraftOutcome> gen =
            svc.generateForContact(group.id);
        assertTrue(String.valueOf(gen.ok ? "" : gen.error), gen.ok);
        assertEquals(1, gen.value.drafts.size());
        StringBuilder turns = new StringBuilder();
        for (com.replymate.core.ai.Turn t : provider.lastRequest.turns) {
            turns.append(t.text).append('\n');
        }
        assertTrue("the group thread attributes words to the member, not the group: "
                + turns,
            turns.toString().contains("Nkem: who has the match tickets?"));
    }

    @Test public void oneToOneBehaviorIsIdenticalInEveryMode() {
        for (String[] mode : new String[][] {
                { },                                             // default off
                { GroupPolicy.KV_GLOBAL, "1" },                  // master on
                { GroupPolicy.keyFor(Channel.WHATSAPP), "1" },   // per-app on
        }) {
            Run r = drive(ParserFixtures.styleDm("com.whatsapp", "Amara",
                ParserFixtures.T_GREET, ParserFixtures.T_FOLLOW), mode);
            assertEquals(ParserRegistry.OutcomeKind.PARSED, r.out.kind);
            assertEquals(2, r.rep.stored);
            assertEquals(1, r.rep.pings.size());
            assertEquals(1, r.contacts.all().size());
            assertEquals("Amara", r.contacts.all().get(0).displayName);
        }
    }
}
