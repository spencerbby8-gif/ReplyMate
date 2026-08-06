package com.replymate.core.listener;

import com.replymate.core.model.Direction;

/** Incoming vs outgoing decision from MessagingStyle sender names.
 *  Rule: sender == style's owner person name → OUTGOING (we typed it in the chat app);
 *  otherwise INCOMING. Unknown owner name ⇒ conservatively INCOMING.
 *  (Imperfect by nature of name matching — documented in blueprints; may be upgraded
 *  with Person-key comparison on API 28+ later without changing call sites.) */
public final class MessageClassifier {

    private MessageClassifier() { }

    public static Direction directionFor(String senderName, String ownerName) {
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
