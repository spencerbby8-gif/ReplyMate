package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;

/** P-intelligence-7: the ONE place that decides a notification can NEVER become a
 *  ReplyMate conversation — group/broadcast-style items, missed-call notices and
 *  app service summaries/architecture noise. These are dropped BEFORE any contact
 *  is created, before any row is stored, and long before a draft could be
 *  considered. P-intelligence-13 softens exactly ONE rule: group conversations
 *  are a user OPT-IN ({@link GroupPolicy}, default OFF) — never globally blocked.
 *  When enabled per app, a group conversation is captured with per-member
 *  attribution and drafts replies like any contact; 1:1 behavior is unchanged.
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

    /** Default: groups are not conversations (P-intelligence-7, unchanged). */
    public static Drop evaluate(NotifEvent e) {
        return evaluate(e, false);
    }

    /** P-intelligence-13: groups are opt-IN (GroupPolicy), never globally blocked.
     *  When the user enabled group chats for this channel, a group item flows
     *  like any other captured message (still subject to the call and
     *  service-chat rules below). Default/OFF behavior is byte-identical to
     *  the original gate. */
    public static Drop evaluate(NotifEvent e, boolean groupsAllowed) {
        if (e == null) return new Drop(true, "null event");
        if (e.group && !groupsAllowed) {
            return new Drop(true,
                "group/broadcast notification — not a direct 1:1 conversation"
                + " (enable group chats in Sources to listen)");
        }
        if (e.contentKind == ContentKind.CALL) {
            return new Drop(true, "missed/declined-call notice — not a message");
        }
        // P-bg-10: the app's OWN service chat (WhatsApp/Telegram system notices,
        // login codes, "you joined…" cards) arrives shaped exactly like a 1:1 —
        // its only tell is the title being precisely the app label. A real
        // person is never saved under exactly "WhatsApp"/"Telegram" (nor any
        // other watched app's label variants), while a contact named e.g.
        // "Amara WhatsApp" must never drop. Unknown channels fall back to the
        // wire key from labelFor, which can never equal the label here.
        if (e.channel != null) {
            String label = WatchedApps.labelFor(e.channel);
            if (!label.isEmpty() && !label.equals(e.channel.wire)
                    && norm(e.conversationTitle).equals(norm(label))) {
                return new Drop(true, "the app's own service chat (system notices) — not a person");
            }
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
