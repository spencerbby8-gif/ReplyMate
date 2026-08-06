package com.replymate.provider.gemini;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import java.util.ArrayList;
import java.util.Map;

/** Gemini AiProvider (BLUEPRINT §5.2/§5.5): HTTPS call with retry/backoff honoring
 *  Retry-After, classified ApiError → user-readable Result errors. */
public final class GeminiProvider implements AiProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http;
    private final RetryPolicy retry;
    private final Logger log;

    public GeminiProvider(String baseUrl, String model, String apiKey,
                          HttpClient http, RetryPolicy retry, Logger log) {
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty())
            ? "https://generativelanguage.googleapis.com" : baseUrl.trim();
        this.model = (model == null || model.trim().isEmpty()) ? "gemini-2.5-flash" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.http = http;
        this.retry = retry;
        this.log = log;
    }

    @Override public String type() { return "gemini"; }

    public String model() { return model; }

    @Override public Result<ChatReply> generate(ChatRequest request) {
        if (apiKey.isEmpty()) return Result.err("AUTH — no API key configured");
        final String url = GeminiPayloads.endpoint(baseUrl, model);
        final Map<String, String> headers = GeminiPayloads.headers(apiKey);
        final String body = GeminiPayloads.generateBody(request);

        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse resp = http.post(url, headers, body);
            if (resp.code >= 200 && resp.code < 300) {
                return GeminiParser.parseReply(resp.body);
            }
            ApiError err = GeminiParser.errorFrom(resp);
            if (retry.shouldRetry(err, attempt)) {
                long wait = retry.sleepMillis(attempt - 1, err.retryAfterSeconds);
                log.w("Gemini", err.type + " (attempt " + attempt + ") — retrying in " + wait + "ms");
                if (!sleepMs(wait)) {
                    return Result.err(err.type + " — interrupted while retrying");
                }
                continue;
            }
            return Result.err(err.type + " — " + err.message);
        }
    }

    @Override public Result<Boolean> validateKey() {
        ChatRequest probe = new ChatRequest(
            "Reply with exactly the word: ok",
            new ArrayList<Turn>(),
            Turn.user("ping"),
            GenerationOpts.of(1, 0.0, 8));
        return generate(probe).map(new Result.Mapper<ChatReply, Boolean>() {
            @Override public Boolean apply(ChatReply r) { return Boolean.TRUE; }
        });
    }

    private static boolean sleepMs(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
