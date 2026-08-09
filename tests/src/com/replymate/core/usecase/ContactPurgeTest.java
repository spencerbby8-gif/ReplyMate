package com.replymate.core.usecase;

import com.replymate.fakes.Fakes;
import com.replymate.core.ports.KvStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-4 pins (directive 2 — press-and-hold Delete): deleting a
 *  conversation must wipe exactly THIS contact's kv families — assistant state,
 *  learning toggles, learned-style cache, manual-send dedupe markers — and never
 *  a neighbour's key (strict isolation in BOTH directions). */
public final class ContactPurgeTest {

    private static KvStore seeded() {
        KvStore kv = new Fakes.KvStoreFake();
        // contact 7's families
        kv.put("assistant.target.7", "{}");
        kv.put("assistant.hash.7", "abc");
        kv.put("assistant.alerted.7", "1");
        kv.put("learn.7.off", "0");
        kv.put("learn.7.paused", "900");
        kv.put("style.7.approved.v2", "[]");
        kv.put("manual.learned.42", "1");
        kv.put("manual.learned.43", "1");
        // contact 8's must survive untouched
        kv.put("assistant.target.8", "{}");
        kv.put("learn.8.off", "1");
        kv.put("style.8.approved.v2", "[\"x\"]");
        kv.put("manual.learned.99", "1");
        // global keys must survive
        kv.put("provider.plan.gemini", "paid");
        kv.put("listener.diag_ring", "…");
        return kv;
    }

    @Test public void keyListCoversEveryKnownFamily() {
        List<String> keys = ContactPurge.kvKeysFor(7, Arrays.asList(42L, 43L));
        assertTrue(keys.contains("assistant.target.7"));
        assertTrue(keys.contains("assistant.hash.7"));
        assertTrue(keys.contains("assistant.alerted.7"));
        assertTrue(keys.contains("learn.7.off"));
        assertTrue(keys.contains("learn.7.paused"));
        assertTrue(keys.contains("style.7.approved.v2"));
        assertTrue(keys.contains("manual.learned.42"));
        assertTrue(keys.contains("manual.learned.43"));
        assertEquals(8, keys.size());
    }

    @Test public void purgeRemovesExactlyThisContactsState() {
        KvStore kv = seeded();
        int removed = ContactPurge.purge(kv, 7, Arrays.asList(42L, 43L));
        assertEquals("all eight seeded families existed", 8, removed);

        for (String k : ContactPurge.kvKeysFor(7, Arrays.asList(42L, 43L))) {
            assertTrue("wiped: " + k, kv.get(k, "").isEmpty());
        }
        // strict isolation: the neighbour + globals are byte-identical
        assertEquals("{}", kv.get("assistant.target.8", ""));
        assertEquals("1", kv.get("learn.8.off", ""));
        assertEquals("[\"x\"]", kv.get("style.8.approved.v2", ""));
        assertEquals("1", kv.get("manual.learned.99", ""));
        assertEquals("paid", kv.get("provider.plan.gemini", ""));
        assertFalse(kv.get("listener.diag_ring", "").isEmpty());
    }

    @Test public void purgeToleratesMissingKeysAndNullDraftIds() {
        KvStore kv = new Fakes.KvStoreFake();
        kv.put("assistant.alerted.5", "1");
        assertEquals(1, ContactPurge.purge(kv, 5, null));
        assertEquals(0, ContactPurge.purge(kv, 5, Collections.<Long>emptyList())); // idempotent
        assertTrue(kv.get("assistant.alerted.5", "").isEmpty());
    }
}
