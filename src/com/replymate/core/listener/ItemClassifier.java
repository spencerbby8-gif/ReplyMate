package com.replymate.core.listener;

import com.replymate.core.model.ContentKind;

/** P-intelligence-17: the pre-generation CLASSIFIER. Maps one parsed event to its
 *  {@link ItemClass} using all platform metadata we honestly have: notification
 *  category, group-conversation flag, sender identity (name/key/uri),
 *  conversation identity (id/title), content kind (media/call), reaction/service/
 *  system text shapes, reply capability (free-form RemoteInput on the item's own
 *  notification), and owner-name mentions. Pure + test-pinned.
 *
 *  HONESTY (re-verified against the official Notification reference, Aug 2026):
 *  Android exposes NO announcement/broadcast flag for third-party watchers. An
 *  announcement is therefore declared ONLY on positive evidence (the item speaks
 *  AS the channel/conversation itself); a human-shaped post in a read-only
 *  channel is indistinguishable pre-generation from a group message — the
 *  delivery guard + capability honesty carry safety there, never a guessed class. */
public final class ItemClassifier {

    public static final class Result {
        public final ItemClass cls;
        public final String reason;
        Result(ItemClass c, String r) { cls = c; reason = r; }
    }

    private ItemClassifier() { }

    /** @param ownerTokens profile-name tokens (EngagementClassifier.ownerTokens),
     *                     empty when the owner set no name */
    public static Result classify(NotifEvent e, String[] ownerTokens) {
        if (e == null) return new Result(ItemClass.UNKNOWN, "null event");

        // ---- hard kinds (never a replyable message) ----
        if (SystemLines.isSystemLine(e.text)) {
            return new Result(ItemClass.SYSTEM, "platform/in-chat system line");
        }
        if (e.channel != null) {
            String label = WatchedApps.labelFor(e.channel);
            if (!label.isEmpty() && !label.equals(e.channel.wire)
                    && norm(e.conversationTitle).equals(norm(label))) {
                // the same tell NoiseGate uses — one source of truth both sides
                return new Result(ItemClass.SERVICE, "the app's own service chat (notices)");
            }
        }
        if (ContentSignals.isReactionNotice(e.text)) {
            return new Result(ItemClass.REACTION, "a reaction notice, not a message");
        }
        if (e.contentKind == ContentKind.CALL) {
            return new Result(ItemClass.CALL, "a call event, not a typed message");
        }
        boolean emptyText = e.text == null || e.text.trim().isEmpty();
        boolean nonText = e.contentKind != null && e.contentKind.isUnreadable();
        if (nonText || (emptyText && e.hasAttachment)) {
            return new Result(ItemClass.MEDIA_ONLY, "media/attachment without readable text");
        }

        // ---- fail-closed: nothing identifies anyone or anywhere ----
        boolean hasSender = SystemLines.hasSenderIdentity(e.senderName, e.senderKey, e.senderUri);
        boolean hasConversation = nonEmpty(e.conversationId) || nonEmpty(e.conversationTitle);
        if (!hasSender && !hasConversation) {
            return new Result(ItemClass.UNKNOWN,
                "no sender and no conversation identity — failing closed");
        }

        // ---- self-announcing channel (positive evidence only) ----
        if (e.group && hasSender && nonEmpty(e.conversationTitle)
                && norm(e.senderName).equals(norm(e.conversationTitle))) {
            return new Result(ItemClass.ANNOUNCEMENT,
                "the item speaks AS the channel/conversation itself (announcement shape)");
        }

        // ---- replyable semantic ladder ----
        if (ownerTokens != null && ownerTokens.length > 0
                && com.replymate.core.convo.EngagementClassifier.mentionsName(e.text, ownerTokens)) {
            return new Result(ItemClass.MENTION, "the owner is addressed by name");
        }
        if (e.hasFreeFormReply) {
            return new Result(ItemClass.DIRECT_REPLY,
                "its own notification exposes a free-form quick-reply action");
        }
        return new Result(e.group ? ItemClass.GROUP_MESSAGE : ItemClass.REAL_1TO1,
            e.group ? "a group conversation message" : "a direct 1:1 message");
    }

    private static boolean nonEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
