package com.replymate.provider;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.json.Json;
import com.replymate.core.util.Result;
import com.replymate.provider.gemini.GeminiPayloads;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.Diagnostics;
import com.replymate.provider.http.HttpResponse;
import com.replymate.provider.http.RetryPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Live-verified 2026-08-07 on a brand-new free-tier key: the Gemini 3 generation
 *  HTTP-400s candidateCount ("Multiple candidates is not enabled for this model").
 *  ReplyMate must retry once WITHOUT the field and deliver the single variant,
 *  instead of failing the generation. Auth/quota/model errors must NOT fallback. */
public class GeminiCandidatesFallbackTest {

    private static ChatRequest req() {
        List<Turn> turns = new ArrayList<Turn>();
        turns.add(Turn.user("Amara: you around?"));
        return new ChatRequest("sys", turns, Turn.user("reply"), GenerationOpts.of(3, 0.8, 220));
    }

    /** 400s any body that mentions candidateCount; 200s a single-candidate answer otherwise. */
    private static final class CandidatesRejectingHttp extends com.replymate.provider.http.HttpClient {
        int posts = 0;
        String lastBody;
        @Override public HttpResponse post(String url, Map<String, String> headers, String body) {
            posts++;
            lastBody = body;
            if (body.contains("candidateCount")) {
                return new HttpResponse(400,
                    "{\"error\":{\"code\":400,\"message\":\"Multiple candidates is not enabled "
                    + "for this model\",\"status\":\"INVALID_ARGUMENT\"}}", null);
            }
            return new HttpResponse(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"one reply only\"}],"
                + "\"role\":\"model\"}}],\"usageMetadata\":{\"promptTokenCount\":5,"
                + "\"candidatesTokenCount\":4}}", null);
        }
    }

    @Test public void fallsBackToSingleCandidateWhenServerRejectsCandidateCount() {
        CandidatesRejectingHttp http = new CandidatesRejectingHttp();
        GeminiProvider p = new GeminiProvider("https://generativelanguage.googleapis.com",
            "m", "k", http, new RetryPolicy(), com.replymate.fakes.Fakes.NOOP_LOG);
        Result<ChatReply> r = p.generate(req());
        assertTrue("expected success, got: " + r.error, r.ok);
        assertEquals(1, r.value.variants.size());
        assertEquals("one reply only", r.value.variants.get(0));
        assertEquals("exactly 2 wire calls (with, then without candidateCount)", 2, http.posts);
        assertFalse(http.lastBody.contains("candidateCount"));
    }

    @Test public void authErrorNeverTriggersCandidateFallback() {
        com.replymate.provider.http.HttpClient http = new com.replymate.provider.http.HttpClient() {
            int posts = 0;
            @Override public HttpResponse post(String url, Map<String, String> headers, String body) {
                posts++;
                return new HttpResponse(400, DiagnosticsTest.GEMINI_400_BAD_KEY, null);
            }
        };
        GeminiProvider p = new GeminiProvider("https://generativelanguage.googleapis.com",
            "m", "k", http, new RetryPolicy(), com.replymate.fakes.Fakes.NOOP_LOG);
        Result<ChatReply> r = p.generate(req());
        assertFalse(r.ok);
        assertTrue(r.error.contains("AUTH"));
    }

    @Test public void bodyBuilderOmitsCandidateCountWhenAsked() {
        String with = GeminiPayloads.generateBody(req(), true);
        assertTrue(with.contains("\"candidateCount\":3"));
        String without = GeminiPayloads.generateBody(req(), false);
        assertFalse(without.contains("candidateCount"));
        assertEquals(0.8, Json.parseObj(without).obj("generationConfig").dbl("temperature"), 0.0001);
        List<Turn> t = new ArrayList<Turn>();
        t.add(Turn.user("x"));
        ChatRequest one = new ChatRequest("s", t, Turn.user("y"), GenerationOpts.of(1, 0.5, 100));
        assertFalse("candidateCount is pointless noise at 1 — omit it even when allowed",
            GeminiPayloads.generateBody(one, true).contains("candidateCount"));
    }

    @Test public void matcherOnlyFiresOnGoogleSayingCandidates() {
        Diagnostics cand = Diagnostics.build("gemini", "POST", "https://x", "m",
            new HttpResponse(400,
                "{\"error\":{\"code\":400,\"message\":\"Multiple candidates is not enabled for this model\"}}", null),
            "Multiple candidates is not enabled for this model");
        assertTrue(GeminiProvider.rejectedMultipleCandidates(cand));

        Diagnostics quota = Diagnostics.build("gemini", "POST", "https://x", "m",
            new HttpResponse(429, DiagnosticsTest.GEMINI_429_LIMIT_ZERO, null), "quota");
        assertFalse(GeminiProvider.rejectedMultipleCandidates(quota));

        Diagnostics auth = Diagnostics.build("gemini", "POST", "https://x", "m",
            new HttpResponse(400, DiagnosticsTest.GEMINI_400_BAD_KEY, null), "bad key");
        assertFalse(GeminiProvider.rejectedMultipleCandidates(auth));

        assertFalse(GeminiProvider.rejectedMultipleCandidates(null));
    }
}
