package com.replymate.core.listener;

/** What to do with each parsed event (approved P2 policy, extended P-audit-deep):
 *   - nothing readable and no attachment → SKIP
 *   - media-only/attachment or any non-TEXT content kind → STORE_ONLY
 *     (placeholder/caption rows, no proactive ping — calls included)
 *   - group conversations → STORE_ONLY (never proactive ping for groups)
 *   - otherwise → STORE_AND_PING (store + our "new message" notification)
 *  Own outgoing messages are additionally demoted to STORE_ONLY by the coordinator.
 *  Content kind (WHAT the item is) is decided by ContentSignals from notification
 *  evidence only — never from which app sent it. */
public final class ListenerFilter {

    public enum Verdict { SKIP, STORE_ONLY, STORE_AND_PING }

    /** Legacy placeholder kept for byte-compat with rows written before 0.9.0 and
     *  with older callers/tests. New writes use ContentKind.placeholder(). */
    public static final String MEDIA_PLACEHOLDER = "[media/voice — open in chat app]";

    private ListenerFilter() { }

    public static Verdict verdict(NotifEvent e) {
        return verdict(e, false);
    }

    /** P-intelligence-13: when group chats are enabled (GroupPolicy, per app),
     *  a real group message pings like a 1:1 — the generation/alert path treats
     *  the group as its contact. Default/OFF keeps groups STORE_ONLY. */
    public static Verdict verdict(NotifEvent e, boolean groupsAllowed) {
        if (e == null) return Verdict.SKIP;
        boolean emptyText = e.text == null || e.text.trim().isEmpty();
        com.replymate.core.model.ContentKind kind = e.contentKind;
        boolean nonText = kind != null && kind.isUnreadable();
        if (emptyText && !e.hasAttachment && !nonText) return Verdict.SKIP;
        if (emptyText || e.hasAttachment || nonText) return Verdict.STORE_ONLY;
        if (e.group && !groupsAllowed) return Verdict.STORE_ONLY;
        return Verdict.STORE_AND_PING;
    }

    /** True when a stored body is one of our placeholder markers — legacy or any
     *  per-kind shape — i.e. NOT real readable text. The generation gate's test. */
    public static boolean isPlaceholder(String body) {
        return ContentSignals.isPlaceholder(body);
    }
    // NOTE: package→channel mapping moved to WatchedApps/ParserRegistry (P3).
}
