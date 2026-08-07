package com.replymate.core.listener;

import com.replymate.core.model.Direction;

/** Incoming vs outgoing decision from MessagingStyle sender identity.
 *  Rule (P-audit-deep, keys first): when BOTH the owner person and the message
 *  sender publish a stable Person key, the KEY decides — two contacts can share a
 *  display name (or the owner and a contact can share one), a key cannot collide.
 *  Legacy no-key case falls back to the name comparison:
 *    sender == style's owner person name → OUTGOING (we typed it in the chat app);
 *    otherwise INCOMING. Unknown owner ⇒ conservatively INCOMING. */
public final class MessageClassifier {

    private MessageClassifier() { }

    public static Direction directionFor(String senderName, String ownerName) {
        return directionFor(senderName, ownerName, null, null);
    }

    /** @param senderKey sender Person key (native per-app user id, nullable)
     *  @param ownerKey  owner Person key (nullable) — keys win over names. */
    public static Direction directionFor(String senderName, String ownerName,
                                         String senderKey, String ownerKey) {
        String sKey = norm(senderKey);
        String oKey = norm(ownerKey);
        if (!sKey.isEmpty() && !oKey.isEmpty()) {
            return oKey.equals(sKey) ? Direction.OUTGOING : Direction.INCOMING;
        }
        String sender = norm(senderName);
        String owner = norm(ownerName);
        if (!owner.isEmpty() && !sender.isEmpty() && owner.equalsIgnoreCase(sender)) {
            return Direction.OUTGOING;
        }
        return Direction.INCOMING;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}
