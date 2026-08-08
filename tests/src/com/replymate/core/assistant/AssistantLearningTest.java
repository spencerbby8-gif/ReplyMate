package com.replymate.core.assistant;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-8 (LEARNING IS CORE): the notification surface must feed the same
 *  learning contract as the manual screen — approve-and-send and copy-as-is are
 *  signals of "the style landed", the Regenerate button is "another take please".
 *  All per-contact, all through the shared gates (private/memory-off/off/paused),
 *  never cross-contact. If a notification action ever stops feeding learning or
 *  starts leaking, these pins fail first. */
public class AssistantLearningTest {

    private Fakes.LearningStoreFake store;
    private LearningService learning;

    @Before public void setUp() {
        store = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(store, new Fakes.KvStoreFake());
    }

    @Test public void quickSendIsAnApprovalWithSendProvenance() {
        Contact ada = Fakes.contact(1, "Ada");
        AssistantLearning.onQuickSent(learning, ada, Long.valueOf(42));
        List<StyleSignal> sigs = store.byContact(1, 10);
        assertEquals(1, sigs.size());
        assertEquals(StyleSignal.Kind.APPROVED, sigs.get(0).kind);
        assertEquals(AssistantLearning.DETAIL_SENT_QUICK, sigs.get(0).detail);
        assertEquals(Long.valueOf(42), sigs.get(0).draftId);
    }

    @Test public void copyFromAlertIsTheSameApprovalAsTheManualScreen() {
        AssistantLearning.onCopied(learning, Fakes.contact(1, "Ada"), Long.valueOf(7));
        List<StyleSignal> sigs = store.byContact(1, 10);
        assertEquals(1, sigs.size());
        assertEquals(StyleSignal.Kind.APPROVED, sigs.get(0).kind);
        // identical detail to ConversationActivity's copy path → same counters bucket
        assertEquals("copied-as-is", sigs.get(0).detail);
    }

    @Test public void regenerateButtonMatchesTheManualRegenerateDetail() {
        AssistantLearning.onRegenerate(learning, Fakes.contact(1, "Ada"));
        List<StyleSignal> sigs = store.byContact(1, 10);
        assertEquals(1, sigs.size());
        assertEquals(StyleSignal.Kind.REGENERATED, sigs.get(0).kind);
        // identical detail to ConversationActivity's re-generate → threshold parity
        assertEquals("re-generate", sigs.get(0).detail);
    }

    @Test public void learningGatesStillBindTheNotificationSurface() {
        Contact priv = Fakes.contact(1, "Ada");
        priv.privateMode = true;
        AssistantLearning.onQuickSent(learning, priv, null);
        AssistantLearning.onRegenerate(learning, priv);
        AssistantLearning.onCopied(learning, priv, null);
        assertTrue("private contacts never feed learning, from ANY surface",
            store.byContact(1, 10).isEmpty());

        Contact noMem = Fakes.contact(2, "Bode");
        noMem.memoryEnabled = false;
        AssistantLearning.onQuickSent(learning, noMem, null);
        assertTrue(store.byContact(2, 10).isEmpty());
    }

    @Test public void signalsStayInsideTheirContact() {
        AssistantLearning.onQuickSent(learning, Fakes.contact(1, "Ada"), null);
        AssistantLearning.onRegenerate(learning, Fakes.contact(2, "Bode"));
        assertEquals(1, store.byContact(1, 10).size());
        assertEquals(1, store.byContact(2, 10).size());
        assertEquals(StyleSignal.Kind.APPROVED, store.byContact(1, 10).get(0).kind);
        assertEquals(StyleSignal.Kind.REGENERATED, store.byContact(2, 10).get(0).kind);
    }

    @Test public void notificationSignalsDriveTheSameHintEngine() {
        Contact ada = Fakes.contact(1, "Ada");
        // 5 regenerations, zero approvals ⇒ the "vary wording" hint must derive
        for (int i = 0; i < 5; i++) AssistantLearning.onRegenerate(learning, ada);
        List<com.replymate.core.learning.LearningEngine.Hint> hints = learning.hintsFor(ada);
        assertFalse("notification-surface signals must reach hint derivation", hints.isEmpty());
        boolean found = false;
        for (com.replymate.core.learning.LearningEngine.Hint h : hints) {
            if (h.line.contains("vary the wording")) found = true;
        }
        assertTrue("5 regen vs 0 approvals ⇒ 'vary the wording' hint", found);
    }
}
