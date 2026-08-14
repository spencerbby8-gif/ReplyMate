package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-9: in-chat system/service lines are chrome, never conversation —
 *  and a human sentence that merely TOUCHES the same words must always pass. */
public final class SystemLinesTest {

    /* ----------------------------------------------------------------- drops */

    @Test public void encryptionNoticesDrop() {
        assertTrue(SystemLines.isSystemLine(
            "Messages and calls are end-to-end encrypted. No one outside of this"
                + " chat, not even WhatsApp, can read or listen to them."
                + " Tap to learn more."));
        assertTrue("decoration-prefixed variant",
            SystemLines.isSystemLine("🔒 Messages and calls are end-to-end encrypted."
                + " Tap to learn more."));
    }

    @Test public void securityCodeNoticesDrop() {
        assertTrue(SystemLines.isSystemLine(
            "Your security code with Amara changed. Tap to learn more."));
        assertTrue(SystemLines.isSystemLine(
            "↩️ Your security code with +234 801 234 5678 changed."));
    }

    @Test public void decryptionWaitingPlaceholdersDrop() {
        assertTrue(SystemLines.isSystemLine(
            "Waiting for this message. This may take a while."));
        assertTrue(SystemLines.isSystemLine("⏳ Waiting for this message"));
    }

    @Test public void bareCallCardsDrop() {
        assertTrue(SystemLines.isSystemLine("Missed voice call"));
        assertTrue(SystemLines.isSystemLine("Missed video call"));
        assertTrue(SystemLines.isSystemLine("Missed call"));
        assertTrue(SystemLines.isSystemLine("Missed group voice call"));
        assertTrue(SystemLines.isSystemLine("Missed group video call"));
        assertTrue(SystemLines.isSystemLine("📞 Missed voice call"));
        assertTrue(SystemLines.isSystemLine("📵 Missed call"));
        assertTrue(SystemLines.isSystemLine("Declined call"));
        assertTrue(SystemLines.isSystemLine("Call declined"));
        assertTrue(SystemLines.isSystemLine("No answer"));
        assertTrue("trailing parenthetical tolerated",
            SystemLines.isSystemLine("Missed voice call (tap to call back)"));
    }

    /* ----------------------------------------------------- real messages pass */

    @Test public void humanSentencesAboutCallsPass() {
        assertFalse("a person apologising is NOT a call card",
            SystemLines.isSystemLine("sorry i missed your call 🙏"));
        assertFalse(SystemLines.isSystemLine("you missed the call with Tunde yesterday"));
        assertFalse(SystemLines.isSystemLine("did you see my missed calls"));
        assertFalse(SystemLines.isSystemLine("call me back when you can"));
    }

    @Test public void humanSentencesAboutEncryptionPass() {
        assertFalse(SystemLines.isSystemLine("are these chats encrypted though?"));
        assertFalse(SystemLines.isSystemLine(
            "so the app says messages are end-to-end encrypted, wild"));
    }

    @Test public void ordinaryTextsPass() {
        assertFalse(SystemLines.isSystemLine("you still coming tonight?"));
        assertFalse(SystemLines.isSystemLine("photo"));   // bare human word, not a card
        assertFalse(SystemLines.isSystemLine("no answer from my side either lol"));
    }

    @Test public void emptiesAreSafe() {
        assertFalse(SystemLines.isSystemLine(null));
        assertFalse(SystemLines.isSystemLine(""));
        assertFalse(SystemLines.isSystemLine("   "));
    }

    /* ------------------------------------------------------ sender identity */

    /* ----------------------------- P-bg-10: announcement ids + time-tailed cards */

    @Test public void announcementNativeIdsAreDetected() {
        assertTrue(SystemLines.isAnnouncementId("120363406601234567@newsletter"));
        assertTrue(SystemLines.isAnnouncementId("2348012345678-1610000000@broadcast"));
        assertTrue(SystemLines.isAnnouncementId("status@broadcast"));
        assertTrue("case-insensitive", SystemLines.isAnnouncementId("XX@NEWSLETTER"));
        assertFalse("a real 1:1 thread id is not an announcement",
            SystemLines.isAnnouncementId("2348012345678@s.whatsapp.net"));
        assertFalse("telegram-style ids are not announcement-shaped",
            SystemLines.isAnnouncementId("-1001234567890"));
        assertFalse(SystemLines.isAnnouncementId(null));
        assertFalse(SystemLines.isAnnouncementId(""));
    }

    @Test public void timeTailedCallCardsDropButConversationDoesNot() {
        assertTrue(SystemLines.isSystemLine("Missed voice call at 2:14 pm"));
        assertTrue(SystemLines.isSystemLine("Missed call at 9:01 AM"));
        assertFalse("a person's sentence ABOUT a time-stamped call is chat, not a card",
            SystemLines.isSystemLine("sorry, missed your call at 2 though 🙏"));
        assertFalse(SystemLines.isSystemLine("call me at 5 when you land"));
    }

    @Test public void senderIdentityRule() {
        assertFalse(SystemLines.hasSenderIdentity(null, null, null));
        assertFalse(SystemLines.hasSenderIdentity("", " ", "\t"));
        assertTrue(SystemLines.hasSenderIdentity("Amara", null, null));
        assertTrue(SystemLines.hasSenderIdentity(null, "person-key-9", null));
        assertTrue(SystemLines.hasSenderIdentity(null, null, "tel:+234…"));
    }
}
