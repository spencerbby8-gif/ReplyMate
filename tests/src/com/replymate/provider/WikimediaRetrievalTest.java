package com.replymate.provider;

import com.replymate.core.search.WebEvidence;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.retrieval.WikimediaRetrieval;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directives 2/6: the encyclopedia fallback — only official,
 *  free, keyless Wikimedia endpoints; a descriptive honest User-Agent; parsed
 *  evidence is bounded + attributed; failures degrade to an EMPTY LIST (the
 *  anti-hallucination rule takes over), never an exception, never invented facts. */
public final class WikimediaRetrievalTest {

    /** Scriptable transport: canned answers per URL substring, records everything. */
    private static final class FakeTransport implements WikimediaRetrieval.Transport {
        final List<String> urls = new ArrayList<String>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        final Map<String, HttpResponse> answers = new HashMap<String, HttpResponse>();
        boolean throwOnCall = false;

        FakeTransport serve(String urlPart, int code, String body) {
            answers.put(urlPart, new HttpResponse(code, body, null));
            return this;
        }

        @Override public HttpResponse get(String url, Map<String, String> h) {
            urls.add(url);
            headers.add(h);
            if (throwOnCall) throw new RuntimeException("network down");
            for (Map.Entry<String, HttpResponse> e : answers.entrySet()) {
                if (url.contains(e.getKey())) return e.getValue();
            }
            return new HttpResponse(404, "{}", null);
        }
    }

    private static final String WIKI_OK =
        "{\"type\":\"standard\",\"title\":\"Odogwu\","
        + "\"extract\":\"Odogwu is an Igbo title meaning a man of great esteem."
        + " It is widely used in Nigerian culture and music.\"}";

    private static final String WIKT_OK =
        "{\"query\":{\"pages\":{\"12345\":{\"title\":\"kaku\","
        + "\"extract\":\"Kaku is a term used in Ghanaian and Nigerian pidgin"
        + " to describe dressing extravagantly or showing off.\"}}}}";

    /* ----------------------------------------------------------- page titles */

    @Test public void questionLikeSubjectsHonestlyDecline() {
        assertEquals("", WikimediaRetrieval.toTitle("who won the arsenal game last night?"));
        assertEquals("", WikimediaRetrieval.toTitle("wetin be fuel price for lagos this week"));
        assertEquals("", WikimediaRetrieval.toTitle(
            "a very long subject with far too many words to be a page"));
    }

    @Test public void termSubjectsBecomePlausibleTitles() {
        assertEquals("odogwu", WikimediaRetrieval.toTitle("odogwu"));
        assertEquals("Burna Boy", WikimediaRetrieval.toTitle("  Burna   Boy "));
    }

    /* --------------------------------------------------------------- parsers */

    @Test public void wikipediaSummaryParsesBoundedAttributedEvidence() {
        WebEvidence e = WikimediaRetrieval.parseWikipedia(WIKI_OK);
        assertNotNull(e);
        assertEquals("Odogwu", e.title);
        assertEquals("Wikipedia", e.source);
        assertTrue(e.snippet.startsWith("Odogwu is an Igbo title"));
        assertTrue("evidence stays bounded", e.snippet.length() <= 220);
    }

    @Test public void disambiguationAndThinPagesAreSkipped() {
        assertNull(WikimediaRetrieval.parseWikipedia(
            "{\"type\":\"disambiguation\",\"title\":\"Jaguar\",\"extract\":\"Jaguar may"
            + " refer to several things, often looked up instead.\"}"));
        assertNull(WikimediaRetrieval.parseWikipedia(
            "{\"type\":\"standard\",\"title\":\"X\",\"extract\":\"Too short.\"}"));
        assertNull(WikimediaRetrieval.parseWikipedia(
            "{\"type\":\"no-extract\",\"title\":\"Y\"}"));
    }

    @Test public void wiktionaryUnknownPageIdStillParses() {
        WebEvidence e = WikimediaRetrieval.parseWiktionary(WIKT_OK);
        assertNotNull(e);
        assertEquals("kaku", e.title);
        assertEquals("Wiktionary", e.source);
        assertTrue(e.snippet.contains("pidgin"));
        assertNull(WikimediaRetrieval.parseWiktionary(
            "{\"query\":{\"pages\":{\"-1\":{\"title\":\"zzzz\",\"missing\":\"\"}}}}"));
    }

    /* ------------------------------------------------------------ the lookup */

    @Test public void wikipediaMissFallsThroughToWiktionary() {
        FakeTransport t = new FakeTransport()
            .serve("wikipedia.org", 404, "{\"title\":\"Not found\"}")
            .serve("wiktionary.org", 200, WIKT_OK);
        List<WebEvidence> out = new WikimediaRetrieval(t).lookup("kaku");
        assertEquals(1, out.size());
        assertEquals("Wiktionary", out.get(0).source);
        assertEquals("both endpoints were consulted, in order",
            2, t.urls.size());
        assertTrue(t.urls.get(0).contains("wikipedia.org/api/rest_v1/page/summary/kaku"));
        assertTrue(t.urls.get(1).contains("wiktionary.org/w/api.php"));
        assertTrue(t.urls.get(1).contains("titles=kaku"));
    }

    @Test public void wikipediaHitDoesNotDoubleFetchWiktionary() {
        FakeTransport t = new FakeTransport()
            .serve("wikipedia.org", 200, WIKI_OK)
            .serve("wiktionary.org", 200, WIKT_OK);
        List<WebEvidence> out = new WikimediaRetrieval(t).lookup("odogwu");
        assertEquals(1, out.size());
        assertEquals("Wikipedia", out.get(0).source);
        assertEquals(1, t.urls.size());
    }

    @Test public void theUserAgentIsHonestAndDescriptive() {
        FakeTransport t = new FakeTransport().serve("wikipedia.org", 200, WIKI_OK);
        new WikimediaRetrieval(t).lookup("odogwu");
        String ua = t.headers.get(0).get("User-Agent");
        assertNotNull("Wikimedia policy requires a UA", ua);
        assertTrue(ua.contains("ReplyMate"));
    }

    @Test public void failuresDegradeToEmptyNeverAnException() {
        FakeTransport down = new FakeTransport();
        down.throwOnCall = true;
        assertTrue(new WikimediaRetrieval(down).lookup("odogwu").isEmpty());
        FakeTransport garbage = new FakeTransport().serve("wikipedia.org", 200, "<html>nope");
        assertTrue("malformed JSON can never become invented facts",
            new WikimediaRetrieval(garbage).lookup("odogwu").isEmpty());
        assertTrue(new WikimediaRetrieval(new FakeTransport()).lookup(null).isEmpty());
        assertTrue(new WikimediaRetrieval(new FakeTransport()).lookup("   ").isEmpty());
    }
}
