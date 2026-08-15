package com.replymate.core.prompt;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ConversationStateService;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: ConversationState lines must arrive ON THE WIRE through a
 *  real generation (topic, reply target, same-name disambiguation, optional
 *  salience) — and 1:1 prompts must carry zero new bytes. */
public final class GroupPromptWireTest {

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
        Fakes.LearningStoreFake ls = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(ls, new Fakes.KvStoreFake());
        DraftService svc = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            profiles, drafts, new Fakes.UsageStoreFake(), gateway, Fakes.IDS,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
        svc.setConversationStateService(convoStates);
        return svc;
    }

    private void group(String name) {
        contacts.put(Fakes.contact(1, name));
        contacts.get(1).isGroup = true;
    }

    private Message in(long cid, String sender, String key, String body, long ts) {
        Message m = Fakes.msg(cid, Direction.INCOMING, body);
        m.senderName = sender;
        m.senderKey = key;
        m.sentAt = ts;
        messages.add(m);
        return m;
    }

    private Message out(long cid, String body, long ts) {
        Message m = Fakes.msg(cid, Direction.OUTGOING, body);
        m.sentAt = ts;
        messages.add(m);
        return m;
    }

    @Test public void targetedReplyNamesAndQuotesTheAddressedMessage() {
        group("Family group");
        in(1, "Chidi", "k-c", "match tickets are out for saturday", now - 60_000);
        in(1, "Musa", "k-m", "Spencer are you still coming to the match?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("count me in");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);

        String sys = p.lastRequest.system;
        assertTrue("the burst topic is stated", sys.contains("What this burst is about: match"));
        assertTrue("the direct-address line rides the wire",
            sys.contains("Musa is talking to YOU directly"));
        String task = p.lastRequest.task.text;
        assertTrue("the task quotes the exact message being answered",
            task.contains("Spencer are you still coming to the match?"));
        assertTrue("the targeted burst line is attributed to its real sender",
            task.contains("from Musa — is the message addressed to you"));
    }

    @Test public void burstTargetAnnotationLeadsTheAnswer() {
        group("Family group");
        in(1, "Chidi", "k-c", "match tickets are out for saturday", now - 60_000);
        in(1, "Ada", "k-a", "want one Chidi?", now - 30_000);
        in(1, "Musa", "k-m", "Spencer are you coming?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("yes");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        String task = p.lastRequest.task.text;
        assertTrue("the targeted burst line is called out for the model",
            task.contains("from Musa — is the message addressed to you"));
    }

    @Test public void optionalDraftsCarryJoinInHonestyNotAnAnswer() {
        group("Family group");
        out(1, "that match was wild", now - 600_000);
        in(1, "Musa", "k-m", "next match is saturday morning people", now - 60_000);
        in(1, "Chidi", "k-c", "hope the pitch is dry by saturday", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("I'll be there");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        assertTrue("the model knows this is only joining in, not an answer",
            p.lastRequest.system.contains("Nobody asked for you by name"));
    }

    @Test public void sameNameMembersAreDisambiguatedOnTheWire() {
        group("Family group");
        in(1, "Chidi", "person-1", "agbero tickets lol", now - 40_000);
        in(1, "Chidi", "person-2", "Spencer you dey come?", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("coming");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        String sys = p.lastRequest.system;
        assertTrue(sys.contains("share a FIRST NAME"));
        assertTrue(sys.contains("DIFFERENT people"));
        assertTrue("\"Chidi 2\" answers as the addressed member",
            p.lastRequest.system.contains("Chidi 2 is talking to YOU directly"));
    }

    @Test public void topicChangeKeepsTheOldTopicVisible() {
        group("Family group");
        // first burst sets the topic
        in(1, "Musa", "k-m", "match tickets are out, get match tickets", now - 300_000);
        in(1, "Chidi", "k-c", "match tickets done for saturday stadium", now - 250_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        svc.generateForContact(1);
        // owner answers, then the room moves on
        out(1, "got mine", now - 200_000);
        in(1, "Musa", "k-m", "dinner plans changed to sushi place", now - 10_000);
        in(1, "Ada", "k-a", "sushi dinner confirmed friday night", now - 5_000);
        assertTrue(svc.generateForContact(1).ok);
        assertTrue("the previous topic is named on the wire",
            p.lastRequest.system.contains("The topic just CHANGED (before: match"));
    }

    @Test public void oneOnOnePromptsCarryZeroNewBytes() {
        contacts.put(Fakes.contact(2, "Ada"));
        Message m = Fakes.msg(2, Direction.INCOMING, "the match tickets are out");
        m.senderName = "Ada";
        m.sentAt = now - 5_000;
        messages.add(m);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(2).ok);
        String sys = p.lastRequest.system;
        String task = p.lastRequest.task.text;
        assertFalse(sys.contains("What this burst is about"));
        assertFalse(sys.contains("talking to YOU directly"));
        assertFalse(sys.contains("share a FIRST NAME"));
        assertFalse(task.contains("is the message addressed to you"));
        assertTrue(task.contains("The message you're replying to — Ada's latest"));
    }

    @Test public void memberRosterUsesStableLabelsNotJustFirstSeenNames() {
        group("Family group");
        in(1, "234801", "person-9", "ticketing wahala", now - 80_000);
        in(1, "Amara", "person-9", "Spencer site is up now o", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("checking");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        assertTrue("the refined (later) name labels the member, not the stale one",
            p.lastRequest.system.contains("Amara is talking to YOU directly"));
    }

    @Test public void activeSubtopicNarrowsTheTopicOnTheWire() {
        group("Family group");
        in(1, "Musa", "k-m", "match tickets are out", now - 30_000);
        in(1, "Chidi", "k-c", "Spencer the ticketing site is crashing o", now - 5_000);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("reloading it");
        DraftService svc = service(new Fakes.GatewayFake(p));
        assertTrue(svc.generateForContact(1).ok);
        assertTrue(p.lastRequest.system.contains("Right now it narrowed to: ticketing"));
    }
}
