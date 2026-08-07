package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.util.Result;
import com.replymate.provider.gemini.GeminiParser;
import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiParserTest {

    @Test public void parsesMultipleCandidatesAndUsage() {
        String body = "{\"candidates\":["
            + "{\"content\":{\"parts\":[{\"text\":\"first\"}]}},"
            + "{\"content\":{\"parts\":[{\"text\":\"second\"},{\"text\":\" half\"}]}},"
            + "{\"content\":{\"role\":\"model\"}}"
            + "],\"usageMetadata\":{\"promptTokenCount\":120,\"candidatesTokenCount\":33}}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertTrue(r.ok);
        assertEquals(2, r.value.variants.size());
        assertEquals("first", r.value.variants.get(0));
        assertEquals("second half", r.value.variants.get(1));   // parts joined
        assertEquals(120, r.value.tokensIn);
        assertEquals(33, r.value.tokensOut);
    }

    @Test public void invalidJsonIsParseError() {
        Result<ChatReply> r = GeminiParser.parseReply("not json{");
        assertFalse(r.ok);
        assertTrue(r.error.startsWith("PARSE"));
    }

    @Test public void blockedResponseMentionsReason() {
        String body = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertFalse(r.ok);
        assertTrue(r.error.contains("blocked"));
        assertTrue(r.error.contains("SAFETY"));
    }

    @Test public void emptyCandidatesIsError() {
        Result<ChatReply> r = GeminiParser.parseReply("{\"candidates\":[]}");
        assertFalse(r.ok);
        assertTrue(r.error.contains("no reply candidates"));
    }

    @Test public void errorFromPrefersRetryAfterHeader() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Retry-After", "9");
        HttpResponse resp = new HttpResponse(429,
            "{\"error\":{\"code\":429,\"message\":\"slow down\"}}", headers);
        ApiError e = GeminiParser.errorFrom(resp);
        assertEquals(ApiError.Type.QUOTA, e.type);
        assertEquals(9, e.retryAfterSeconds);
        assertTrue(e.message.contains("slow down"));
    }

    @Test public void errorFromFallsBackToBodyRetryDelay() {
        HttpResponse resp = new HttpResponse(429,
            "{\"error\":{\"code\":429,\"details\":[{\"retryDelay\":\"17s\"}]}}",
            new HashMap<String, String>());
        ApiError e = GeminiParser.errorFrom(resp);
        assertEquals(17, e.retryAfterSeconds);
    }

    @Test public void authErrorCarriesProviderDetail() {
        HttpResponse resp = new HttpResponse(403,
            "{\"error\":{\"message\":\"API key not valid\"}}", new HashMap<String, String>());
        ApiError e = GeminiParser.errorFrom(resp);
        assertEquals(ApiError.Type.AUTH, e.type);
        assertTrue(e.message.contains("API key not valid"));
        assertFalse(e.retryable());
    }

    /* -------------- P-audit-deep: cut-off candidates are never saved as drafts ------ */

    @Test public void allCandidatesCutOffAtMaxTokensIsTruncationError() {
        String body = "{\"candidates\":["
            + "{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"MAX_TOKENS\"},"
            + "{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"half a sen\"}]},\"finishReason\":\"MAX_TOKENS\"}"
            + "],\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":220}}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertFalse(r.ok);
        assertTrue("got: " + r.error, r.error.startsWith("TRUNCATED"));
        assertTrue(r.error.contains("output-token limit"));
        assertTrue(r.error.contains("MAX_TOKENS"));
    }

    @Test public void finishedCandidatesSurviveWhenOthersAreCutOff() {
        String body = "{\"candidates\":["
            + "{\"content\":{\"parts\":[{\"text\":\"complete reply\"}]},\"finishReason\":\"STOP\"},"
            + "{\"content\":{\"parts\":[{\"text\":\"cut off mid\"}]},\"finishReason\":\"MAX_TOKENS\"}"
            + "]}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertTrue(r.ok);
        assertEquals(1, r.value.variants.size());
        assertEquals("complete reply", r.value.variants.get(0));
    }

    @Test public void thinkingModelBurningBudgetGetsHonestDiagnosis() {
        // thinking models can consume maxOutputTokens on thought parts only → empty text
        String body = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\","
            + "\"content\":{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"reasoning…\"}]}}]}";
        Result<ChatReply> r = GeminiParser.parseReply(body);
        assertFalse(r.ok);
        assertTrue(r.error.startsWith("TRUNCATED"));
    }

    @Test public void longProviderMessageIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append('e');
        String detail = GeminiParser.extractProviderMessage(
            "{\"error\":{\"message\":\"" + sb + "\"}}");
        assertTrue(detail.length() <= 141);
        assertTrue(detail.endsWith("…"));
    }
}
