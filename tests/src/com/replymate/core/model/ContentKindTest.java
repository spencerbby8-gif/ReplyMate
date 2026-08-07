package com.replymate.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-audit-deep: the content-kind model itself — wire mapping, unreadable/media
 *  flags, and LEGACY-row inference through Message.effectiveKind(). */
public class ContentKindTest {

    @Test public void everyKindHasAStableWireAndLabel() {
        for (ContentKind k : ContentKind.values()) {
            assertNotNull(k.wire);
            assertFalse(k.wire.isEmpty());
            assertNotNull(k.label());
            if (k != ContentKind.TEXT) {
                assertEquals(k, ContentKind.fromWire(k.wire));
            } else {
                // empty content_type (legacy row) maps to null, not TEXT
                assertNull(ContentKind.fromWire(""));
                assertEquals(ContentKind.TEXT, ContentKind.fromWire("text"));
            }
        }
        assertEquals(ContentKind.UNKNOWN, ContentKind.fromWire("hologram")); // future kinds degrade
    }

    @Test public void unreadableAndMediaSetsAreExact() {
        assertFalse(ContentKind.TEXT.isUnreadable());
        assertFalse(ContentKind.TEXT.isMedia());
        assertTrue(ContentKind.IMAGE.isMedia() && ContentKind.IMAGE.isUnreadable());
        assertTrue(ContentKind.VIDEO.isMedia() && ContentKind.VIDEO.isUnreadable());
        assertTrue(ContentKind.VOICE.isMedia() && ContentKind.VOICE.isUnreadable());
        assertTrue(ContentKind.STICKER.isMedia() && ContentKind.STICKER.isUnreadable());
        assertTrue(ContentKind.UNKNOWN.isMedia() && ContentKind.UNKNOWN.isUnreadable());
        assertTrue("calls are unreadable but NOT media", ContentKind.CALL.isUnreadable());
        assertFalse(ContentKind.CALL.isMedia());
    }

    @Test public void legacyRowsInferFromStoredBodyShape() {
        Message old = new Message();
        old.body = "[media/voice — open in chat app]";       // pre-0.9.0 placeholder
        old.contentKind = "";
        assertEquals(ContentKind.UNKNOWN, old.effectiveKind());

        Message text = new Message();
        text.body = "hello there";
        assertEquals(ContentKind.TEXT, text.effectiveKind());

        Message voicePh = new Message();
        voicePh.body = ContentKind.VOICE.placeholder();
        assertEquals(ContentKind.VOICE, voicePh.effectiveKind());
    }

    @Test public void explicitKindWinsOverBodyShape() {
        Message m = new Message();
        m.contentKind = ContentKind.IMAGE.wire;
        m.body = "rate this fit";             // captioned photo
        assertEquals(ContentKind.IMAGE, m.effectiveKind());
    }
}
