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
        JsonObj root = JsonObj.create();

        root.put("system_instruction", JsonObj.create()
            .put("parts", JsonArr.create()
                .add(JsonObj.create().put("text", req.system))));

        JsonArr contents = JsonArr.create();
        for (Turn t : req.turns) contents.add(turnJson(t));
        if (req.task != null) contents.add(turnJson(req.task));
        root.put("contents", contents);

        root.put("generationConfig", JsonObj.create()
            .put("temperature", req.opts.temperature)
            .put("candidateCount", req.opts.candidates)
            .put("maxOutputTokens", req.opts.maxOutputTokens));

        return root.toJson();
    }

    static JsonObj turnJson(Turn t) {
        return JsonObj.create()
            .put("role", t.role == Turn.Role.USER ? "user" : "model")
            .put("parts", JsonArr.create()
                .add(JsonObj.create().put("text", t.text)));
    }
}
