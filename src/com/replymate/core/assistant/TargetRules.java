package com.replymate.core.assistant;

/** P-intelligence-17: the two pure rules behind safe target CAPTURE/ADOPTION
 *  (AssistantTargetStore delegates here so the decisions are JVM-pinned):
 *
 *  CAPTURE — every new notification of a conversation re-saves target geometry.
 *  An actionless re-post must NEVER wipe a good target for the same conversation
 *  (apps post content first and attach/not-attach actions independently);
 *  a notification from a DIFFERENT conversation always replaces (the contact
 *  moved on); a notification WITH a real reply action always replaces (fresh
 *  geometry wins).
 *
 *  ADOPTION — the generate-time live re-probe exists so a first draft can offer
 *  "Approve &amp; send" when the capture-time raw predated the visible actions
 *  (WhatsApp attaches them a beat later). It may ONLY adopt a live notification
 *  that is THE SAME conversation (strict identity, same package) and really
 *  exposes a free-form action. Anything else is the cross-notification borrow
 *  this phase exists to kill. */
public final class TargetRules {

    private TargetRules() { }

    /** true ⇒ save() writes the new geometry; false ⇒ the stored target is kept. */
    public static boolean shouldReplaceOnCapture(boolean oldUsable,
                                                 boolean newHasDirectAction,
                                                 boolean sameConversation) {
        if (newHasDirectAction) return true;         // fresh real geometry always wins
        if (oldUsable && sameConversation) return false; // actionless re-post: keep the good one
        return true;                                 // nothing worth keeping / other conversation
    }

    /** true ⇒ the generate-time re-probe may adopt THIS live notification. */
    public static boolean mayAdoptLive(boolean storedIdentifiable,
                                       boolean sameConversation,
                                       boolean liveHasDirectAction) {
        return storedIdentifiable && sameConversation && liveHasDirectAction;
    }
}
