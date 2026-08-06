package com.replymate.core.listener;

import com.replymate.core.model.Channel;

/** What to do with each parsed event (approved P2 policy):
 *   - nothing readable and no attachment → SKIP
 *   - media-only/attachment → STORE_ONLY (placeholder, no proactive ping)
 *   - group conversations → STORE_ONLY (never proactive ping for groups)
 *   - otherwise → STORE_AND_PING (store + our "new message" notification)
 *  Own outgoing messages are additionally demoted to STORE_ONLY by the coordinator.
 */
public final class ListenerFilter {

    public enum Verdict { SKIP, STORE_ONLY, STORE_AND_PING }

    public static final String MEDIA_PLACEHOLDER = "[media/voice — open in chat app]";

    private ListenerFilter() { }

    public static Verdict verdict(NotifEvent e) {
        if (e == null) return Verdict.SKIP;
        boolean emptyText = e.text == null || e.text.trim().isEmpty();
        if (emptyText && !e.hasAttachment) return Verdict.SKIP;
        if (emptyText || e.hasAttachment) return Verdict.STORE_ONLY;
        if (e.group) return Verdict.STORE_ONLY;
        return Verdict.STORE_AND_PING;
    }

    /** Watched packages (P2 scope: WhatsApp + Telegram only). */
    public static Channel channelForPackage(String pkg) {
        if (pkg == null) return null;
        if (pkg.equals("com.whatsapp")) return Channel.WHATSAPP;
        if (pkg.equals("org.telegram.messenger")) return Channel.TELEGRAM;
        return null;
    }
}
