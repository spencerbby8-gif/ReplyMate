package com.replymate.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.provider.anthropic.AnthropicApi;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.openai.OpenAiPayloads;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** P-background-8 (REPRO + pin): 1.5.6's automatic reasoning can attach a paid
 *  "thinking" control while the chat-tuned output budget stays 220 tokens —
 *  every provider counts thinking INSIDE the output cap, so thinking starves the
 *  answer: finishReason MAX_TOKENS / empty content / hard 400, and background
 *  generation produced NO draft on real devices. The contract: whenever a
 *  thinking/reasoning control is emitted, the output cap MUST exceed the
 *  thinking budget plus real room for the reply text. */
public class OutputBudgetTest {

    private static ChatRequest chatReq(boolean search, String reasoning) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("wetin dey happen for the arsenal game?"));
        return new ChatRequest("You write replies.", turns,
            Turn.user("Write the reply.\nOutput only the reply text."),
            GenerationOpts.of(2, 0.8, 220).withSearch(search).withReasoning(reasoning));
    }

    /* ----------------------------------------------------------- Gemini ---- */

    @Test public void gemini25ThinkingNeverStarvesTheAnswer() {
        // LOW → thinkingBudget 512 must leave answer room inside maxOutputTokens.
        JsonObj low = Json.parseObj(GeminiPayloads.generateBody(
            chatReq(false, "low"), true, false, "low", "gemini-2.5-flash"))
            .obj("generationConfig");
        long lowCap = low.lng("maxOutputTokens", 0);
        long lowBudget = low.obj("thinkingConfig").lng("thinkingBudget", 0);
        assertTrue("LOW thinking budget " + lowBudget + " must fit INSIDE the output cap "
                + lowCap + " with room for the reply (was starved: 512 inside 220)",
            lowCap >= lowBudget + 220);

        JsonObj high = Json.parseObj(GeminiPayloads.generateBody(
            chatReq(false, "high"), true, false, "high", "gemini-2.5-flash"))
            .obj("generationConfig");
        long highCap = high.lng("maxOutputTokens", 0);
        long highBudget = high.obj("thinkingConfig").lng("thinkingBudget", 0);
        assertTrue("HIGH thinking budget " + highBudget + " must fit INSIDE the output cap "
                + highCap + " with room for the reply",
            highCap >= highBudget + 220);
    }

    @Test public void geminiDefaultThinkingUntouched() {
        // DEFAULT level sends no thinking config and the plain 220 cap (legacy fast path).
        JsonObj root = Json.parseObj(GeminiPayloads.generateBody(
            chatReq(false, "default"), true, false, "default", "gemini-2.5-flash"));
        assertFalse(root.obj("generationConfig").has("thinkingConfig"));
        assertEquals(220, root.obj("generationConfig").lng("maxOutputTokens", 0));
    }

    @Test public void gemini3LevelThinkingAlsoCoversTheAnswer() {
        JsonObj gc = Json.parseObj(GeminiPayloads.generateBody(
            chatReq(false, "high"), true, false, "high", "gemini-3-flash"))
            .obj("generationConfig");
        assertEquals("high", gc.obj("thinkingConfig").str("thinkingLevel"));
        assertTrue("gemini-3 thinking needs headroom over the plain chat cap",
            gc.lng("maxOutputTokens", 0) > 220);
    }

    /* --------------------------------------------------------- DeepSeek ---- */

    @Test public void deepseekThinkingNeverStarvesTheAnswer() {
        JsonObj low = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(false, "low"), "deepseek-chat", true, "deepseek", null));
        assertEquals("enabled", low.obj("thinking").str("type"));
        assertTrue("thinking shares max_tokens on DeepSeek — enabled needs headroom",
            low.lng("max_tokens", 0) >= 220 + 512);

        JsonObj def = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(false, "default"), "deepseek-chat", true, "deepseek", null));
        assertEquals("disabled", def.obj("thinking").str("type"));
        assertEquals(220, def.lng("max_tokens", 0));   // fast path stays fast
    }

    /* ------------------------------------------------------------- Kimi ---- */

    @Test public void kimiThinkingEnabledNeverStarvesTheAnswer() {
        JsonObj high = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(false, "high"), "kimi-k2.6", true, "kimi", null));
        assertEquals("enabled", high.obj("thinking").str("type"));
        assertTrue("kimi reasoning_content shares max_tokens — enabled needs headroom",
            high.lng("max_tokens", 0) >= 220 + 1024);

        // search rides with thinking OFF (official rule) → no headroom needed.
        JsonObj search = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(true, "high"), "kimi-k2.6", true, "kimi", null));
        assertEquals("disabled", search.obj("thinking").str("type"));
        assertEquals(220, search.lng("max_tokens", 0));
    }

    /* -------------------------------------------------------- OpenRouter --- */

    @Test public void openrouterReasoningEffortNeverStarvesTheAnswer() {
        JsonObj low = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(false, "low"), "openai/gpt-5.2", true, "openrouter", null));
        assertEquals("low", low.obj("reasoning").str("effort"));
        assertTrue("reasoning tokens are completion tokens — effort needs headroom",
            low.lng("max_tokens", 0) >= 220 + 512);
    }

    /* ---------------------------------------------------------- Mistral ---- */

    @Test public void mistralReasoningEffortNeverStarvesTheAnswer() {
        JsonObj low = Json.parseObj(OpenAiPayloads.chatBody(
            chatReq(false, "low"), "mistral-small-latest", true, "mistral", null));
        assertEquals("low", low.str("reasoning_effort"));
        assertTrue("mistral reasoning shares max_tokens",
            low.lng("max_tokens", 0) >= 220 + 512);
    }

    /* ------------------------------------------- already-safe dialects ----- */

    @Test public void anthropicThinkingAlreadyAddsBudgetOnTop() {
        JsonObj low = Json.parseObj(AnthropicApi.messagesBody(
            chatReq(false, "low"), "claude-sonnet-4-5", false, "low"));
        assertEquals(220 + 1024, low.lng("max_tokens", 0));

        JsonObj high = Json.parseObj(AnthropicApi.messagesBody(
            chatReq(false, "high"), "claude-sonnet-4-5", false, "high"));
        assertEquals(220 + 8192, high.lng("max_tokens", 0));
    }

    @Test public void openAiResponsesAlreadyAddsReasoningHeadroom() {
        JsonObj low = Json.parseObj(com.replymate.provider.openai.ResponsesApi.body(
            chatReq(false, "low"), "gpt-5", false, "low"));
        assertEquals(220 + 1500, low.lng("max_output_tokens", 0));
    }
}
