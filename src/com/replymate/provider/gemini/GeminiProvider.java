package com.replymate.provider.gemini;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import com.replymate.provider.http.Diagnostics;
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
        // P-polish: no hardcoded defaults — ProviderType supplies the default base URL
        // at config time; the model always comes from live discovery or user input.
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.http = http;
        this.retry = retry;
        this.log = log;
    }

    @Override public String type() { return "gemini"; }

    public String model() { return model; }

    // Diagnostics of the most recent failed attempt (single-flight usage in this app).
    private Diagnostics lastDiag;

    @Override public Result<ChatReply> generate(ChatRequest request) {
        if (apiKey.isEmpty()) return Result.err("AUTH — no API key configured");
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured — open Settings → AI providers");
        if (model.isEmpty()) return Result.err("No model selected — pick one in Settings → AI providers");
        final String url = GeminiPayloads.endpoint(baseUrl, model);
        final Map<String, String> headers = GeminiPayloads.headers(apiKey);

        boolean extras = request.opts.search
            || !"default".equals(request.opts.reasoning);
        Result<ChatReply> r = callWithRetry(url, headers,
            GeminiPayloads.generateBody(request, true, request.opts.search,
                request.opts.reasoning, model));
        if (!r.ok && rejectedMultipleCandidates(lastDiag)) {
            // Live-verified 2026-08-07: the Gemini 3 generation rejects candidateCount
            // ("Multiple candidates is not enabled for this model"). Graceful fallback:
            // retry once without the field — the app gets 1 variant instead of failing.
            log.i("Gemini", "retrying without candidateCount (model rejects multiple candidates)");
            r = callWithRetry(url, headers,
                GeminiPayloads.generateBody(request, false, request.opts.search,
                    request.opts.reasoning, model));
        }
        if (!r.ok && extras && rejectedSearchOrThinking(lastDiag)) {
            // Capability honesty (P-intelligence-6): this model rejected the search
            // tool or thinking config IN THE PROVIDER'S OWN WORDS — degrade once to a
            // plain call and say exactly what was (not) used, never pretend.
            log.i("Gemini", "model rejected search/thinking — retrying once without extras");
            r = callWithRetry(url, headers,
                GeminiPayloads.generateBody(request, true, false, "default", model));
            if (!r.ok && rejectedMultipleCandidates(lastDiag)) {
                r = callWithRetry(url, headers,
                    GeminiPayloads.generateBody(request, false, false, "default", model));
            }
            if (r.ok) {
                r = Result.ok(r.value.withNote("this model doesn't support live search"
                    + " or thinking here, so the reply used built-in knowledge only"));
            }
        }
        return r;
    }

    /** True only when Google's own error text pins the failure on the search tool
     *  or the thinking config — auth/quota/model errors must surface untouched. */
    public static boolean rejectedSearchOrThinking(Diagnostics d) {
        if (d == null || d.cause != Diagnostics.Cause.OTHER) return false;
        String low = (d.providerMsg + "\n" + d.rawBody).toLowerCase(java.util.Locale.US);
        return low.contains("google_search") || low.contains("googlesearch")
            || low.contains("search grounding") || low.contains("tool is not supported")
            || low.contains("thinkingconfig") || low.contains("thinking config")
            || low.contains("thinkinglevel") || low.contains("thinkingbudget")
            || low.contains("thinking level") || low.contains("thinking budget");
    }

    /** True only when Google's own words pin the failure on multiple candidates. */
    public static boolean rejectedMultipleCandidates(Diagnostics d) {
        if (d == null || d.cause != Diagnostics.Cause.OTHER) return false;
        String low = (d.providerMsg + "\n" + d.rawBody).toLowerCase(java.util.Locale.US);
        return low.contains("multiple candidates")
            || low.contains("candidatecount") || low.contains("candidate count");
    }

    private Result<ChatReply> callWithRetry(String url, Map<String, String> headers,
                                            String body) {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResponse resp = http.post(url, headers, body);
            if (resp.code >= 200 && resp.code < 300) {
                return GeminiParser.parseReply(resp.body);
            }
            // P-provider-audit: honest diagnostics from the REAL status + RAW body.
            lastDiag = Diagnostics.build(type(), "POST", url, model, resp,
                GeminiParser.extractProviderMessage(resp.body));
            if (retry.shouldRetry(lastDiag.error, attempt)) {
                long wait = retry.sleepMillis(attempt - 1, lastDiag.error.retryAfterSeconds);
                log.w("Gemini", lastDiag.oneLiner() + " (attempt " + attempt + ") — retrying in " + wait + "ms");
                if (!sleepMs(wait)) {
                    return Result.err(lastDiag.error.type + " — interrupted while retrying");
                }
                continue;
            }
            return Result.err(lastDiag.display());
        }
    }

    @Override public Result<Boolean> validateKey() {
        // Free probe: the models endpoint only verifies credentials, it never burns a
        // generation token (replaces the old 1-token probe).
        Result<java.util.List<String>> models = listModels();
        if (models.ok) return Result.ok(Boolean.TRUE);
        return Result.err(models.error);
    }

    @Override public Result<java.util.List<String>> listModels() {
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured");
        HttpResponse resp = http.get(GeminiPayloads.modelsEndpoint(baseUrl),
            GeminiPayloads.headers(apiKey));
        if (resp.code >= 200 && resp.code < 300) {
            return GeminiParser.parseModels(resp.body);
        }
        Diagnostics d = Diagnostics.build(type(), "GET", GeminiPayloads.modelsEndpoint(baseUrl),
            "", resp, GeminiParser.extractProviderMessage(resp.body));
        return Result.err(d.display());
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
