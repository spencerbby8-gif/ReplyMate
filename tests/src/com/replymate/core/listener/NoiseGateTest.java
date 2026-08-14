package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-7 directive: non-message noise is identified BEFORE any draft
 *  or conversation can exist — group/broadcast items, missed/declined-call
 *  notices, app service-summary geometry — while real direct messages (incl.
 *  captioned media) are NEVER dropped. Every rule pinned both ways. */
public final class NoiseGateTest {

    private static NotifEvent ev(String text, ContentKind kind, boolean group) {
        NotifEvent e = new NotifEvent();
        e.text = text;
        e.contentKind = kind;
        e.group = group;
        return e;
    }

    /* ---------------------------------------------------------------- drops */

    @Test public void groupAndBroadcastItemsDropBeforeAnythingExists() {
        NoiseGate.Drop d = NoiseGate.evaluate(
            ev("who has the match tickets?", ContentKind.TEXT, true));
        assertTrue(d.drop);
        assertTrue(d.reason.contains("not a direct 1:1"));
    }

    @Test public void missedAndDeclinedCallNoticesDrop() {
        NoiseGate.Drop d = NoiseGate.evaluate(
            ev("Missed voice call", ContentKind.CALL, false));
        assertTrue(d.drop);
        assertTrue(d.reason.contains("not a message"));
    }

    @Test public void directOneToOneMessagesNeverDrop() {
        assertFalse(NoiseGate.evaluate(
            ev("you still coming tonight?", ContentKind.TEXT, false)).drop);
        assertFalse(NoiseGate.evaluate(
            ev("Bro check this 📷", ContentKind.IMAGE, false)).drop);
    }

    @Test public void nullEventsDropSafely() {
        assertTrue(NoiseGate.evaluate(null).drop);
    }

    /* ----------------------------------------- P-bg-10: app service chats */

    private static NotifEvent serviceEv(com.replymate.core.model.Channel ch, String title) {
        NotifEvent e = new NotifEvent();
        e.text = "WhatsApp Web session opened";
        e.contentKind = ContentKind.TEXT;
        e.group = false;
        e.channel = ch;
        e.conversationTitle = title;
        return e;
    }

    @Test public void appLabeledServiceChatsDropBeforeAnythingExists() {
        // the app's OWN system chat is shaped exactly like a 1:1 — only its
        // title gives it away: it IS the app label
        NoiseGate.Drop wa = NoiseGate.evaluate(
            serviceEv(com.replymate.core.model.Channel.WHATSAPP, "WhatsApp"));
        assertTrue(wa.drop);
        assertTrue(wa.reason.contains("service chat"));
        NoiseGate.Drop tg = NoiseGate.evaluate(
            serviceEv(com.replymate.core.model.Channel.TELEGRAM, "telegram"));
        assertTrue("title case-variant of the label still drops", tg.drop);
        assertTrue(tg.reason.contains("service chat"));
    }

    @Test public void realContactsMentioningAppNamesNeverDrop() {
        // a REAL person whose display name happens to contain an app name is a
        // conversation, not a service chat — the label must match the WHOLE title
        assertFalse(NoiseGate.evaluate(
            serviceEv(com.replymate.core.model.Channel.WHATSAPP, "Amara WhatsApp")).drop);
        assertFalse(NoiseGate.evaluate(
            serviceEv(com.replymate.core.model.Channel.WHATSAPP, "Chidi")).drop);
        // and no channel at all must never crash nor drop on the label rule
        NotifEvent bare = ev("you still coming tonight?", ContentKind.TEXT, false);
        assertFalse(NoiseGate.evaluate(bare).drop);
    }

    /* -------------------------------------------------- service-summary chrome */

    @Test public void appServiceSummaryGeometryIsRecognized() {
        String[] chrome = {
            "23 new messages", "1 new messages", "You have new messages",
            "You have 3 new messages", "You may have new messages",
            "You have 12 unread messages", "You have 2 unread chats",
            "5 chats", "2 conversations", "3 unread chats", "in 4 chats",
            "23 new messages in 5 chats", "Tap to view", "Tap to see more"
        };
        for (String s : chrome) {
            assertTrue("must be treated as app chrome: " + s,
                NoiseGate.isSummaryGeometry(s));
        }
    }

    @Test public void humanLookalikesAreNeverSwallowed() {
        String[] human = {
            "she said 23 new messages lol",       // prose, not bare geometry
            "got 5 new messages from work today",
            "are we still on for dinner at 7",
            "photo",                                // a person CAN type this
            "new messages feature is rolling out",
            "2 chats ago you said something else",
            ""
        };
        for (String s : human) {
            assertFalse("must stay a real message: " + s,
                NoiseGate.isSummaryGeometry(s));
        }
    }

    /* ------------------------------------------------ readable-text judgment */

    @Test public void plainTextAndCaptionedMediaAreReadable() {
        assertTrue(NoiseGate.isReadableText(ContentKind.TEXT, "on my way"));
        assertTrue("a human caption IS a real message",
            NoiseGate.isReadableText(ContentKind.IMAGE, "Bro check this 📷"));
    }

    @Test public void bareMediaFallbacksAndCallsAreNotReadable() {
        assertFalse(NoiseGate.isReadableText(ContentKind.IMAGE, "📷 Photo"));
        assertFalse(NoiseGate.isReadableText(ContentKind.VOICE, "🎤 Voice message"));
        assertFalse(NoiseGate.isReadableText(ContentKind.STICKER, "sticker"));
        assertFalse(NoiseGate.isReadableText(ContentKind.CALL, "Missed voice call"));
        assertFalse(NoiseGate.isReadableText(ContentKind.TEXT, ""));
        assertFalse(NoiseGate.isReadableText(null, "   "));
    }
}
