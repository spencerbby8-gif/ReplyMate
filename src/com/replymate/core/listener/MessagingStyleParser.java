package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.util.List;

/** Shared parser for apps that post real framework MessagingStyle data
 *  (WhatsApp, Telegram, Signal, Google Messages, Messenger, and Slack/Discord
 *  on versions where they use MessagingStyle). Falls back to the plain
 *  title/text shape when no MessagingStyle history exists. */
public final class MessagingStyleParser implements NotifParser {

    private final Channel channel;

    public MessagingStyleParser(Channel channel) {
        this.channel = channel;
    }

    @Override public Result parse(RawNotif raw) {
        if (raw == null) return Result.fail("empty notification object");
        try {
            // Category gate: ongoing calls / progress / status notifications carry a
            // title+text that LOOKS like a message ("WhatsApp · Ongoing call") but is
            // not one. Real message notifications are "msg"/"social"; absent category
            // is tolerated (some apps don't set it). Declared non-message → ignore —
            // EXCEPT call-category events that report a FINISHED outcome ("Missed
            // voice call"): those are conversation facts worth storing (kind CALL,
            // store-only), never ongoing ringing state.
            boolean callEvent = raw.category != null && "call".equals(raw.category)
                && ContentSignals.isCallEvent(firstNonBlank(trim(raw.text), trim(raw.bigText)));
            if (raw.category != null
                    && !"msg".equals(raw.category)
                    && !"social".equals(raw.category)
                    && !callEvent) {
                return Result.ignore("non-message category: " + raw.category);
            }
            boolean group = raw.group != null && raw.group.booleanValue();
            if (raw.messages.isEmpty()) {
                // P-ux-fix: app self-status (backup/sync/checking/progress cards) is
                // never a chat message — ignore before it can touch a conversation.
                if (StatusFilter.isSelfStatus(raw, WatchedApps.labelFor(channel))) {
                    return Result.ignore("app self-status (backup/sync/progress)");
                }
                // single-shot notification (no MessagingStyle history) — the classic fallback
                String text = trim(raw.text);
                if (text.isEmpty()) text = trim(raw.bigText);
                if (text.isEmpty()) return Result.ignore("no readable message content");
                NotifEvent e = base(raw, group);
                e.senderName = firstNonBlank(raw.convTitle, raw.title);
                e.text = text;
                e.timestampMs = raw.postTimeMs;
                classify(e, null, false, text, callEvent);
                return Result.events(single(e));
            }
            List<NotifEvent> out = new java.util.ArrayList<NotifEvent>();
            for (RawNotif.Entry m : raw.messages) {
                NotifEvent e = base(raw, group);
                e.text = m.text;
                e.timestampMs = m.timestampMs > 0 ? m.timestampMs : raw.postTimeMs;
                e.senderName = m.senderName;
                e.senderKey = m.senderKey;
                e.senderUri = m.senderUri;
                e.hasAttachment = m.hasAttachment;
                e.mediaMime = m.mimeType;
                e.mediaUri = m.dataUri;
                classify(e, m.mimeType, m.hasAttachment, m.text,
                    callEvent || (raw.category != null && "call".equals(raw.category)
                        && ContentSignals.isCallEvent(m.text)));
                out.add(e);
            }
            return Result.events(out);
        } catch (RuntimeException boom) {
            return Result.fail("parser crash mapped: " + boom.getClass().getSimpleName());
        }
    }

    protected NotifEvent base(RawNotif raw, boolean group) {
        NotifEvent e = new NotifEvent();
        e.channel = channel;
        e.packageName = raw.packageName;
        e.conversationTitle = firstNonBlank(raw.convTitle, raw.title);
        e.conversationId = raw.conversationId;
        e.ownerName = raw.ownerName;
        e.ownerKey = raw.ownerKey;
        e.group = group;
        return e;
    }

    /** Content-kind decision shared by all paths (P-audit-deep). Sets contentKind and
     *  marks hasAttachment for unreadable media so the verdict policy treats it as
     *  media even when the app published no attachment marker (e.g. a bare "📷 Photo"
     *  fallback text). Calls stay attachment-free but are store-only by kind. */
    static void classify(NotifEvent e, String mime, boolean hasAttachment,
                         String text, boolean callEvent) {
        com.replymate.core.model.ContentKind kind = callEvent
            ? com.replymate.core.model.ContentKind.CALL
            : ContentSignals.classify(mime, hasAttachment, text);
        e.contentKind = kind;
        if (kind != null && kind.isMedia()) e.hasAttachment = true;
    }

    private static List<NotifEvent> single(NotifEvent e) {
        List<NotifEvent> out = new java.util.ArrayList<NotifEvent>();
        out.add(e);
        return out;
    }

    static String trim(String s) { return s == null ? "" : s.trim(); }

    static String firstNonBlank(String a, String b) {
        if (a != null && !trim(a).isEmpty()) return a;
        if (b != null && !trim(b).isEmpty()) return b;
        return null;
    }
}
