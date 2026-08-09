package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;

/** P-intelligence-7: the ONE place that decides a notification can NEVER become a
 *  ReplyMate conversation — group/broadcast-style items, missed-call notices and
 *  app service summaries/architecture noise. These are dropped BEFORE any contact
 *  is created, before any row is stored, and long before a draft could be
 *  considered, because the owner's rule is absolute: real (direct, 1:1, human)
 *  messages only create ReplyMate conversations.
 *
 *  Pure + Android-free so every rule is pinned by JVM tests. The geometry shapes
 *  are matched EXACTLY after normalization (like ContentSignals shapes): they are
 *  app-generated chrome, not human prose — a person never types precisely
 *  "23 new messages" as their whole message, and a real 1:1 message always
 *  carries MessagingStyle history on the watched conversation apps. */
public final class NoiseGate {

    private NoiseGate() { }

    /** One evaluation: never throws; reason is audit-safe. */
    public static final class Drop {
        public final boolean drop;
        public final String reason;
        private Drop(boolean drop, String reason) {
            this.drop = drop;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final Drop KEEP = new Drop(false, "");

    /** Group/broadcast-style items and missed-call notices — never a conversation. */
    public static Drop evaluate(NotifEvent e) {
        if (e == null) return new Drop(true, "null event");
        if (e.group) {
            return new Drop(true,
                "group/broadcast notification — not a direct 1:1 conversation");
        }
        if (e.contentKind == ContentKind.CALL) {
            return new Drop(true, "missed/declined-call notice — not a message");
        }
        return KEEP;
    }

    /** App service-summary geometry (normalized EXACT match). These are the shapes
     *  system/app summary cards use — never a human's whole text on watched apps:
     *    "23 new messages" · "you have new messages" · "you have 3 new messages"
     *    "you may have new messages" · "5 chats" · "2 conversations"
     *    "3 unread chats" · "in 4 chats" · "tap to view…"  */
    public static boolean isSummaryGeometry(String text) {
        String t = norm(text);
        if (t.isEmpty()) return false;
        if (t.matches("\\d+ new messages")) return true;
        if (t.matches("you (may )?have( \\d+)? new messages")) return true;
        if (t.matches("you have \\d+ unread( messages| chats?)")) return true;
        if (t.matches("\\d+ (chats|conversations)")) return true;
        if (t.matches("\\d+ unread chats?")) return true;
        if (t.matches("in \\d+ chats?")) return true;
        if (t.matches("\\d+ new messages in \\d+ chats?")) return true;
        if (t.startsWith("tap to view") || t.startsWith("tap to see")) return true;
        return false;
    }

    /** True when this event carries human-readable conversational text (what the
     *  "never create a conversation for non-text first contact" rule tests).
     *  A real caption next to media ("Bro check this 📷") IS a real message and
     *  reads true; the app's bare placeholder ("📷 Photo") reads false. */
    public static boolean isReadableText(ContentKind kind, String text) {
        if (kind == ContentKind.CALL) return false;
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return false;
        if (kind != null && kind.isUnreadable() && ContentSignals.isFallbackShape(t)) {
            return false;
        }
        return true;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(java.util.Locale.US).replaceAll("\\s+", " ");
    }
}
