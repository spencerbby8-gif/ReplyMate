package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

/** The shared MessagingStyle parser behind the FULL-support apps. */
public class MessagingStyleParserTest {

    private final MessagingStyleParser parser = new MessagingStyleParser(Channel.WHATSAPP);

    @Test public void oneEventPerHistoryEntry() {
        RawNotif raw = ParserFixtures.styleDm("com.whatsapp", "Amara",
            ParserFixtures.T_GREET, ParserFixtures.T_FOLLOW);
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(2, r.events.size());
        NotifEvent first = r.events.get(0);
        assertEquals(Channel.WHATSAPP, first.channel);
        assertEquals("com.whatsapp", first.packageName);
        assertEquals("Amara", first.conversationTitle);
        assertEquals("Amara", first.senderName);
        assertEquals("Me", first.ownerName);
        assertEquals(ParserFixtures.T_GREET, first.text);
        assertEquals(1000L, first.timestampMs);
        assertFalse(first.hasAttachment);
    }

    @Test public void mediaEntryKeepsAttachmentMarkerAndMayLackText() {
        RawNotif raw = ParserFixtures.raw("org.telegram.messenger");
        raw.title = "Sam";
        raw.messages.add(ParserFixtures.msg(null, 3000L, "Sam", true));
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertTrue(r.events.get(0).hasAttachment);
        assertNull(r.events.get(0).text);
        assertEquals(3000L, r.events.get(0).timestampMs);
    }

    @Test public void missingEntryTimestampFallsBackToPostTime() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.postTimeMs = 4242L;
        raw.title = "Ada";
        raw.messages.add(ParserFixtures.msg("hi", 0L, "Ada", false));
        NotifParser.Result r = parser.parse(raw);
        assertEquals(4242L, r.events.get(0).timestampMs);
    }

    @Test public void singleShotWithoutHistoryUsesTitleAndText() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.text = ParserFixtures.T_GREET;
        raw.postTimeMs = 9000L;
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(1, r.events.size());
        assertEquals("Amara", r.events.get(0).senderName);
        assertEquals(ParserFixtures.T_GREET, r.events.get(0).text);
    }

    @Test public void bigTextUsedWhenTextMissing() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.bigText = ParserFixtures.T_FOLLOW;
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(ParserFixtures.T_FOLLOW, r.events.get(0).text);
    }

    @Test public void groupFlagTriState() {
        RawNotif raw = ParserFixtures.styleDm("com.whatsapp", "Ada", "hi", "yo");
        raw.group = null;                                    // not declared
        assertFalse(parser.parse(raw).events.get(0).group);  // default direct
        raw.group = Boolean.TRUE;
        assertTrue(parser.parse(raw).events.get(0).group);
    }

    @Test public void nothingReadableIsIgnored() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");   // call/progress style notif
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertNotNull(r.reason);
    }

    @Test public void nullRawFailsInsteadOfThrowing() {
        NotifParser.Result r = parser.parse(null);
        assertEquals(NotifParser.Result.Kind.FAIL, r.kind);
        assertNotNull(r.reason);
    }

    @Test public void blankTextIsNotContent() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Ada";
        raw.text = "   ";
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
    }

    @Test public void callCategoryIsIgnoredNotIngested() {
        // "WhatsApp · Ongoing call" looks like title+text but is NOT a message.
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "WhatsApp";
        raw.text = "Ongoing voice call";
        raw.category = "call";
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertTrue(r.reason.contains("call"));
    }

    @Test public void progressAndStatusCategoriesAlsoIgnored() {
        for (String cat : new String[] {"progress", "status", "sys", "reminder", "alarm"}) {
            RawNotif raw = ParserFixtures.raw("org.telegram.messenger");
            raw.title = "Telegram";
            raw.text = "Backing up chats…";
            raw.category = cat;
            assertEquals(cat, NotifParser.Result.Kind.IGNORE, parser.parse(raw).kind);
        }
    }

    @Test public void msgAndSocialAndMissingCategoryPassTheGate() {
        for (String cat : new String[] {null, "msg", "social"}) {
            RawNotif raw = ParserFixtures.raw("com.whatsapp");
            raw.title = "Ada";
            raw.text = ParserFixtures.T_GREET;
            raw.category = cat;
            assertEquals("category " + cat, NotifParser.Result.Kind.EVENTS, parser.parse(raw).kind);
        }
    }
}
