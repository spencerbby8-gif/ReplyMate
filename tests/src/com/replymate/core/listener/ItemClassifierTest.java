package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.ContentKind;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: the pre-generation classifier — every platform-metadata
 *  axis honestly mapped, UNKNOWN failing closed. */
public final class ItemClassifierTest {

    private static NotifEvent ev(String text, String sender, String convTitle, boolean group) {
        NotifEvent e = new NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.text = text;
        e.senderName = sender;
        e.conversationTitle = convTitle;
        e.group = group;
        return e;
    }

    @Test public void directOneOnOne() {
        ItemClassifier.Result r = ItemClassifier.classify(
            ev("see you at 4", "Amara", "Amara", false), new String[0]);
        assertEquals(ItemClass.REAL_1TO1, r.cls);
    }

    @Test public void groupMessage() {
        ItemClassifier.Result r = ItemClassifier.classify(
            ev("match moved", "Musa", "Family group", true), new String[0]);
        assertEquals(ItemClass.GROUP_MESSAGE, r.cls);
    }

    @Test public void ownerMentionBeatsCapabilityAndGroup() {
        NotifEvent e = ev("Spencer are you coming?", "Musa", "Family group", true);
        e.hasFreeFormReply = true;
        ItemClassifier.Result r = ItemClassifier.classify(e, new String[]{"spencer"});
        assertEquals(ItemClass.MENTION, r.cls);
    }

    @Test public void freeFormActionMakesItDirectReply() {
        NotifEvent e = ev("see you at 4", "Amara", "Amara", false);
        e.hasFreeFormReply = true;
        assertEquals(ItemClass.DIRECT_REPLY, ItemClassifier.classify(e, new String[0]).cls);
    }

    @Test public void selfAnnouncingChannelIsAnAnnouncement() {
        NotifEvent e = ev("new rules are live, read them", "announcements", "announcements", true);
        e.conversationId = "999000111";
        ItemClassifier.Result r = ItemClassifier.classify(e, new String[0]);
        assertEquals(ItemClass.ANNOUNCEMENT, r.cls);
        assertTrue(r.cls.isNonReplyable());
    }

    @Test public void appServiceChatIsService() {
        ItemClassifier.Result r = ItemClassifier.classify(
            ev("messages you send are now secured", "WhatsApp", "WhatsApp", false),
            new String[0]);
        assertEquals(ItemClass.SERVICE, r.cls);
        assertTrue(r.cls.isNonReplyable());
    }

    @Test public void systemLineIsSystem() {
        ItemClassifier.Result r = ItemClassifier.classify(
            ev("Your security code with Amara changed. Tap to learn more.",
                "Amara", "Amara", false),
            new String[0]);
        assertEquals(ItemClass.SYSTEM, r.cls);
        assertTrue(r.cls.isNonReplyable());
    }

    @Test public void reactionsCallsAndMediaAreTheirOwnKinds() {
        assertEquals(ItemClass.REACTION, ItemClassifier.classify(
            ev("Reacted ❤️ to \"see you\"", "Amara", "Amara", false), new String[0]).cls);
        // a bare "Missed voice call" card with no call category is a SYSTEM line
        // (P-background-9) and classifies SYSTEM; a live call KIND is CALL.
        NotifEvent call = ev("Ongoing call", "Amara", "Amara", false);
        call.contentKind = ContentKind.CALL;
        assertEquals(ItemClass.CALL, ItemClassifier.classify(call, new String[0]).cls);
        NotifEvent media = ev("", "Amara", "Amara", false);
        media.hasAttachment = true;
        assertEquals(ItemClass.MEDIA_ONLY, ItemClassifier.classify(media, new String[0]).cls);
    }

    @Test public void nothingIdentifiedFailsClosedAsUnknown() {
        ItemClassifier.Result r = ItemClassifier.classify(
            ev("tap to view your messages", null, null, false), new String[0]);
        assertEquals(ItemClass.UNKNOWN, r.cls);
        assertTrue(r.reason.contains("failing closed"));
    }

    @Test public void keyedOrUriIdentityStillCountsAsASender() {
        NotifEvent e = ev("hi", null, "Amara", false);
        e.senderKey = "person-9";
        assertEquals(ItemClass.REAL_1TO1, ItemClassifier.classify(e, new String[0]).cls);
    }
}
