package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

/** The title/text parser behind the PARTIAL (Slack, Discord) and
 *  LIMITED (Instagram, X, TikTok — category-gated) apps. */
public class TitleTextParserTest {

    private final TitleTextParser slack = new TitleTextParser(Channel.SLACK, false);
    private final TitleTextParser instagram = new TitleTextParser(Channel.INSTAGRAM, true);

    @Test public void slackTitleIsTheSenderOrSpace() {
        RawNotif raw = ParserFixtures.titleText(
            "com.Slack", "Ada in #general", ParserFixtures.T_GROUP, "msg");
        NotifParser.Result r = slack.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        NotifEvent e = r.events.get(0);
        assertEquals(Channel.SLACK, e.channel);
        assertTrue("'#…' space infers group", e.group);
        assertEquals(ParserFixtures.T_GROUP, e.text);
    }

    @Test public void senderColonBodySplitWhenNoTitle() {
        RawNotif raw = ParserFixtures.titleText(
            "com.discord", null, "Ada: " + ParserFixtures.T_GREET, "msg");
        NotifParser.Result r = slack.parse(raw);           // same parser family
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals("Ada", r.events.get(0).senderName);
        assertEquals(ParserFixtures.T_GREET, r.events.get(0).text);
    }

    @Test public void colonOnlySplitForPlausibleSenderPrefix() {
        RawNotif raw = ParserFixtures.titleText("com.discord", null,
            "note: keeping this — the colon is far into the message body and not a sender", "msg");
        // prefix <= 40 chars is still treated as sender — document that honestly
        NotifParser.Result r = slack.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertNotNull(r.events.get(0).text);
    }

    @Test public void promoCategoryIgnoredForGatedApps() {
        RawNotif raw = ParserFixtures.titleText(
            "com.instagram.android", "Instagram", ParserFixtures.T_PROMO, "promo");
        NotifParser.Result r = instagram.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertTrue(r.reason.contains("promo"));
    }

    @Test public void msgCategoryAcceptedForGatedApps() {
        RawNotif raw = ParserFixtures.titleText(
            "com.instagram.android", "ada.obi", ParserFixtures.T_GREET, "msg");
        NotifParser.Result r = instagram.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals("ada.obi", r.events.get(0).senderName);
    }

    @Test public void missingCategoryNotPunished() {
        RawNotif raw = ParserFixtures.titleText(
            "com.x.android", "ada_ng", ParserFixtures.T_GREET, null);
        NotifParser.Result r = new TitleTextParser(Channel.X, true).parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
    }

    @Test public void promoStillParsedForUngatedApps() {
        RawNotif raw = ParserFixtures.titleText(
            "com.Slack", "Slackbot", ParserFixtures.T_PROMO, "promo");
        NotifParser.Result r = slack.parse(raw);
        assertEquals("ungated apps keep their noise (user opted in)",
            NotifParser.Result.Kind.EVENTS, r.kind);
    }

    @Test public void messagingStyleHistoryUsedWhenNoPlainText() {
        RawNotif raw = ParserFixtures.raw("com.discord");
        raw.title = "#team";
        raw.category = "msg";
        raw.messages.add(ParserFixtures.msg(ParserFixtures.T_GROUP, 1000L, "Ada", false));
        NotifParser.Result r = slack.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(ParserFixtures.T_GROUP, r.events.get(0).text);
        assertEquals("Ada", r.events.get(0).senderName);
    }

    /* -------------------------------------------- P-background-9: discord noise */

    @Test public void discordAnnouncementChannelIsGroupAndNeverPings() {
        TitleTextParser discord = new TitleTextParser(Channel.DISCORD, false);
        RawNotif raw = ParserFixtures.titleText(
            "com.discord", "#announcements", "server maintenance tonight at 11pm", "msg");
        NotifParser.Result r = discord.parse(raw);
        assertEquals("channel broadcasts still parse (memory can use them)",
            NotifParser.Result.Kind.EVENTS, r.kind);
        assertTrue("'#…' title infers a channel/group", r.events.get(0).group);
        assertEquals("but the assistant NEVER pings for a broadcast",
            ListenerFilter.Verdict.STORE_ONLY,
            ListenerFilter.verdict(r.events.get(0)));
    }

    @Test public void discordDirectMessageStillPings() {
        TitleTextParser discord = new TitleTextParser(Channel.DISCORD, false);
        RawNotif raw = ParserFixtures.titleText(
            "com.discord", "Ada", "yo, call at 8?", "msg");
        NotifParser.Result r = discord.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertFalse(r.events.get(0).group);
        assertEquals(ListenerFilter.Verdict.STORE_AND_PING,
            ListenerFilter.verdict(r.events.get(0)));
    }

    @Test public void emptyEverythingIgnored() {
        RawNotif raw = ParserFixtures.raw("com.zhiliaoapp.musically");
        NotifParser.Result r = new TitleTextParser(Channel.TIKTOK, true).parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
    }

    @Test public void nullRawFailsSafely() {
        assertEquals(NotifParser.Result.Kind.FAIL, slack.parse(null).kind);
    }

    /* -------------------------- P-audit-deep: kinds + calls -------------------------- */

    @Test public void slackSentAPhotoIsMediaNotText() {
        // real Slack media notification: title = sender, text = the exact fallback label
        RawNotif raw = ParserFixtures.titleText("com.Slack", "team-x", "sent a photo", "msg");
        NotifEvent e = slack.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.IMAGE, e.contentKind);
        assertTrue(e.hasAttachment);
    }

    @Test public void slackSentenceMentioningAPhotoStaysText() {
        RawNotif raw = ParserFixtures.titleText("com.Slack", "team-x", "did you like the photo?", "msg");
        assertEquals(com.replymate.core.model.ContentKind.TEXT, slack.parse(raw).events.get(0).contentKind);
    }

    @Test public void discordMissedCallStoredAsCallEvent() {
        RawNotif raw = ParserFixtures.titleText("com.discord", "Ada", "Missed voice call", "call");
        NotifEvent e = slack.parse(raw).events.get(0);
        assertEquals(com.replymate.core.model.ContentKind.CALL, e.contentKind);
    }

    @Test public void ongoingCallStateIgnoredForTitleTextApps() {
        RawNotif raw = ParserFixtures.titleText("com.discord", "Ada", "Ongoing voice call", "call");
        NotifParser.Result r = slack.parse(raw);
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        assertTrue(r.reason.contains("call"));
    }
}
