package com.replymate.provider.http;

import com.replymate.core.model.ProviderType;
import java.util.Locale;

/** Provider-audit diagnostics: a single, honest, user-visible record of every failed
 *  provider call. Built from the REAL status + RAW body (never assumed), so the user can
 *  see: provider · endpoint · model · HTTP status · what the provider actually said ·
 *  what ReplyMate thinks it means · what to try next.
 *
 *  Classification is message-aware, not just status-aware, because real providers
 *  disagree on status codes (verified live, 2026-08-07):
 *    - Google Gemini returns HTTP 400 + reason API_KEY_INVALID for a bad key (not 401)
 *    - xAI (Grok) returns HTTP 400 {"error":"Incorrect API key…"} (string error, not 401)
 *    - Grok also returns HTTP 400 "Model not found: X" — model problems, not auth, not quota
 *    - Mistral returns HTTP 401 {"detail":"Invalid API Key"} (no "error" envelope)
 *    - Gemini free-tier can return HTTP 429 with "limit: 0" — the model is unavailable
 *      on the key (plan/model restriction), NOT quota exhaustion. Never retry those.
 *    - 402 (DeepSeek/OpenRouter) = no balance/credits — top up, don't retry. */
public final class Diagnostics {

    /** Why the call failed, finer-grained than ApiError.Type for interpretation. */
    public enum Cause {
        TRANSPORT, AUTH, MODEL, NOT_FOUND, QUOTA_ZERO, QUOTA, NO_BALANCE, SERVER, OTHER
    }

    public final String providerLabel;
    public final String method;
    public final String url;
    public final String model;
    public final int status;            // -1 = transport failure
    public final String rawBody;        // truncated excerpt of what came back
    public final String providerMsg;    // extracted provider error text ("" if none)
    public final Cause cause;
    public final ApiError error;        // refined classification (retryable honored)
    public final String interpretation; // "ReplyMate read:"
    public final String suggestion;     // "Suggested fix:"

    private Diagnostics(String providerLabel, String method, String url, String model,
                        int status, String rawBody, String providerMsg, Cause cause,
                        ApiError error, String interpretation, String suggestion) {
        this.providerLabel = providerLabel;
        this.method = method;
        this.url = url;
        this.model = model;
        this.status = status;
        this.rawBody = rawBody;
        this.providerMsg = providerMsg;
        this.cause = cause;
        this.error = error;
        this.interpretation = interpretation;
        this.suggestion = suggestion;
    }

    /** Build from a real non-2xx response. providerMsg must already be extracted from the
     *  body in a shape-tolerant way by the caller's parser (string/object/detail forms). */
    public static Diagnostics build(String wireType, String method, String url, String model,
                                    HttpResponse resp, String providerMsg) {
        String label = labelFor(wireType);
        String msg = providerMsg == null ? "" : providerMsg;
        String raw = resp.body == null ? "" : resp.body;
        String low = (msg + "\n" + raw.substring(0, Math.min(raw.length(), 1200)))
            .toLowerCase(Locale.US);
        int status = resp.code;

        long retryAfter = ApiError.parseRetryAfter(raw);
        String retryHead = resp.header("retry-after");
        if (retryHead != null) {
            try { retryAfter = Long.parseLong(retryHead.trim()); } catch (NumberFormatException ignored) { }
        }

        Cause cause;
        ApiError.Type type;
        boolean retryable;
        String interpretation;
        String suggestion;

        if (status < 0) {
            cause = Cause.TRANSPORT; type = ApiError.Type.NETWORK; retryable = true;
            String reason = resp.header("x-error");
            interpretation = "No response from the server"
                + (reason == null ? "" : " (" + reason + ")") + ".";
            suggestion = isLocalUrl(url)
                ? "This is a local/self-hosted server — make sure it is running (for Ollama: "
                  + "the Ollama app or `ollama serve`), that the device can reach the host, "
                  + "and that the base URL + port are right."
                : "Check the internet connection, then try again. If it persists, the provider "
                  + "may be blocking the network or the base URL may be wrong.";
        } else if (status == 401 || status == 403 || looksAuthish(status, low)) {
            cause = Cause.AUTH; type = ApiError.Type.AUTH; retryable = false;
            interpretation = "The provider rejected the API key for this call"
                + (status == 400 ? " (yes — this provider reports bad keys as HTTP 400, "
                  + "not 401; verified against its live API)" : "") + ".";
            suggestion = "Open Settings → AI providers → this provider and re-check the key: "
                + "paste it exactly with no spaces, or generate a fresh one from the provider's console.";
        } else if (status == 400 && looksModelish(low)) {
            cause = Cause.MODEL; type = ApiError.Type.UNKNOWN; retryable = false;
            interpretation = "The provider does not recognize this model"
                + (model == null || model.isEmpty() ? "" : " (\"" + model + "\")")
                + " for this endpoint.";
            suggestion = "Open the provider and tap \"Discover models (live)\", then pick one "
                + "from the returned list — hand-typed or remembered names often don't exist "
                + "or were renamed/retired.";
        } else if (status == 404) {
            cause = Cause.NOT_FOUND; type = ApiError.Type.UNKNOWN; retryable = false;
            interpretation = "Endpoint or model not found (HTTP 404)"
                + (model == null || model.isEmpty() ? "" : " — \"" + model + "\"")
                + ". The model may have been retired or renamed.";
            suggestion = "Run \"Discover models (live)\" and pick a returned model; if discovery "
                + "itself 404s, fix the base URL to match the provider's current docs.";
        } else if (status == 402) {
            cause = Cause.NO_BALANCE; type = ApiError.Type.QUOTA; retryable = false;
            interpretation = "No balance/credits on the account (HTTP 402) — nothing was consumed by this call.";
            suggestion = "Top up the account or add credits with the provider, then retry. "
                + "Retrying without a top-up cannot succeed.";
        } else if (status == 429 && hasZeroLimit(raw)) {
            cause = Cause.QUOTA_ZERO; type = ApiError.Type.QUOTA; retryable = false;
            interpretation = "Quota limit is 0 for this model on your key — this is NOT "
                + "used-up quota. Nothing was consumed; the provider does not offer this "
                + "model to your key/plan at all.";
            suggestion = "Pick a different model (for Google AI Studio: one of the models the "
                + "free tier actually covers today — see the rate-limits page; the Flash/Flash-Lite "
                + "tier is the free workhorse), or enable billing for this model. Retrying the "
                + "same model will never succeed.";
        } else if (status == 429) {
            cause = Cause.QUOTA; type = ApiError.Type.QUOTA; retryable = true;
            interpretation = "Rate limited — a per-minute or per-day cap was actually reached";
            if (retryAfter > 0) interpretation += " (provider says wait ~" + retryAfter + "s)";
            interpretation += ".";
            suggestion = "Wait for the window to reset and try again. If it keeps happening on a "
                + "fresh day, switch model or check the plan with the provider.";
        } else if (status >= 500 && status < 600) {
            cause = Cause.SERVER; type = ApiError.Type.SERVER; retryable = true;
            interpretation = "The provider's own server failed (HTTP " + status + ") — usually temporary.";
            suggestion = "ReplyMate retries these automatically; try again in a minute. If it "
                + "persists, check the provider's status page.";
        } else {
            cause = Cause.OTHER; type = ApiError.Type.UNKNOWN; retryable = false;
            interpretation = "Unusual reply from the provider (HTTP " + status
                + ") — see \"Provider said\" below for its own words.";
            suggestion = "If the text mentions the model, run \"Discover models (live)\" and re-pick. "
                + "Otherwise re-check the base URL against the provider's docs.";
        }

        ApiError error = new ApiError(type, interpretation, retryAfter, retryable);
        return new Diagnostics(label, method, stripQuery(url), model, status,
            truncate(raw, 900), truncate(msg, 300), cause, error, interpretation, suggestion);
    }

    /** One-line summary for logs/compact surfaces. */
    public String oneLiner() {
        return error.type + " — " + interpretation;
    }

    /** Full user-facing block: every field the audit mandate requires the user to see. */
    public String display() {
        StringBuilder sb = new StringBuilder();
        sb.append(error.type).append(" — ").append(interpretation).append('\n');
        sb.append("Provider: ").append(providerLabel).append('\n');
        sb.append("Endpoint: ").append(method).append(' ').append(url).append('\n');
        if (model != null && !model.isEmpty()) sb.append("Model: ").append(model).append('\n');
        sb.append("HTTP status: ").append(status < 0 ? "no response (transport failure)" : String.valueOf(status)).append('\n');
        sb.append("Provider said: ").append(providerMsg.isEmpty() ? "(no message in the reply body)" : providerMsg).append('\n');
        sb.append("ReplyMate read: ").append(interpretation).append('\n');
        sb.append("Suggested fix: ").append(suggestion).append('\n');
        if (!rawBody.isEmpty()) sb.append("— raw provider body —\n").append(rawBody);
        return sb.toString().trim();
    }

    // ---------- pattern helpers (kept regex-free; substrings verified against live bodies) ----------

    /** Bad-key signals inside the message/body. Covers: Google's "API key not valid" +
     *  API_KEY_INVALID reason; xAI's "Incorrect API key provided"; OpenRouter's
     *  "Missing Authentication header"; Mistral "Invalid API Key"; generic auth phrases. */
    public static boolean looksAuthish(int status, String low) {
        if (status == 401 || status == 403) return true;
        return low.contains("api key not valid")
            || low.contains("api_key_invalid")
            || low.contains("incorrect api key")
            || low.contains("invalid api key")
            || low.contains("invalid x-api-key")
            || low.contains("authentication fail")
            || low.contains("invalid authentication")
            || low.contains("missing authentication")
            || low.contains("unregistered callers")
            || low.contains("no auth credentials");
    }

    /** Model-problem signals: xAI "Model not found: X", Google "does not exist …". */
    public static boolean looksModelish(String low) {
        return low.contains("model not found")
            || low.contains("model does not exist")
            || low.contains("no such model");
    }

    /** "limit: 0" or "quotaValue": "0" anywhere in the body → quota was never granted. */
    public static boolean hasZeroLimit(String body) {
        if (body == null) return false;
        return body.contains("limit: 0")
            || body.contains("\"quotaValue\": \"0\"")
            || body.contains("\"quotaValue\":\"0\"")
            || body.contains("\"quotaValue\":0")
            || body.contains("\"quotaValue\":\"0\"");
    }

    private static boolean isLocalUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.startsWith("http://localhost") || u.startsWith("http://127.")
            || u.startsWith("http://10.") || u.startsWith("http://192.168.")
            || u.contains(".local");
    }

    private static String labelFor(String wireType) {
        try {
            return ProviderType.fromWire(wireType).label;
        } catch (RuntimeException ignore) {
            return wireType == null ? "Unknown provider" : wireType;
        }
    }

    private static String stripQuery(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
