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
        JsonArr messages = JsonArr.create();
        if (req.system != null && !req.system.trim().isEmpty()) {
            messages.add(JsonObj.create().put("role", "system").put("content", req.system));
        }
        for (Turn t : req.turns) {
            messages.add(JsonObj.create()
                .put("role", t.role == Turn.Role.USER ? "user" : "assistant")
                .put("content", t.text));
        }
        if (req.task != null) {
            messages.add(JsonObj.create().put("role", "user").put("content", req.task.text));
        }
        JsonObj body = JsonObj.create()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", req.opts.temperature)
            .put("max_tokens", req.opts.maxOutputTokens);
        if (includeN && req.opts.candidates > 1) {
            body.put("n", req.opts.candidates);
        }
        return body.toJson();
    }

    static String trimBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
