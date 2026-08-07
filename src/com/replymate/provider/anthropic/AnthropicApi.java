package com.replymate.provider.anthropic;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Anthropic Messages dialect (verified against official docs, 2026-08):
 *    POST {base}/v1/messages   ·  headers x-api-key + anthropic-version: 2023-06-01
 *    GET  {base}/v1/models     ·  {data:[{id}]}
 *  Reply: {content:[{type:"text",text}], usage:{input_tokens,output_tokens}}.
 *  System prompt is a TOP-LEVEL field (not a message); roles must alternate, so
 *  consecutive same-role turns from our thread are merged with a newline. */
public final class AnthropicApi {

    /** Pinned API version header (official docs; stable since launch). */
    public static final String VERSION = "2023-06-01";

    private AnthropicApi() { }

    public static Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-api-key", apiKey == null ? "" : apiKey.trim());
        h.put("anthropic-version", VERSION);
        return h;
    }

    public static String messagesEndpoint(String baseUrl) {
        return trimBase(baseUrl) + "/v1/messages";
    }

    public static String modelsEndpoint(String baseUrl) {
        return trimBase(baseUrl) + "/v1/models";
    }

    public static String messagesBody(ChatRequest req, String model) {
        JsonArr messages = JsonArr.create();
        String carryRole = null;         // role of the currently open message
        StringBuilder carry = new StringBuilder();
        for (Turn t : req.turns) {
            String role = t.role == Turn.Role.USER ? "user" : "assistant";
            if (carryRole != null && !carryRole.equals(role)) {
                messages.add(JsonObj.create().put("role", carryRole)
                    .put("content", carry.toString()));
                carry.setLength(0);
            }
            if (carry.length() > 0) carry.append('\n');   // merge consecutive same roles
            carry.append(t.text);
            carryRole = role;
        }
        if (req.task != null) {
            String role = "user";
            if (carryRole != null && !carryRole.equals(role)) {
                messages.add(JsonObj.create().put("role", carryRole)
                    .put("content", carry.toString()));
                carry.setLength(0);
            }
            if (carry.length() > 0) carry.append('\n');
            carry.append(req.task.text);
            carryRole = role;
        }
        if (carryRole != null) {
            messages.add(JsonObj.create().put("role", carryRole)
                .put("content", carry.toString()));
        }
        JsonObj body = JsonObj.create()
            .put("model", model)
            .put("max_tokens", req.opts.maxOutputTokens)
            .put("temperature", req.opts.temperature)
            .put("messages", messages);
        if (req.system != null && !req.system.trim().isEmpty()) {
            body.put("system", req.system);
        }
        return body.toJson();
    }

    public static Result<com.replymate.core.ai.ChatReply> parseReply(String body) {
        try {
            com.replymate.core.json.JsonObj root = com.replymate.core.json.Json.parseObj(body);
            // P-audit-deep: stop_reason "max_tokens" = the single completion was cut
            // off — never saved as a finished draft.
            if ("max_tokens".equals(root.str("stop_reason"))) {
                return Result.err(com.replymate.provider.gemini.GeminiParser
                    .truncationMessage(1, "stop_reason \"max_tokens\""));
            }
            Object contentRaw = root.raw("content");
            if (!(contentRaw instanceof List)) {
                return Result.err("PARSE — provider reply had no content blocks");
            }
            StringBuilder text = new StringBuilder();
            for (Object bRaw : (List<?>) contentRaw) {
                if (!(bRaw instanceof Map)) continue;
                Object t = ((Map<?, ?>) bRaw).get("text");
                if (t instanceof String) text.append((String) t);
            }
            String out = text.toString().trim();
            if (out.isEmpty()) return Result.err("PARSE — provider reply had no text");
            int tin = 0, tout = 0;
            Object usageRaw = root.raw("usage");
            if (usageRaw instanceof Map) {
                Object in = ((Map<?, ?>) usageRaw).get("input_tokens");
                Object o = ((Map<?, ?>) usageRaw).get("output_tokens");
                if (in instanceof Number) tin = ((Number) in).intValue();
                if (o instanceof Number) tout = ((Number) o).intValue();
            }
            List<String> single = new ArrayList<String>();
            single.add(out);
            return Result.ok(new com.replymate.core.ai.ChatReply(single, tin, tout, null));
        } catch (RuntimeException boom) {
            return Result.err("PARSE — " + boom.getMessage());
        }
    }

    /** {data:[{id}]} — same envelope as the OpenAI models list. */
    public static Result<List<String>> parseModels(String body) {
        return com.replymate.provider.openai.OpenAiParser.parseModels(body);
    }

    public static ApiError errorFrom(HttpResponse resp) {
        ApiError base = ApiError.of(resp.code, resp.body);
        String msg = extractProviderMessage(resp.body);
        return new ApiError(base.type, msg.isEmpty() ? base.message : msg,
            base.retryAfterSeconds);
    }

    /** Anthropic error envelope (verified live 2026-08-07):
     *  {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}
     *  — the error.message form, same tolerant extraction as the OpenAI dialect. */
    public static String extractProviderMessage(String body) {
        return com.replymate.provider.openai.OpenAiParser.extractProviderMessage(body);
    }

    static String trimBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
