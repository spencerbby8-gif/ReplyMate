package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-8: after the original notification is dismissed, approval re-attaches
 *  to the SAME conversation when the app re-posts it under a NEW sbn key — via STRICT
 *  official-identity equality only. A fuzzy match could send a reply into the wrong
 *  chat; these pins make sure matching is strong where strong identity exists and
 *  silent (honest fallback) where it doesn't. */
public class ConversationMatchTest {

    private static final String WA = "com.whatsapp";

    @Test public void sameNativeConversationIdMatchesStrongly() {
        assertTrue(ConversationMatch.same(
            WA, "2348012345678@s.whatsapp.net", "", "",
            WA, "2348012345678@s.whatsapp.net", "", ""));
    }

    @Test public void differentConversationIdsNeverMatch() {
        assertFalse(ConversationMatch.same(
            WA, "111@s.whatsapp.net", "", "",
            WA, "222@s.whatsapp.net", "", ""));
    }

    @Test public void conversationTitleMatchesWhenIdsAreAbsent() {
        assertTrue(ConversationMatch.same(
            WA, "", "\"Family 🏠\"", "",
            WA, "", "\"Family 🏠\"", ""));
    }

    @Test public void plainTitleMatchesOneOnOneChats() {
        assertTrue(ConversationMatch.same(
            WA, "", "", "Ada Lovelace",
            WA, "", "", "Ada Lovelace"));
    }

    @Test public void differentPackageNeverMatches() {
        assertFalse(ConversationMatch.same(
            WA, "111@s.whatsapp.net", "", "",
            "org.telegram.messenger", "111@s.whatsapp.net", "", ""));
    }

    @Test public void emptyIdentityFieldsAreNeverEqual() {
        // two different chats, both without any identity fields ⇒ honest no-match
        assertFalse(ConversationMatch.same(WA, "", "", "", WA, "", "", ""));
        // an empty field on ONE side must never satisfy a field on the other
        assertFalse(ConversationMatch.same(WA, "234@s.whatsapp.net", "", "", WA, "", "", ""));
        assertFalse(ConversationMatch.same(WA, "", "", "", WA, "", "", "Ada"));
    }

    @Test public void strongIdWithoutTitleStillMatchesPlainTitlelessLiveCopy() {
        // stored capture had the native id; the re-post has it too but no title
        assertTrue(ConversationMatch.same(
            WA, "234@s.whatsapp.net", "", "Ada Lovelace",
            WA, "234@s.whatsapp.net", "", ""));
    }

    @Test public void identifiableRequiresAtLeastOneField() {
        assertFalse(ConversationMatch.identifiable("", "", ""));
        assertTrue(ConversationMatch.identifiable("x", "", ""));
        assertTrue(ConversationMatch.identifiable("", "c", ""));
        assertTrue(ConversationMatch.identifiable("", "", "t"));
    }
}
