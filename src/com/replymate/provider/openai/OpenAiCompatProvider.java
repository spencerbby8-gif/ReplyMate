package com.replymate.provider.openai;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import com.replymate.provider.http.Diagnostics;
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

    // Diagnostics of the most recent failed attempt (single-flight usage in this app).
    private Diagnostics lastDiag;

    @Override public Result<ChatReply> generate(ChatRequest request) {
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured — open Settings → AI providers");
        if (model.isEmpty()) return Result.err("No model selected — pick one in Settings → AI providers");
        Map<String, String> headers = OpenAiPayloads.headers(apiKey);
        String url = OpenAiPayloads.chatEndpoint(baseUrl);
        Result<ChatReply> r = callWithRetry(url, headers, OpenAiPayloads.chatBody(request, model, true));
        if (!r.ok && rejectedN(lastDiag)) {
            // graceful fallback: this server rejected the n>1 variants parameter itself
            // (verified message match — NOT auth/model/quota problems, which must surface
            //  honestly instead of provoking a confusing double request).
            log.i(wireType, "retrying without n (server may not support variants)");
            r = callWithRetry(url, headers, OpenAiPayloads.chatBody(request, model, false));
        }
        return r;
    }

    /** True only when the provider's own words pin the failure on the n parameter —
     *  e.g. "'n' is not supported by this server" (captured live). */
    public static boolean rejectedN(Diagnostics d) {
        if (d == null || d.cause != Diagnostics.Cause.OTHER) return false;
        String low = (d.providerMsg + "\n" + d.rawBody).toLowerCase(java.util.Locale.US);
        return low.contains("\"n\"") || low.contains("'n'") || low.contains(" n is not")
            || low.contains("parameter n") || low.contains("num_candidates")
            || low.contains("candidatecount");
    }

    private Result<ChatReply> callWithRetry(String url, Map<String, String> headers,
                                            String body) {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse resp = http.post(url, headers, body);
            if (resp.code >= 200 && resp.code < 300) {
                return OpenAiParser.parseReply(resp.body);
            }
            lastDiag = Diagnostics.build(wireType, "POST", url, model, resp,
                OpenAiParser.extractProviderMessage(resp.body));
            if (retry.shouldRetry(lastDiag.error, attempt)) {
                long wait = retry.sleepMillis(attempt - 1, lastDiag.error.retryAfterSeconds);
                log.w(wireType, lastDiag.oneLiner() + " (attempt " + attempt + ") — retrying in " + wait + "ms");
                if (!sleepMs(wait)) {
                    return Result.err(lastDiag.error.type + " — interrupted while retrying");
                }
                continue;
            }
            return Result.err(lastDiag.display());
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
        String url = OpenAiPayloads.modelsEndpoint(baseUrl);
        lastDiag = Diagnostics.build(wireType, "GET", url, "", resp,
            OpenAiParser.extractProviderMessage(resp.body));
        return Result.err(lastDiag.display());
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
