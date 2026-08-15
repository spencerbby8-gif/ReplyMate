package com.replymate.core.listener;

/** P-background-8: same-conversation matching between a CAPTURED reply target and
 *  a live notification from the same app. After the user dismisses the original
 *  notification, the source app may re-post the SAME conversation under a NEW
 *  sbn key (WhatsApp churns keys on updates/re-posts). A strict, official-fields
 *  match lets the approve path resolve the reply action on the FRESH notification
 *  instead of failing over to the cached PendingIntent blindly.
 *
 *  P-intelligence-17R (re-verified against the official direct-reply guidance,
 *  Aug 2026: "If you reuse a PendingIntent, a user might reply to a different
 *  conversation than the one they intend"): tiers are now DECISIVE whenever both
 *  sides carry the field —
 *    1. conversationId (notification shortcut id — the app's native thread id)
 *    2. conversationTitle (MessagingStyle conversation title)
 *    3. plain EXTRA_TITLE (1:1 chats carry the sender name there)
 *  A tier where BOTH sides have the field decides: equal ⇒ same conversation,
 *  different ⇒ NOT (a weaker tier can never resurrect it — two same-name chats
 *  with different native ids must never match). A tier only one side carries
 *  falls through (re-post drift is real: the first post may lack the shortcut id
 *  the re-post carries, and vice versa). Empty on both sides is never equality.
 *  No fuzz anywhere: a wrong guess would send a reply into the wrong chat, which
 *  is far worse than an honest fallback. */
public final class ConversationMatch {

    private ConversationMatch() {
    }

    /** True when both sides carry a provably-shared identity (see tier rules). */
    public static boolean same(String pkgA, String convIdA, String convTitleA, String titleA,
                               String pkgB, String convIdB, String convTitleB, String titleB) {
        if (!eq(pkgA, pkgB)) return false;
        // tier 1 — native conversation id: DECISIVE when both sides carry it
        if (nonEmpty(convIdA) && nonEmpty(convIdB)) return eq(convIdA, convIdB);
        // tier 2 — conversation title: DECISIVE when both sides carry it
        if (nonEmpty(convTitleA) && nonEmpty(convTitleB)) return eq(convTitleA, convTitleB);
        // tier 3 — plain title (1:1 identity): DECISIVE when both sides carry it
        if (nonEmpty(titleA) && nonEmpty(titleB)) return eq(titleA, titleB);
        // one-sided/empty everywhere: nothing PROVES these are the same chat
        return false;
    }

    /** True when at least one identity field is available to match on at all. */
    public static boolean identifiable(String convId, String convTitle, String title) {
        return nonEmpty(convId) || nonEmpty(convTitle) || nonEmpty(title);
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equals(b);
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
