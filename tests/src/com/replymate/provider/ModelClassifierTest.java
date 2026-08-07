package com.replymate.provider;

import com.replymate.provider.discovery.ModelClassifier;
import com.replymate.provider.discovery.ModelClassifier.Badge;
import com.replymate.provider.discovery.ModelClassifier.ModelStatus;
import com.replymate.provider.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Model classification (P-model-classify): every badge is mapped from REAL provider
 *  responses (fixtures copied from the live captures, incl. the owner's fresh-key run),
 *  requests go to the correct per-dialect URLs, and known-fail models are ALWAYS sorted
 *  below working ones — the picker must never recommend a model that just failed. */
public class ModelClassifierTest {

    /** Routes canned responses by the :generateContent model or the body's model id. */
    private static final class CannedHttp extends com.replymate.provider.http.HttpClient {
        final Map<String, HttpResponse> byModel = new HashMap<String, HttpResponse>();
        String lastUrl, lastBody;
        Map<String, String> lastHeaders;
        CannedHttp respond(String model, int code, String body) {
            byModel.put(model, new HttpResponse(code, body, null));
            return this;
        }
        private static String modelOf(String url, String body) {
            if (url.contains("/models/")) {
                String tail = url.substring(url.indexOf("/models/") + 8);
                int colon = tail.indexOf(':');
                return colon > 0 ? tail.substring(0, colon) : tail;
            }
            int i = body.indexOf("\"model\":\"");
            if (i < 0) return "?";
            int j = body.indexOf('"', i + 9);
            return body.substring(i + 9, j);
        }
        @Override public HttpResponse post(String url, Map<String, String> headers, String body) {
            lastUrl = url; lastBody = body; lastHeaders = headers;
            HttpResponse r = byModel.get(modelOf(url, body));
            return r == null ? new HttpResponse(418, "{}", null) : r;
        }
    }

    @Test public void badgesFromRealResponseShapes() {
        CannedHttp http = new CannedHttp()
            .respond("yes-ok", 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}")
            .respond("gone-404", 404, "{\"error\":{\"code\":404,\"message\":\"This model models/gone-404 is no longer available to new users.\",\"status\":\"NOT_FOUND\"}}")
            .respond("zero-429", 429, DiagnosticsTest.GEMINI_429_LIMIT_ZERO)
            .respond("no-key", 400, DiagnosticsTest.GEMINI_400_BAD_KEY)
            .respond("ghost-400", 400, "{\"code\":\"invalid-argument\",\"error\":\"Model not found: ghost-400\"}")
            .respond("broke-402", 402, "{\"error\":{\"message\":\"Insufficient Balance\"}}")
            .respond("busy-429", 429, "{\"error\":{\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}")
            .respond("flat-404", 404, "{\"error\":{\"message\":\"Not found\",\"status\":\"NOT_FOUND\"}}");

        assertEquals(Badge.WORKING, ModelClassifier.statusFor("gemini", "yes-ok", http.byModel.get("yes-ok")).badge);
        assertEquals(Badge.DEPRECATED, ModelClassifier.statusFor("gemini", "gone-404", http.byModel.get("gone-404")).badge);
        ModelStatus zero = ModelClassifier.statusFor("gemini", "zero-429", http.byModel.get("zero-429"));
        assertEquals(Badge.QUOTA_UNAVAILABLE, zero.badge);
        assertTrue(zero.note.contains("0 quota"));
        assertEquals(Badge.AUTH_FAILED, ModelClassifier.statusFor("gemini", "no-key", http.byModel.get("no-key")).badge);
        assertEquals(Badge.UNSUPPORTED, ModelClassifier.statusFor("grok", "ghost-400", http.byModel.get("ghost-400")).badge);
        assertEquals(Badge.REQUIRES_BILLING, ModelClassifier.statusFor("deepseek", "broke-402", http.byModel.get("broke-402")).badge);
        ModelStatus busy = ModelClassifier.statusFor("gemini", "busy-429", http.byModel.get("busy-429"));
        assertEquals("a real per-minute rate limit proves the model works", Badge.WORKING, busy.badge);
        assertTrue(busy.note.contains("rate-limited"));
        assertEquals("generic 404 without phasing language", Badge.UNSUPPORTED,
            ModelClassifier.statusFor("openai_compat", "flat-404", http.byModel.get("flat-404")).badge);
        ModelStatus transport = ModelClassifier.statusFor("ollama", "llama3.1",
            HttpResponse.transportFailure("Connection refused"));
        assertEquals(Badge.UNKNOWN, transport.badge);
    }

    @Test public void freeTierSignalsAreDataDrivenNotGuessed() {
        assertEquals(Badge.FREE_TIER,
            ModelClassifier.statusFor("openrouter", "qwen/qwen3-coder:free",
                new HttpResponse(200, "{}", null)).badge);
        assertEquals(Badge.PAID,
            ModelClassifier.statusFor("openrouter", "openai/gpt-5.5",
                new HttpResponse(200, "{}", null)).badge);
        assertEquals(Badge.FREE_TIER,
            ModelClassifier.statusFor("ollama", "llama3.1",
                new HttpResponse(200, "{}", null)).badge);
        assertEquals("plain working is not claimed free without evidence", Badge.WORKING,
            ModelClassifier.statusFor("gemini", "gemini-3.5-flash-lite",
                new HttpResponse(200, "{}", null)).badge);
    }

    @Test public void probesHitTheRightDialectsAndBodies() {
        CannedHttp http = new CannedHttp()
            .respond("gemini-3.5-flash-lite", 200, "{}")
            .respond("claude-sonnet-5", 200, "{}")
            .respond("deepseek-chat", 200, "{}");
        ModelClassifier.classify("gemini", "https://generativelanguage.googleapis.com",
            Arrays.asList("gemini-3.5-flash-lite"), "K", http);
        assertTrue(http.lastUrl.endsWith("/v1beta/models/gemini-3.5-flash-lite:generateContent"));
        assertTrue(http.lastBody.contains("\"maxOutputTokens\":1"));

        ModelClassifier.classify("anthropic", "https://api.anthropic.com",
            Arrays.asList("claude-sonnet-5"), "K", http);
        assertTrue(http.lastUrl.endsWith("/v1/messages"));
        assertTrue(http.lastBody.contains("\"max_tokens\":1"));
        assertEquals("K", http.lastHeaders.get("x-api-key"));
        assertEquals("2023-06-01", http.lastHeaders.get("anthropic-version"));

        ModelClassifier.classify("deepseek", "https://api.deepseek.com",
            Arrays.asList("deepseek-chat"), "K", http);
        assertTrue(http.lastUrl.endsWith("/chat/completions"));
        assertTrue(http.lastBody.contains("\"model\":\"deepseek-chat\""));
    }

    @Test public void knownFailNeverOutranksWorking() {
        CannedHttp http = new CannedHttp()
            .respond("a-deprecated", 404, "{\"error\":{\"code\":404,\"message\":\"This model is no longer available to new users.\"}}")
            .respond("b-quota", 429, DiagnosticsTest.GEMINI_429_LIMIT_ZERO)
            .respond("c-working", 200, "{}")
            .respond("d-auth", 400, DiagnosticsTest.GEMINI_400_BAD_KEY)
            .respond("e-paid", 200, "{}");
        List<ModelStatus> out = ModelClassifier.classify("gemini", "https://x",
            Arrays.asList("a-deprecated", "b-quota", "c-working", "d-auth", "e-paid"), "K", http);
        assertEquals("c-working", out.get(0).model);
        assertEquals("e-paid", out.get(1).model);
        // everything from index 2 down is known-fail/unknown — never recommended first
        for (int i = 2; i < out.size(); i++) {
            assertTrue(ModelClassifier.rank(out.get(i).badge) > 0);
        }
        assertEquals("a-deprecated", out.get(out.size() - 1).model);   // worst, dead last
    }
}
