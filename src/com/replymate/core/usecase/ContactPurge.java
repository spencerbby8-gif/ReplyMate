package com.replymate.core.usecase;

import com.replymate.core.ports.KvStore;
import java.util.ArrayList;
import java.util.List;

/** P-intelligence-4 (press-and-hold Delete): the SINGLE source of truth for which
 *  kv keys stick to a contact. DB rows cascade through foreign keys, but kv is a
 *  plain key space — without this, deleting a conversation would leave per-contact
 *  assistant state (reply target, alert flags), learning toggles and the learned-
 *  style cache orphaned forever. Pure + small so the JVM suite pins every family.
 *
 *  STRICT ISOLATION: only keys EXPLICITLY built with this contact's id/draft ids
 *  are touched — another contact's keys are provably left alone. */
public final class ContactPurge {

    private ContactPurge() { }

    /** Every kv key derived from this contact's id (+ its draft ids). */
    public static List<String> kvKeysFor(long contactId, List<Long> draftIds) {
        List<String> keys = new ArrayList<String>();
        // assistant pipeline state (AssistantPlanner formulas)
        keys.add("assistant.target." + contactId);
        keys.add("assistant.hash." + contactId);
        keys.add("assistant.alerted." + contactId);
        // per-contact learning toggles (LearningService formulas)
        keys.add("learn." + contactId + ".off");
        keys.add("learn." + contactId + ".paused");
        // learned-style cache (MemoryService formula)
        keys.add("style." + contactId + ".approved.v2");
        // manual-send learner dedupe markers, keyed by DRAFT id
        if (draftIds != null) {
            for (Long d : draftIds) {
                if (d != null && d.longValue() > 0) keys.add("manual.learned." + d);
            }
        }
        return keys;
    }

    /** Delete all of them; returns how many actually existed. */
    public static int purge(KvStore kv, long contactId, List<Long> draftIds) {
        if (kv == null) return 0;
        int removed = 0;
        for (String key : kvKeysFor(contactId, draftIds)) {
            if (!kv.get(key, "").isEmpty()) removed++;
            kv.delete(key);
        }
        return removed;
    }
}
