package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.anthropic.AnthropicApi;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Anthropic Messages dialect (P-polish): top-level system, alternating-role merge,
 *  required max_tokens, content-block reply parse, usage, models envelope, errors. */
public class AnthropicApiTest {

    private static ChatRequest req() {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: you around?"));
        turns.add(Turn.user("Amara: hello??"));            // consecutive user turn
        turns.add(new Turn(Turn.Role.MODEL, "yes o"));
        return new ChatRequest("system here", turns, Turn.user("task here"),
            GenerationOpts.of(3, 0.8, 220));
    }

    @Test public void bodyCarriesTopLevelSystemAndMergedRoles() {
        JsonObj o = Json.parseObj(AnthropicApi.messagesBody(req(), "claude-x"));
        assertEquals("claude-x", o.str("model"));
        assertEquals(220L, (long) o.lng("max_tokens"));                 // required field
        assertEquals("system here", o.str("system"));                   // top-level ✓
        List<?> messages = (List<?>) o.raw("messages");
        // user(2 merged) → model → user(task): NICO alternate, 3 entries
        assertEquals(3, messages.size());
        assertEquals("user", ((Map<?, ?>) messages.get(0)).get("role"));
        String merged = (String) ((Map<?, ?>) messages.get(0)).get("content");
        assertTrue(merged.contains("you around?"));
        assertTrue(merged.contains("hello??"));
        assertEquals("assistant", ((Map<?, ?>) messages.get(1)).get("role"));
        assertEquals("user", ((Map<?, ?>) messages.get(2)).get("role"));
        assertEquals("task here", ((Map<?, ?>) messages.get(2)).get("content"));
        // the OpenAI-only "n" parameter must never leak into Anthropic bodies
        assertNull(o.raw("n"));
    }

    @Test public void headersMatchOfficialAuth() {
        Map<String, String> h = AnthropicApi.headers("sk-ant-1");
        assertEquals("sk-ant-1", h.get("x-api-key"));
        assertEquals("2023-06-01", h.get("anthropic-version"));
    }

    @Test public void endpointsAreV1MessagesAndModels() {
        assertEquals("https://api.anthropic.com/v1/messages",
            AnthropicApi.messagesEndpoint("https://api.anthropic.com/"));
        assertEquals("https://api.anthropic.com/v1/models",
            AnthropicApi.modelsEndpoint("https://api.anthropic.com"));
    }

    @Test public void replyParseReadsTextBlocksAndUsage() {
        Result<ChatReply> r = AnthropicApi.parseReply(
            "{\"content\":[{\"type\":\"text\",\"text\":\"running late, \"},{\"type\":\"text\",\"text\":\"sorry o\"}],"
            + "\"usage\":{\"input_tokens\":30,\"output_tokens\":6}}");
        assertTrue(r.ok);
        assertEquals(1, r.value.variants.size());      // single completion per call
        assertEquals("running late, sorry o", r.value.variants.get(0));
        assertEquals(30, r.value.tokensIn);
        assertEquals(6, r.value.tokensOut);
    }

    @Test public void modelsEnvelopeParses() {
        Result<List<String>> r = AnthropicApi.parseModels(
            "{\"data\":[{\"id\":\"claude-b\"},{\"id\":\"claude-a\"}]}");
        assertTrue(r.ok);
        assertEquals(Arrays.asList("claude-a", "claude-b"), r.value);
    }

    @Test public void errorCarriesTypedProviderMessage() {
        ApiError err = AnthropicApi.errorFrom(new HttpResponse(401,
            "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}", null));
        assertEquals(ApiError.Type.AUTH, err.type);
        assertEquals("invalid x-api-key", err.message);
    }
}
