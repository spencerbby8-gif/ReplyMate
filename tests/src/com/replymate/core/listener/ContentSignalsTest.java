package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-audit-deep: content-kind detection — evidence-based (MIME first, then the apps'
 *  canonical fallback shapes), decoupled from source identity, tuned so real
 *  human-typed words are NEVER demoted to media. */
public class ContentSignalsTest {

    /* ------------------------------------------------- MIME is the strongest signal */

    @Test public void mimeDecidesTheFamily() {
        assertEquals(ContentKind.IMAGE, ContentSignals.classify("image/jpeg", true, "📷 Photo"));
        assertEquals(ContentKind.VIDEO, ContentSignals.classify("video/mp4", true, "🎥 Video"));
        assertEquals(ContentKind.AUDIO, ContentSignals.classify("audio/mpeg", true, "🎵 Audio"));
        assertEquals(ContentKind.UNKNOWN, ContentSignals.classify("application/pdf", true, "📄 Document"));
        assertEquals(ContentKind.UNKNOWN, ContentSignals.classify("application/pdf", true, null));
    }

    @Test public void voiceNoteIsAudioRefinedByTheAppText() {
        assertEquals("voice notes ride on audio/*; the app text decides",
            ContentKind.VOICE,
            ContentSignals.classify("audio/ogg; codecs=opus", true, "🎤 Voice message (0:07)"));
        assertEquals(ContentKind.AUDIO, ContentSignals.classify("audio/aac", true, "Blessing – new song.mp3"));
        assertEquals(ContentKind.AUDIO, ContentSignals.classify("audio/ogg", true, null));
    }

    @Test public void stickerNeedsTheAppToSaySo() {
        assertEquals(ContentKind.STICKER, ContentSignals.classify("image/webp", true, "Sticker"));
        assertEquals("webp alone is not proof of a sticker (photos can be webp)",
            ContentKind.IMAGE, ContentSignals.classify("image/webp", true, "look at this"));
        assertEquals(ContentKind.IMAGE, ContentSignals.classify("image/webp", true, null));
    }

    /* -------------------------------- fallback shapes (single-shot / no MIME paths) */

    @Test public void emojiAndMultiwordShapesAreTrustedWithoutAttachmentFlags() {
        assertEquals(ContentKind.IMAGE, ContentSignals.classify(null, false, "📷 Photo"));
        assertEquals(ContentKind.VOICE, ContentSignals.classify(null, false, "Voice message"));
        assertEquals(ContentKind.VOICE, ContentSignals.classify(null, false, "🎤 Voice message (0:12)"));
        assertEquals(ContentKind.IMAGE, ContentSignals.classify(null, false, "sent a photo"));
        assertEquals(ContentKind.VIDEO, ContentSignals.classify(null, false, "sent a video"));
    }

    @Test public void bareHumanWordsNeverBecomeMediaWithoutAttachmentEvidence() {
        // a human can literally type "photo" / "video" / "audio" as a real message
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "photo"));
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "video"));
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "sticker"));
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "Rate my audio setup"));
        // …but WITH attachment evidence the same bare word is the app's fallback label
        assertEquals(ContentKind.IMAGE, ContentSignals.classify(null, true, "photo"));
        assertEquals(ContentKind.VIDEO, ContentSignals.classify(null, true, "video"));
        assertEquals(ContentKind.STICKER, ContentSignals.classify(null, true, "sticker"));
    }

    @Test public void realSentencesStayTextEvenWhenTheyMentionMedia() {
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "send me the photo when you can"));
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "did you see that video i sent"));
        assertEquals(ContentKind.TEXT, ContentSignals.classify(null, false, "I love that picture"));
    }

    @Test public void gifAndVideoShapes() {
        assertEquals("bare \"GIF\" could be human-typed — needs attachment evidence",
            ContentKind.TEXT, ContentSignals.classify(null, false, "GIF"));
        assertEquals("a labelled GIF attachment is animated content → video-kind",
            ContentKind.VIDEO, ContentSignals.classify(null, true, "GIF"));
        assertEquals("an actual image/gif MIME keeps it in the image family",
            ContentKind.IMAGE, ContentSignals.classify("image/gif", true, "GIF"));
        assertEquals(ContentKind.VIDEO, ContentSignals.classify(null, false, "🎥 Video"));
    }

    /* ------------------------------------------------------------ normalization */

    @Test public void normalizeStripsDurationsAndVariationSelectors() {
        assertEquals("🎤 voice message", ContentSignals.normalize("🎤 Voice message (0:07)"));
        assertEquals(ContentSignals.normalize("voice message"),
            ContentSignals.normalize("voice  message\n"));
    }

    /* ----------------------------------------------------------------- call events */

    @Test public void callOutcomesDetectedFromAppText() {
        assertTrue(ContentSignals.isCallEvent("Missed voice call"));
        assertTrue(ContentSignals.isCallEvent("Missed video call"));
        assertTrue(ContentSignals.isCallEvent("missed call"));
        assertFalse("ringing/ongoing state is never an event",
            ContentSignals.isCallEvent("Ongoing voice call"));
        assertFalse(ContentSignals.isCallEvent("Incoming voice call"));
        assertFalse(ContentSignals.isCallEvent("call me when you land"));
        assertFalse(ContentSignals.isCallEvent(null));
    }

    /* ------------------------------------------------------------- placeholders */

    /* ---------------------------------------------- P-background-9: reactions */

    @Test public void reactionNoticesAreNotMessages() {
        // WhatsApp / Signal shapes (quoted target message)
        assertTrue(ContentSignals.isReactionNotice("Reacted ❤️ to “on my way”"));
        assertTrue(ContentSignals.isReactionNotice("Reacted 👍 to \"ok, 7 it is\""));
        assertTrue(ContentSignals.isReactionNotice("reacted 😂 to “you serious??”"));
        // Instagram / Messenger shapes
        assertTrue(ContentSignals.isReactionNotice("Liked your message"));
        assertTrue(ContentSignals.isReactionNotice("loved your message"));
    }

    @Test public void humanWordsAboutReactingStayMessages() {
        assertFalse("no quote after 'to' ⇒ a real sentence",
            ContentSignals.isReactionNotice("I reacted to the news so fast lol"));
        assertFalse("reacted as a verb mid-sentence, not the app notice",
            ContentSignals.isReactionNotice("she reacted badly when I told her"));
        assertFalse("mentions liking, not the canned notice",
            ContentSignals.isReactionNotice("I really liked your message from yesterday"));
        assertFalse(ContentSignals.isReactionNotice("reacted"));
        assertFalse(ContentSignals.isReactionNotice("Reacted to our plan yesterday — can't wait"));
        assertFalse(ContentSignals.isReactionNotice(null));
        assertFalse(ContentSignals.isReactionNotice(""));
    }

    @Test public void everyNonTextKindHasAPlaceholderThatIsRecognizedBack() {
        for (ContentKind k : ContentKind.values()) {
            if (k == ContentKind.TEXT) continue;
            String ph = k.placeholder();
            assertFalse(k.wire, ph.isEmpty());
            assertTrue(k.wire + " placeholder must round-trip",
                ContentSignals.isPlaceholder(ph));
        }
        assertTrue(ContentSignals.isPlaceholder(ContentKind.LEGACY_PLACEHOLDER));
        assertFalse(ContentSignals.isPlaceholder("did you see my message?"));
        assertFalse(ContentSignals.isPlaceholder(null));
    }

    @Test public void classifyAndPlaceholderAreCoherent() {
        // whatever classify tells us, its placeholder must never be misread as text
        ContentKind k = ContentSignals.classify("video/mp4", true, "🎥 Video");
        assertEquals(ContentKind.VIDEO, k);
        assertTrue(ContentSignals.isPlaceholder(k.placeholder()));
    }
}
