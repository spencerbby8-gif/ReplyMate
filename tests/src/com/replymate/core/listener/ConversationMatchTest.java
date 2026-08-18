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

    /* ---------------- P-intelligence-17R: decisive tiers ---------------- */

    @Test public void differentNativeIdsNeverMatchEvenWhenTitlesCollide() {
        // two same-name chats on one app: ids BOTH present and DIFFERENT decide —
        // the title tier can never resurrect the match (cross-send hole closed).
        assertFalse(ConversationMatch.same(
            WA, "111@s.whatsapp.net", "", "Family",
            WA, "222@s.whatsapp.net", "", "Family"));
        assertFalse(ConversationMatch.same(
            WA, "111", "Family", "Family",
            WA, "222", "Family", "Family"));
    }

    @Test public void differentConversationTitlesNeverMatchEvenWhenPlainTitlesCollide() {
        // both channels surfaced under the same plain title ("News") — the
        // conversationTitle tier decides: different ⇒ not the same conversation.
        assertFalse(ConversationMatch.same(
            WA, "", "#general", "News",
            WA, "", "#announcements", "News"));
    }

    @Test public void discordCrossChannelBorrowIsStructurallyImpossible() {
        // P-intelligence-19R §1: Discord fires one notification PER CHANNEL, often
        // inside the same server (same package, sibling #channels). Approving the
        // #general draft must NEVER adopt #random's notification — with native ids,
        // with titles only, and when the server reuses the same plain title.
        final String DISCORD = "com.discord";
        assertFalse(ConversationMatch.same(
            DISCORD, "4477445566", "#general", "",
            DISCORD, "8811223344", "#random", ""));
        assertFalse(ConversationMatch.same(
            DISCORD, "", "#general", "",
            DISCORD, "", "#random", ""));
        assertFalse(ConversationMatch.same(
            DISCORD, "", "#general", "My Server",
            DISCORD, "", "#random", "My Server"));
        // …and its OWN channel re-post (with or without the native id) DOES match:
        assertTrue(ConversationMatch.same(
            DISCORD, "4477445566", "#general", "",
            DISCORD, "4477445566", "#general", ""));
        assertTrue(ConversationMatch.same(
            DISCORD, "", "#general", "",
            DISCORD, "", "#general", ""));
        assertTrue(ConversationMatch.same(
            DISCORD, "4477445566", "#general", "",
            DISCORD, "", "#general", ""));
    }

    @Test public void oneSidedNativeIdArbitratesOnTheSharedTitle() {
        // re-post drift (documented at ingest): the first post may lack the
        // shortcut id the re-post carries — one-sided identity falls through to
        // the shared title instead of refusing a legit chat.
        assertTrue(ConversationMatch.same(
            WA, "234@s.whatsapp.net", "", "Ada",
            WA, "", "", "Ada"));
        assertTrue(ConversationMatch.same(
            WA, "", "", "Ada",
            WA, "234@s.whatsapp.net", "", "Ada"));
    }
}
