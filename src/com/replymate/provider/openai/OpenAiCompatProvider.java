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

        // P-intelligence-6 dialect routing (capability-driven, docs-verified):
        //  · OPENAI needs the Responses dialect for native web search (chat
        //    completions has no optional search) and for reasoning models.
        //  · GROK (xAI) search lives on the xAI Responses endpoint — the legacy
        //    search_parameters Live Search is retired (410 Gone, Jan 12 2026).
        //  · every other family keeps chat/completions with its documented extras
        //    applied by OpenAiPayloads (OpenRouter tool+reasoning, Kimi builtin+
        //    thinking, DeepSeek thinking+effort, Mistral effort).
        com.replymate.core.model.ProviderType pt =
            com.replymate.core.model.ProviderType.fromWire(wireType);
        com.replymate.core.caps.ModelCaps caps =
            com.replymate.core.caps.ModelCaps.of(pt, model);
        boolean wantsExtras = request.opts.search
            || !"default".equals(request.opts.reasoning);
        boolean responsesRoute =
            (pt == com.replymate.core.model.ProviderType.OPENAI
                    && (request.opts.search
                        || (!"default".equals(request.opts.reasoning)
                            && caps.reasoning
                                == com.replymate.core.caps.ModelCaps.Reasoning.ADJUSTABLE)))
            || (pt == com.replymate.core.model.ProviderType.GROK && request.opts.search);
        if (responsesRoute) {
            Result<ChatReply> rr = callResponses(headers, request);
            if (rr.ok && "grok".equals(wireType) && rr.value.searchQueries == 0) {
                // xAI reports server-tool counts separately from the output items.
                int calls = ResponsesApi.xaiToolCalls(responsesLastBody);
                if (calls > 0) {
                    rr = Result.ok(new ChatReply(rr.value.variants, rr.value.tokensIn,
                        rr.value.tokensOut, rr.value.limits, calls,
                        rr.value.searchSources, rr.value.reasoningTokens, rr.value.note));
                }
            }
            return rr;
        }

        String url = OpenAiPayloads.chatEndpoint(baseUrl);
        if (pt == com.replymate.core.model.ProviderType.KIMI && request.opts.search) {
            return kimiSearchLoop(url, headers, request);
        }

        Result<ChatReply> r = callWithRetry(url, headers,
            OpenAiPayloads.chatBody(request, model, true, wireType, null));
        if (!r.ok && rejectedN(lastDiag)) {
            // graceful fallback: this server rejected the n>1 variants parameter itself
            // (verified message match — NOT auth/model/quota problems, which must surface
            //  honestly instead of provoking a confusing double request).
            log.i(wireType, "retrying without n (server may not support variants)");
            r = callWithRetry(url, headers,
                OpenAiPayloads.chatBody(request, model, false, wireType, null));
        }
        if (!r.ok && wantsExtras && rejectedExtras(lastDiag)) {
            log.i(wireType, "model rejected search/reasoning extras — one plain retry");
            r = callWithRetry(url, headers, OpenAiPayloads.chatBody(
                stripExtras(request), model, true, wireType, null));
            if (r.ok) {
                r = Result.ok(r.value.withNote("this model doesn't support the live"
                    + " search or thinking options here — built-in knowledge only"));
            }
        }
        return r;
    }

    private String responsesLastBody = "";

    /** The official POST {base}/responses path with an honest single-step
     *  degradation to chat/completions when the endpoint/tool isn't there
     *  (404/501 or the provider's own "responses/web_search not supported"). */
    private Result<ChatReply> callResponses(Map<String, String> headers,
                                            ChatRequest request) {
        String url = ResponsesApi.endpoint(baseUrl);
        String body = ResponsesApi.body(request, model, request.opts.search,
            request.opts.reasoning);
        HttpResponse resp = http.post(url, headers, body);
        responsesLastBody = resp.body;
        if (resp.code >= 200 && resp.code < 300) {
            return ResponsesApi.parseReply(resp.body);
        }
        String msg = OpenAiParser.extractProviderMessage(resp.body);
        String low = (msg + "\n" + resp.body).toLowerCase(java.util.Locale.US);
        boolean unsupported = resp.code == 404 || resp.code == 501
            || low.contains("responses api") || low.contains("web_search")
            || low.contains("not supported");
        if (!unsupported) {
            lastDiag = Diagnostics.build(wireType, "POST", url, model, resp, msg);
            return Result.err(lastDiag.display());   // REAL failure, shown as such
        }
        log.i(wireType, "responses endpoint/tool unsupported — plain chat retry");
        Result<ChatReply> r = callWithRetry(OpenAiPayloads.chatEndpoint(baseUrl), headers,
            OpenAiPayloads.chatBody(stripExtras(request), model, true, wireType, null));
        if (r.ok) {
            r = Result.ok(r.value.withNote("live search isn't available on this"
                + " endpoint — the reply used built-in knowledge only"));
        }
        return r;
    }

    /** Moonshot's official $web_search loop: when the model answers with
     *  finish_reason "tool_calls", the client appends the assistant message
     *  verbatim plus one role:"tool" message PER CALL carrying the arguments
     *  JSON unchanged — the platform executes the search itself. Capped at 2
     *  echo rounds (cost + latency), then the model must answer with what it got. */
    private Result<ChatReply> kimiSearchLoop(String url, Map<String, String> headers,
                                             ChatRequest request) {
        com.replymate.core.json.JsonArr messages = null;
        for (int round = 0; round <= 2; round++) {
            HttpResponse resp = postOnce(url, headers,
                OpenAiPayloads.chatBody(request, model, round == 0, wireType, messages));
            if (resp.code < 200 || resp.code >= 300) {
                lastDiag = Diagnostics.build(wireType, "POST", url, model, resp,
                    OpenAiParser.extractProviderMessage(resp.body));
                return Result.err(lastDiag.display());
            }
            com.replymate.core.util.Result<OpenAiParser.RawReply> raw =
                OpenAiParser.parseRaw(resp.body);
            if (!raw.ok) return com.replymate.core.util.Result.err(raw.error);
            if (!raw.value.wantsToolCalls() || round == 2) {
                Result<ChatReply> r = OpenAiParser.parseReply(resp.body);
                if (r.ok && round > 0) {
                    r = Result.ok(new ChatReply(r.value.variants, r.value.tokensIn,
                        r.value.tokensOut, r.value.limits, 1, null,
                        r.value.reasoningTokens, r.value.note));
                }
                return r;
            }
            if (messages == null) {
                messages = com.replymate.core.json.JsonArr.create();
                if (request.system != null && !request.system.trim().isEmpty()) {
                    messages.add(com.replymate.core.json.JsonObj.create()
                        .put("role", "system").put("content", request.system));
                }
                for (com.replymate.core.ai.Turn t : request.turns) {
                    messages.add(com.replymate.core.json.JsonObj.create()
                        .put("role", t.role == com.replymate.core.ai.Turn.Role.USER
                            ? "user" : "assistant").put("content", t.text));
                }
                if (request.task != null) {
                    messages.add(com.replymate.core.json.JsonObj.create()
                        .put("role", "user").put("content", request.task.text));
                }
            }
            // the assistant tool_calls message round-trips raw + byte-exact
            messages.add(com.replymate.core.json.Json.parseObj(
                raw.value.assistantMessageJson));
            for (Map<?, ?> call : raw.value.toolCalls) {
                messages.add(OpenAiParser.toolEchoMessage(call));
            }
            log.i("kimi", "$web_search echo round " + (round + 1));
        }
        return Result.err("PARSE — search loop did not produce a reply");
    }

    /** Raw single POST (no retry policy) — used by strict-sequence tool loops. */
    private HttpResponse postOnce(String url, Map<String, String> headers, String body) {
        return http.post(url, headers, body);
    }

    /** True only when the provider's own words pin the failure on search or the
     *  reasoning fields (NEVER auth/quota/model errors — those surface as-is). */
    public static boolean rejectedExtras(Diagnostics d) {
        if (d == null || d.cause != Diagnostics.Cause.OTHER) return false;
        String low = (d.providerMsg + "\n" + d.rawBody).toLowerCase(java.util.Locale.US);
        return low.contains("web_search") || low.contains("openrouter:web")
            || low.contains("builtin_function") || low.contains("reasoning_effort")
            || low.contains("thinking") || low.contains("tool is not supported")
            || (low.contains("tool_calls") && low.contains("not supported"));
    }

    private static ChatRequest stripExtras(ChatRequest req) {
        return new ChatRequest(req.system, req.turns, req.task,
            req.opts.withSearch(false).withReasoning("default"));
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
