package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.util.ArrayList;
import java.util.List;

/** Shared parser for apps whose chat notifications typically carry only
 *  title + text (Slack, Discord, Instagram/X/TikTok DMs, and any fallback case).
 *  Category gate: when the notification declares a NON-message category
 *  (promo/recommendation/like-storm noise), IGNORE by design. */
public class TitleTextParser implements NotifParser {

    private final Channel channel;
    private final boolean requireMessageCategory;

    public TitleTextParser(Channel channel, boolean requireMessageCategory) {
        this.channel = channel;
        this.requireMessageCategory = requireMessageCategory;
    }

    @Override public Result parse(RawNotif raw) {
        if (raw == null) return Result.fail("empty notification object");
        try {
            if (requireMessageCategory && raw.category != null
                    && !"msg".equals(raw.category) && !"social".equals(raw.category)
                    && !"message".equals(raw.category)) {
                return Result.ignore("non-message category: " + raw.category);
            }
            // Call-category notifications are never chat messages: store only a
            // FINISHED call outcome (missed/declined) as a CALL event; ringing /
            // ongoing state is noise regardless of the message-category setting.
            boolean callOutcome = raw.category != null && "call".equals(raw.category);
            if (callOutcome && !ContentSignals.isCallEvent(
                    MessagingStyleParser.trim(raw.text).isEmpty()
                        ? MessagingStyleParser.trim(raw.bigText)
                        : MessagingStyleParser.trim(raw.text))) {
                return Result.ignore("non-final call state");
            }
            boolean group = raw.group != null && raw.group.booleanValue();
            if (!group) group = looksLikeGroup(raw);

            // P-ux-fix: app self-status (unread digests, sync/progress cards) is
            // never a chat message — gated to self-titled, evidence-free items only.
            if (StatusFilter.isSelfStatus(raw, WatchedApps.labelFor(channel))) {
                return Result.ignore("app self-status (backup/sync/progress)");
            }

            String text = MessagingStyleParser.trim(raw.text);
            if (text.isEmpty()) text = MessagingStyleParser.trim(raw.bigText);
            if (text.isEmpty()) {
                // Last resort: apps that report MessagingStyle history
                if (!raw.messages.isEmpty()) {
                    List<NotifEvent> out = new ArrayList<NotifEvent>();
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
                        MessagingStyleParser.classify(e, m.mimeType, m.hasAttachment,
                            m.text, false);
                        out.add(e);
                    }
                    return Result.events(out);
                }
                return Result.ignore("no readable message content");
            }

            String title = MessagingStyleParser.trim(raw.title);
            String sender = senderFrom(raw, text);
            String body = bodyFrom(raw, text, sender);
            boolean callEvent = raw.category != null && "call".equals(raw.category)
                && ContentSignals.isCallEvent(body);

            NotifEvent e = base(raw, group);
            e.text = body;
            e.senderName = sender;
            e.timestampMs = raw.postTimeMs;
            MessagingStyleParser.classify(e, null, false, body, callEvent);
            if (sender != null && title.regionMatches(true, 0, sender, 0, sender.length())) {
                // "Title == sender" is the strongest identity hint these apps give
                e.conversationTitle = title;
            }
            List<NotifEvent> out = new ArrayList<NotifEvent>();
            out.add(e);
            return Result.events(out);
        } catch (RuntimeException boom) {
            return Result.fail("parser crash mapped: " + boom.getClass().getSimpleName());
        }
    }

    private NotifEvent base(RawNotif raw, boolean group) {
        NotifEvent e = new NotifEvent();
        e.channel = channel;
        e.packageName = raw.packageName;
        e.conversationTitle = MessagingStyleParser.firstNonBlank(raw.convTitle, raw.title);
        e.ownerName = raw.ownerName;
        e.group = group;
        return e;
    }

    /** Heuristic: "#channel" in title/text → group space. Subclass-tunable. */
    protected boolean looksLikeGroup(RawNotif raw) {
        String t = MessagingStyleParser.trim(raw.title);
        return t.indexOf('#') >= 0;
    }

    /** Many single-shot apps write "Sender: message" in the text. */
    protected String senderFrom(RawNotif raw, String text) {
        String title = MessagingStyleParser.trim(raw.title);
        if (!title.isEmpty()) return title;
        int c = text.indexOf(':');
        if (c > 0 && c <= 40) {
            String maybe = text.substring(0, c).trim();
            if (!maybe.isEmpty() && maybe.indexOf('\n') < 0) return maybe;
        }
        return null;
    }

    protected String bodyFrom(RawNotif raw, String text, String sender) {
        if (sender != null) {
            String prefix = sender + ":";
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return text.substring(prefix.length()).trim();
            }
        }
        return text;
    }
}
