package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §2: WhatsApp-status notices must never produce conversations,
 *  contacts or drafts — while a genuine status REPLY (a normal chat message
 *  with MessagingStyle history) is first-class conversation and always flows.
 *
 *  Evidence model: WhatsApp renders status likes/reactions/reshares as a
 *  single-shot title/text notification with NO MessagingStyle history; a status
 *  reply lands in the 1:1 chat and carries MessagingStyle entries. The gate is
 *  provenance + canonical-shape, not keywords: the exact same words typed by a
 *  human inside a chat can never match. */
public final class StatusNoticeTest {

    private static RawNotif raw(String pkg, String title, String text) {
        RawNotif r = new RawNotif();
        r.packageName = pkg;
        r.title = title;
        r.text = text;
        return r;
    }

    private static RawNotif.Entry entry(String sender, String text) {
        RawNotif.Entry e = new RawNotif.Entry();
        e.senderName = sender;
        e.text = text;
        return e;
    }

    /* ------------------------------------------------ canonical notices DROP */

    @Test public void statusLikeIsIgnoredByMessagingStyleParser() {
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        assertEquals(NotifParser.Result.Kind.IGNORE,
            p.parse(raw("com.whatsapp", "Adaeze", "Liked your status")).kind);
    }

    @Test public void statusReactionEmojiIsIgnored() {
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        assertEquals(NotifParser.Result.Kind.IGNORE,
            p.parse(raw("com.whatsapp", "Adaeze", "Reacted ❤️ to your status")).kind);
        assertEquals(NotifParser.Result.Kind.IGNORE,
            p.parse(raw("com.whatsapp", "Kunle", "Reacted 😂 to your status")).kind);
    }

    @Test public void statusReshareIsIgnored() {
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        assertEquals(NotifParser.Result.Kind.IGNORE,
            p.parse(raw("com.whatsapp", "Tobi", "Reshared your status")).kind);
    }

    @Test public void statusNoticeIgnoredOnTitleTextPathToo() {
        // any title/text-channel app publishing WA-style status notices gets the
        // same treatment via the shared canonical-shape gate
        TitleTextParser p = new TitleTextParser(Channel.INSTAGRAM, true);
        assertEquals(NotifParser.Result.Kind.IGNORE,
            p.parse(raw("com.instagram.android", "adaeze", "Liked your status")).kind);
    }

    /* ------------------------------------------- authentic status reply FLOW */

    @Test public void statusReplyInsideChatIsAConversationMessage() {
        // A reply to my status arrives in the 1:1 chat WITH MessagingStyle —
        // the text can even reference the status; it is conversation, always.
        RawNotif r = raw("com.whatsapp", "Adaeze", "😂😂 that status was too real");
        r.messages.add(entry("Adaeze", "😂😂 that status was too real"));
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        NotifParser.Result out = p.parse(r);
        assertEquals(NotifParser.Result.Kind.EVENTS, out.kind);
        assertFalse(out.events.isEmpty());
        assertEquals("Adaeze", out.events.get(0).senderName);
    }

    @Test public void humanTypedWordsInsideChatAreNeverStatusFiltered() {
        // Someone replying inside the chat writing the very words — history
        // present ⇒ provenance gate sees conversation, never a notice.
        RawNotif r = raw("com.whatsapp", "Adaeze", "I liked your status idea, run with it");
        r.messages.add(entry("Adaeze", "I liked your status idea, run with it"));
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        assertEquals(NotifParser.Result.Kind.EVENTS, p.parse(r).kind);
    }

    @Test public void humanSingleShotSentenceIsNotACanonicalNotice() {
        // no history, but the phrasing is a human sentence, not the app's
        // canonical card → must survive (only the exact app shapes are filtered)
        MessagingStyleParser p = new MessagingStyleParser(Channel.WHATSAPP);
        assertEquals(NotifParser.Result.Kind.EVENTS,
            p.parse(raw("com.whatsapp", "Adaeze", "I liked your status about the wedding, send more pics")).kind);
    }

    /* --------------------------------------------------------- signal itself */

    @Test public void canonicalShapeUnitPins() {
        assertTrue(ContentSignals.isStatusNotice("Liked your status"));
        assertTrue(ContentSignals.isStatusNotice("  Loved your status  "));
        assertTrue(ContentSignals.isStatusNotice("Reacted 😂 to your status"));
        assertTrue(ContentSignals.isStatusNotice("Reshared your status"));
        assertFalse(ContentSignals.isStatusNotice("I liked your status idea"));
        assertFalse(ContentSignals.isStatusNotice("your status was funny"));
        assertFalse(ContentSignals.isStatusNotice(""));
        assertFalse(ContentSignals.isStatusNotice(null));
        assertFalse("a bare emoji + 'your status' without a reaction verb is ambiguous — not filtered",
            ContentSignals.isStatusNotice("❤️ your status"));
    }
}
