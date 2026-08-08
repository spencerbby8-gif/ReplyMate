package com.replymate.core.listener;

/** P-background-8: same-conversation matching between a CAPTURED reply target and
 *  a live notification from the same app. After the user dismisses the original
 *  notification, the source app may re-post the SAME conversation under a NEW
 *  sbn key (WhatsApp churns keys on updates/re-posts). A strict, official-fields
 *  match lets the approve path resolve the reply action on the FRESH notification
 *  instead of failing over to the cached PendingIntent blindly.
 *  Precedence (strongest official identity first):
 *    1. conversationId (notification shortcut id — the app's native thread id)
 *    2. conversationTitle (MessagingStyle conversation title)
 *    3. plain EXTRA_TITLE (1:1 chats carry the sender name there)
 *  Every comparison is exact and non-empty — no fuzz: a wrong guess would send a
 *  reply into the wrong chat, which is far worse than an honest fallback. */
public final class ConversationMatch {

    private ConversationMatch() {
    }

    /** True when both sides carry the same non-empty identity. */
    public static boolean same(String pkgA, String convIdA, String convTitleA, String titleA,
                               String pkgB, String convIdB, String convTitleB, String titleB) {
        if (!eq(pkgA, pkgB)) return false;
        if (nonEmpty(convIdA) && eq(convIdA, convIdB)) return true;
        if (nonEmpty(convTitleA) && eq(convTitleA, convTitleB)) return true;
        return nonEmpty(titleA) && eq(titleA, titleB);
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
