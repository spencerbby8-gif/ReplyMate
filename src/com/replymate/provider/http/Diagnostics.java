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
 *    - 402 (DeepSeek/OpenRouter) = no balance/credits — top up, don't retry.
 *
 *  P-background-11 refresh against the CURRENT official docs:
 *    - HTTP 403 is PERMISSION_DENIED (Google) / permission_error (Anthropic) /
 *      "country, region, or territory not supported" (OpenAI): the key was
 *      ACCEPTED but lacks permission for the API/model/region. Calling that an
 *      invalid key is the exact lie the audit forbids — 403 only means AUTH when
 *      the body itself says the key is bad (Google's "unregistered callers",
 *      API_KEY_INVALID), else it is its own PERMISSION outcome.
 *    - HTTP 400 FAILED_PRECONDITION ("free tier is not available in your
 *      country" / billing not enabled) is a PLAN problem, not a request bug.
 *    - HTTP 429 splits two ways that must never be conflated: a windowed rate
 *      limit (rate_limit_exceeded / RATE_LIMIT_EXCEEDED — retry after the
 *      window) versus billing exhaustion (insufficient_quota /
 *      credit_balance_exhausted / *_spend_limit_exceeded — only a top-up or a
 *      raised limit fixes it; retrying is pointless).
 *    - Model-unavailable can also surface as 404/400 "is not found for API
 *      version" / "not supported for generateContent" / "model_not_found". */
public final class Diagnostics {

    /** Why the call failed, finer-grained than ApiError.Type for interpretation. */
    public enum Cause {
        TRANSPORT, AUTH, PERMISSION, PLAN, MODEL, NOT_FOUND,
        QUOTA_ZERO, QUOTA, NO_BALANCE, SERVER, OTHER
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
        } else if (status == 401 || looksBadKeyEvidence(low)) {
            // 401 UNAUTHENTICATED / AuthenticationError: wrong, missing, expired or
            // revoked credentials — plus 400/403 bodies that PROVE a bad key in the
            // provider's own words (validated per-provider in DiagnosticsTest).
            cause = Cause.AUTH; type = ApiError.Type.AUTH; retryable = false;
            interpretation = "The provider rejected the API key for this call"
                + (status == 400 ? " (yes — this provider reports bad keys as HTTP 400, "
                  + "not 401; verified against its live API)" : "") + ".";
            suggestion = "Open Settings → AI providers → this provider and re-check the key: "
                + "paste it exactly with no spaces, or generate a fresh one from the provider's console.";
        } else if (status == 403) {
            // P-background-11: PERMISSION_DENIED without bad-key evidence is NOT an
            // auth failure — official docs: key accepted, but the API/model/region is
            // not permitted for it (Anthropic permission_error; OpenAI unsupported
            // country/region; Google PERMISSION_DENIED). "Re-check the key" would
            // send the owner on the wrong errand.
            cause = Cause.PERMISSION; type = ApiError.Type.AUTH; retryable = false;
            interpretation = "Permission denied (HTTP 403) — the provider ACCEPTED the key"
                + " but will not let it use this API/model/region. This is not an"
                + " invalid-key error and a fresh key from the same account hits the same wall"
                + (msg.isEmpty() ? "" : " — provider said: \"" + truncate(msg, 160) + "\"") + ".";
            suggestion = "Check what this key is allowed to do: enable the API for the key's project"
                + " (Google Cloud: the Generative Language API), confirm this model and your"
                + " country/region are covered by the account, or use a key from a project that has access.";
        } else if (status == 400 && looksPlanPrecondition(low)) {
            // P-background-11: Google's FAILED_PRECONDITION — free tier unavailable in
            // this country / billing not enabled. A plan problem, never a request bug.
            cause = Cause.PLAN; type = ApiError.Type.UNKNOWN; retryable = false;
            interpretation = "The provider refused on a plan precondition (HTTP 400"
                + " FAILED_PRECONDITION) — e.g. the free tier is not available for this"
                + " region or billing is not enabled. Nothing was consumed; retrying"
                + " the same request cannot succeed.";
            suggestion = "Enable billing for the key's project, or switch to a model/plan the"
                + " account and region actually cover (run \"Discover models (live)\" to see"
                + " which ones work right now).";
        } else if ((status == 400 || status == 404) && looksModelish(low)) {
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
        } else if (status == 429 && looksBillingExhaustion(low)) {
            // P-background-11: OpenAI insufficient_quota / credit_balance_exhausted /
            // *_spend_limit_exceeded, DeepSeek "exceeded your current quota" — the
            // billing balance or a hard spend cap is gone. This is NOT the retryable
            // per-minute window below; only the account can fix it.
            cause = Cause.NO_BALANCE; type = ApiError.Type.QUOTA; retryable = false;
            interpretation = "The account's credits or spend limit are exhausted (billing)"
                + " — NOT a per-minute rate window. Waiting will not fix this call; nothing"
                + " further was consumed.";
            suggestion = "Top up credits or raise the spend/quota limit in the provider's"
                + " billing console, then retry. (A plain per-minute limit would say"
                + " 'rate limit' and carry a retry hint — this one is billing.)";
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
        // P-background-11: everything PERSISTED passes through the secret redactor —
        // a provider error body must never be the reason a key (ours or a pasted
        // example) lands in the on-device diagnostic kv.
        return new Diagnostics(label, method,
            com.replymate.core.privacy.Secrets.redact(stripQuery(url)), model, status,
            com.replymate.core.privacy.Secrets.redact(truncate(raw, 900)),
            com.replymate.core.privacy.Secrets.redact(truncate(msg, 300)),
            cause, error, interpretation, suggestion);
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

    /** Compat wrapper kept for existing callers/tests: the status shortcut is
     *  still honored (401/403 are auth-FAMILY), but classification uses the
     *  evidence-only form below so permission errors surface as permissions. */
    public static boolean looksAuthish(int status, String low) {
        if (status == 401 || status == 403) return true;
        return looksBadKeyEvidence(low);
    }

    /** Body PROOF of a bad key (never the mere status). Covers: Google's
     *  "API key not valid" + API_KEY_INVALID reason + "unregistered callers";
     *  xAI's "Incorrect API key provided"; OpenRouter's "Missing Authentication
     *  header"; Mistral "Invalid API Key"; generic auth phrases. */
    public static boolean looksBadKeyEvidence(String low) {
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

    /** Plan-precondition signals (Google FAILED_PRECONDITION family): free tier
     *  unavailable in this region / billing not enabled. */
    public static boolean looksPlanPrecondition(String low) {
        return low.contains("failed_precondition")
            || low.contains("free tier is not available")
            || (low.contains("billing") && low.contains("not enabled"));
    }

    /** Billing-exhaustion 429 signals (official codes/messages): OpenAI
     *  insufficient_quota / credit_balance_exhausted / spend limits, DeepSeek
     *  "exceeded your current quota" / insufficient_user_quota. */
    public static boolean looksBillingExhaustion(String low) {
        return low.contains("insufficient_quota")
            || low.contains("insufficient_user_quota")
            || low.contains("credit_balance_exhausted")
            || low.contains("spend_limit_exceeded")
            || low.contains("exceeded your current quota")
            || low.contains("insufficient balance")
            || low.contains("billing_hard_limit");
    }

    /** Model-problem signals: xAI "Model not found: X", Google "does not exist…",
     *  Google 404/400 "is not found for API version" / "not supported for
     *  generateContent", OpenAI "model_not_found". */
    public static boolean looksModelish(String low) {
        return low.contains("model not found")
            || low.contains("model does not exist")
            || low.contains("no such model")
            || low.contains("model_not_found")
            || low.contains("is not found for api version")
            || low.contains("not supported for generatecontent")
            || low.contains("unsupported model")
            || low.contains("invalid model");
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
