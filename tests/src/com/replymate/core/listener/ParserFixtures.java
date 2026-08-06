package com.replymate.core.listener;

/** Shared builders + message-text constants for the per-app parser fixtures (P3).
 *  Texts are intentionally app-flavored but tiny and synthetic. */
final class ParserFixtures {

    private ParserFixtures() { }

    static final String T_GREET = "you still coming tonight?";
    static final String T_FOLLOW = "bring the charger abeg";
    static final String T_GROUP = "who has the match tickets?";
    static final String T_PROMO = "Your weekly recap is ready";

    static RawNotif raw(String pkg) {
        RawNotif r = new RawNotif();
        r.packageName = pkg;
        r.postTimeMs = 1000L;
        return r;
    }

    static RawNotif.Entry msg(String text, long ts, String sender, boolean attachment) {
        RawNotif.Entry e = new RawNotif.Entry();
        e.text = text;
        e.timestampMs = ts;
        e.senderName = sender;
        e.hasAttachment = attachment;
        return e;
    }

    /** The shape WhatsApp/Telegram/Signal/GMessages/Messenger post for a 1:1 chat. */
    static RawNotif styleDm(String pkg, String who, String t1, String t2) {
        RawNotif r = raw(pkg);
        r.title = who;
        r.convTitle = who;
        r.ownerName = "Me";
        r.group = Boolean.FALSE;
        r.messages.add(msg(t1, 1000L, who, false));
        r.messages.add(msg(t2, 2000L, who, false));
        return r;
    }

    /** Group shape: conversationTitle set, per-message senders, owner excluded later. */
    static RawNotif styleGroup(String pkg, String groupName, String sender, String text) {
        RawNotif r = raw(pkg);
        r.title = groupName;
        r.convTitle = groupName;
        r.ownerName = "Me";
        r.group = Boolean.TRUE;
        r.messages.add(msg(text, 1000L, sender, false));
        return r;
    }

    /** Slack/Discord single-shot shape. */
    static RawNotif titleText(String pkg, String title, String text, String category) {
        RawNotif r = raw(pkg);
        r.title = title;
        r.text = text;
        r.category = category;
        return r;
    }
}
