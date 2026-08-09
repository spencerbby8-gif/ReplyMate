package com.replymate.core.search;

import com.replymate.fakes.Fakes;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 8: the on-device lookup cache — repeat looks for
 *  the same subject inside a week are FREE (native billing avoided, encyclopedia
 *  traffic avoided), corruption never crashes a reply, staleness reads as a
 *  fresh miss. */
public final class SearchCacheTest {

    private static final long T0 = 1_700_000_000_000L;

    private static WebEvidence ev(String t) {
        return new WebEvidence(t, t + " — a short verified snippet.", "Wikipedia");
    }

    @Test public void putThenGetRoundTripsSameWeek() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        SearchCache.put(kv, "odogwu", Arrays.asList(ev("Odogwu")), T0);
        List<WebEvidence> hit = SearchCache.get(kv, "odogwu", T0 + 1000);
        assertNotNull(hit);
        assertEquals(1, hit.size());
        assertEquals("Odogwu", hit.get(0).title);
        assertEquals("Wikipedia", hit.get(0).source);
        assertTrue(hit.get(0).snippet.contains("verified snippet"));
    }

    @Test public void subjectKeysAreNormalised() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        SearchCache.put(kv, "  Odogwu!! ", Arrays.asList(ev("Odogwu")), T0);
        assertNotNull("case + punctuation + spacing all hit the same entry",
            SearchCache.get(kv, "odogwu", T0));
        assertNotNull(SearchCache.get(kv, "ODOGWU", T0));
        assertNull("a different subject never aliases", SearchCache.get(kv, "kaku", T0));
    }

    @Test public void staleEntriesReadAsAMiss() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        SearchCache.put(kv, "fuel price", Arrays.asList(ev("Fuel")), T0);
        assertNull("past the 7-day TTL the lookup must re-run",
            SearchCache.get(kv, "fuel price", T0 + SearchCache.TTL_MS + 1));
        assertNotNull("just inside the TTL it still serves",
            SearchCache.get(kv, "fuel price", T0 + SearchCache.TTL_MS - 1));
    }

    @Test public void corruptionNeverCrashesAReply() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        kv.put("search.v1.odogwu", "{not json at all");
        assertNull(SearchCache.get(kv, "odogwu", T0));
        kv.put("search.v1.kaku", "{\"q\":\"kaku\",\"at\":1}");   // no facts array
        assertNull(SearchCache.get(kv, "kaku", T0));
    }

    @Test public void emptySubjectsAndEmptyFactsStoreNothing() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        SearchCache.put(kv, "  ", Arrays.asList(ev("X")), T0);
        SearchCache.put(kv, "odogwu",
            java.util.Collections.<WebEvidence>emptyList(), T0);
        SearchCache.put(kv, "odogwu", null, T0);
        assertNull(SearchCache.get(kv, "odogwu", T0));
    }

    @Test public void evidenceIsBoundedToTwoEntries() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        SearchCache.put(kv, "odogwu",
            Arrays.asList(ev("One"), ev("Two"), ev("Three")), T0);
        List<WebEvidence> hit = SearchCache.get(kv, "odogwu", T0);
        assertNotNull(hit);
        assertTrue("the cache never grows an unbounded prompt", hit.size() <= 2);
    }
}
