package com.replymate.provider;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.provider.gemini.GeminiPayloads;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Golden wire-format test — pinned to the documented generateContent shape. */
public class GeminiPayloadTest {

    @Test public void goldenRequestBody() {
        ChatRequest req = new ChatRequest(
            "SYS",
            Arrays.asList(Turn.user("Ama: hi"), Turn.model("hey")),
            Turn.user("TASK"),
            GenerationOpts.of(3, 0.8, 220));
        String body = GeminiPayloads.generateBody(req);
        String expected = "{\"system_instruction\":{\"parts\":[{\"text\":\"SYS\"}]},"
            + "\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"Ama: hi\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"text\":\"hey\"}]},"
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"TASK\"}]}],"
            + "\"generationConfig\":{\"temperature\":0.8,\"candidateCount\":3,\"maxOutputTokens\":220}}";
        assertEquals(expected, body);
    }

    @Test public void endpointTrimsTrailingSlash() {
        assertEquals("https://x.test/v1beta/models/m:generateContent",
            GeminiPayloads.endpoint("https://x.test/", "m"));
        assertEquals("https://x.test/v1beta/models/m:generateContent",
            GeminiPayloads.endpoint("https://x.test", "m"));
    }

    @Test public void headersCarryApiKeyOnly() {
        Map<String, String> h = GeminiPayloads.headers("abc123");
        assertEquals("abc123", h.get("x-goog-api-key"));
        assertEquals(1, h.size());
    }

    @Test public void nullTaskIsOmittedFromContents() {
        ChatRequest req = new ChatRequest("S", Arrays.asList(Turn.user("one")), null,
            GenerationOpts.defaults());
        String body = GeminiPayloads.generateBody(req);
        assertFalse(body.contains("TASK"));
        assertEquals(1, countOccurrences(body, "\"role\":"));
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
