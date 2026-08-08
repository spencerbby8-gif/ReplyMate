package com.replymate.core.assistant;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;

/** P-background-8 (LEARNING IS CORE): the notification-surface actions feed the
 *  SAME learning contract as the manual conversation screen — approved sends and
 *  copies are approvals, the Regenerate button is a regeneration, swiping the
 *  draft alert away is a rejection. Detail strings deliberately match
 *  ConversationActivity so counters treat both surfaces identically.
 *  Every call flows through LearningService.record, so the existing gates
 *  (private mode, memory off, learning off/paused) apply here too — pure JVM so
 *  the mapping is unit-pinned, not assumed. */
public final class AssistantLearning {

    /** Same detail strings as ConversationActivity's manual paths. */
    public static final String DETAIL_SENT_QUICK = "sent-quick-reply";
    public static final String DETAIL_COPIED = "copied-as-is";
    public static final String DETAIL_REGEN = "re-generate";

    private AssistantLearning() {
    }

    /** Human approved the draft and it actually fired through the app's reply target. */
    public static void onQuickSent(LearningService learning, Contact contact, Long draftId) {
        if (learning == null) return;
        learning.record(contact, StyleSignal.Kind.APPROVED, DETAIL_SENT_QUICK, draftId);
    }

    /** Human copied the draft from the alert (an as-is approval of this style). */
    public static void onCopied(LearningService learning, Contact contact, Long draftId) {
        if (learning == null) return;
        learning.record(contact, StyleSignal.Kind.APPROVED, DETAIL_COPIED, draftId);
    }

    /** The Regenerate button: the owner asked for a fresh take over an existing draft. */
    public static void onRegenerate(LearningService learning, Contact contact) {
        if (learning == null) return;
        learning.record(contact, StyleSignal.Kind.REGENERATED, DETAIL_REGEN, null);
    }

    /* NOTE: a dismissed (swiped-away) draft alert is deliberately NOT recorded.
     *  Android fires the same delete-intent on auto-cancel after ANY button/body
     *  tap, so a true swipe-rejection can't be told apart from an action tap —
     *  counting it would inject false REJECTED signals. True rejections arrive
     *  from the conversation screen's Delete button (unambiguous provenance). */
}
