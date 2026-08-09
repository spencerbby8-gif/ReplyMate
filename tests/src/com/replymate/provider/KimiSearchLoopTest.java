package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.util.Result;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import com.replymate.provider.openai.OpenAiCompatProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 2: Kimi's $web_search is the OFFICIAL echo loop —
 *  the model answers with tool_calls, we echo each call back VERBATIM as a
 *  role:"tool" message, the Moonshot platform executes the search, and the next
 *  round returns the grounded reply. The loop is bounded (≤3 wire calls). */
public final class KimiSearchLoopTest {

    private static final String TOOL_CALL_REPLY = "{\"choices\":[{"
        + "\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"$web_search\","
        + "\"arguments\":\"{\\\"query\\\":\\\"odogwu meaning\\\"}\"}}]}}]}";

    private static final String FINAL_REPLY = "{\"choices\":[{"
        + "\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
        + "\"content\":\"Odogwu is an Igbo title for a big man — they're hyping you.\"}}],"
        + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":18}}";

    private static ChatRequest searchReq() {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: that guy na real odogwu"));
        return new ChatRequest("sys", turns, Turn.user("translate then reply"),
            GenerationOpts.of(1, 0.8, 220).withSearch(true));
    }

    private static final class ScriptedHttp extends HttpClient {
        final List<String> bodies = new ArrayList<String>();
        final List<String> responses = new ArrayList<String>();
        ScriptedHttp serve(String body) { responses.add(body); return this; }
        @Override public HttpResponse post(String url, Map<String, String> headers,
                                           String body) {
            bodies.add(body);
            String r = responses.size() > bodies.size() - 1
                ? responses.get(bodies.size() - 1)
                : responses.get(responses.size() - 1);   // repeat the last answer
            return new HttpResponse(200, r, null);
        }
    }

    private static OpenAiCompatProvider kimi(HttpClient http) {
        return new OpenAiCompatProvider("kimi", "https://api.moonshot.ai/v1",
            "kimi-k2.5", "test-key", http, new RetryPolicy(),
            com.replymate.fakes.Fakes.NOOP_LOG);
    }

    @Test public void theEchoRoundTripsVerbatimAndGroundsTheReply() {
        ScriptedHttp http = new ScriptedHttp().serve(TOOL_CALL_REPLY).serve(FINAL_REPLY);
        Result<ChatReply> r = kimi(http).generate(searchReq());
        assertTrue("loop failed: " + r.error, r.ok);
        assertEquals(2, http.bodies.size());
        String second = http.bodies.get(1);
        assertTrue("the assistant tool_calls message round-trips",
            second.contains("\"tool_calls\""));
        assertTrue("the echo is a role:tool message",
            second.contains("\"role\":\"tool\""));
        assertTrue("the tool_call_id rides along", second.contains("\"call_1\""));
        assertTrue("the function name rides along",
            second.contains("\"$web_search\""));
        assertTrue("the arguments echo back VERBATIM (platform executes them)",
            second.contains("odogwu meaning"));
        assertEquals("the grounded reply becomes the draft",
            "Odogwu is an Igbo title for a big man — they're hyping you.",
            r.value.variants.get(0));
        assertEquals("a search ACTUALLY ran (platform-side) — it is credited",
            1, r.value.searchQueries);
    }

    @Test public void anImmediateAnswerSkipsTheLoop() {
        ScriptedHttp http = new ScriptedHttp().serve(FINAL_REPLY);
        Result<ChatReply> r = kimi(http).generate(searchReq());
        assertTrue(r.ok);
        assertEquals(1, http.bodies.size());
        assertEquals(0, r.value.searchQueries);
    }

    @Test public void theLoopIsHardBounded() {
        ScriptedHttp http = new ScriptedHttp().serve(TOOL_CALL_REPLY);  // always repeats
        Result<ChatReply> r = kimi(http).generate(searchReq());
        assertTrue("the loop must stop after 3 wire calls, got " + http.bodies.size(),
            http.bodies.size() <= 3);
        if (!r.ok) {
            assertTrue("an unanswerable loop fails honestly, never hangs",
                r.error.length() > 0);
        }
    }
}
