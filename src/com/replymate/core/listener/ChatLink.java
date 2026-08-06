package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Official deep-link builders per channel (P3). Everything is a standard
 *  URL the OS resolves into the target app when installed — no unofficial APIs.
 *  Where Android/the app offers no reliable chat deep link (handles unknown),
 *  launchUrl() returns null and the app falls back to the package launcher. */
public final class ChatLink {

    private ChatLink() { }

    /** Best official URL to open a chat flow for this channel, or null when none
     *  can be built from data we actually have (never fabricate). */
    public static String launchUrl(Channel channel, String displayName, String textToShare) {
        if (channel == null) return null;
        switch (channel) {
            case WHATSAPP:
                if (textToShare != null && !textToShare.trim().isEmpty()) {
                    // official wa.me share flow → user lands on the chat picker with text ready
                    return "https://wa.me/?text=" + enc(textToShare);
                }
                return null;
            case TELEGRAM:
                if (textToShare != null && !textToShare.trim().isEmpty()) {
                    // official t.me share flow
                    return "https://t.me/share/url?url=&text=" + enc(textToShare);
                }
                return null;
            case MESSENGER:
                // fb-messenger deep links need the numeric thread/user id, which
                // notifications never expose → no honest deep link; package fallback.
                return null;
            default:
                return null;
        }
    }

    private static String enc(String s) {
        try {
            // %20 rather than '+' — unambiguous in every URL context.
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
