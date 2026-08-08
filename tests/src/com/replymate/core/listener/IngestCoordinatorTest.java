package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class IngestCoordinatorTest {

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
                                 String text, long ts, boolean group, boolean attach) {
        NotifEvent e = new NotifEvent();
        e.channel = ch;
        e.conversationTitle = conv;
        e.senderName = sender;
        e.ownerName = owner;
        e.text = text;
        e.timestampMs = ts;
        e.group = group;
        e.hasAttachment = attach;
        return e;
    }

    private static List<NotifEvent> list(NotifEvent... events) {
        return new ArrayList<NotifEvent>(Arrays.asList(events));
    }

    private static java.util.Set<Channel> allOn() {
        return null;   // null set = no gate (registry normally gates before this layer)
    }

    private static java.util.Set<Channel> only(Channel... channels) {
        return new java.util.HashSet<Channel>(Arrays.asList(channels));
    }

    @Test public void storesIncomingAndAutoDiscoversContactAndPingsOnce() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "you dey?", 1000, false, false),
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "??", 2000, false, false)), allOn());

        assertEquals(2, rep.stored);
        assertEquals(0, rep.duplicates);
        assertEquals(1, rep.pings.size());
        assertEquals("??", rep.pings.get(0).snippet);          // latest incoming wins
        assertEquals("Amara", rep.pings.get(0).displayName);

        Contact created = contacts.all().get(0);
        assertEquals("Amara", created.displayName);
        assertEquals(2, messages.countByContact(created.id));
        List<Message> thread = messages.lastMessages(created.id, 10);
        assertEquals(Direction.INCOMING, thread.get(0).direction);
        assertEquals(com.replymate.core.model.Source.LISTENER, thread.get(0).source);
        assertEquals("2", kv.get(IngestCoordinator.KV_STORED_TOTAL, "0"));
        assertTrue(DiagnosticsRing.lines(kv.get(IngestCoordinator.KV_RING, "")).size() == 1);
    }

    @Test public void repostedNotificationDoesNotDuplicateOldMessages() {
        // WhatsApp re-posts the conversation with message#1+#2 in MessagingStyle:
        IngestReport first = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "m1", 1000, false, false)), allOn());
        assertEquals(1, first.stored);

        IngestReport second = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "m1", 1000, false, false),   // OLD msg again
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "m2", 2000, false, false)), allOn());

        assertEquals(1, second.stored);        // only m2 is new
        assertEquals(1, second.duplicates);    // m1 deduped by content hash
        Contact c = contacts.all().get(0);
        assertEquals(2, messages.countByContact(c.id));   // total still 2, not 3
    }

    @Test public void ownMessagesStoredAsOutgoingNeverPing() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Me", "Me", "my reply", 1000, false, false)), allOn());
        assertEquals(1, rep.stored);
        assertEquals(0, rep.pings.size());
        Contact c = contacts.all().get(0);
        assertEquals(Direction.OUTGOING, messages.lastMessages(c.id, 1).get(0).direction);
    }

    /* -------------------------------------------- P-background-9: react-filter */

    @Test public void reactionNoticesAreDroppedBeforeStorageNeverPing() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "on my way", 1000, false, false),
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me",
                "Reacted ❤️ to “on my way”", 2000, false, false)), allOn());

        assertEquals("only the real message is stored", 1, rep.stored);
        assertEquals(1, rep.filtered);
        assertEquals(1, rep.pings.size());
        assertEquals("on my way", rep.pings.get(0).snippet);
        Contact created = contacts.all().get(0);
        assertEquals(1, messages.countByContact(created.id));
        assertEquals("on my way",
            messages.lastMessages(created.id, 10).get(0).body);
    }

    @Test public void groupsStoredButNeverPing() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "Family", "Ada", "Me", "hello fam", 1000, true, false)), allOn());
        assertEquals(1, rep.stored);
        assertEquals(0, rep.pings.size());
        assertEquals("family", contacts.all().get(0).displayName.equals("Family")
            ? "family" : "fail");   // display name kept
    }

    @Test public void mediaOnlyStoresPlaceholderNoPing() {
        IngestReport rep = engine.handle(list(
            ev(Channel.TELEGRAM, "Sam", "Sam", "Me", null, 1000, false, true)), allOn());
        assertEquals(1, rep.stored);
        assertEquals(0, rep.pings.size());
        Contact c = contacts.all().get(0);
        assertEquals(ListenerFilter.MEDIA_PLACEHOLDER, messages.lastMessages(c.id, 1).get(0).body);
    }

    @Test public void emptyTextWithoutAttachmentFiltered() {
        IngestReport rep = engine.handle(list(
            ev(Channel.TELEGRAM, "Sam", "Sam", "Me", "  ", 1000, false, false)), allOn());
        assertEquals(0, rep.stored);
        assertEquals(1, rep.filtered);
        assertTrue(contacts.all().isEmpty());
    }

    @Test public void watchTogglesGateChannels() {
        IngestReport rep = engine.handle(list(
            ev(Channel.WHATSAPP, "A", "A", "Me", "wa msg", 1000, false, false),
            ev(Channel.TELEGRAM, "B", "B", "Me", "tg msg", 1001, false, false)), only(Channel.TELEGRAM));
        assertEquals(1, rep.stored);            // telegram only
        assertEquals(1, rep.filtered);          // whatsapp gated out
        assertEquals(1, contacts.all().size());
        assertEquals("B", contacts.all().get(0).displayName);
    }

    @Test public void isolationBetweenContactsMaintained() {
        engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "A1", 1000, false, false),
            ev(Channel.TELEGRAM, "Zoe", "Zoe", "Me", "Z1", 1001, false, false)), allOn());
        assertEquals(2, contacts.all().size());
        for (Contact c : contacts.all()) {
            List<Message> thread = messages.lastMessages(c.id, 10);
            assertEquals(1, thread.size());
            if (c.displayName.equals("Amara")) assertEquals("A1", thread.get(0).body);
            else assertEquals("Z1", thread.get(0).body);
        }
    }

    @Test public void unknownConversationStillWorks() {
        IngestReport rep = engine.handle(list(
            ev(Channel.TELEGRAM, null, null, "Me", "hey", 1000, false, false)), allOn());
        assertEquals(1, rep.stored);
        assertEquals(1, rep.pings.size());
        assertTrue(rep.pings.get(0).displayName.length() > 0);
    }

    @Test public void ringCappedAtCap() {
        for (int i = 0; i < 20; i++) {
            engine.handle(list(
                ev(Channel.WHATSAPP, "P" + i, "P" + i, "Me", "m" + i, 1000 + i, false, false)),
                allOn());
        }
        assertTrue(DiagnosticsRing.lines(kv.get(IngestCoordinator.KV_RING, "")).size()
            <= DiagnosticsRing.CAP);
    }

    /* --------------- P-audit-deep: content kinds + media pipeline + identity --------- */

    private static NotifEvent media(Channel ch, String conv, String text,
                                    String mime, String uri,
                                    com.replymate.core.model.ContentKind kind) {
        NotifEvent e = ev(ch, conv, conv, "Me", text, 1000L, false, true);
        e.contentKind = kind;
        e.mediaMime = mime;
        e.mediaUri = uri;
        return e;
    }

    @Test public void mediaOnlyStoredWithKindPlaceholderMimeAndUriNoPing() {
        engine.handle(list(media(Channel.WHATSAPP, "Amara", "📷 Photo",
            "image/jpeg", "content://wa/123", com.replymate.core.model.ContentKind.IMAGE)), allOn());
        Contact c = contacts.all().get(0);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE.placeholder(), m.body);
        assertEquals("image", m.contentKind);
        assertEquals("image/jpeg", m.mediaMime);
        assertEquals("content://wa/123", m.mediaUri);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, m.effectiveKind());
    }

    @Test public void captionedMediaKeepsTheCaptionAndTheKind() {
        engine.handle(list(media(Channel.WHATSAPP, "Amara", "rate this fit",
            "image/jpeg", "content://wa/441", com.replymate.core.model.ContentKind.IMAGE)), allOn());
        Contact c = contacts.all().get(0);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals("rate this fit", m.body);          // the REAL text, answerable
        assertEquals("image", m.contentKind);           // …but honestly flagged as photo
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, m.effectiveKind());
    }

    @Test public void missedCallStoredAsCallEventNoPing() {
        NotifEvent call = media(Channel.WHATSAPP, "Amara", "Missed voice call",
            null, null, com.replymate.core.model.ContentKind.CALL);
        call.hasAttachment = false;
        IngestReport rep = engine.handle(list(call), allOn());
        assertEquals(1, rep.stored);
        assertEquals(0, rep.pings.size());
        Contact c = contacts.all().get(0);
        Message m = messages.lastMessages(c.id, 1).get(0);
        assertEquals("Missed voice call", m.body);
        assertEquals(com.replymate.core.model.ContentKind.CALL, m.effectiveKind());
        assertEquals("", m.mediaUri);      // calls never carry media references
    }

    @Test public void mediaRowsNeverLeakMimeOrUriIntoTextMessages() {
        engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "hello there", 777L, false, false),
            media(Channel.WHATSAPP, "Amara", "🎥 Video", "video/mp4", "content://wa/v9",
                com.replymate.core.model.ContentKind.VIDEO)), allOn());
        Contact c = contacts.all().get(0);
        java.util.List<Message> thread = messages.lastMessages(c.id, 10);
        assertEquals("", thread.get(0).mediaMime);   // text row stays clean
        assertEquals("", thread.get(0).mediaUri);
        assertEquals("video/mp4", thread.get(1).mediaMime);
    }

    @Test public void identicalMediaRepostDedupesAcrossPlaceholderGenerations() {
        IngestReport first = engine.handle(list(media(Channel.WHATSAPP, "Amara", "📷 Photo",
            "image/jpeg", "content://wa/123", com.replymate.core.model.ContentKind.IMAGE)), allOn());
        assertEquals(1, first.stored);
        IngestReport second = engine.handle(list(media(Channel.WHATSAPP, "Amara", "📷 Photo",
            "image/jpeg", "content://wa/123", com.replymate.core.model.ContentKind.IMAGE)), allOn());
        assertEquals(0, second.stored);
        assertEquals(1, second.duplicates);
    }

    @Test public void nativeConversationIdBecomesThePrimaryIdentity() {
        NotifEvent e = ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "hey", 1000L, false, false);
        e.conversationId = "23480@s.whatsapp.net";
        engine.handle(list(e), allOn());
        assertEquals(1, contacts.all().size());
        assertNotNull(contacts.findChannel(Channel.WHATSAPP, "cid:23480@s.whatsapp.net"));
    }

    @Test public void identityUpgradesRelinkWithoutForkingTheContact() {
        // first: legacy app — title key only
        engine.handle(list(
            ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "first", 1000L, false, false)), allOn());
        // later: same chat now publishes its native thread id
        NotifEvent e2 = ev(Channel.WHATSAPP, "Amara", "Amara", "Me", "second", 2000L, false, false);
        e2.conversationId = "23480@s.whatsapp.net";
        engine.handle(list(e2), allOn());
        assertEquals("no contact fork on identity upgrade", 1, contacts.all().size());
        Contact c = contacts.all().get(0);
        assertEquals(2, countMessages(c.id));
        assertNotNull(contacts.findChannel(Channel.WHATSAPP, "cid:23480@s.whatsapp.net"));
    }

    @Test public void directionFollowsPersonKeysWhenNamesAreAmbiguous() {
        // owner AND a contact both named "Kelechi": the app-published keys decide
        NotifEvent mine = ev(Channel.WHATSAPP, "Kelechi", "Kelechi", "Kelechi", "my own reply", 1000L, false, false);
        mine.senderKey = "jid-owner";
        mine.ownerKey = "jid-owner";
        NotifEvent theirs = ev(Channel.WHATSAPP, "Kelechi", "Kelechi", "Kelechi", "hey from the other kelechi", 2000L, false, false);
        theirs.senderKey = "jid-contact";
        theirs.ownerKey = "jid-owner";
        engine.handle(list(mine, theirs), allOn());
        Contact c = contacts.all().get(0);
        java.util.List<Message> thread = messages.lastMessages(c.id, 10);
        assertEquals(Direction.OUTGOING, thread.get(0).direction);
        assertEquals(Direction.INCOMING, thread.get(1).direction);
    }

    private int countMessages(long contactId) {
        return messages.countByContact(contactId);
    }
}
