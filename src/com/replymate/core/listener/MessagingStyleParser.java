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
                // P-intelligence-7: app service-summary geometry ("23 new messages",
                // "you have new messages", "tap to view…") is chrome, never a human
                // message — dropped before it can become a conversation.
                if (NoiseGate.isSummaryGeometry(text)) {
                    return Result.ignore("service summary: " + text);
                }
                NotifEvent e = base(raw, group);
                e.senderName = firstNonBlank(raw.convTitle, raw.title);
                e.text = text;
                e.timestampMs = raw.postTimeMs;
                classify(e, null, false, text, callEvent);
                return Result.events(single(e));
            }
            // P-background-9: apps inject their own chrome INTO a chat's history
            // (encryption notices, security-code lines, upsell cards) as entries
            // with NO sender identity. Human senders on every watched app always
            // publish at least one of name/key/uri — so when the SAME payload
            // carries sendered entries, sender-less entries are system inserts and
            // are dropped here. When EVERY entry lacks identity (an app that simply
            // never names senders) nothing is dropped — legacy behavior, zero risk
            // of swallowing a real thread.
            boolean anyNamedSender = false;
            for (RawNotif.Entry probe : raw.messages) {
                if (SystemLines.hasSenderIdentity(
                        probe.senderName, probe.senderKey, probe.senderUri)) {
                    anyNamedSender = true;
                    break;
                }
            }
            List<NotifEvent> out = new java.util.ArrayList<NotifEvent>();
            // P-intelligence-15: HISTORIC context first (older, chronological) —
            // same sender-identity hygiene as live entries, flagged so ingest
            // stores them as grounding but never lets them ping/draft.
            for (RawNotif.Entry m : raw.historic) {
                if (anyNamedSender && !SystemLines.hasSenderIdentity(
                        m.senderName, m.senderKey, m.senderUri)) {
                    continue;
                }
                NotifEvent e = base(raw, group);
                e.text = m.text;
                e.timestampMs = m.timestampMs > 0 ? m.timestampMs : raw.postTimeMs;
                e.senderName = m.senderName;
                e.senderKey = m.senderKey;
                e.senderUri = m.senderUri;
                e.hasAttachment = m.hasAttachment;
                e.mediaMime = m.mimeType;
                e.mediaUri = m.dataUri;
                e.historic = true;
                classify(e, m.mimeType, m.hasAttachment, m.text, false);
                out.add(e);
            }
            for (RawNotif.Entry m : raw.messages) {
                if (anyNamedSender && !SystemLines.hasSenderIdentity(
                        m.senderName, m.senderKey, m.senderUri)) {
                    continue;   // sender-less system insert inside a real history
                }
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
        // P-intelligence-17: this item's own reply capability (both action surfaces).
        e.hasFreeFormReply =
            com.replymate.core.assistant.AssistantPlanner.directAction(raw.actions) != null;
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
