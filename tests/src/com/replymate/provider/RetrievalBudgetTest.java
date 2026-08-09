package com.replymate.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.replymate.core.search.WebEvidence;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.retrieval.WikimediaRetrieval;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** P-background-8 (REPRO + pin): the encyclopedia fallback runs synchronously
 *  INSIDE the background generation — with the shared provider timeouts
 *  (15s connect / 45s read, ×2 endpoints) a hung or crawling network used to
 *  park the draft thread for up to ~2 minutes per message, and two such drafts
 *  saturate the 2-thread background pool: on real devices "background
 *  generation is broken". The contract: a lookup can NEVER park generation —
 *  hard wall-clock budget, honestly empty beyond it; fast networks untouched. */
public class RetrievalBudgetTest {

    static final class SleepingTransport implements WikimediaRetrieval.Transport {
        final long sleepMs;
        final String answer;
        int calls = 0;
        SleepingTransport(long sleepMs, String answer) {
            this.sleepMs = sleepMs;
            this.answer = answer;
        }
        @Override public HttpResponse get(String url, Map<String, String> headers) {
            calls++;
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new HttpResponse(0, "", java.util.Collections.<String, String>emptyMap());
            }
            return new HttpResponse(200, answer, java.util.Collections.<String, String>emptyMap());
        }
    }

    private static final String WIKI_OK = "{\"type\":\"standard\",\"title\":\"Odogwu\","
        + "\"extract\":\"Odogwu is an Igbo title meaning a great man or leader,"
        + " often used as a heroic epithet in Nigerian speech today.\"}";

    /** The killer the owner described: the network ACCEPTS the connection and
     *  NEVER answers (the old code waited out the shared 45s read timeout on
     *  EACH endpoint — the draft thread parked ~90s, and two such drafts
     *  saturated the whole 2-thread background pool). */
    @Test public void aHungNetworkIsAbandonedInsideTheBudget() {
        SleepingTransport t = new SleepingTransport(45_000, "{}");
        WikimediaRetrieval r = new WikimediaRetrieval(t);
        long t0 = System.currentTimeMillis();
        List<WebEvidence> out = r.lookup("odogwu");
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("lookup must abandon a hung endpoint at the hard deadline, took "
                + elapsed + "ms", elapsed < 13_000);
        assertEquals("a dead first leg burns the budget — the second endpoint is not even tried",
            1, t.calls);
        assertTrue("a timed-out lookup is honestly empty, never a crash", out.isEmpty());
    }

    @Test public void aCrawlingTransportNeverExceedsTheTotalBudget() {
        // Both legs crawl but answer (spotty data): the second leg must be cut
        // at the SHARED deadline instead of stacking another full wait.
        SleepingTransport t = new SleepingTransport(3_500, "{}");
        long t0 = System.currentTimeMillis();
        List<WebEvidence> out = new WikimediaRetrieval(t).lookup("odogwu");
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("two crawling legs are capped by the shared budget, took "
                + elapsed + "ms", elapsed < 12_000);
        assertTrue(out.isEmpty());
    }

    @Test public void aSlowHealthyLookupInsideTheBudgetStillAnswers() {
        // The feature itself stays: a healthy-but-not-instant endpoint answers,
        // and a successful first leg skips the second entirely.
        SleepingTransport t = new SleepingTransport(600, WIKI_OK);
        long t0 = System.currentTimeMillis();
        List<WebEvidence> out = new WikimediaRetrieval(t).lookup("odogwu");
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("healthy lookups still return evidence", !out.isEmpty());
        assertTrue(out.get(0).snippet.contains("Igbo"));
        assertEquals(1, t.calls);
        assertTrue("fast answers stay fast, took " + elapsed + "ms", elapsed < 6_000);
    }

    /** The AppContainer wiring contract: the retrieval transport is NOT the
     *  shared provider client — it carries its own tight per-call timeouts so a
     *  black-holed read gives up in seconds, not 45. */
    @Test public void theProductionRetrievalClientIsTight() {
        HttpClient c = WikimediaRetrieval.tightHttpClient();
        assertTrue("connect timeout must be seconds, not the shared 15s",
            c.connectTimeoutMs() <= 5_000);
        assertTrue("read timeout must be seconds, not the shared 45s",
            c.readTimeoutMs() <= 8_000);
        assertEquals(15_000, HttpClient.DEFAULT_CONNECT_MS);   // provider calls untouched
        assertEquals(45_000, HttpClient.DEFAULT_READ_MS);
    }
}
