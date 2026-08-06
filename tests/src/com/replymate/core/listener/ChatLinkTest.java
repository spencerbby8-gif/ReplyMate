package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

/** Official deep-link builders: only honest links from data we actually hold. */
public class ChatLinkTest {

    @Test public void whatsappShareLinkWithText() {
        String url = ChatLink.launchUrl(Channel.WHATSAPP, "Amara", "see you at 7!");
        assertNotNull(url);
        assertTrue(url.startsWith("https://wa.me/?text="));
        assertTrue(url.contains("see%20you%20at%207%21"));
    }

    @Test public void telegramShareLinkWithText() {
        String url = ChatLink.launchUrl(Channel.TELEGRAM, "Sam", "on my way");
        assertNotNull(url);
        assertTrue(url.startsWith("https://t.me/share/url?"));
        assertTrue(url.contains("text=on%20my%20way"));
    }

    @Test public void noLinkWithoutShareText() {
        assertNull(ChatLink.launchUrl(Channel.WHATSAPP, "Amara", null));
        assertNull(ChatLink.launchUrl(Channel.WHATSAPP, "Amara", "   "));
        assertNull(ChatLink.launchUrl(Channel.TELEGRAM, "Sam", ""));
    }

    @Test public void specialCharsAreUrlEncoded() {
        String url = ChatLink.launchUrl(Channel.WHATSAPP, "A", "100% sure & ready?");
        assertNotNull(url);
        assertTrue(url.contains("100%25%20sure%20%26%20ready%3F"));
        assertFalse(url.contains(" & "));
    }

    @Test public void channelsWithoutHonestLinksReturnNull() {
        // package-launcher fallback is the app layer's job (DeepLinks) — never fabricate.
        assertNull(ChatLink.launchUrl(Channel.MESSENGER, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.SIGNAL, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.GOOGLE_MESSAGES, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.SLACK, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.DISCORD, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.INSTAGRAM, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.X, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(Channel.TIKTOK, "Ada", "hi"));
        assertNull(ChatLink.launchUrl(null, "Ada", "hi"));
    }
}
