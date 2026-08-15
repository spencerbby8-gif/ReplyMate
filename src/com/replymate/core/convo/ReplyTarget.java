package com.replymate.core.convo;

/** P-intelligence-16b: platform-agnostic REPLY TARGET — which message a draft
 *  answers. Native platform reply/reference ids are used WHEN the platform
 *  exposes them; Android's public MessagingStyle surface carries NONE (verified
 *  against the current official reference — messages expose sender Person /
 *  timestamp / text only), so identity here is ReplyMate's own stored notifKey
 *  + snippet, resolved from deterministic engagement evidence. When nothing
 *  clearly addresses the owner there is NO target — one is never invented,
 *  and a draft quote is never faked. */
public final class ReplyTarget {

    public enum Confidence { HIGH, MEDIUM }

    public final long conversationId;      // ReplyMate contactId (stable scope)
    public final String senderStableId;    // Participant.stableId ("" when unknown)
    public final String senderLabel;       // collision-safe label for prompts/UI
    public final String messageNotifKey;   // stored message identity ("" when unavailable)
    public final String snippet;           // exact text being answered (≤240 chars)
    public final long messageAtMs;
    public final Confidence confidence;
    public final String reason;            // e.g. "mentioned you by name", "direct question to the room"

    public ReplyTarget(long conversationId, String senderStableId, String senderLabel,
                       String messageNotifKey, String snippet, long messageAtMs,
                       Confidence confidence, String reason) {
        this.conversationId = conversationId;
        this.senderStableId = senderStableId == null ? "" : senderStableId;
        this.senderLabel = senderLabel == null ? "" : senderLabel;
        this.messageNotifKey = messageNotifKey == null ? "" : messageNotifKey;
        String s = snippet == null ? "" : snippet.trim();
        this.snippet = s.length() > 240 ? s.substring(0, 240) + "…" : s;
        this.messageAtMs = messageAtMs;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    public boolean hasIdentity() { return !senderLabel.isEmpty() || !messageNotifKey.isEmpty(); }
}
