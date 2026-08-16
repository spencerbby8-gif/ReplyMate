package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ContactService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: UNKNOWN fails CLOSED at ingest (no contact, no row, no
 *  ping, no ledger line — never becomes a normal message); announcements are
 *  stored as attributed context but can never ping a burst/draft; every stored
 *  row carries WHAT it was plus its own conversation identity (schema v9). */
public final class FailClosedIngestTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
    }

    private static NotifEvent ev(Channel ch, String conv, String sender, String owner,
                                 String text, long ts, boolean group) {
        NotifEvent e = new NotifEvent();
        e.channel = ch;
        e.conversationTitle = conv;
        e.senderName = sender;
        e.ownerName = owner;
        e.text = text;
        e.timestampMs = ts;
        e.group = group;
        return e;
    }

    private static List<NotifEvent> list(NotifEvent... events) {
        return new ArrayList<NotifEvent>(Arrays.asList(events));
    }

    @Test public void unknownFailsClosedBeforeAnythingExists() {
        // No sender identity, no conversation identity — the announcement-list /
        // stray-summary shape on a watched package. It must NOT become a normal
        // message; it stops before contact, row, ping, or ledger.
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, null, null, null, "tap to view your messages",
                1000, false)), null);

        assertEquals(0, rep.stored);
        assertEquals(1, rep.filtered);
        assertEquals(0, rep.pings.size());
        assertTrue("no contact may be created for an unidentified item",
            contacts.all().isEmpty());
        assertTrue("nothing reached the ledger",
            kv.get(IngestCoordinator.KV_RING, "").isEmpty());
    }

    @Test public void announcementStoresAsContextButNeverPings() {
        // Groups enabled; the item speaks AS the channel itself — context for the
        // conversation, never a reply target: stored, zero pings, honestly stamped.
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        NotifEvent e = ev(Channel.DISCORD, "#announcements", "#announcements", "Me",
            "new rules are live, read them", 1000, true);
        e.conversationId = "999000111";

        IngestReport rep = engine.handle(list(e), null);

        assertEquals(1, rep.stored);
        assertEquals("an announcement never pings a burst/draft", 0, rep.pings.size());
        Contact c = contacts.all().get(0);
        assertTrue(c.isGroup);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals("announcement", m.itemClass);
        assertEquals("999000111", m.convId);
        assertEquals("#announcements", m.convTitle);
        assertTrue(kv.get(IngestCoordinator.KV_RING, "").contains("[announcement]"));
    }

    @Test public void mentionKeepsIdentityStampsAndPings() {
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        kv.put(ProfileService.KEY_NAME, "Spencer");
        NotifEvent e = ev(Channel.WHATSAPP, "Family group", "Musa", "Me",
            "Spencer are you coming?", 1000, true);
        e.conversationId = "fam-1";

        IngestReport rep = engine.handle(list(e), null);

        assertEquals(1, rep.stored);
        assertEquals(1, rep.pings.size());
        Contact c = contacts.all().get(0);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals("mention", m.itemClass);
        assertEquals("fam-1", m.convId);
        assertEquals("Family group", m.convTitle);
    }

    @Test public void realOneOnOneIsStampedAndFlowsThrough() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "you dey?", 1000, false)), null);

        assertEquals(1, rep.stored);
        assertEquals(1, rep.pings.size());
        Contact c = contacts.all().get(0);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals("real_1to1", m.itemClass);
        assertEquals("Amara", m.convTitle);
        assertTrue(kv.get(IngestCoordinator.KV_RING, "").contains("[real_1to1]"));
    }

    @Test public void replyCapableServerChannelMessagePingsNormally() {
        // P-19: groups enabled; a Discord #general message WITH a real Reply
        // action is a normal conversation — stored, pings, honestly stamped
        // direct_reply (the announcement demote must NOT touch it).
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        NotifEvent e = ev(Channel.DISCORD, "#general", "#general", "Me",
            "match moved to sunday?", 1000, true);
        e.conversationId = "4477445566";
        e.hasFreeFormReply = true;

        IngestReport rep = engine.handle(list(e), null);

        assertEquals(1, rep.stored);
        assertEquals("a replyable server message pings like any conversation",
            1, rep.pings.size());
        Message m = messages.lastMessages(contacts.all().get(0).id, 1).get(0);
        assertEquals("direct_reply", m.itemClass);
        assertEquals("4477445566", m.convId);
        assertTrue(kv.get(IngestCoordinator.KV_RING, "").contains("[direct_reply]"));
    }

    @Test public void groupDefaultOffStillDropsBeforeTheClassifierMatters() {
        // Regression guard: the P-17 classifier never weakened the group opt-in.
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Family group", "Musa", "Me", "hello fam",
                1000, true)), null);

        assertEquals(0, rep.stored);
        assertEquals(1, rep.filtered);
        assertEquals(0, rep.pings.size());
        assertTrue(contacts.all().isEmpty());
    }
}
