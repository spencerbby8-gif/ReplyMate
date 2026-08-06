package com.replymate.core.memory;

import com.replymate.core.model.MemoryFact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Memory merge + recall ranking (BLUEPRINT §5.6, decision #7 — P4).
 *
 *  Merge policy (MemoryMergeTest guards every rule):
 *    - dedupe/merge key: (contact_id, text_norm) — normalized by FactNormalizer;
 *      within one batch, duplicates collapse to the strongest candidate first;
 *    - same key as an EXISTING fact → update in place: stronger importance/confidence
 *      wins, text refreshes, updated_at bumps, row id and pin state preserved;
 *    - PINNED facts are never rewritten or superseded (user locked them);
 *    - DISABLED facts are never resurrected by a merge (user intent preserved);
 *    - candidates below MIN_CONFIDENCE are dropped;
 *    - everything is strictly contact-scoped (isolation gate, see IsolationSuite).
 *
 *  Recall ranking for prompt L2: pinned first (recency desc), then importance desc,
 *  then recency; caller applies the ≤30 cap via rankActive(facts, limit). */
public final class MemoryEngine {

    /** Snapshot counters for jobs/UI/diagnostics. */
    public static final class MergeReport {
        public int added;
        public int updated;
        public int skippedPinned;
        public int skippedDisabled;
        public int skippedLowConfidence;
        public int skippedEmpty;
        public int dedupedWithinBatch;

        public String summary() {
            return "+" + added + " upd " + updated + " pin " + skippedPinned
                + " dis " + skippedDisabled + " low " + skippedLowConfidence
                + " empty " + skippedEmpty + " dup " + dedupedWithinBatch;
        }
    }

    private MemoryEngine() { }

    /** Merge normalized candidates into one contact's fact store. Mutates the given
     *  existing-list (loads via store beforehand); every candidate is normalized and
     *  clamped before it can touch the store. */
    public static MergeReport merge(long contactId,
                                    List<MemoryFact> existing,
                                    List<MemoryFact> candidates,
                                    FactStoreWriter writer,
                                    long now) {
        MergeReport rep = new MergeReport();
        if (candidates == null || candidates.isEmpty()) return rep;
        if (existing == null) existing = new ArrayList<MemoryFact>();

        // index current facts by merge key
        Map<String, MemoryFact> byNorm = new HashMap<String, MemoryFact>();
        for (MemoryFact f : existing) byNorm.put(f.textNorm, f);

        // collapse batch duplicates to the strongest candidate per key
        Map<String, MemoryFact> batchBest = new HashMap<String, MemoryFact>();
        List<String> order = new ArrayList<String>();
        for (MemoryFact raw : candidates) {
            if (raw == null || raw.text == null || raw.text.trim().isEmpty()) {
                rep.skippedEmpty++;
                continue;
            }
            MemoryFact c = copyClamped(raw);
            c.contactId = contactId;
            c.textNorm = FactNormalizer.normalize(c.text);
            if (c.textNorm.isEmpty()) { rep.skippedEmpty++; continue; }
            if (c.confidence < FactNormalizer.MIN_CONFIDENCE) {
                rep.skippedLowConfidence++;
                continue;
            }
            MemoryFact prev = batchBest.get(c.textNorm);
            if (prev == null) {
                order.add(c.textNorm);
                batchBest.put(c.textNorm, c);
            } else {
                rep.dedupedWithinBatch++;
                if (stronger(c, prev)) batchBest.put(c.textNorm, c);
            }
        }

        for (String norm : order) {
            MemoryFact c = batchBest.get(norm);
            MemoryFact cur = byNorm.get(norm);
            if (cur == null) {
                c.createdAt = now;
                c.updatedAt = now;
                c.id = writer.upsertFact(c);
                existing.add(c);
                byNorm.put(norm, c);
                rep.added++;
            } else if (cur.pinned) {
                rep.skippedPinned++;
            } else if (cur.disabled) {
                rep.skippedDisabled++;
            } else {
                boolean changed = false;
                if (!cur.text.equals(c.text)) { cur.text = c.text; changed = true; }
                if (c.importance > cur.importance) { cur.importance = c.importance; changed = true; }
                if (c.confidence > cur.confidence) { cur.confidence = c.confidence; changed = true; }
                if (c.category != cur.category) { cur.category = c.category; changed = true; }
                if (c.sourceMessageId != null) { cur.sourceMessageId = c.sourceMessageId; changed = true; }
                cur.updatedAt = now;
                writer.upsertFact(cur);
                if (changed) rep.updated++;
            }
        }
        return rep;
    }

    /** The write side of the store, narrowed so merge() never even SEES deletes or
     *  cross-contact operations (compile-time isolation surface). */
    public interface FactStoreWriter {
        long upsertFact(MemoryFact f);
    }

    /** Recall ranking: pinned first (recency desc), then importance desc, then recency. */
    public static List<MemoryFact> rankActive(List<MemoryFact> facts, int limit) {
        List<MemoryFact> sorted = new ArrayList<MemoryFact>();
        if (facts != null) {
            for (MemoryFact f : facts) {
                if (f != null && !f.disabled) sorted.add(f);
            }
        }
        java.util.Collections.sort(sorted, new java.util.Comparator<MemoryFact>() {
            @Override public int compare(MemoryFact a, MemoryFact b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                if (a.pinned) {
                    // within pinned: most recently touched first
                    if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt ? -1 : 1;
                }
                if (a.importance != b.importance) return b.importance - a.importance;
                if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt ? -1 : 1;
                return (int) (a.id - b.id);
            }
        });
        return limit >= 0 && sorted.size() > limit
            ? new ArrayList<MemoryFact>(sorted.subList(0, limit)) : sorted;
    }

    /** c beats prev when importance or (tie) confidence is higher. */
    private static boolean stronger(MemoryFact c, MemoryFact prev) {
        if (c.importance != prev.importance) return c.importance > prev.importance;
        return c.confidence > prev.confidence;
    }

    private static MemoryFact copyClamped(MemoryFact f) {
        MemoryFact c = new MemoryFact();
        c.contactId = f.contactId;
        c.category = f.category;
        c.text = f.text.trim();
        c.importance = FactNormalizer.clampImportance(f.importance);
        c.confidence = FactNormalizer.clampConfidence(f.confidence);
        c.pinned = f.pinned;
        c.disabled = f.disabled;
        c.sourceMessageId = f.sourceMessageId;
        return c;
    }
}
