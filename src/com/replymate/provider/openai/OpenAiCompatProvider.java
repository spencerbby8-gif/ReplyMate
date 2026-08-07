package com.replymate.provider.openai;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import java.util.List;
import java.util.Map;

/** One adapter for the whole OpenAI-compatible family (P-polish provider abstraction):
 *  OpenAI, OpenRouter, DeepSeek, Grok, Kimi, Mistral, Ollama, custom endpoints.
 *  Graceful degradation: if a provider rejects the n>1 variants parameter (some
 *  compatible servers do), the call is retried once WITHOUT n and we accept the single
 *  variant — the app keeps working instead of failing the generation. */
public final class OpenAiCompatProvider implements AiProvider {

    private final String wireType;
    private final String baseUrl;
    private final String model;
    private final String apiKey;      // may be "" for keyless servers (Ollama)
    private final HttpClient http;
    private final RetryPolicy retry;
    private final Logger log;

    public OpenAiCompatProvider(String wireType, String baseUrl, String model, String apiKey,
                                HttpClient http, RetryPolicy retry, Logger log) {
        this.wireType = wireType == null ? "openai_compat" : wireType;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = http;
        this.retry = retry;
        this.log = log;
    }

    @Override public String type() { return wireType; }

    public String model() { return model; }

    @Override public Result<ChatReply> generate(ChatRequest request) {
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured — open Settings → AI providers");
        if (model.isEmpty()) return Result.err("No model selected — pick one in Settings → AI providers");
        Map<String, String> headers = OpenAiPayloads.headers(apiKey);
        Result<ChatReply> r = callWithRetry(OpenAiPayloads.chatEndpoint(baseUrl),
            headers, OpenAiPayloads.chatBody(request, model, true), true);
        if (!r.ok && r.error != null && (r.error.startsWith("UNKNOWN") || r.error.startsWith("PARSE"))) {
            // graceful fallback: n>1 unsupported by this server → single-variant call
            log.i(wireType, "retrying without n (server may not support variants)");
            r = callWithRetry(OpenAiPayloads.chatEndpoint(baseUrl),
                headers, OpenAiPayloads.chatBody(request, model, false), true);
        }
        return r;
    }

    private Result<ChatReply> callWithRetry(String url, Map<String, String> headers,
                                            String body, boolean allowRetry) {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse resp = http.post(url, headers, body);
            if (resp.code >= 200 && resp.code < 300) {
                return OpenAiParser.parseReply(resp.body);
            }
            ApiError err = OpenAiParser.errorFrom(resp);
            if (allowRetry && retry.shouldRetry(err, attempt)) {
                long wait = retry.sleepMillis(attempt - 1, err.retryAfterSeconds);
                log.w(wireType, err.type + " (attempt " + attempt + ") — retrying in " + wait + "ms");
                if (!sleepMs(wait)) {
                    return Result.err(err.type + " — interrupted while retrying");
                }
                continue;
            }
            return Result.err(err.type + " — " + err.message);
        }
    }

    @Override public Result<Boolean> validateKey() {
        Result<List<String>> models = listModels();
        if (models.ok) return Result.ok(Boolean.TRUE);
        return Result.err(models.error);
    }

    @Override public Result<List<String>> listModels() {
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured");
        HttpResponse resp = http.get(OpenAiPayloads.modelsEndpoint(baseUrl),
            OpenAiPayloads.headers(apiKey));
        if (resp.code >= 200 && resp.code < 300) {
            return OpenAiParser.parseModels(resp.body);
        }
        ApiError err = OpenAiParser.errorFrom(resp);
        return Result.err(err.type + " — " + err.message);
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
