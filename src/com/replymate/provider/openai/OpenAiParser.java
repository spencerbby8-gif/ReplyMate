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
            for (Object cRaw : (List<?>) choicesRaw) {
                if (!(cRaw instanceof Map)) continue;
                Object msgRaw = ((Map<?, ?>) cRaw).get("message");
                if (!(msgRaw instanceof Map)) continue;
                Object content = ((Map<?, ?>) msgRaw).get("content");
                if (content instanceof String && !((String) content).trim().isEmpty()) {
                    variants.add(((String) content).trim());
                }
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

    /** {error:{message}} — the stable cross-provider error shape (official). */
    public static String extractProviderMessage(String body) {
        try {
            Object errRaw = Json.parseObj(body).raw("error");
            if (errRaw instanceof Map) {
                Object msg = ((Map<?, ?>) errRaw).get("message");
                if (msg instanceof String) return (String) msg;
            }
        } catch (RuntimeException ignore) { }
        return "";
    }

    private static int intOf(Object n) {
        return n instanceof Number ? ((Number) n).intValue() : 0;
    }
}
