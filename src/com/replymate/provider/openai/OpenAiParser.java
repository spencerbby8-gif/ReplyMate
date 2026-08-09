package com.replymate.provider.openai;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parser for the OpenAI dialect: replies, model lists and errors.
 *  Shapes (official docs): reply {choices:[{message:{content}}], usage:{prompt_tokens,
 *  completion_tokens}} · models {data:[{id}]} · error {error:{message,type|code}}. */
public final class OpenAiParser {

    private OpenAiParser() { }

    public static Result<ChatReply> parseReply(String body) {
        try {
            JsonObj o = Json.parseObj(body);
            Object choicesRaw = o.raw("choices");
            if (!(choicesRaw instanceof List)) {
                return Result.err("PARSE — provider reply had no choices");
            }
            List<String> variants = new ArrayList<String>();
            int truncated = 0;
            for (Object cRaw : (List<?>) choicesRaw) {
                if (!(cRaw instanceof Map)) continue;
                Object finishRaw = ((Map<?, ?>) cRaw).get("finish_reason");
                // P-audit-deep: finish_reason "length" = cut off — never a draft.
                boolean cut = "length".equals(finishRaw) || "max_tokens".equals(finishRaw);
                Object msgRaw = ((Map<?, ?>) cRaw).get("message");
                if (!(msgRaw instanceof Map)) {
                    if (cut) truncated++;
                    continue;
                }
                Object content = ((Map<?, ?>) msgRaw).get("content");
                // P-intelligence-6 anti-CoT guard: reasoning_content (DeepSeek et al)
                // is read by NOBODY — it is private CoT and must never reach drafts,
                // summaries, logs, or audit snapshots.
                if (cut) { truncated++; continue; }
                if (content instanceof String && !((String) content).trim().isEmpty()) {
                    variants.add(((String) content).trim());
                }
            }
            if (variants.isEmpty() && truncated > 0) {
                return Result.err(com.replymate.provider.gemini.GeminiParser
                    .truncationMessage(truncated, "finish_reason \"length\""));
            }
            if (variants.isEmpty()) {
                return Result.err("PARSE — provider reply had no text");
            }
            int tin = 0, tout = 0;
            Object usageRaw = o.raw("usage");
            if (usageRaw instanceof Map) {
                tin = intOf(((Map<?, ?>) usageRaw).get("prompt_tokens"));
                tout = intOf(((Map<?, ?>) usageRaw).get("completion_tokens"));
            }
            return Result.ok(new ChatReply(variants, tin, tout, null));
        } catch (RuntimeException boom) {
            return Result.err("PARSE — " + boom.getMessage());
        }
    }

    /** Model ids from GET {base}/models ({data:[{id}]}), sorted, de-duplicated. */
    public static Result<List<String>> parseModels(String body) {
        try {
            JsonObj o = Json.parseObj(body);
            Object dataRaw = o.raw("data");
            if (!(dataRaw instanceof List)) {
                return Result.err("PARSE — model list had no data array");
            }
            List<String> out = new ArrayList<String>();
            for (Object mRaw : (List<?>) dataRaw) {
                if (!(mRaw instanceof Map)) continue;
                Object id = ((Map<?, ?>) mRaw).get("id");
                if (id instanceof String && !((String) id).trim().isEmpty()
                        && !out.contains(id)) {
                    out.add(((String) id).trim());
                }
            }
            java.util.Collections.sort(out);
            if (out.isEmpty()) return Result.err("Provider returned an empty model list");
            return Result.ok(out);
        } catch (RuntimeException boom) {
            return Result.err("PARSE — " + boom.getMessage());
        }
    }

    public static ApiError errorFrom(HttpResponse resp) {
        ApiError base = ApiError.of(resp.code, resp.body);
        String providerMsg = extractProviderMessage(resp.body);
        return new ApiError(base.type,
            providerMsg.isEmpty() ? base.message : providerMsg, base.retryAfterSeconds);
    }

    /** P-intelligence-6: the raw insight a provider-side TOOL LOOP needs —
     *  finish_reason verbatim, the assistant message as raw JSON (tool_calls must
     *  round-trip byte-exact), and the tool calls themselves. Reply text stays
     *  with parseReply; CoT fields (reasoning_content) are never surfaced. */
    public static final class RawReply {
        public final String finishReason;
        public final String assistantMessageJson;
        public final List<Map<?, ?>> toolCalls;

        RawReply(String finishReason, String assistantMessageJson,
                 List<Map<?, ?>> toolCalls) {
            this.finishReason = finishReason == null ? "" : finishReason;
            this.assistantMessageJson = assistantMessageJson == null ? "" : assistantMessageJson;
            this.toolCalls = toolCalls;
        }

        public boolean wantsToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public static Result<RawReply> parseRaw(String body) {
        try {
            JsonObj o = Json.parseObj(body);
            Object choicesRaw = o.raw("choices");
            if (!(choicesRaw instanceof List) || ((List<?>) choicesRaw).isEmpty()) {
                return Result.err("PARSE — provider reply had no choices");
            }
            Object cRaw = ((List<?>) choicesRaw).get(0);
            if (!(cRaw instanceof Map)) return Result.err("PARSE — malformed choice");
            String finish = String.valueOf(((Map<?, ?>) cRaw).get("finish_reason"));
            Object msgRaw = ((Map<?, ?>) cRaw).get("message");
            String msgJson = "";
            List<Map<?, ?>> calls = new ArrayList<Map<?, ?>>();
            if (msgRaw instanceof Map) {
                msgJson = Json.write((Map<?, ?>) msgRaw);
                Object tc = ((Map<?, ?>) msgRaw).get("tool_calls");
                if (tc instanceof List) {
                    for (Object t : (List<?>) tc) {
                        if (t instanceof Map) calls.add((Map<?, ?>) t);
                    }
                }
            }
            return Result.ok(new RawReply(
                "null".equals(finish) ? "" : finish, msgJson, calls));
        } catch (RuntimeException boom) {
            return Result.err("PARSE — " + boom.getMessage());
        }
    }

    /** One Kimi $web_search round: the official echo is a role:"tool" message whose
     *  content is the arguments JSON VERBATIM (Moonshot executes the search). */
    public static JsonObj toolEchoMessage(Map<?, ?> toolCall) {
        Object id = toolCall.get("id");
        Object fnRaw = toolCall.get("function");
        String name = "";
        String args = "{}";
        if (fnRaw instanceof Map) {
            Object n = ((Map<?, ?>) fnRaw).get("name");
            if (n instanceof String) name = (String) n;
            Object a = ((Map<?, ?>) fnRaw).get("arguments");
            if (a instanceof String && !((String) a).trim().isEmpty()) args = (String) a;
        }
        return JsonObj.create()
            .put("role", "tool")
            .put("tool_call_id", id instanceof String ? (String) id : "")
            .put("name", name)
            .put("content", args);
    }

    /** Provider error text from any of the shapes real compatible servers actually send
     *  (all captured live 2026-08-07): {error:{message}} (OpenAI/DeepSeek/Kimi/OpenRouter),
     *  {error:"…"} as a bare STRING (xAI/Grok), {detail:"…"} (Mistral), {message:"…"}
     *  top-level (misc gateways). error.code may be a string OR a number. */
    public static String extractProviderMessage(String body) {
        try {
            JsonObj o = Json.parseObj(body);
            Object errRaw = o.raw("error");
            if (errRaw instanceof String && !((String) errRaw).trim().isEmpty()) {
                return ((String) errRaw).trim();
            }
            if (errRaw instanceof Map) {
                Object msg = ((Map<?, ?>) errRaw).get("message");
                if (msg instanceof String && !((String) msg).trim().isEmpty()) return (String) msg;
                Object code = ((Map<?, ?>) errRaw).get("code");   // string or number
                if (code != null) return String.valueOf(code);
            }
            String detail = o.str("detail");
            if (detail != null && !detail.trim().isEmpty()) return detail.trim();
            String m = o.str("message");
            if (m != null && !m.trim().isEmpty()) return m.trim();
        } catch (RuntimeException ignore) { }
        return "";
    }

    private static int intOf(Object n) {
        return n instanceof Number ? ((Number) n).intValue() : 0;
    }
}
