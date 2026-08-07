package com.replymate.provider;

import com.replymate.core.util.Result;
import com.replymate.provider.gemini.GeminiParser;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Gemini discovery + model config (P-polish): official /v1beta/models surface,
 *  generateContent filtering + prefix stripping, key-header auth, and the guarantee
 *  that NO model name is hardcoded as a fallback anymore. */
public class GeminiDiscoveryTest {

    @Test public void modelsEndpointIsOfficial() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models",
            GeminiPayloads.modelsEndpoint("https://generativelanguage.googleapis.com/"));
    }

    @Test public void authUsesTheKeyHeaderNotTheUrl() {
        Map<String, String> h = GeminiPayloads.headers("abc123");
        assertEquals("abc123", h.get("x-goog-api-key"));   // keys never ride the URL
    }

    @Test public void parseModelsFiltersGenerateContentAndStripsPrefix() {
        Result<List<String>> r = GeminiParser.parseModels(
            "{\"models\":["
            + "{\"name\":\"models/zebra\",\"supportedGenerationMethods\":[\"generateContent\"]},"
            + "{\"name\":\"models/embedder\",\"supportedGenerationMethods\":[\"embedContent\"]},"
            + "{\"name\":\"models/alpha\",\"supportedGenerationMethods\":[\"generateContent\"]}"
            + "]}");
        assertTrue(r.ok);
        assertEquals(Arrays.asList("alpha", "zebra"), r.value);
    }

    private static final class ModelsHttp extends com.replymate.provider.http.HttpClient {
        @Override public HttpResponse get(String url, Map<String, String> headers) {
            if (!"abc".equals(headers.get("x-goog-api-key"))) {
                return new HttpResponse(403, "{\"error\":{\"message\":\"bad key\"}}", null);
            }
            return new HttpResponse(200,
                "{\"models\":[{\"name\":\"models/flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}",
                null);
        }
    }

    @Test public void validateKeyIsAFreeModelsProbeNotAGeneration() {
        GeminiProvider ok = new GeminiProvider("https://generativelanguage.googleapis.com",
            "flash", "abc", new ModelsHttp(), new RetryPolicy(),
            com.replymate.fakes.Fakes.NOOP_LOG);
        assertTrue(ok.validateKey().ok);
        GeminiProvider bad = new GeminiProvider("https://generativelanguage.googleapis.com",
            "flash", "nope", new ModelsHttp(), new RetryPolicy(),
            com.replymate.fakes.Fakes.NOOP_LOG);
        assertFalse(bad.validateKey().ok);
        assertTrue(bad.validateKey().error.contains("AUTH"));
    }

    @Test public void noModelFallbackIsHardcodedAnymore() {
        GeminiProvider p = new GeminiProvider("https://generativelanguage.googleapis.com",
            "", "k", new ModelsHttp(), new RetryPolicy(), com.replymate.fakes.Fakes.NOOP_LOG);
        assertEquals("", p.model());
        assertTrue(p.generate(null).error.contains("model"));
    }
}
