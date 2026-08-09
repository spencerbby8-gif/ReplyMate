package com.replymate.provider.openai;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.HashMap;
import java.util.Map;

/** OpenAI chat-completions dialect (verified against official docs, 2026-08):
 *    POST {base}/chat/completions  +  GET {base}/models
 *  Covers OpenAI, OpenRouter, DeepSeek, Grok (xAI), Kimi (Moonshot), Mistral, Ollama
 *  and any OpenAI-compatible API. Bearer auth except keyless local servers (Ollama). */
public final class OpenAiPayloads {

    private OpenAiPayloads() { }

    public static Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            h.put("Authorization", "Bearer " + apiKey.trim());
        }
        // OpenRouter optional attribution headers are intentionally skipped — never
        // fabricate identity; requests stay anonymous per privacy rules.
        return h;
    }

    public static String chatEndpoint(String baseUrl) {
        return trimBase(baseUrl) + "/chat/completions";
    }

    public static String modelsEndpoint(String baseUrl) {
        return trimBase(baseUrl) + "/models";
    }

    /** Chat body. includeN=false omits the n parameter (fallback for providers that
     *  reject n>1; the provider handles graceful degradation, not us). */
    public static String chatBody(ChatRequest req, String model, boolean includeN) {
        return chatBody(req, model, includeN, "openai_compat", null);
    }

    /** P-intelligence-6: body assembled from the message list (so the Kimi
     *  $web_search echo loop can re-post with appended tool messages) plus this
     *  provider family's documented capability fields — every one traced to
     *  official docs in docs/provider-capability-map.md:
     *    OPENROUTER → tools:[{type:"openrouter:web_search",parameters:{caps}}]
     *                 + reasoning:{effort:"low|high"}
     *    KIMI      → tools:[{type:"builtin_function",function:{name:"$web_search"}}]
     *                 (thinking auto-disabled while searching — official rule)
     *                 + thinking:{type:"enabled|disabled"}
     *    DEEPSEEK  → thinking:{type} + reasoning_effort "low","high"
     *    MISTRAL   → reasoning_effort "low","high" (mistral-small family only —
     *                 other models would 422, nothing is sent to them)
     *    OPENAI    → reasoning_effort "low","high" (reasoning models; plain
     *                 models see nothing — and OpenAI web search routes to the
     *                 Responses dialect instead, never this path) */
    public static String chatBody(ChatRequest req, String model, boolean includeN,
                                  String wireType, JsonArr messages) {
        if (messages == null) {
            messages = JsonArr.create();
            if (req.system != null && !req.system.trim().isEmpty()) {
                messages.add(JsonObj.create()
                    .put("role", "system").put("content", req.system));
            }
            for (Turn t : req.turns) {
                messages.add(JsonObj.create()
                    .put("role", t.role == Turn.Role.USER ? "user" : "assistant")
                    .put("content", t.text));
            }
            if (req.task != null) {
                messages.add(JsonObj.create()
                    .put("role", "user").put("content", req.task.text));
            }
        }
        JsonObj body = JsonObj.create()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", req.opts.temperature)
            .put("max_tokens", req.opts.maxOutputTokens);
        if (includeN && req.opts.candidates > 1) {
            body.put("n", req.opts.candidates);
        }
        applyExtras(body, req, model, wireType);
        return body.toJson();
    }

    static void applyExtras(JsonObj body, ChatRequest req, String model, String wireType) {
        boolean search = req.opts.search;
        String level = req.opts.reasoning;
        boolean low = "low".equals(level), high = "high".equals(level);
        String wire = wireType == null ? "openai_compat" : wireType;
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.US);
        if ("openrouter".equals(wire)) {
            if (search) {
                body.put("tools", JsonArr.create().add(JsonObj.create()
                    .put("type", "openrouter:web_search")
                    .put("parameters", JsonObj.create()
                        .put("max_results", 3).put("max_total_results", 3))));
            }
            if (low || high) {
                body.put("reasoning", JsonObj.create().put("effort", low ? "low" : "high"));
            }
        } else if ("kimi".equals(wire)) {
            if (search) {
                // platform.moonshot.ai: $web_search + thinking is not supported on
                // K2.5/K2.6 — thinking goes OFF whenever the search tool rides.
                body.put("tools", JsonArr.create().add(JsonObj.create()
                    .put("type", "builtin_function")
                    .put("function", JsonObj.create().put("name", "$web_search"))));
                body.put("thinking", JsonObj.create().put("type", "disabled"));
            } else if (!m.startsWith("kimi-k3")) {
                // kimi-k3 reasons permanently; below it the toggle is official.
                body.put("thinking", JsonObj.create()
                    .put("type", (low || high) ? "enabled" : "disabled"));
            }
        } else if ("deepseek".equals(wire)) {
            body.put("thinking", JsonObj.create()
                .put("type", (low || high) ? "enabled" : "disabled"));
            if (low || high) body.put("reasoning_effort", low ? "low" : "high");
        } else if ("mistral".equals(wire)
                && (m.startsWith("mistral-small") || m.startsWith("ministral"))
                && (low || high)) {
            body.put("reasoning_effort", low ? "low" : "high");
        } else if ("openai".equals(wire) && (low || high) && openAiReasons(m)) {
            body.put("reasoning_effort", low ? "low" : "high");
        }
    }

    static boolean openAiReasons(String mLower) {
        String m = mLower == null ? "" : mLower;
        return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3")
            || m.startsWith("o4");
    }

    static String trimBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
