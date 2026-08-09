package com.replymate.core.caps;

import com.replymate.core.model.ProviderType;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 8: the docs-verified capability map is pinned per
 *  provider × model — who gets the NATIVE search tool, who falls back to the
 *  encyclopedia, who has an adjustable reasoning control, and how family
 *  mismatches are detected (Grok models on an Anthropic endpoint and friends). */
public final class ModelCapsTest {

    /* ------------------------------------------------------- search transport */

    @Test public void nativeSearchProvidersAreExactlyTheDocumentedOnes() {
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.GEMINI, "gemini-2.5-flash").search);
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.OPENAI, "gpt-5").search);
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.OPENROUTER, "anything/mistral").search);
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.ANTHROPIC, "claude-haiku-4.5").search);
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.GROK, "grok-4").search);
        assertEquals(ModelCaps.SearchTransport.NATIVE,
            ModelCaps.of(ProviderType.KIMI, "kimi-k2.5").search);
    }

    @Test public void fallbackSearchProvidersAreExactlyTheOnesWithoutAnOfficialTool() {
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.DEEPSEEK, "deepseek-chat").search);
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.MISTRAL, "mistral-large-latest").search);
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.OLLAMA, "llama3.2").search);
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.OPENAI_COMPAT, "my-model").search);
        assertEquals("unknown wire never invents a tool",
            ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(null, "whatever").search);
    }

    @Test public void gemmaAndToolLessGeminiModelsGetNoNativeSearch() {
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.GEMINI, "gemma-3n-e4b").search);
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.GEMINI, "text-embedding-004").search);
        assertEquals(ModelCaps.SearchTransport.FALLBACK,
            ModelCaps.of(ProviderType.GEMINI, "imagen-3.0").search);
    }

    /* ------------------------------------------------------------- reasoning */

    @Test public void adjustableReasoningIsExactlyWhereTheDocsShowAControl() {
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.GEMINI, "gemini-2.5-pro").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.GEMINI, "gemini-3-flash").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.OPENAI, "o3").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.ANTHROPIC, "claude-sonnet-4.5").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.DEEPSEEK, "deepseek-v4").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.KIMI, "kimi-k2.5").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.MISTRAL, "mistral-small-latest").reasoning);
        assertEquals(ModelCaps.Reasoning.ADJUSTABLE,
            ModelCaps.of(ProviderType.MISTRAL, "ministral-8b").reasoning);
    }

    @Test public void alwaysOnReasoningIsOnlyWhereTheModelReasonsByDesign() {
        assertEquals(ModelCaps.Reasoning.ALWAYS_ON,
            ModelCaps.of(ProviderType.GROK, "grok-4").reasoning);
        assertEquals(ModelCaps.Reasoning.ALWAYS_ON,
            ModelCaps.of(ProviderType.KIMI, "kimi-k3").reasoning);
        assertEquals(ModelCaps.Reasoning.ALWAYS_ON,
            ModelCaps.of(ProviderType.MISTRAL, "magistral-medium").reasoning);
    }

    @Test public void noReasoningControlIsSentWhereNoneIsDocumented() {
        assertEquals(ModelCaps.Reasoning.UNSUPPORTED,
            ModelCaps.of(ProviderType.OPENAI, "gpt-4o-mini").reasoning);
        assertEquals(ModelCaps.Reasoning.UNSUPPORTED,
            ModelCaps.of(ProviderType.MISTRAL, "mistral-large-latest").reasoning);
        assertEquals(ModelCaps.Reasoning.UNSUPPORTED,
            ModelCaps.of(ProviderType.OLLAMA, "qwen3").reasoning);
        assertEquals(ModelCaps.Reasoning.UNSUPPORTED,
            ModelCaps.of(ProviderType.OPENAI_COMPAT, "my-model").reasoning);
        assertEquals(ModelCaps.Reasoning.UNSUPPORTED,
            ModelCaps.of(ProviderType.GEMINI, "gemma-3n-e4b").reasoning);
    }

    /* --------------------------------------------------------- family binding */

    @Test public void familyMismatchesAreDetected() {
        assertFalse(ModelCaps.familyMatches(ProviderType.ANTHROPIC, "grok-4"));
        assertFalse(ModelCaps.familyMatches(ProviderType.GEMINI, "claude-haiku-4.5"));
        assertFalse(ModelCaps.familyMatches(ProviderType.OPENAI, "deepseek-chat"));
        assertFalse(ModelCaps.familyMatches(ProviderType.KIMI, "mistral-large"));
    }

    @Test public void familyMatchesAcceptTheirOwnAndRoutersAcceptAnything() {
        assertTrue(ModelCaps.familyMatches(ProviderType.ANTHROPIC, "claude-haiku-4.5"));
        assertTrue(ModelCaps.familyMatches(ProviderType.OPENAI, "o3"));
        assertTrue(ModelCaps.familyMatches(ProviderType.KIMI, "moonshot-v1-8k"));
        assertTrue(ModelCaps.familyMatches(ProviderType.OPENROUTER, "claude-haiku-4.5"));
        assertTrue(ModelCaps.familyMatches(ProviderType.OLLAMA, "any-local-model"));
        assertTrue(ModelCaps.familyMatches(ProviderType.OPENAI_COMPAT, "anything"));
        assertTrue("an empty model field is never flagged", 
            ModelCaps.familyMatches(ProviderType.GEMINI, ""));
    }

    /* ------------------------------------------------------------ audit lines */

    @Test public void summaryLineIsHonestAndComplete() {
        String s = ModelCaps.of(ProviderType.GEMINI, "gemini-2.5-flash").summary();
        assertTrue(s.contains("live web search: native"));
        assertTrue(s.contains("reasoning: adjustable"));
        String d = ModelCaps.of(ProviderType.DEEPSEEK, "deepseek-chat").summary();
        assertTrue(d.contains("encyclopedia fallback"));
        assertTrue(d.contains("reasoning: adjustable"));
        String o = ModelCaps.of(ProviderType.OLLAMA, "llama3.2").summary();
        assertTrue(o.contains("encyclopedia fallback"));
        assertTrue(o.contains("reasoning: not documented"));
    }
}
