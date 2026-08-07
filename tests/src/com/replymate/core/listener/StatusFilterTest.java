package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

/** App self-status filtering (P-ux-fix): backup/sync/progress/digest items must be
 *  ignored; real human messages must NEVER be swallowed. */
public class StatusFilterTest {

    private static RawNotif raw(String pkg, String title, String text) {
        RawNotif r = new RawNotif();
        r.packageName = pkg;
        r.title = title;
        r.text = text;
        return r;
    }

    @Test public void whatsappBackupIsIgnored() {
        RawNotif r = raw("com.whatsapp", "WhatsApp", "Backing up your messages…");
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
        RawNotif done = raw("com.whatsapp", "WhatsApp", "Your messages are backed up");
        assertTrue(StatusFilter.isSelfStatus(done, WatchedApps.labelFor(Channel.WHATSAPP)));
        RawNotif checking = raw("com.whatsapp", "WhatsApp", "Checking for new messages");
        assertTrue(StatusFilter.isSelfStatus(checking, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void ongoingSelfStatusIsIgnoredWithoutPhraseMatch() {
        RawNotif r = raw("com.whatsapp", "WhatsApp", "Working…");
        r.ongoing = true;
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void progressCardsAreIgnored() {
        RawNotif r = raw("org.telegram.messenger", "Telegram", "Updating your chats");
        r.progressMax = 100;
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.TELEGRAM)));
    }

    @Test public void unreadDigestOfTheAppItselfIsIgnored() {
        RawNotif r = raw("com.discord", "Discord", "You have 3 unread messages");
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.DISCORD)));
    }

    @Test public void selfTitledEmptyBodyIsIgnored() {
        RawNotif r = raw("com.whatsapp", "WhatsApp", null);
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void humanMessageWithMessagingStyleHistoryIsNeverFiltered() {
        RawNotif r = raw("com.whatsapp", "Amara", "I'm backing up my laptop today o");
        RawNotif.Entry e = new RawNotif.Entry();
        e.text = "I'm backing up my laptop today o";
        e.senderName = "Amara";
        r.messages.add(e);
        assertFalse("conversation evidence must win over the phrase match",
            StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void singleShotHumanMessageIsNeverFiltered() {
        // a person named Amara texting about a backup → titled like a PERSON
        RawNotif r = raw("com.whatsapp", "Amara", "did you finish the backup?");
        assertFalse(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void humanPlausibleTextSurvives() {
        RawNotif r = raw("com.whatsapp", "Mum", "I am preparing dinner, come over");
        assertFalse(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void packageTitledStatusIsIgnored() {
        RawNotif r = raw("com.whatsapp", "com.whatsapp", "Restoring media…");
        assertTrue(StatusFilter.isSelfStatus(r, WatchedApps.labelFor(Channel.WHATSAPP)));
    }

    @Test public void nullRawIsSafe() {
        assertFalse(StatusFilter.isSelfStatus(null, "WhatsApp"));
    }
}
