package com.replymate.provider;

import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.Diagnostics;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.openai.OpenAiCompatProvider;
import com.replymate.provider.openai.OpenAiParser;
import com.replymate.provider.gemini.GeminiParser;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider audit (P-provider-audit): error mapping must reflect what providers ACTUALLY
 *  say — never assumed. Every fixture below is a REAL body captured from a live request
 *  against the provider's official endpoint on 2026-08-07 (see docs/provider-probes/).
 *  Only the "limit: 0" 429 body is text-built from Google's documented error shape plus
 *  the exact public reports (reddit r/Bard 1pgwy47, ai.google.dev forum 171555). */
public class DiagnosticsTest {

    // ---- LIVE-CAPTURED BODIES (verbatim from docs/provider-probes/, 2026-08-07) ----

    static final String GEMINI_400_BAD_KEY =
        "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
        + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
        + "\"reason\":\"API_KEY_INVALID\",\"domain\":\"googleapis.com\",\"metadata\":"
        + "{\"service\":\"generativelanguage.googleapis.com\"}}]}}";

    static final String GEMINI_403_NO_KEY =
        "{\"error\":{\"code\":403,\"message\":\"Method doesn't allow unregistered callers "
        + "(callers without established identity). Please use API Key or other form of API "
        + "consumer identity to call this API.\",\"status\":\"PERMISSION_DENIED\"}}";

    static final String GROK_400_BAD_KEY_STRING_ERR =
        "{\"code\":\"invalid-argument\",\"error\":\"Incorrect API key provided. "
        + "You can obtain an API key from https://console.x.ai.\"}";

    static final String GROK_400_MODEL_NOT_FOUND =
        "{\"code\":\"invalid-argument\",\"error\":\"Model not found: AUDIT-MODEL\"}";

    static final String OPENAI_401 =
        "{\"error\":{\"message\":\"Incorrect API key provided: sk-audit*********************0000. "
        + "You can find your API key at https://platform.openai.com/account/api-keys.\","
        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}";

    static final String OPENROUTER_401 =
        "{\"error\":{\"message\":\"Missing Authentication header\",\"code\":401}}";

    static final String ANTHROPIC_401 =
        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"},"
        + "\"request_id\":\"req_011CdnVktGH3RWWztiCGXU1y\"}";

    static final String DEEPSEEK_401 =
        "{\"error\":{\"message\":\"Authentication Fails, Your api key: ****0000 is invalid\","
        + "\"type\":\"authentication_error\",\"param\":null,\"code\":\"invalid_request_error\"}}";

    static final String KIMI_401 =
        "{\"error\":{\"message\":\"Invalid Authentication\",\"type\":\"invalid_authentication_error\"}}";

    static final String MISTRAL_401 =
        "{\"detail\":\"Invalid API Key\"}";

    /** Documented Google QuotaFailure shape (ai.google.dev error docs) + the exact "limit: 0"
     *  wording from the public field reports of NEW free-tier keys hitting 429. */
    /** Loaded verbatim at test time from the live wire capture
     *  (docs/provider-probes/gemini-2.5-pro-429-full.body): a brand-new free-tier
     *  key's FIRST ever request to gemini-2.5-pro. "limit: 0" appears four times —
     *  quota was never granted; nothing was exhausted. The test suite reads the
     *  actual file so the fixture can never drift from the real capture. */
    static final String GEMINI_429_LIMIT_ZERO = readProbe("gemini-2.5-pro-429-full.body");

    /** Google's documented RetryInfo shape (ai.google.dev error docs): a REAL per-minute
     *  rate-limit response (not the limit:0 case) carrying retryDelay for backoff. */
    static final String GEMINI_429_REAL_RPM =
        "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\","
        + "\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
        + "\"reason\":\"RATE_LIMIT_EXCEEDED\"},{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\","
        + "\"retryDelay\":\"17s\"}]}}";

    static String readProbe(String name) {
        try {
            String src = System.getProperty("replymate.src", "src");
            java.io.File f = new java.io.File(new java.io.File(src).getParentFile(),
                "docs/provider-probes/" + name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (java.io.IOException boom) {
            throw new AssertionError("probe fixture unreadable: " + name + " — " + boom);
        }
    }

    private static Map<String, String> hdr(String k, String v) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static Diagnostics openaiStyle(String wire, int code, String body) {
        return Diagnostics.build(wire, "POST", "https://example/v1/chat/completions", "m",
            new HttpResponse(code, body, null), OpenAiParser.extractProviderMessage(body));
    }

    // ---- the audit mandate: never assume — verify each provider's REAL error ----

    @Test public void geminiBadKeyIsAuthEvenThoughStatusIs400() {
        Diagnostics d = Diagnostics.build("gemini", "GET",
            "https://generativelanguage.googleapis.com/v1beta/models", "",
            new HttpResponse(400, GEMINI_400_BAD_KEY, null),
            GeminiParser.extractProviderMessage(GEMINI_400_BAD_KEY));
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertFalse("bad key must not auto-retry", d.error.retryable());
        assertTrue(d.providerMsg.contains("API key not valid"));
        assertTrue(d.display().contains("Provider said: API key not valid"));
    }

    @Test public void geminiMissingKey403IsAuth() {
        Diagnostics d = Diagnostics.build("gemini", "GET",
            "https://generativelanguage.googleapis.com/v1beta/models", "",
            new HttpResponse(403, GEMINI_403_NO_KEY, null),
            GeminiParser.extractProviderMessage(GEMINI_403_NO_KEY));
        assertEquals(ApiError.Type.AUTH, d.error.type);
    }

    @Test public void grokStringErrorIsAuthEvenThoughStatusIs400() {
        Diagnostics d = openaiStyle("grok", 400, GROK_400_BAD_KEY_STRING_ERR);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertTrue("string-form error must be surfaced, not dropped", d.providerMsg.contains("Incorrect API key"));
        assertFalse(d.error.retryable());
    }

    @Test public void grokModelNotFoundIsModelNotAuthNotQuota() {
        Diagnostics d = openaiStyle("grok", 400, GROK_400_MODEL_NOT_FOUND);
        assertEquals(Diagnostics.Cause.MODEL, d.cause);
        assertEquals(ApiError.Type.UNKNOWN, d.error.type);
        assertFalse(d.error.retryable());
        assertTrue(d.suggestion.contains("Discover models"));
        assertFalse("must not auto-suggest re-checking the key", d.interpretation.contains("key"));
    }

    @Test public void openai401Auth() {
        Diagnostics d = openaiStyle("openai", 401, OPENAI_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertTrue(d.providerMsg.contains("Incorrect API key"));
    }

    @Test public void openrouterNumericErrorCodeStillYieldsMessage() {
        Diagnostics d = openaiStyle("openrouter", 401, OPENROUTER_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertEquals("Missing Authentication header", d.providerMsg);
    }

    @Test public void anthropicEnvelopeAuth() {
        Diagnostics d = openaiStyle("anthropic", 401, ANTHROPIC_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertEquals("invalid x-api-key", d.providerMsg);
    }

    @Test public void deepseekAuth() {
        Diagnostics d = openaiStyle("deepseek", 401, DEEPSEEK_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertTrue(d.providerMsg.contains("Authentication Fails"));
    }

    @Test public void kimiAuth() {
        Diagnostics d = openaiStyle("kimi", 401, KIMI_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
    }

    @Test public void mistralDetailShapeIsAuthAndSurfaced() {
        Diagnostics d = openaiStyle("mistral", 401, MISTRAL_401);
        assertEquals(ApiError.Type.AUTH, d.error.type);
        assertEquals("Invalid API Key", d.providerMsg);
    }

    // ---- THE Gemini investigation: 429 on a brand-new key is NOT assumed exhaustion ----

    @Test public void geminiZeroLimit429IsNotExhaustionAndNeverRetries() {
        Diagnostics d = Diagnostics.build("gemini", "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
            "gemini-2.5-pro", new HttpResponse(429, GEMINI_429_LIMIT_ZERO, null),
            GeminiParser.extractProviderMessage(GEMINI_429_LIMIT_ZERO));
        assertEquals(Diagnostics.Cause.QUOTA_ZERO, d.cause);
        assertEquals(ApiError.Type.QUOTA, d.error.type);
        assertFalse("limit:0 can never succeed on retry", d.error.retryable());
        assertTrue(d.interpretation.contains("NOT used-up quota"));
        assertTrue("guidance must point at a different model or billing, not waiting",
            d.suggestion.contains("different model"));
        assertTrue(d.display().contains("HTTP status: 429"));
        assertTrue(d.display().contains("limit: 0"));
    }

    @Test public void geminiRealRateLimit429StillRetriesAndParsesRetryDelay() {
        Diagnostics d = Diagnostics.build("gemini", "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            "gemini-2.5-flash", new HttpResponse(429, GEMINI_429_REAL_RPM, null),
            GeminiParser.extractProviderMessage(GEMINI_429_REAL_RPM));
        assertEquals(Diagnostics.Cause.QUOTA, d.cause);
        assertTrue(d.error.retryable());
        assertEquals(17, d.error.retryAfterSeconds);
    }

    @Test public void http402IsBalanceNotExhaustionAndDoesNotRetry() {
        Diagnostics d = openaiStyle("deepseek", 402,
            "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"exceeded_current_quota_error\","
            + "\"param\":null,\"code\":\"insufficient_user_quota\"}}");
        assertEquals(ApiError.Type.QUOTA, d.error.type);
        assertFalse(d.error.retryable());
        assertTrue(d.suggestion.toLowerCase(java.util.Locale.US).contains("top up"));
    }

    @Test public void transportFailureIsNetworkWithLocalServerHint() {
        Diagnostics local = Diagnostics.build("ollama", "GET", "http://localhost:11434/v1/models",
            "", HttpResponse.transportFailure("Connection refused"), "");
        assertEquals(ApiError.Type.NETWORK, local.error.type);
        assertTrue(local.error.retryable());
        assertTrue(local.interpretation.contains("Connection refused"));
        assertTrue(local.suggestion.contains("Ollama"));

        Diagnostics remote = Diagnostics.build("openai", "POST", "https://api.openai.com/v1/chat/completions",
            "m", HttpResponse.transportFailure("timeout"), "");
        assertEquals(ApiError.Type.NETWORK, remote.error.type);
        assertFalse(remote.suggestion.contains("Ollama"));
    }

    // ---- the display block must carry every field the mandate demands ----

    @Test public void displayShowsEveryRequiredFieldAndNeverLeaksKeys() {
        Diagnostics d = openaiStyle("grok", 400, GROK_400_MODEL_NOT_FOUND);
        String block = d.display();
        assertTrue(block.contains("Provider: "));
        assertTrue(block.contains("Endpoint: POST https://example/v1/chat/completions"));
        assertTrue(block.contains("Model: m"));
        assertTrue(block.contains("HTTP status: 400"));
        assertTrue(block.contains("Provider said: "));
        assertTrue(block.contains("ReplyMate read: "));
        assertTrue(block.contains("Suggested fix: "));
        assertTrue(block.contains("raw provider body"));
        assertFalse("no API key may appear anywhere in diagnostics",
            block.toLowerCase(java.util.Locale.US).contains("sk-"));
    }

    @Test public void providerLabelComesFromWireType() {
        Diagnostics d = openaiStyle("deepseek", 401, DEEPSEEK_401);
        assertTrue(d.display().contains("DeepSeek"));
    }

    // ---- n-fallback: only for servers that name the n parameter, nothing else ----

    @Test public void nFallbackOnlyOnExplicitNRejection() {
        Diagnostics nRejected = openaiStyle("openai_compat", 400,
            "{\"error\":{\"message\":\"'n' is not supported by this server\"}}");
        assertTrue(OpenAiCompatProvider.rejectedN(nRejected));

        assertFalse("model problems must NOT trigger the n-fallback",
            OpenAiCompatProvider.rejectedN(openaiStyle("grok", 400, GROK_400_MODEL_NOT_FOUND)));
        assertFalse("auth problems must NOT trigger the n-fallback",
            OpenAiCompatProvider.rejectedN(openaiStyle("grok", 400, GROK_400_BAD_KEY_STRING_ERR)));
        assertFalse("quota must NOT trigger the n-fallback",
            OpenAiCompatProvider.rejectedN(openaiStyle("deepseek", 402,
                "{\"error\":{\"message\":\"Insufficient Balance\"}}")));
    }

    /* ================= P-background-11: current-docs classification refresh =====
     * Fixtures follow the OFFICIAL error models (ai.google.dev / cloud.google.com
     * Vertex "API errors", platform.openai.com error-codes, Anthropic error
     * envelope). The mandate: never call a real quota / rate-limit / permission /
     * provider error an invalid key without evidence. */

    /** Google PERMISSION_DENIED with NO bad-key evidence (the documented Vertex/
     *  Gemini shape for "this project/key has no permission for this API"). */
    static final String GEMINI_403_PERMISSION =
        "{\"error\":{\"code\":403,\"message\":\"Generative Language API has not been used in "
        + "project 123 before or it is disabled.\",\"status\":\"PERMISSION_DENIED\"}}";

    static final String GEMINI_400_FAILED_PRECONDITION =
        "{\"error\":{\"code\":400,\"message\":\"Gemini API free tier is not available in your "
        + "country. Please enable billing on your project in Google AI Studio.\","
        + "\"status\":\"FAILED_PRECONDITION\"}}";

    static final String OPENAI_429_INSUFFICIENT_QUOTA =
        "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and "
        + "billing details.\",\"type\":\"insufficient_quota\",\"param\":null,"
        + "\"code\":\"insufficient_quota\"}}";

    static final String OPENAI_429_RATE_WINDOW =
        "{\"error\":{\"message\":\"Rate limit reached for gpt-4o-mini in organization org-x on "
        + "requests per min (RPM): Limit 500, Used 812.\",\"type\":\"tokens\","
        + "\"param\":null,\"code\":\"rate_limit_exceeded\"}}";

    static final String OPENAI_429_SPEND_LIMIT =
        "{\"error\":{\"message\":\"Your organization reached its enforced spend limit.\","
        + "\"type\":\"server_error\",\"param\":null,\"code\":\"organization_spend_limit_exceeded\"}}";

    static final String ANTHROPIC_403_PERMISSION =
        "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":\"Your API key "
        + "does not have permission to use the specified resource.\"},\"request_id\":\"req_x\"}";

    static final String OPENAI_404_MODEL =
        "{\"error\":{\"message\":\"The model `gpt-audit` does not exist or you do not have "
        + "access to it.\",\"type\":\"invalid_request_error\",\"param\":\"model\","
        + "\"code\":\"model_not_found\"}}";

    static final String GEMINI_404_MODEL_GONE =
        "{\"error\":{\"code\":404,\"message\":\"models/gemini-old is not found for API version "
        + "v1beta, or is not supported for generateContent.\",\"status\":\"NOT_FOUND\"}}";

    @Test public void plain403IsPermissionNeverABadKeyClaim() {
        Diagnostics d = Diagnostics.build("gemini", "GET",
            "https://generativelanguage.googleapis.com/v1beta/models", "",
            new HttpResponse(403, GEMINI_403_PERMISSION, null),
            GeminiParser.extractProviderMessage(GEMINI_403_PERMISSION));
        assertEquals(Diagnostics.Cause.PERMISSION, d.cause);
        assertEquals(ApiError.Type.AUTH, d.error.type);   // auth FAMILY, honest copy
        assertFalse(d.error.retryable());
        assertTrue(d.interpretation.contains("ACCEPTED the key"));
        assertTrue("only ever named as a NEGATION — never claimed",
            d.interpretation.contains("not an invalid-key error"));
        assertTrue(d.suggestion.contains("enable the API"));
    }

    @Test public void freeTierRegionPreconditionIsAPlanProblem() {
        Diagnostics d = Diagnostics.build("gemini", "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            "gemini-2.5-flash", new HttpResponse(400, GEMINI_400_FAILED_PRECONDITION, null),
            GeminiParser.extractProviderMessage(GEMINI_400_FAILED_PRECONDITION));
        assertEquals(Diagnostics.Cause.PLAN, d.cause);
        assertFalse(d.error.retryable());
        assertTrue(d.interpretation.contains("plan precondition"));
        assertTrue(d.suggestion.contains("billing"));
        assertFalse("must not mislabel as a bad key", d.suggestion.contains("re-check the key"));
    }

    @Test public void openaiInsufficientQuotaIsBillingNeverAKeyAndNeverARetry() {
        Diagnostics d = openaiStyle("openai", 429, OPENAI_429_INSUFFICIENT_QUOTA);
        assertEquals(Diagnostics.Cause.NO_BALANCE, d.cause);
        assertEquals(ApiError.Type.QUOTA, d.error.type);
        assertFalse("billing exhaustion cannot succeed on retry", d.error.retryable());
        assertTrue(d.suggestion.toLowerCase(java.util.Locale.US).contains("top up"));
        assertFalse(d.interpretation.contains("key"));
    }

    @Test public void openaiWindowedRateLimitStillRetries() {
        Diagnostics d = openaiStyle("openai", 429, OPENAI_429_RATE_WINDOW);
        assertEquals(Diagnostics.Cause.QUOTA, d.cause);
        assertTrue("a per-minute window is the retryable 429", d.error.retryable());
        assertTrue(d.interpretation.contains("per-minute"));
    }

    @Test public void openaiSpendLimitIsBillingExhaustion() {
        Diagnostics d = openaiStyle("openai", 429, OPENAI_429_SPEND_LIMIT);
        assertEquals(Diagnostics.Cause.NO_BALANCE, d.cause);
        assertFalse(d.error.retryable());
    }

    @Test public void anthropicPermissionErrorIsPermission() {
        Diagnostics d = openaiStyle("anthropic", 403, ANTHROPIC_403_PERMISSION);
        assertEquals(Diagnostics.Cause.PERMISSION, d.cause);
        assertTrue(d.interpretation.contains("Permission denied"));
    }

    @Test public void modelErrorsAt404ClassifyAsModel() {
        Diagnostics d1 = openaiStyle("openai", 404, OPENAI_404_MODEL);
        assertEquals(Diagnostics.Cause.MODEL, d1.cause);
        assertTrue(d1.suggestion.contains("Discover models"));
        Diagnostics d2 = Diagnostics.build("gemini", "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-old:generateContent",
            "gemini-old", new HttpResponse(404, GEMINI_404_MODEL_GONE, null),
            GeminiParser.extractProviderMessage(GEMINI_404_MODEL_GONE));
        assertEquals(Diagnostics.Cause.MODEL, d2.cause);
    }

    @Test public void everyPersistedFieldIsSecretFree() {
        String bodyWithSecrets = "{\"error\":{\"message\":\"bad call — tried key "
            + "AIzaSyAuditOnly0123456789abcd and Authorization: Bearer sk-abcdef0123456789abcdef\"}}";
        Diagnostics d = Diagnostics.build("gemini", "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/m:generateContent?key=AIzaSyAuditOnly0123456789abcd",
            "m", new HttpResponse(400, bodyWithSecrets, null),
            GeminiParser.extractProviderMessage(bodyWithSecrets));
        String all = d.display() + "\n" + d.rawBody + "\n" + d.providerMsg + "\n" + d.url;
        assertFalse(all.contains("AIzaSyAuditOnly0123456789abcd"));
        assertFalse(all.contains("sk-abcdef0123456789abcdef"));
        assertTrue("the honest explanation still shows", d.display().contains("ReplyMate read:"));
    }

    // ---- shape-tolerant extraction (the four real envelopes) ----

    @Test public void extractionCoversEveryRealShape() {
        assertEquals("Incorrect API key provided. You can obtain an API key from https://console.x.ai.",
            OpenAiParser.extractProviderMessage(GROK_400_BAD_KEY_STRING_ERR));   // string error
        assertEquals("Invalid API Key",
            OpenAiParser.extractProviderMessage(MISTRAL_401));                   // {detail}
        assertEquals("invalid x-api-key",
            OpenAiParser.extractProviderMessage(ANTHROPIC_401));                 // nested error.message
        assertEquals("Missing Authentication header",
            OpenAiParser.extractProviderMessage(OPENROUTER_401));                // numeric code tolerated
        assertEquals("hello world",
            OpenAiParser.extractProviderMessage("{\"message\":\"hello world\"}")); // top-level message fallback
        assertEquals("", OpenAiParser.extractProviderMessage("{\"nope\":true}"));
        assertEquals("", OpenAiParser.extractProviderMessage("not json"));
    }
}
