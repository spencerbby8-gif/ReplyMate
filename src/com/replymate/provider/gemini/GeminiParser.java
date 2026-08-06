package com.replymate.provider.gemini;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Response/error parsing for the Gemini generateContent API. */
public final class GeminiParser {

    private GeminiParser() { }

    /** Parse a 2xx body into a ChatReply. */
    public static Result<ChatReply> parseReply(String body) {
        final JsonObj root;
        try {
            root = Json.parseObj(body);
        } catch (RuntimeException e) {
            return Result.err("PARSE — invalid JSON from provider: " + e.getMessage());
        }

        JsonArr candidates = root.arr("candidates");
        if (candidates == null || candidates.size() == 0) {
            String feedback = root.obj("promptFeedback") != null
                ? " (blocked: " + String.valueOf(root.obj("promptFeedback").str("blockReason")) + ")"
                : "";
            return Result.err("PARSE — provider returned no reply candidates" + feedback);
        }

        List<String> variants = new ArrayList<String>();
        for (int i = 0; i < candidates.size(); i++) {
            JsonObj cand = candidates.obj(i);
            if (cand == null) continue;
            JsonObj content = cand.obj("content");
            if (content == null) continue;
            JsonArr parts = content.arr("parts");
            if (parts == null) continue;
            StringBuilder text = new StringBuilder();
            for (int p = 0; p < parts.size(); p++) {
                String t = parts.obj(p) == null ? null : parts.obj(p).str("text");
                if (t != null) text.append(t);
            }
            String v = text.toString().trim();
            if (!v.isEmpty()) variants.add(v);
        }

        int tokensIn = 0, tokensOut = 0;
        JsonObj usage = root.obj("usageMetadata");
        if (usage != null) {
            tokensIn = (int) usage.lng("promptTokenCount", 0);
            tokensOut = (int) usage.lng("candidatesTokenCount", 0);
        }
        return Result.ok(new ChatReply(variants, tokensIn, tokensOut, RateLimitInfo.NONE));
    }

    /** Build a classified error from any non-2xx response (Retry-After header wins over body). */
    public static ApiError errorFrom(HttpResponse resp) {
        ApiError base = ApiError.of(resp.code, resp.body);
        long retryAfter = base.retryAfterSeconds;
        String header = resp.header("retry-after");
        if (header != null) {
            try {
                retryAfter = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) { }
        }
        String detail = extractProviderMessage(resp.body);
        String message = base.message + (detail == null ? "" : " — " + detail);
        return new ApiError(base.type, message, retryAfter);
    }

    /** Best-effort extraction of {"error":{"message":…}} from a provider error body (≤140 chars). */
    public static String extractProviderMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonObj root = Json.parseObj(body);
            JsonObj err = root.obj("error");
            if (err == null) return null;
            String msg = err.str("message");
            if (msg == null) return null;
            return msg.length() > 140 ? msg.substring(0, 140) + "…" : msg;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
