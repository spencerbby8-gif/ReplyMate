package com.replymate.provider.anthropic;

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

/** Anthropic adapter (P-polish provider abstraction). Dialect: Messages API.
 *  Note: /v1/messages returns ONE completion per call (no n parameter), so Anthropic
 *  providers yield a single variant per generation — honest adapter limitation. */
public final class AnthropicProvider implements AiProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http;
    private final RetryPolicy retry;
    private final Logger log;

    public AnthropicProvider(String baseUrl, String model, String apiKey,
                             HttpClient http, RetryPolicy retry, Logger log) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = http;
        this.retry = retry;
        this.log = log;
    }

    @Override public String type() { return "anthropic"; }

    public String model() { return model; }

    @Override public Result<ChatReply> generate(ChatRequest request) {
        if (apiKey.isEmpty()) return Result.err("AUTH — no API key configured");
        if (model.isEmpty()) return Result.err("No model selected — pick one in Settings → AI providers");
        final String url = AnthropicApi.messagesEndpoint(baseUrl);
        final Map<String, String> headers = AnthropicApi.headers(apiKey);

        boolean extras = request.opts.search || !"default".equals(request.opts.reasoning);
        Result<ChatReply> r = post(url, headers,
            AnthropicApi.messagesBody(request, model,
                request.opts.search, request.opts.reasoning), 0);
        if (!r.ok && extras && rejectedSearchOrThinking(lastDiag)) {
            // capability honesty: the model rejected the tool or thinking fields in
            // its own words — degrade once and say exactly what was (not) used.
            log.i("anthropic", "model rejected search/thinking — one plain retry");
            r = post(url, headers,
                AnthropicApi.messagesBody(stripExtras(request), model, false, "default"), 0);
            if (r.ok) {
                r = Result.ok(r.value.withNote("this model doesn't support live search"
                    + " or thinking here, so the reply used built-in knowledge only"));
            }
        }
        return r;
    }

    private Diagnostics lastDiag;

    private Result<ChatReply> post(String url, Map<String, String> headers,
                                   String body, int attempt0) {
        int attempt = attempt0;
        while (true) {
            attempt++;
            HttpResponse resp = http.post(url, headers, body);
            if (resp.code >= 200 && resp.code < 300) {
                return AnthropicApi.parseReply(resp.body);
            }
            Diagnostics d = Diagnostics.build(type(), "POST", url, model, resp,
                AnthropicApi.extractProviderMessage(resp.body));
            lastDiag = d;
            if (retry.shouldRetry(d.error, attempt)) {
                long wait = retry.sleepMillis(attempt - 1, d.error.retryAfterSeconds);
                log.w("anthropic", d.oneLiner() + " (attempt " + attempt + ") — retrying in " + wait + "ms");
                if (!sleepMs(wait)) {
                    return Result.err(d.error.type + " — interrupted while retrying");
                }
                continue;
            }
            return Result.err(d.display());
        }
    }

    /** True only when Anthropic's own words pin the failure on the search tool or
     *  the thinking/output_config fields — other errors surface unchanged. */
    public static boolean rejectedSearchOrThinking(Diagnostics d) {
        if (d == null || d.cause != Diagnostics.Cause.OTHER) return false;
        String low = (d.providerMsg + "\n" + d.rawBody).toLowerCase(java.util.Locale.US);
        return low.contains("web_search") || low.contains("thinking")
            || low.contains("output_config") || low.contains("budget_tokens");
    }

    private static ChatRequest stripExtras(ChatRequest req) {
        return new ChatRequest(req.system, req.turns, req.task,
            req.opts.withSearch(false).withReasoning("default"));
    }

    @Override public Result<Boolean> validateKey() {
        Result<List<String>> models = listModels();
        if (models.ok) return Result.ok(Boolean.TRUE);
        return Result.err(models.error);
    }

    @Override public Result<List<String>> listModels() {
        if (baseUrl.isEmpty()) return Result.err("No provider base URL configured");
        HttpResponse resp = http.get(AnthropicApi.modelsEndpoint(baseUrl),
            AnthropicApi.headers(apiKey));
        if (resp.code >= 200 && resp.code < 300) {
            return AnthropicApi.parseModels(resp.body);
        }
        Diagnostics d = Diagnostics.build(type(), "GET", AnthropicApi.modelsEndpoint(baseUrl),
            "", resp, AnthropicApi.extractProviderMessage(resp.body));
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
