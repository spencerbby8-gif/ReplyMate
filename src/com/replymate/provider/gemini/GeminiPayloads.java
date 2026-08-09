package com.replymate.provider.gemini;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.HashMap;
import java.util.Map;

/** Wire-format builder for the Gemini generateContent API (BLUEPRINT §5.2). */
public final class GeminiPayloads {

    private GeminiPayloads() { }

    public static Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-goog-api-key", apiKey);
        return h;
    }

    public static String endpoint(String baseUrl, String model) {
        return trimBase(baseUrl) + "/v1beta/models/" + model + ":generateContent";
    }

    /** Official model discovery (ai.google.dev/gemini-api/docs/models): the returned
     *  ids are prefixed "models/" by the API — callers strip the prefix. */
    public static String modelsEndpoint(String baseUrl) {
        return trimBase(baseUrl) + "/v1beta/models";
    }

    static String trimBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    public static String generateBody(ChatRequest req) {
        return generateBody(req, true);
    }

    public static String generateBody(ChatRequest req, boolean sendCandidates) {
        return generateBody(req, sendCandidates, req.opts.search, req.opts.reasoning, null);
    }

    /** P-intelligence-6 full form: capability requests ride the SAME call.
     *  search → tools:[{google_search:{}}] (REST casing, ai.google.dev docs);
     *  reasoning low/high → generationConfig.thinkingConfig with thinkingLevel
     *  (Gemini 3 models) or thinkingBudget (2.5 series) — model decides the
     *  family. includeThoughts is NEVER requested: thoughts stay private. */
    /** @param sendCandidates false to omit generationConfig.candidateCount entirely —
     *  live-verified 2026-08-07: the Gemini 3 generation rejects the field
     *  ("Multiple candidates is not enabled for this model"), while Gemini 2.x accepts
     *  multiple candidates. Providers retry without it when the server says so. */
    public static String generateBody(ChatRequest req, boolean sendCandidates,
                                      boolean search, String reasoning, String model) {
        JsonObj root = JsonObj.create();

        root.put("system_instruction", JsonObj.create()
            .put("parts", JsonArr.create()
                .add(JsonObj.create().put("text", req.system))));

        JsonArr contents = JsonArr.create();
        for (Turn t : req.turns) contents.add(turnJson(t));
        if (req.task != null) contents.add(turnJson(req.task));
        root.put("contents", contents);

        JsonObj genConfig = JsonObj.create()
            .put("temperature", req.opts.temperature);
        if (sendCandidates && req.opts.candidates > 1) {
            genConfig.put("candidateCount", req.opts.candidates);
        }
        genConfig.put("maxOutputTokens", req.opts.maxOutputTokens);
        if (thinkingCapable(model)) {
            if ("low".equals(reasoning) || "high".equals(reasoning)) {
                JsonObj thinking = JsonObj.create();
                if (isGemini3(model)) {
                    thinking.put("thinkingLevel", reasoning);
                } else {
                    thinking.put("thinkingBudget", "high".equals(reasoning) ? 4096 : 512);
                }
                genConfig.put("thinkingConfig", thinking);
            }
            // DEFAULT sends nothing: dynamic thinking stays the provider's choice.
        }
        root.put("generationConfig", genConfig);

        if (search) {
            root.put("tools", JsonArr.create()
                .add(JsonObj.create().put("google_search", JsonObj.create())));
        }

        return root.toJson();
    }

    /** Thinking family per the official docs: 2.5 and 3.x models think;
     *  older/gemma don't (nothing is sent for them — never a guessed param). */
    static boolean thinkingCapable(String model) {
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.US);
        return m.contains("2.5") || m.startsWith("gemini-3") || m.contains("-3.");
    }

    static boolean isGemini3(String model) {
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.US);
        return m.startsWith("gemini-3") || m.contains("-3.");
    }

    static JsonObj turnJson(Turn t) {
        return JsonObj.create()
            .put("role", t.role == Turn.Role.USER ? "user" : "model")
            .put("parts", JsonArr.create()
                .add(JsonObj.create().put("text", t.text)));
    }
}
