package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContentKind;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-7 end-to-end at the pipeline seam: EVERY non-message noise
 *  class (backup cards, service summaries, announcements, group/broadcast,
 *  missed calls, reaction-only, media-only-first-contact) is stopped before a
 *  ReplyMate conversation or draft can exist — while a real 1:1 message always
 *  flows through. Fixtures go through the REAL parsers where possible. */
public final class NoiseIngestTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;
    private MessagingStyleParser waParser;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
        waParser = new MessagingStyleParser(Channel.WHATSAPP);
    }

    private static Set<Channel> allOn() {
        Set<Channel> s = new HashSet<Channel>();
        s.addAll(Arrays.asList(Channel.values()));
        return s;
    }

    private static NotifEvent ev(String conv, String sender, String text,
                                 boolean group, boolean attach, ContentKind kind) {
        NotifEvent e = new NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.conversationTitle = conv;
        e.senderName = sender;
        e.ownerName = "Me";
        e.text = text;
        e.timestampMs = 1000L;
        e.group = group;
        e.hasAttachment = attach;
        e.contentKind = kind;
        return e;
    }

    private static List<NotifEvent> list(NotifEvent... events) {
        return new ArrayList<NotifEvent>(Arrays.asList(events));
    }

    /* --------------------------------------- parser-level service-noise drops */

    @Test public void groupSummaryNumbersNeverParseIntoMessages() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "WhatsApp";
        raw.text = "23 new messages";
        NotifParser.Result r = waParser.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertTrue("either gate may fire first — both stop it pre-conversation",
            r.reason.contains("service summary") || r.reason.contains("self-status"));
    }

    @Test public void youHaveNewMessagesIsAppChromeNotAMessage() {
        for (String t : new String[] {"You have new messages",
                "You have 3 new messages", "You may have new messages"}) {
            RawNotif raw = ParserFixtures.raw("com.whatsapp");
            raw.title = "WhatsApp";
            raw.text = t;
            NotifParser.Result r = waParser.parse(raw);
            assertEquals("must ignore: " + t, NotifParser.Result.Kind.IGNORE, r.kind);
        }
    }

    @Test public void backupCardsAreAppSelfStatus() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "WhatsApp";
        raw.text = "Backing up messages";
        raw.progressMax = 100;
        NotifParser.Result r = waParser.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertTrue(r.reason.contains("self-status"));

        RawNotif drive = ParserFixtures.raw("com.whatsapp");
        drive.title = "WhatsApp";
        drive.text = "Google Drive backup in progress";
        assertEquals(NotifParser.Result.Kind.IGNORE, waParser.parse(drive).kind);
    }

    @Test public void endToEndEncryptionNoticeIsServiceNoise() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "WhatsApp";
        raw.text = "Messages are end-to-end encrypted. No one outside of this"
            + " chat can read them.";
        assertEquals(NotifParser.Result.Kind.IGNORE, waParser.parse(raw).kind);
    }

    @Test public void tapToViewAndUnreadDigestsNeverParse() {
        for (String t : new String[] {"Tap to view", "12 new messages in 3 chats"}) {
            RawNotif raw = ParserFixtures.raw("org.telegram.messenger");
            raw.title = "Telegram";
            raw.text = t;
            assertEquals("must ignore: " + t,
                NotifParser.Result.Kind.IGNORE,
                new MessagingStyleParser(Channel.TELEGRAM).parse(raw).kind);
        }
    }

    /* ------------------------------------------- ingest-level conversation ban */

    @Test public void groupBroadcastNeverCreatesConversationOrPing() {
        NotifParser.Result parsed = waParser.parse(
            ParserFixtures.styleGroup("com.whatsapp", "Family", "Ada", "hello fam"));
        assertEquals(NotifParser.Result.Kind.EVENTS, parsed.kind);   // parser yields…
        IngestReport rep = engine.handle(parsed.events, allOn());
        assertEquals(0, rep.stored);                                  // …ingest stops it
        assertTrue(rep.filtered >= 1);
        assertEquals(0, rep.pings.size());
        assertTrue("no home-list entry for a group", contacts.all().isEmpty());
    }

    @Test public void missedCallNoiseNeverCreatesConversation() {
        IngestReport rep = engine.handle(list(
            ev("Tobi", "Tobi", "Missed voice call", false, false, ContentKind.CALL)),
            allOn());
        assertEquals(0, rep.stored);
        assertTrue(rep.filtered >= 1);
        assertEquals(0, rep.pings.size());
        assertTrue(contacts.all().isEmpty());
    }

    @Test public void reactionOnlyNoticesNeverCreateConversation() {
        IngestReport rep = engine.handle(list(
            ev("Tobi", "Tobi", "Reacted 👍 to “on my way”", false, false,
                ContentKind.TEXT)), allOn());
        assertEquals(0, rep.stored);
        assertTrue(rep.filtered >= 1);
        assertTrue(contacts.all().isEmpty());
    }

    @Test public void voiceNoteEchoFromAFreshSenderCreatesNothing() {
        IngestReport rep = engine.handle(list(
            ev("Ada", "Ada", "🎤 Voice message (0:07)", false, true, ContentKind.VOICE)),
            allOn());
        assertEquals(0, rep.stored);
        assertTrue(contacts.all().isEmpty());
        assertEquals(0, rep.pings.size());
    }

    /* ---------------------------------------------- real messages stay perfect */

    @Test public void aRealFirstDirectMessageStillCreatesTheConversation() {
        NotifParser.Result parsed = waParser.parse(ParserFixtures.styleDm(
            "com.whatsapp", "Tobi", "you still coming tonight?", "bring the charger abeg"));
        assertEquals(NotifParser.Result.Kind.EVENTS, parsed.kind);
        IngestReport rep = engine.handle(parsed.events, allOn());
        assertEquals(2, rep.stored);
        assertEquals(1, rep.pings.size());
        assertEquals(1, contacts.all().size());
        Contact c = contacts.all().get(0);
        assertEquals("Tobi", c.displayName);
        assertEquals("the LATEST message is what a first draft would answer",
            "bring the charger abeg",
            messages.lastMessages(c.id, 1).get(0).body);
    }

    @Test public void aCaptionedPhotoFromAFreshSenderIsARealMessage() {
        IngestReport rep = engine.handle(list(
            ev("Ada", "Ada", "Bro check this 📷", false, true, ContentKind.IMAGE)),
            allOn());
        assertEquals("human captions create conversations", 1, rep.stored);
        assertEquals(1, contacts.all().size());
        assertEquals("captions stay verbatim(context for drafts)",
            "Bro check this 📷",
            messages.lastMessages(contacts.all().get(0).id, 1).get(0).body);
        assertEquals("media+caption informs but never proactive-pings per policy",
            0, rep.pings.size());
    }
}
