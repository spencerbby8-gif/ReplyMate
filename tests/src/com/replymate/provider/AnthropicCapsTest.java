package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.anthropic.AnthropicApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directives 2/3: Anthropic's native web_search server tool +
 *  the 2026 thinking contract — adaptive+effort on 4.6+, budget_tokens on older
 *  models, NOTHING for unparseable ids (never a guessed param), temperature
 *  dropped whenever thinking rides, and only type:text blocks ever become a
 *  draft (thinking/tool blocks are private machinery). */
public final class AnthropicCapsTest {

    private static ChatRequest req(boolean search, String reasoning) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: any latest on the elections?"));
        return new ChatRequest("sys", turns, Turn.user("reply"),
            GenerationOpts.of(1, 0.8, 220).withSearch(search).withReasoning(reasoning));
    }

    private static JsonObj body(String model, boolean search, String reasoning) {
        return Json.parseObj(
            AnthropicApi.messagesBody(req(search, reasoning), model, search, reasoning));
    }

    /* ---------------------------------------------------------------- wire */

    @Test public void searchAttachesTheOfficialServerTool() {
        JsonObj b = body("claude-sonnet-4.5", true, "default");
        JsonObj tool = b.arr("tools").obj(0);
        assertEquals("web_search_20250305", tool.str("type"));
        assertEquals("web_search", tool.str("name"));
        assertEquals(3L, tool.lng("max_uses", 0));
    }

    @Test public void claude46AndNewerGetAdaptiveThinkingPlusEffort() {
        JsonObj b = body("claude-sonnet-4.7", false, "high");
        assertEquals("adaptive", b.obj("thinking").str("type"));
        assertEquals("high", b.obj("output_config").str("effort"));
        assertFalse("thinking replaces temperature", b.has("temperature"));
        assertTrue("max_tokens must cover thinking + reply",
            b.lng("max_tokens", 0) >= 220L + 8192L);
    }

    @Test public void olderClaudesGetBudgetedThinking() {
        JsonObj b = body("claude-haiku-4.5", false, "low");
        assertEquals("enabled", b.obj("thinking").str("type"));
        assertEquals(1024L, b.obj("thinking").lng("budget_tokens", 0));
        assertFalse("adaptive is 4.6+ only", b.has("output_config"));
        assertFalse(b.has("temperature"));
        JsonObj plain = body("claude-haiku-4.5", false, "default");
        assertTrue("temperature survives on the plain path",
            plain.dbl("temperature") != null);
    }

    @Test public void unparseableModelsGetNothingGuessed() {
        JsonObj b = body("claude-future-thing", false, "high");
        assertFalse(b.has("thinking"));
        assertFalse(b.has("output_config"));
        assertTrue("temperature stays when thinking is off",
            b.dbl("temperature") != null);
    }

    /* --------------------------------------------------------------- parsing */

    @Test public void onlyTextBlocksBecomeTheReplySearchesAreCounted() {
        String body = "{\"content\":["
            + "{\"type\":\"thinking\",\"thinking\":\"private chain\"},"
            + "{\"type\":\"server_tool_use\",\"name\":\"web_search\"},"
            + "{\"type\":\"web_search_tool_result\",\"content\":[]},"
            + "{\"type\":\"text\",\"text\":\"The tribunal adjourned to next week.\"}],"
            + "\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":80,\"output_tokens\":30,"
            + "\"server_tool_use\":{\"web_search_requests\":2}}}";
        Result<ChatReply> r = AnthropicApi.parseReply(body);
        assertTrue("parse failed: " + r.error, r.ok);
        assertEquals("The tribunal adjourned to next week.", r.value.variants.get(0));
        assertEquals("the official billing counter is the audit metadata",
            2, r.value.searchQueries);
        assertFalse("private thinking never becomes a draft",
            r.value.variants.get(0).contains("private chain"));
    }

    @Test public void aReplyWithoutTextFailsHonestly() {
        Result<ChatReply> r = AnthropicApi.parseReply(
            "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"only thoughts\"}],"
            + "\"stop_reason\":\"end_turn\"}");
        assertFalse(r.ok);
        assertTrue(r.error.contains("PARSE"));
    }
}
