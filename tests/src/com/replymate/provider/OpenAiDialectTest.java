package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Result;
import com.replymate.provider.http.RetryPolicy;
import com.replymate.provider.openai.OpenAiCompatProvider;
import com.replymate.provider.openai.OpenAiParser;
import com.replymate.provider.openai.OpenAiPayloads;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** OpenAI-compatible dialect (P-polish): request shape per official docs, variants +
 *  graceful n-fallback behavior, model-list discovery, error mapping, keyless mode. */
public class OpenAiDialectTest {

    private static ChatRequest req(int candidates) {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: you around?"));
        return new ChatRequest("system here", turns, Turn.user("task here"),
            GenerationOpts.of(candidates, 0.8, 220));
    }

    /* --------------------------------------------------------------- payloads */

    @Test public void chatBodyMatchesOfficialShape() {
        String body = OpenAiPayloads.chatBody(req(3), "my-model", true);
        JsonObj o = Json.parseObj(body);
        assertEquals("my-model", o.str("model"));
        assertEquals(0.8, o.dbl("temperature").doubleValue(), 0.001);
        assertEquals(220L, (long) o.lng("max_tokens"));
        assertEquals(3L, (long) o.lng("n"));
        List<?> messages = (List<?>) o.raw("messages");
        assertEquals(3, messages.size());                       // system + turn + task
        assertEquals("system", ((Map<?, ?>) messages.get(0)).get("role"));
        assertEquals("system here", ((Map<?, ?>) messages.get(0)).get("content"));
        assertEquals("user", ((Map<?, ?>) messages.get(2)).get("role"));
    }

    @Test public void nIsOmittedWhenFallbackRequestedOrSingleVariant() {
        assertNull(Json.parseObj(OpenAiPayloads.chatBody(req(3), "m", false)).raw("n"));
        assertNull(Json.parseObj(OpenAiPayloads.chatBody(req(1), "m", true)).raw("n"));
    }

    @Test public void bearerHeaderOnlyWhenKeyPresent() {
        assertTrue(OpenAiPayloads.headers("sk-1").containsKey("Authorization"));
        assertNull(OpenAiPayloads.headers("").get("Authorization"));   // Ollama keyless
        assertNull(OpenAiPayloads.headers(null).get("Authorization"));
    }

    @Test public void endpointsTrimTrailingSlashes() {
        assertEquals("https://x.ai/v1/chat/completions",
            OpenAiPayloads.chatEndpoint("https://x.ai/v1/"));
        assertEquals("https://x.ai/v1/models", OpenAiPayloads.modelsEndpoint("https://x.ai/v1"));
    }

    /* ---------------------------------------------------------------- parser */

    @Test public void parseReplyCollectsAllChoicesAndUsage() {
        String body = "{\"choices\":["
            + "{\"message\":{\"role\":\"assistant\",\"content\":\"first \"}},"
            + "{\"message\":{\"role\":\"assistant\",\"content\":\" second\"}}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":9}}";
        Result<ChatReply> r = OpenAiParser.parseReply(body);
        assertTrue(r.ok);
        assertEquals(Arrays.asList("first", "second"), r.value.variants);
        assertEquals(12, r.value.tokensIn);
        assertEquals(9, r.value.tokensOut);
    }

    @Test public void parseModelsReadsDataIds() {
        String body = "{\"data\":[{\"id\":\"zeta\"},{\"id\":\"alpha\"},{\"id\":\"alpha\"}]}";
        Result<List<String>> r = OpenAiParser.parseModels(body);
        assertTrue(r.ok);
        assertEquals(Arrays.asList("alpha", "zeta"), r.value);
    }

    @Test public void errorsCarryTheProvidersOwnMessage() {
        String msg = OpenAiParser.extractProviderMessage(
            "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"authentication_error\"}}");
        assertEquals("Invalid API key", msg);
    }

    /* -------------------------------------------------- provider n-fallback path */

    /** Harness HttpClient that rejects any body containing \"n\": with a 400. */
    private static final class NoNHttp extends com.replymate.provider.http.HttpClient {
        int posts;
        @Override public com.replymate.provider.http.HttpResponse post(
                String url, Map<String, String> headers, String jsonBody) {
            posts++;
            if (jsonBody.contains("\"n\":")) {
                return new com.replymate.provider.http.HttpResponse(400,
                    "{\"error\":{\"message\":\"'n' is not supported by this server\"}}", null);
            }
            return new com.replymate.provider.http.HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"solo reply\"}}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4}}", null);
        }
    }

    @Test public void providerFallsBackToSingleVariantWhenNRejected() {
        OpenAiCompatProvider p = new OpenAiCompatProvider("openai_compat",
            "https://x/v1", "m", "k", new NoNHttp(), new RetryPolicy(), com.replymate.fakes.Fakes.NOOP_LOG);
        Result<ChatReply> r = p.generate(req(3));
        assertTrue(r.ok);
        assertEquals(1, r.value.variants.size());
        assertEquals("solo reply", r.value.variants.get(0));
    }

    @Test public void missingBaseUrlOrModelFailsFriendlyWithoutHttp() {
        OpenAiCompatProvider noBase = new OpenAiCompatProvider("custom", "", "m", "k",
            new NoNHttp(), new RetryPolicy(), null);
        assertTrue(noBase.generate(req(1)).error.contains("base URL"));
        OpenAiCompatProvider noModel = new OpenAiCompatProvider("custom", "https://x", "", "k",
            new NoNHttp(), new RetryPolicy(), null);
        assertTrue(noModel.generate(req(1)).error.contains("model"));
    }
}
