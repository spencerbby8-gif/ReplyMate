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

    /* ---------------- P-audit-deep: content kinds, media data, identity ---------------- */

    private static RawNotif.Entry mediaEntry(String text, String mime, String uri) {
        RawNotif.Entry e = ParserFixtures.msg(text, 1000L, "Amara", true);
        e.mimeType = mime;
        e.dataUri = uri;
        return e;
    }

    @Test public void photoFallbackClassifiedImageWithMediaReferenceKept() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.messages.add(mediaEntry("📷 Photo", "image/jpeg", "content://wa/123"));
        NotifEvent e = parser.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, e.contentKind);
        assertTrue(e.hasAttachment);
        assertEquals("image/jpeg", e.mediaMime);
        assertEquals("content://wa/123", e.mediaUri);
    }

    @Test public void voiceNoteWithDurationClassifiedVoice() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.messages.add(mediaEntry("🎤 Voice message (0:07)", "audio/ogg; codecs=opus", "content://wa/vn7"));
        assertEquals(com.replymate.core.model.ContentKind.VOICE, parser.parse(raw).events.get(0).contentKind);
    }

    @Test public void videoAndStickerClassifiedFromEvidence() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.messages.add(mediaEntry("🎥 Video", "video/mp4", "content://wa/v9"));
        raw.messages.add(mediaEntry("Sticker", "image/webp", "content://wa/st1"));
        assertEquals(com.replymate.core.model.ContentKind.VIDEO, parser.parse(raw).events.get(0).contentKind);
        assertEquals(com.replymate.core.model.ContentKind.STICKER, parser.parse(raw).events.get(1).contentKind);
    }

    @Test public void captionedPhotoStaysImageButTextIsPreserved() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.messages.add(mediaEntry("rate this fit", "image/jpeg", "content://wa/441"));
        NotifEvent e = parser.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, e.contentKind);
        assertEquals("rate this fit", e.text);   // the caption — real, answerable text
    }

    @Test public void emptyAttachmentIsUnknownMediaNotText() {
        RawNotif raw = ParserFixtures.raw("org.telegram.messenger");
        raw.title = "Sam";
        raw.messages.add(ParserFixtures.msg(null, 1000L, "Sam", true));  // attach flag, no mime
        NotifEvent e = parser.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.UNKNOWN, e.contentKind);
        assertTrue(e.hasAttachment);
    }

    @Test public void singleShotPhotoFallbackDetectedWithoutAnyEntries() {
        // BigPicture-style apps post EXTRA_TEXT "📷 Photo" with NO messages array
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.text = "📷 Photo";
        NotifEvent e = parser.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, e.contentKind);
        assertTrue(e.hasAttachment);
    }

    @Test public void missedCallCategoryProducesCallEvent() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.text = "Missed voice call";
        raw.category = "call";
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(com.replymate.core.model.ContentKind.CALL, r.events.get(0).contentKind);
        assertEquals("Missed voice call", r.events.get(0).text);
    }

    @Test public void nativeIdentityFieldsPropagateUntouched() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");
        raw.title = "Amara";
        raw.conversationId = "2348012345678@s.whatsapp.net";   // WhatsApp's OWN thread id
        raw.ownerKey = "owner-jid";
        RawNotif.Entry e = ParserFixtures.msg(ParserFixtures.T_GREET, 1000L, "Amara", false);
        e.senderKey = "amara-jid";
        e.senderUri = "tel:+2348012345678";
        raw.messages.add(e);
        NotifEvent parsed = parser.parse(raw).events.get(0);
        assertEquals("2348012345678@s.whatsapp.net", parsed.conversationId);
        assertEquals("amara-jid", parsed.senderKey);
        assertEquals("tel:+2348012345678", parsed.senderUri);
        assertEquals("owner-jid", parsed.ownerKey);
    }
}
