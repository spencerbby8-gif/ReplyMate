package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.openai.ResponsesApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6: the OpenAI/xAI Responses dialect — pinned wire shape
 *  (store:false privacy, web_search tool with tool_choice auto, reasoning effort,
 *  token headroom, NO temperature) and pinned parsing (message/output_text only,
 *  reasoning items skipped, search counters + citation titles as safe metadata). */
public final class ResponsesApiTest {

    private static ChatRequest req(boolean search, String reasoning) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: what's the fuel price today?"));
        GenerationOpts opts = GenerationOpts.of(1, 0.8, 220)
            .withSearch(search).withReasoning(reasoning);
        return new ChatRequest("You are reply helper.", turns,
            Turn.user("reply to amara"), opts);
    }

    @Test public void endpointAppendsResponsesToTheBase() {
        assertEquals("https://api.openai.com/v1/responses",
            ResponsesApi.endpoint("https://api.openai.com/v1"));
        assertEquals("https://api.x.ai/v1/responses",
            ResponsesApi.endpoint("https://api.x.ai/v1/"));
    }

    @Test public void bodyCarriesSearchReasoningAndPrivacyFlags() {
        JsonObj b = Json.parseObj(ResponsesApi.body(req(true, "high"), "gpt-5",
            true, "high"));
        assertEquals("gpt-5", b.str("model"));
        assertEquals(Boolean.FALSE, b.raw("store"));
        assertEquals("You are reply helper.", b.str("instructions"));
        assertEquals("web_search", b.arr("tools").obj(0).str("type"));
        assertEquals("auto", b.str("tool_choice"));
        assertEquals("high", b.obj("reasoning").str("effort"));
        assertEquals("reasoning tokens live INSIDE this cap — headroom required",
            220L + 1500L, b.lng("max_output_tokens", 0));
        assertFalse("temperature is not supported on the reasoning path",
            b.has("temperature"));
        assertEquals(2, b.arr("input").size());
        assertEquals("user", b.arr("input").obj(0).str("role"));
    }

    @Test public void plainCallsSendNoToolsNoReasoning() {
        JsonObj b = Json.parseObj(
            ResponsesApi.body(req(false, "default"), "gpt-5", false, "default"));
        assertFalse(b.has("tools"));
        assertFalse(b.has("tool_choice"));
        assertFalse(b.has("reasoning"));
        assertEquals(" Responses replies are clamped to a sane floor",
            256L, b.lng("max_output_tokens", 0));
        assertEquals(Boolean.FALSE, b.raw("store"));   // privacy is unconditional
    }

    @Test public void replyParsingCountsSearchAndReasoningSkipsThoughts() {
        String body = "{"
            + "\"status\":\"completed\","
            + "\"output\":["
            + "  {\"type\":\"reasoning\",\"summary\":[]},"
            + "  {\"type\":\"web_search_call\",\"id\":\"ws_1\"},"
            + "  {\"type\":\"message\",\"role\":\"assistant\",\"content\":[{"
            + "     \"type\":\"output_text\",\"text\":\"Fuel is around ₦880 today.\","
            + "     \"annotations\":["
            + "       {\"type\":\"url_citation\",\"title\":\"NNPC price list\"},"
            + "       {\"type\":\"url_citation\",\"title\":\"NNPC price list\"},"
            + "       {\"type\":\"url_citation\",\"title\":\"Vanguard\"}]}]},"
            + "  {\"type\":\"web_search_call\",\"id\":\"ws_2\"}],"
            + "\"usage\":{\"input_tokens\":120,\"output_tokens\":60,"
            + "  \"output_tokens_details\":{\"reasoning_tokens\":42}}}";
        Result<ChatReply> r = ResponsesApi.parseReply(body);
        assertTrue("parse failed: " + r.error, r.ok);
        assertEquals("Fuel is around ₦880 today.", r.value.variants.get(0));
        assertEquals(2, r.value.searchQueries);
        assertEquals("duplicate citation titles collapse", 2, r.value.searchSources.size());
        assertEquals("NNPC price list", r.value.searchSources.get(0));
        assertEquals(42, r.value.reasoningTokens);
        assertEquals(120, r.value.tokensIn);
        assertEquals(60, r.value.tokensOut);
    }

    @Test public void convenienceOutputTextIsTheFallback() {
        Result<ChatReply> r = ResponsesApi.parseReply(
            "{\"status\":\"completed\",\"output_text\":\"quick reply\","
            + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");
        assertTrue(r.ok);
        assertEquals("quick reply", r.value.variants.get(0));
        assertEquals(0, r.value.searchQueries);
    }

    @Test public void emptyRepliesFailHonestlyWithTheStatus() {
        Result<ChatReply> r = ResponsesApi.parseReply(
            "{\"status\":\"incomplete\",\"output\":[]}");
        assertFalse(r.ok);
        assertTrue(r.error.contains("PARSE"));
        assertTrue(r.error.contains("incomplete"));
        Result<ChatReply> junk = ResponsesApi.parseReply("not json");
        assertFalse(junk.ok);
    }

    @Test public void xaiToolCountsAreOpportunisticAndNeverRequired() {
        assertEquals(3, ResponsesApi.xaiToolCalls(
            "{\"usage\":{\"server_side_tool_usage\":{\"web_search\":2,"
            + "\"x_search\":1}}}"));
        assertEquals(0, ResponsesApi.xaiToolCalls("{\"usage\":{\"input_tokens\":1}}"));
        assertEquals(0, ResponsesApi.xaiToolCalls("garbage"));
    }
}
