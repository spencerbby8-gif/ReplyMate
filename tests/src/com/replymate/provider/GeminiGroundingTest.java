package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.gemini.GeminiParser;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directives 2/3/6: Gemini's native grounding + thinking —
 *  official REST casing on the wire, thought parts NEVER leak into drafts,
 *  grounding metadata becomes safe counters/titles, and a model that rejects
 *  the extras degrades to a plain call WITH an honest note. */
public final class GeminiGroundingTest {

    private static ChatRequest req(boolean search, String reasoning) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: who won the game?"));
        return new ChatRequest("sys", turns, Turn.user("reply"),
            GenerationOpts.of(1, 0.8, 220).withSearch(search).withReasoning(reasoning));
    }

    /* ---------------------------------------------------------------- wire */

    @Test public void searchAttachesGoogleSearchWithRestCasing() {
        String body = GeminiPayloads.generateBody(req(true, "default"),
            false, true, "default", "gemini-2.5-flash");
        JsonObj b = Json.parseObj(body);
        assertTrue(b.arr("tools").obj(0).has("google_search"));
    }

    @Test public void gemini3GetsThinkingLevel25GetsThinkingBudget() {
        JsonObj g3 = Json.parseObj(GeminiPayloads.generateBody(req(false, "high"),
            false, false, "high", "gemini-3-flash"));
        assertEquals("high", g3.obj("generationConfig")
            .obj("thinkingConfig").str("thinkingLevel"));
        JsonObj g25 = Json.parseObj(GeminiPayloads.generateBody(req(false, "high"),
            false, false, "high", "gemini-2.5-pro"));
        assertEquals(4096L, g25.obj("generationConfig")
            .obj("thinkingConfig").lng("thinkingBudget", 0));
        JsonObj g25low = Json.parseObj(GeminiPayloads.generateBody(req(false, "low"),
            false, false, "low", "gemini-2.5-pro"));
        assertEquals(512L, g25low.obj("generationConfig")
            .obj("thinkingConfig").lng("thinkingBudget", 0));
    }

    @Test public void defaultReasoningAndNonThinkingModelsSendNothing() {
        JsonObj plain = Json.parseObj(GeminiPayloads.generateBody(req(false, "default"),
            false, false, "default", "gemini-2.5-flash"));
        assertFalse(plain.obj("generationConfig").has("thinkingConfig"));
        JsonObj old = Json.parseObj(GeminiPayloads.generateBody(req(false, "high"),
            false, false, "high", "gemini-1.5-flash"));
        assertFalse("never a guessed param for models without the control",
            old.obj("generationConfig").has("thinkingConfig"));
        assertFalse("includeThoughts is never requested — thoughts stay private",
            Json.parseObj(GeminiPayloads.generateBody(req(false, "high"),
                false, false, "high", "gemini-2.5-pro"))
                .obj("generationConfig").has("includeThoughts"));
    }

    /* --------------------------------------------------------------- parsing */

    @Test public void thoughtsNeverLeakGroundingBecomesMetadata() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":["
            + "{\"text\":\"let me think privately\",\"thought\":true},"
            + "{\"text\":\"Arsenal won 2-1, Saka scored the winner.\"}],"
            + "\"role\":\"model\"},\"finishReason\":\"STOP\","
            + "\"groundingMetadata\":{"
            + "\"webSearchQueries\":[\"arsenal result tonight\",\"premier league\"],"
            + "\"groundingChunks\":["
            + "{\"web\":{\"title\":\"BBC Sport\",\"uri\":\"https://bbc.co.uk/sport\"}},"
            + "{\"web\":{\"title\":\"Sky Sports\",\"uri\":\"https://skysports.com\"}}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":20}}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertTrue("parse failed: " + r.error, r.ok);
        String all = r.value.variants.toString();
        assertTrue(all.contains("Arsenal won 2-1"));
        assertFalse("thought parts NEVER become drafts", all.contains("privately"));
        assertEquals(2, r.value.searchQueries);
        assertEquals(2, r.value.searchSources.size());
        assertTrue(r.value.searchSources.contains("BBC Sport"));
    }

    /* ------------------------------------------------- graceful degradation */

    private static final class ExtrasRejectingHttp extends HttpClient {
        final List<String> bodies = new ArrayList<String>();
        @Override public HttpResponse post(String url, Map<String, String> headers,
                                           String body) {
            bodies.add(body);
            if (body.contains("google_search")) {
                return new HttpResponse(400, "{\"error\":{\"code\":400,"
                    + "\"message\":\"Unable to submit request because the tool is not"
                    + " supported for this model.\",\"status\":\"INVALID_ARGUMENT\"}}", null);
            }
            return new HttpResponse(200, "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"honest reply without live grounding\"}],\"role\":\"model\"}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":4}}", null);
        }
    }

    @Test public void rejectedExtrasDegradeOnceWithAnHonestNote() {
        ExtrasRejectingHttp http = new ExtrasRejectingHttp();
        GeminiProvider p = new GeminiProvider("https://generativelanguage.googleapis.com",
            "gemma-3n-e4b", "test-key", http, new RetryPolicy(),
            com.replymate.fakes.Fakes.NOOP_LOG);
        Result<ChatReply> r = p.generate(req(true, "default"));
        assertTrue("expected graceful success, got: " + r.error, r.ok);
        assertEquals(2, http.bodies.size());
        assertFalse("the retry is a plain call", http.bodies.get(1).contains("google_search"));
        assertEquals("honest reply without live grounding", r.value.variants.get(0));
        assertFalse("the honest degradation note is never empty",
            r.value.note.isEmpty());
        assertTrue(r.value.note.contains("built-in knowledge"));
    }
}
