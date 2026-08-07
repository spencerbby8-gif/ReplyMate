package com.replymate.provider.discovery;

import com.replymate.provider.anthropic.AnthropicApi;
import com.replymate.provider.http.Diagnostics;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.openai.OpenAiParser;
import com.replymate.provider.openai.OpenAiPayloads;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live per-model classification (P-model-classify): instead of showing every discovered
 *  model equally, each one is ACTIVELY probed with a minimal request through the provider's
 *  real dialect and badged from the REAL response — never from model-name guesses.
 *  Probe cost: 1 output token per model, user-initiated only. */
public final class ModelClassifier {

    public enum Badge {
        WORKING, FREE_TIER, PAID, UNSUPPORTED, DEPRECATED,
        QUOTA_UNAVAILABLE, REQUIRES_BILLING, AUTH_FAILED, UNKNOWN
    }

    /** Outcome for one model. */
    public static final class ModelStatus {
        public final String model;
        public final Badge badge;
        public final String note;

        public ModelStatus(String model, Badge badge, String note) {
            this.model = model;
            this.badge = badge;
            this.note = note == null ? "" : note;
        }
    }

    private ModelClassifier() { }

    /** Probe every model once and return statuses sorted so RECOMMENDED models
     *  (working/free) come first and models KNOWN TO FAIL are always at the bottom —
     *  the app must never recommend a model that just failed on this key. */
    public static List<ModelStatus> classify(String wire, String baseUrl,
                                             List<String> models, String apiKey,
                                             HttpClient http) {
        List<ModelStatus> out = new ArrayList<ModelStatus>();
        if (models == null) return out;
        for (String m : models) {
            if (m == null || m.trim().isEmpty()) continue;
            out.add(probeOne(wire == null ? "openai_compat" : wire, baseUrl, m.trim(), apiKey, http));
        }
        Collections.sort(out, new Comparator<ModelStatus>() {
            @Override public int compare(ModelStatus a, ModelStatus b) {
                int r = rank(a.badge) - rank(b.badge);
                return r != 0 ? r : a.model.compareToIgnoreCase(b.model);
            }
        });
        return out;
    }

    /** Single live probe for one model. */
    public static ModelStatus probeOne(String wire, String baseUrl, String model,
                                       String apiKey, HttpClient http) {
        HttpResponse resp;
        if ("gemini".equals(wire)) {
            String url = trim(baseUrl) + "/v1beta/models/" + model + ":generateContent";
            Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("x-goog-api-key", apiKey == null ? "" : apiKey);
            resp = http.post(url, h,
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":1}}");
        } else if ("anthropic".equals(wire)) {
            resp = http.post(trim(baseUrl) + "/v1/messages",
                AnthropicApi.headers(apiKey),
                "{\"model\":\"" + model + "\",\"max_tokens\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        } else {
            resp = http.post(trim(baseUrl) + "/chat/completions",
                OpenAiPayloads.headers(apiKey),
                "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hi\"}],\"max_tokens\":1}");
        }
        return statusFor(wire, model, resp);
    }

    /** Raw response → badge. Message-aware via the same patterns Diagnostics uses
     *  (all verified against live provider bodies). */
    public static ModelStatus statusFor(String wire, String model, HttpResponse resp) {
        if (resp.code >= 200 && resp.code < 300) {
            // success → classify free-ness ONLY from real signals, never guesses:
            if ("ollama".equals(wire)) return new ModelStatus(model, Badge.FREE_TIER, "local server — no per-use cost");
            if ("openrouter".equals(wire) && model.endsWith(":free")) {
                return new ModelStatus(model, Badge.FREE_TIER, "free route per OpenRouter (:free)");
            }
            if ("openrouter".equals(wire)) {
                return new ModelStatus(model, Badge.PAID, "works — paid route per OpenRouter pricing");
            }
            return new ModelStatus(model, Badge.WORKING, "probe succeeded on this key");
        }

        Diagnostics d = Diagnostics.build(wire, "POST", "probe:" + model, model, resp,
            OpenAiParser.extractProviderMessage(resp.body));
        String low = (d.providerMsg + "\n" + resp.body).toLowerCase(Locale.US);
        switch (d.cause) {
            case AUTH:
                return new ModelStatus(model, Badge.AUTH_FAILED,
                    "key rejected — fix the key, then re-test");
            case MODEL:
                return new ModelStatus(model, Badge.UNSUPPORTED, shortNote(d.providerMsg));
            case NOT_FOUND:
                boolean phased = low.contains("no longer available") || low.contains("retired")
                    || low.contains("deprecated");
                return new ModelStatus(model,
                    phased ? Badge.DEPRECATED : Badge.UNSUPPORTED, shortNote(d.providerMsg));
            case QUOTA_ZERO:
                return new ModelStatus(model, Badge.QUOTA_UNAVAILABLE,
                    "provider grants 0 quota for this model on your key (e.g. not in free tier)");
            case NO_BALANCE:
                return new ModelStatus(model, Badge.REQUIRES_BILLING,
                    "account needs balance/credits before this can run");
            case QUOTA:
                return new ModelStatus(model, Badge.WORKING,
                    "rate-limited right now — that still proves the model works on your key; retry shortly");
            default:
                return new ModelStatus(model, Badge.UNKNOWN,
                    shortNote(d.providerMsg.isEmpty() ? "HTTP " + resp.code : d.providerMsg));
        }
    }

    /** Recommendation rank — lower is better; known-fail always below unknown. */
    public static int rank(Badge b) {
        switch (b) {
            case WORKING: case FREE_TIER: return 0;
            case PAID: return 1;
            case UNKNOWN: return 2;
            case QUOTA_UNAVAILABLE: return 3;
            case REQUIRES_BILLING: return 4;
            case AUTH_FAILED: return 5;
            case UNSUPPORTED: return 6;
            default: return 7;   // DEPRECATED
        }
    }

    public static String labelFor(Badge b) {
        switch (b) {
            case WORKING: return "Working";
            case FREE_TIER: return "Free tier";
            case PAID: return "Paid";
            case UNSUPPORTED: return "Unsupported";
            case DEPRECATED: return "Deprecated";
            case QUOTA_UNAVAILABLE: return "Quota unavailable";
            case REQUIRES_BILLING: return "Requires billing";
            case AUTH_FAILED: return "Authentication failed";
            default: return "Unknown";
        }
    }

    private static String shortNote(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
    }

    private static String trim(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
