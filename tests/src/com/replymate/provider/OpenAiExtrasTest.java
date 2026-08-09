package com.replymate.provider;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.provider.openai.OpenAiPayloads;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directives 2/3/8: per-provider search + reasoning EXTRAS on
 *  the OpenAI-compatible dialect — ONLY the wire shapes each provider documents
 *  are ever sent (docs/provider-capability-map.md); providers without a
 *  documented control get NOTHING (never an invented parameter). */
public final class OpenAiExtrasTest {

    private static JsonObj body(String wire, String model, boolean search,
                                String reasoning) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: you around?"));
        GenerationOpts opts = GenerationOpts.of(1, 0.8, 220)
            .withSearch(search).withReasoning(reasoning);
        ChatRequest req = new ChatRequest("sys", turns, Turn.user("reply"), opts);
        return Json.parseObj(OpenAiPayloads.chatBody(req, model, false, wire, null));
    }

    @Test public void openrouterGetsTheDocumentedToolAndUnifiedEffort() {
        JsonObj b = body("openrouter", "mistralai/mistral-large", true, "high");
        assertEquals("openrouter:web_search", b.arr("tools").obj(0).str("type"));
        assertEquals(3L, b.arr("tools").obj(0).obj("parameters").lng("max_results", 0));
        assertEquals("high", b.obj("reasoning").str("effort"));
    }

    @Test public void kimiSearchAttachesTheBuiltinAndForcesThinkingOff() {
        JsonObj b = body("kimi", "kimi-k2.5", true, "high");
        assertEquals("builtin_function", b.arr("tools").obj(0).str("type"));
        assertEquals("$web_search",
            b.arr("tools").obj(0).obj("function").str("name"));
        assertEquals("official rule: $web_search is incompatible with thinking",
            "disabled", b.obj("thinking").str("type"));
    }

    @Test public void kimiThinkingToggleIsOfficialAndK3IsUntouched() {
        JsonObj deep = body("kimi", "kimi-k2.5", false, "high");
        assertEquals("enabled", deep.obj("thinking").str("type"));
        JsonObj fast = body("kimi", "kimi-k2.5", false, "default");
        assertEquals("disabled", fast.obj("thinking").str("type"));
        JsonObj k3 = body("kimi", "kimi-k3", false, "high");
        assertFalse("kimi-k3 reasons permanently — nothing is toggled",
            k3.has("thinking"));
    }

    @Test public void deepseekGetsTheThinkingToggleAndEffort() {
        JsonObj deep = body("deepseek", "deepseek-v4", false, "high");
        assertEquals("enabled", deep.obj("thinking").str("type"));
        assertEquals("high", deep.str("reasoning_effort"));
        JsonObj fast = body("deepseek", "deepseek-v4", false, "default");
        assertEquals("disabled", fast.obj("thinking").str("type"));
        assertFalse(fast.has("reasoning_effort"));
    }

    @Test public void mistralOnlySendsEffortWhereDocumented() {
        JsonObj small = body("mistral", "mistral-small-latest", false, "low");
        assertEquals("low", small.str("reasoning_effort"));
        JsonObj magistral = body("mistral", "magistral-medium", false, "high");
        assertFalse("magistral reasons natively — the parameter 422s",
            magistral.has("reasoning_effort"));
        JsonObj large = body("mistral", "mistral-large-latest", false, "high");
        assertFalse("no documented control on mistral-large — nothing invented",
            large.has("reasoning_effort"));
        JsonObj search = body("mistral", "mistral-small-latest", true, "low");
        assertFalse("chat completions exposes no search tool — fallback layer"
            + " handles it, the wire stays clean", search.has("tools"));
    }

    @Test public void openaiChatCompletionsOnlySendsEffortOnReasoningModels() {
        JsonObj gpt5 = body("openai", "gpt-5", false, "low");
        assertEquals("low", gpt5.str("reasoning_effort"));
        JsonObj gpt4o = body("openai", "gpt-4o", false, "low");
        assertFalse(gpt4o.has("reasoning_effort"));
    }

    @Test public void unknownCompatibleEndpointsGetNoExtrasAtAll() {
        JsonObj b = body("openai_compat", "my-model", true, "high");
        assertFalse(b.has("tools"));
        assertFalse(b.has("thinking"));
        assertFalse(b.has("reasoning"));
        assertFalse(b.has("reasoning_effort"));
    }

    @Test public void ollamaNeverReceivesExtras() {
        JsonObj b = body("ollama", "llama3.2", true, "high");
        assertFalse(b.has("tools"));
        assertFalse(b.has("thinking"));
        assertFalse(b.has("reasoning_effort"));
    }
}
