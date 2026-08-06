package com.replymate.core.memory;

import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.FactCategory;
import com.replymate.core.model.MemoryFact;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** RELEASE GATE (BLUEPRINT §8 P4): per-contact isolation of the memory layers.
 *  Contact A's facts/summaries must never appear in, merge into, or be mutated by
 *  contact B's operations — in EITHER direction. This suite must stay green for
 *  every release from P4 on. */
public class IsolationSuite {

    private static final long A = 1;
    private static final long B = 2;
    private static final String A_SECRET = "A-SECRET: Amara is afraid of flying";
    private static final String B_SECRET = "B-SECRET: Bode collects stamps";

    private Fakes.MemoryStoreFake store;

    @Before public void setUp() {
        store = new Fakes.MemoryStoreFake();
    }

    private static MemoryFact cand(String text) {
        MemoryFact f = new MemoryFact();
        f.text = text;
        f.category = FactCategory.PERSON;
        f.importance = 4;
        f.confidence = 0.9;
        return f;
    }

    @Test public void mergingForANeverTouchesB() {
        MemoryEngine.merge(A, store.allFacts(A),
            new ArrayList<MemoryFact>(Arrays.asList(cand(A_SECRET))), store, 1000L);
        assertEquals(1, store.allFacts(A).size());
        assertTrue("A's fact must not leak into B's store", store.allFacts(B).isEmpty());
        assertTrue(store.activeFacts(B).isEmpty());
    }

    @Test public void mergeForcesCandidateContactScope() {
        // A hostile/buggy job arming a candidate with someone else's contactId:
        MemoryFact trojan = cand(A_SECRET);
        trojan.contactId = B;                        // lie on the candidate
        MemoryEngine.merge(A, store.allFacts(A),
            new ArrayList<MemoryFact>(Arrays.asList(trojan)), store, 1000L);
        assertEquals("engine re-scopes to the caller's contact", A,
            store.allFacts(A).get(0).contactId);
        assertTrue(store.allFacts(B).isEmpty());
    }

    @Test public void sameTextNormOnTwoContactsStaysSeparate() {
        // Same sentence from two different people = two different facts, no cross-merge.
        MemoryEngine.merge(A, store.allFacts(A),
            new ArrayList<MemoryFact>(Arrays.asList(cand("likes tea"))), store, 1000L);
        MemoryEngine.merge(B, store.allFacts(B),
            new ArrayList<MemoryFact>(Arrays.asList(cand("LIKES TEA"))), store, 2000L);
        assertEquals(1, store.allFacts(A).size());
        assertEquals(1, store.allFacts(B).size());
        assertEquals("likes tea", store.allFacts(A).get(0).text);
        assertEquals("LIKES TEA", store.allFacts(B).get(0).text);
        // later update on B must not bump A's row
        MemoryEngine.MergeReport rep = MemoryEngine.merge(B, store.allFacts(B),
            new ArrayList<MemoryFact>(Arrays.asList(cand("likes TEA!!"))), store, 3000L);
        assertEquals(1, rep.updated);
        assertEquals(1000L, store.allFacts(A).get(0).updatedAt);
    }

    @Test public void recallForBExcludesAEvenWithPinnedBait() {
        MemoryEngine.merge(A, store.allFacts(A),
            new ArrayList<MemoryFact>(Arrays.asList(cand(A_SECRET))), store, 1000L);
        MemoryFact pinned = store.allFacts(A).get(0);
        store.setFactPinned(pinned.id, true);        // pinned ranks #1 — worst-case bait
        for (MemoryFact f : MemoryEngine.rankActive(store.activeFacts(B), 30)) {
            assertFalse("isolation breach: A fact visible to B", f.text.contains("A-SECRET"));
        }
        assertTrue(MemoryEngine.rankActive(store.activeFacts(B), 30).isEmpty());
    }

    @Test public void summariesAreContactScoped() {
        ContactSummary sa = new ContactSummary();
        sa.contactId = A;
        sa.summaryText = "A-only summary " + A_SECRET;
        sa.version = 1;
        store.insertSummary(sa);
        assertNull(store.latestSummary(B));
        assertTrue(store.latestSummary(A).summaryText.contains("A-SECRET"));
    }

    @Test public void deleteAllForContactLeavesTheOtherUntouched() {
        MemoryEngine.merge(A, store.allFacts(A),
            new ArrayList<MemoryFact>(Arrays.asList(cand(A_SECRET))), store, 1L);
        MemoryEngine.merge(B, store.allFacts(B),
            new ArrayList<MemoryFact>(Arrays.asList(cand(B_SECRET))), store, 1L);
        store.deleteAllForContact(A);
        assertTrue(store.allFacts(A).isEmpty());
        assertEquals(1, store.allFacts(B).size());
        assertTrue(store.allFacts(B).get(0).text.contains("B-SECRET"));
    }

    /* ------------------------------------------------ P4 style + learning gates */

    @Test public void styleRowsForBNeverShapeAVoice() {
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        com.replymate.core.style.StyleService styles =
            Fakes.styleService(settings, learning);

        settings.put(B, "tone", "2");
        settings.put(B, "custom.prompt", "B-SECRET custom prompt");

        com.replymate.core.model.Contact aContact = new com.replymate.core.model.Contact();
        aContact.id = A;
        aContact.displayName = "Amara";
        com.replymate.core.style.StyleService.ComposedVoice v = styles.compose(aContact);
        String all = v.voiceLine + v.extraLines + v.why.toString();
        assertFalse("B's custom prompt leaked into A", all.contains("B-SECRET"));
        assertFalse("B's tone override leaked into A", v.voiceLine.contains("direct and to the point"));
    }

    @Test public void learningSignalsNeverCrossContacts() {
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, kv);

        com.replymate.core.model.Contact aContact = new com.replymate.core.model.Contact();
        aContact.id = A;
        aContact.displayName = "Amara";
        com.replymate.core.model.Contact bContact = new com.replymate.core.model.Contact();
        bContact.id = B;
        bContact.displayName = "Bode";

        for (int i = 0; i < 5; i++) {
            learning.record(aContact, com.replymate.core.model.StyleSignal.Kind.EDITED, "shorter", null);
        }
        assertEquals(5, learning.counters(A).total());
        assertEquals("A's signals must not count for B", 0, learning.counters(B).total());
        assertTrue("B gets no hints derived from A", learning.hintsFor(bContact).isEmpty());

        // per-contact switches are independent too
        learning.setOff(A, true);
        assertTrue(learning.isOff(A));
        assertFalse(learning.isOff(B));
    }
}
