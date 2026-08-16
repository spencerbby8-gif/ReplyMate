package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-18 §3: "+ Them" — manually recorded MISSED INCOMING messages.
 *  Stored as INCOMING with MANUALLY_ADDED provenance and correct attribution;
 *  included in burst/topic/context/memory/history like any incoming row; never
 *  fabricated platform metadata; never double-processed when a late notification
 *  of the same words arrives. */
public final class ManualIncomingTest {

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

    private void link(long contactId, Channel ch) {
        ContactChannel cc = new ContactChannel();
        cc.contactId = contactId;
        cc.channel = ch;
        cc.remoteKey = "k" + contactId;
        contacts.channels.add(cc);
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
        svc.setConversationStateService(new ConversationStateService(kv, Fakes.FIXED_CLOCK));
        return svc;
    }

    /* ----------------------------------------------------------- storage law */

    @Test public void storesIncomingAttributedWithManualProvenance() {
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        ManualEntryService.Result r = ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "did the package land?", "");
        assertEquals(ManualEntryService.Outcome.STORED, r.outcome);
        Message m = messages.lastMessages(1, 1).get(0);
        assertEquals(Direction.INCOMING, m.direction);
        assertEquals("did the package land?", m.body);
        assertEquals(Source.MANUALLY_ADDED, m.source);
        assertEquals("Amara", m.senderName);              // 1:1 defaults to the contact
        assertEquals(Channel.WHATSAPP, m.channel);        // real origin (context/dedupe)
        assertEquals(Fakes.FIXED_CLOCK.now(), m.sentAt);
    }

    @Test public void noPlatformMetadataIsFabricated() {
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "hi", "");
        Message m = messages.lastMessages(1, 1).get(0);
        assertNull("never a notification dedupe key", m.notifKey);
        assertEquals("no classification without notification evidence", "", m.itemClass);
        assertEquals("no platform sender id exists", "", m.senderKey);
        assertEquals("", m.mediaMime);
        assertEquals("", m.mediaUri);
    }

    @Test public void conversationIdentityIsCopiedNeverInvented() {
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        // a previously CAPTURED row carries the conversation's real identity
        Message prior = Fakes.msg(1, Direction.INCOMING, "earlier");
        prior.convId = "2348012345678@s.whatsapp.net";
        prior.convTitle = "Amara";
        messages.add(prior);
        ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "later words", "");
        Message m = messages.lastMessages(1, 2).get(1);
        assertEquals("2348012345678@s.whatsapp.net", m.convId);
        assertEquals("Amara", m.convTitle);
    }

    @Test public void identityStaysEmptyWhenNothingWasEverCaptured() {
        contacts.put(Fakes.contact(1, "Amara"));
        ManualEntryService.Result r = ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "hi", "");
        assertEquals(ManualEntryService.Outcome.STORED, r.outcome);
        Message m = messages.lastMessages(1, 1).get(0);
        assertEquals(Channel.MANUAL, m.channel);          // manual-only contact
        assertEquals("", m.convId);                       // unknown stays unknown
        assertEquals("", m.convTitle);
    }

    @Test public void emptyTextAndMissingContactRefuseHonestly() {
        contacts.put(Fakes.contact(1, "Amara"));
        assertEquals(ManualEntryService.Outcome.EMPTY_TEXT,
            ManualEntryService.addIncomingFromThem(
                contacts, messages, Fakes.FIXED_CLOCK, 1, "   ", "").outcome);
        assertEquals(ManualEntryService.Outcome.NO_CONTACT,
            ManualEntryService.addIncomingFromThem(
                contacts, messages, Fakes.FIXED_CLOCK, 99, "hi", "").outcome);
        assertEquals("nothing stored on refusal", 0, messages.countByContact(1));
    }

    @Test public void groupEntriesRequireTheNamedMember() {
        Contact g = Fakes.contact(2, "Family group");
        g.isGroup = true;
        contacts.put(g);
        link(2, Channel.WHATSAPP);
        assertEquals(ManualEntryService.Outcome.NEEDS_SENDER,
            ManualEntryService.addIncomingFromThem(
                contacts, messages, Fakes.FIXED_CLOCK, 2, "match moved", "").outcome);
        assertEquals(0, messages.countByContact(2));
        ManualEntryService.Result ok = ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 2, "match moved", "Chidi");
        assertEquals(ManualEntryService.Outcome.STORED, ok.outcome);
        assertEquals("Chidi", messages.lastMessages(2, 1).get(0).senderName);
    }

    /* ------------------------------------------- immediate conversation effect */

    @Test public void theNextDraftAnswersTheManuallyAddedMessage() {
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did you land safe?"));
        ManualEntryService.Result added = ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "yes o! boarding now", "");

        Fakes.FakeProvider p = Fakes.FakeProvider.returning("nice one!", "safe trip");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);

        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
        assertEquals("the draft answers THAT message",
            added.messageId, drafts.saved.get(0).inReplyToId.longValue());
        String wholeRequest = p.lastRequest.system + " " + p.lastRequest.turns
            + " " + (p.lastRequest.task == null ? "" : p.lastRequest.task.text);
        assertTrue("their words are in the provider request",
            wholeRequest.contains("boarding now"));
        assertEquals("the manual row created no ping/draft side effects of its own",
            1, drafts.saved.size());
    }

    @Test public void groupMentionAddedByHandDrivesReplyRequired() {
        Contact g = Fakes.contact(2, "Family group");
        g.isGroup = true;
        contacts.put(g);
        link(2, Channel.WHATSAPP);
        Message earlier = Fakes.msg(2, Direction.INCOMING, "match tickets are out");
        earlier.senderName = "Musa";
        earlier.sentAt = Fakes.FIXED_CLOCK.now() - 60_000;
        messages.add(earlier);
        ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 2, "Spencer are you still coming?", "Chidi");

        Fakes.FakeProvider p = Fakes.FakeProvider.returning("count me in");
        ConversationStateService convoStates =
            new ConversationStateService(kv, Fakes.FIXED_CLOCK);
        DraftService svc = service(new Fakes.GatewayFake(p));
        svc.setConversationStateService(convoStates);
        Result<DraftOutcome> r = svc.generateForContact(2);

        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, p.calls);
        assertTrue(convoStates.lastFor(2).startsWith("REPLY_REQUIRED|MENTIONED|Chidi"));
    }

    @Test public void aLateNotificationOfTheSameWordsCollapsesNotDuplicates() {
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "see you at 4", "");

        // the platform delivers it late as a real notification: ingest must fold
        // it (near-dup: same contact+channel+direction+exact body, same window) —
        // never double-process, never ping.
        com.replymate.core.listener.NotifEvent e = new com.replymate.core.listener.NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.conversationTitle = "Amara";
        e.senderName = "Amara";
        e.text = "see you at 4";
        e.timestampMs = Fakes.FIXED_CLOCK.now();
        java.util.List<com.replymate.core.listener.NotifEvent> batch =
            new java.util.ArrayList<com.replymate.core.listener.NotifEvent>();
        batch.add(e);
        com.replymate.core.listener.IngestReport rep = new com.replymate.core.listener
            .IngestCoordinator(new ContactService(contacts, Fakes.FIXED_CLOCK),
                messages, new Fakes.KvStoreFake(), Fakes.FIXED_CLOCK, Fakes.NOOP_LOG)
            .handle(batch, null);

        assertEquals(0, rep.stored);
        assertEquals(1, rep.duplicates);
        assertEquals(0, rep.pings.size());
        assertEquals("the manual row remains the single source of truth",
            1, messages.countByContact(1));
        assertEquals(Source.MANUALLY_ADDED,
            messages.lastMessages(1, 1).get(0).source);
    }

    @Test public void memoryAndContextSeeTheManualRowAsTheFreshestIncoming() {
        // Memory recall + the context builder key off the freshest INCOMING of the
        // SAME hot window DraftService feeds them (no source filter — by design
        // the manual row must participate exactly like a captured one).
        contacts.put(Fakes.contact(1, "Amara"));
        link(1, Channel.WHATSAPP);
        Message old = Fakes.msg(1, Direction.INCOMING, "plain older topic words here");
        old.sentAt = Fakes.FIXED_CLOCK.now() - 300_000;
        messages.add(old);
        ManualEntryService.addIncomingFromThem(
            contacts, messages, Fakes.FIXED_CLOCK, 1, "grandma Ngozi called about Enugu", "");

        Message newest = null;
        java.util.List<Message> hot =
            messages.lastMessages(1, com.replymate.core.memory.MemoryService.HOT_WINDOW);
        for (Message m : hot) {
            if (m.direction == Direction.INCOMING) newest = m;
        }
        assertNotNull(newest);
        assertEquals(Source.MANUALLY_ADDED, newest.source);
        assertEquals("grandma Ngozi called about Enugu", newest.body);
    }
}
