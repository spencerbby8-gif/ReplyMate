package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: THE SEND-INTEGRITY GUARD. Approved text may only fire
 *  through a captured reply target whose identity matches the CONVERSATION
 *  IDENTITY OF THE MESSAGE BEING ANSWERED. The Discord cross-channel borrow
 *  (approval text for an #announcements item sent through a #general reply
 *  action) is the canonical REFUSE shape. */
public final class DeliveryGuardTest {

    @Test public void usableTargetOnTheSameConversationIsVerifiedAllow() {
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.discord", "111", "#general", "",
            "com.discord", "111", "#general");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d.verdict);
        assertTrue(d.allowed());
        assertTrue(d.verified());
    }

    @Test public void theCrossChannelBorrowIsRefused() {
        // THE bug: the answered item came from #announcements (no Reply action of
        // its own); the captured target was captured from the #general
        // notification of the SAME app. Same package, different conversation.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.discord", "222", "#general", "",
            "com.discord", "999", "#announcements");
        assertEquals(DeliveryGuard.Verdict.REFUSE_DIFFERENT_CONVERSATION, d.verdict);
        assertFalse(d.allowed());
        assertFalse(d.verified());
        assertTrue(d.reason.contains("DIFFERENT conversation"));
        assertTrue(d.reason.contains("never borrows"));
    }

    @Test public void aTargetFromAnotherAppIsRefused() {
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.whatsapp", "", "Amara", "Amara",
            "com.discord", "999", "#announcements");
        assertEquals(DeliveryGuard.Verdict.REFUSE_DIFFERENT_APP, d.verdict);
        assertFalse(d.allowed());
        assertTrue(d.reason.contains("different app"));
    }

    @Test public void anUnusableTargetIsRefused() {
        DeliveryGuard.Decision d = DeliveryGuard.check(false,
            "com.discord", "111", "#general", "",
            "com.discord", "111", "#general");
        assertEquals(DeliveryGuard.Verdict.REFUSE_NOT_USABLE, d.verdict);
        assertFalse(d.allowed());
        assertTrue(d.reason.contains("no usable reply target"));
    }

    @Test public void aMessageWithoutConversationIdentityAllowsByContactScope() {
        // 1:1 chat / pre-v9 row: the message carries no conversation identity, so
        // a contact-scoped target is allowed but honestly marked UNVERIFIED.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.whatsapp", "", "", "Amara",
            "com.whatsapp", "", "");
        assertEquals(DeliveryGuard.Verdict.ALLOW_UNVERIFIED, d.verdict);
        assertTrue(d.allowed());
        assertFalse("never a silent verified", d.verified());
        assertTrue(d.reason.contains("contact scope"));
    }

    @Test public void conversationIdEqualityDominatesTitleDrift() {
        // The app renamed the channel between capture and approval; the shortcut
        // id is the stronger identity and must not cause a false refusal.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.discord", "111", "#general", "",
            "com.discord", "111", "#general-renamed");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d.verdict);
        assertTrue(d.verified());
    }

    @Test public void titleTierMatchesWhenNoShortcutIdExists() {
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.slack", "", "team-chat", "",
            "com.slack", "", "team-chat");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d.verdict);
    }

    @Test public void aTargetThatLostAllIdentityCannotMatchAnIdentifiedMessage() {
        // Fail closed: nothing on the target side proves same-conversation.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.discord", "", "", "",
            "com.discord", "999", "#announcements");
        assertEquals(DeliveryGuard.Verdict.REFUSE_DIFFERENT_CONVERSATION, d.verdict);
        assertFalse(d.allowed());
    }

    /* ---------------- P-intelligence-17R: the delivery regression repro ---------------- */

    @Test public void aKnownOneOnOneChatWithTitleOnlyIdentityMUSTAllow() {
        // THE 17R REGRESSION (owner, on-device): a legit 1:1 chat whose identity
        // lives ONLY in the title — the parser stamps the message row with its
        // title-fallback convTitle ("Amara"), the captured target carries the title.
        // Approval must ALLOW (verified), never "DIFFERENT conversation".
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.whatsapp", "", "", "Amara",
            "com.whatsapp", "", "Amara");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d.verdict);
        assertTrue(d.allowed());
        assertTrue(d.verified());
    }

    @Test public void shortcutIdDriftAcrossRepostsMustNotRefuseALegitChat() {
        // Ingest-documented drift: the row was stamped from the FIRST post (no
        // shortcut id yet), the target was captured from the re-post (id present).
        // One-sided id must arbitrate on the shared title, never refuse.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.whatsapp", "2348012345678@s.whatsapp.net", "", "Amara",
            "com.whatsapp", "", "Amara");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d.verdict);
        assertTrue(d.verified());
        // and the reverse one-sided shape
        DeliveryGuard.Decision d2 = DeliveryGuard.check(true,
            "com.whatsapp", "", "", "Amara",
            "com.whatsapp", "2348012345678@s.whatsapp.net", "Amara");
        assertEquals(DeliveryGuard.Verdict.ALLOW, d2.verdict);
    }

    @Test public void differentConversationIdsRefuseEvenWhenTitlesCollide() {
        // Two same-name conversations on one app: ids BOTH present and DIFFERENT
        // must be decisive — the title tier can never resurrect the match.
        DeliveryGuard.Decision d = DeliveryGuard.check(true,
            "com.whatsapp", "111@s.whatsapp.net", "", "Family",
            "com.whatsapp", "222@s.whatsapp.net", "Family");
        assertEquals(DeliveryGuard.Verdict.REFUSE_DIFFERENT_CONVERSATION, d.verdict);
        assertFalse(d.allowed());
    }
}
