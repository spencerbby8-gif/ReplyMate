package com.replymate.core.memory;

import com.replymate.core.model.FactCategory;
import com.replymate.core.model.MemoryFact;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Every merge rule of BLUEPRINT §5.6, executable. */
public class MemoryMergeTest {

    private static final long NOW = 4000L;

    private Fakes.MemoryStoreFake store;

    @Before public void setUp() {
        store = new Fakes.MemoryStoreFake();
    }

    private static MemoryFact cand(String text, int importance, double confidence) {
        MemoryFact f = new MemoryFact();
        f.text = text;
        f.category = FactCategory.PREFERENCE;
        f.importance = importance;
        f.confidence = confidence;
        return f;
    }

    private static List<MemoryFact> batch(MemoryFact... facts) {
        return new ArrayList<MemoryFact>(Arrays.asList(facts));
    }

    @Test public void dedupesByTextNormAcrossTextsWithCaseNoise() {
        MemoryEngine.MergeReport rep = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("Amara likes tea", 3, 0.7),
            cand("amara LIKES TEA", 5, 0.9)), store, NOW);
        assertEquals(1, rep.added);
        assertEquals(1, rep.dedupedWithinBatch);
        assertEquals(1, store.allFacts(1).size());
        MemoryFact saved = store.allFacts(1).get(0);
        assertEquals("amara likes tea", saved.textNorm);
        assertEquals(5, saved.importance);          // strongest batch candidate won
        assertEquals(0.9, saved.confidence, 1e-9);
        assertEquals(FactCategory.PREFERENCE, saved.category);
    }

    @Test public void existingFactUpdatesInPlaceKeepingIdAndRaising() {
        MemoryEngine.MergeReport first = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("Amara likes tea", 2, 0.6)), store, NOW);
        assertEquals(1, first.added);
        long id = store.allFacts(1).get(0).id;

        MemoryEngine.MergeReport second = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("Amara likes TEA!!", 5, 0.95)), store, NOW);
        assertEquals(0, second.added);
        assertEquals(1, second.updated);
        assertEquals(1, store.allFacts(1).size());
        MemoryFact f = store.allFacts(1).get(0);
        assertEquals("row id must survive merges", id, f.id);
        assertEquals(5, f.importance);
        assertEquals(0.95, f.confidence, 1e-9);
        assertEquals("Amara likes TEA!!", f.text);
        assertEquals(NOW, f.updatedAt);
    }

    @Test public void pinnedFactsAreNeverRewritten() {
        MemoryEngine.merge(1, store.allFacts(1), batch(cand("Amara likes tea", 3, 0.7)), store, NOW);
        MemoryFact f = store.allFacts(1).get(0);
        store.setFactPinned(f.id, true);

        MemoryEngine.MergeReport rep = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("amara likes tea", 5, 0.99)), store, NOW);
        assertEquals(1, rep.skippedPinned);
        assertEquals(0, rep.updated);
        MemoryFact still = store.allFacts(1).get(0);
        assertEquals(3, still.importance);          // untouched
        assertEquals("Amara likes tea", still.text);
    }

    @Test public void disabledFactsAreNeverResurrectedByMerges() {
        MemoryEngine.merge(1, store.allFacts(1), batch(cand("Amara likes tea", 3, 0.7)), store, NOW);
        long id = store.allFacts(1).get(0).id;
        store.setFactDisabled(id, true);            // user intent (or supersede)

        MemoryEngine.MergeReport rep = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("Amara likes tea", 5, 0.99)), store, NOW);
        assertEquals(1, rep.skippedDisabled);
        assertTrue(store.allFacts(1).get(0).disabled);
        assertEquals(0, store.activeFacts(1).size());
    }

    @Test public void lowConfidenceAndEmptyCandidatesAreDropped() {
        MemoryEngine.MergeReport rep = MemoryEngine.merge(1, store.allFacts(1), batch(
            cand("maybe likes tea", 3, 0.2),
            cand("   ", 3, 0.9),
            cand("!!!", 3, 0.9),
            null), store, NOW);
        assertEquals(0, rep.added);
        assertEquals(1, rep.skippedLowConfidence);
        assertEquals(3, rep.skippedEmpty);          // blank + punct-only + null
        assertTrue(store.allFacts(1).isEmpty());
    }

    @Test public void importanceIsClampedBeforeAnyWrite() {
        MemoryEngine.merge(1, store.allFacts(1), batch(cand("urgent-ish fact", 42, 0.8)), store, NOW);
        assertEquals(5, store.allFacts(1).get(0).importance);
    }

    @Test public void recallRankingPinnedThenImportanceThenRecency() {
        List<MemoryFact> facts = new ArrayList<MemoryFact>();
        facts.add(f(1, "a", false, 5, 100));
        facts.add(f(2, "b", false, 5, 300));        // same importance, newer
        facts.add(f(3, "c", true, 1, 50));          // pinned beats everything
        facts.add(f(4, "d", false, 2, 999));
        facts.add(f(5, "e", true, 1, 900));         // pinned, newer than c
        List<MemoryFact> ranked = MemoryEngine.rankActive(facts, 30);
        assertEquals(5, ranked.get(0).id);          // pinned newest first
        assertEquals(3, ranked.get(1).id);
        assertEquals(2, ranked.get(2).id);          // importance 5, recency 300
        assertEquals(1, ranked.get(3).id);          // importance 5, recency 100
        assertEquals(4, ranked.get(4).id);          // importance 2 last
    }

    @Test public void recallCapAndDisabledFiltered() {
        List<MemoryFact> facts = new ArrayList<MemoryFact>();
        for (int i = 0; i < 40; i++) facts.add(f(i + 1, "f" + i, false, 3, i));
        MemoryFact off = f(999, "off", true, 5, 5000);
        off.disabled = true;
        facts.add(off);
        List<MemoryFact> ranked = MemoryEngine.rankActive(facts, 30);
        assertEquals(30, ranked.size());
        for (MemoryFact f : ranked) assertFalse(f.disabled);
    }

    private static MemoryFact f(long id, String text, boolean pinned, int importance, long updatedAt) {
        MemoryFact f = new MemoryFact();
        f.id = id;
        f.text = text;
        f.textNorm = text;
        f.pinned = pinned;
        f.importance = importance;
        f.updatedAt = updatedAt;
        return f;
    }
}
