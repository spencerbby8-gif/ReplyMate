package com.replymate.core.model;

import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 4: provider-page Base URL binding — picking a
 *  provider SUGGESTS its official endpoint (auto-fill), but a hand-typed URL is
 *  sacred and never silently replaced. Switching providers re-suggests; typing
 *  is preserved. Every field stays editable for every provider. */
public final class ProviderBaseUrlTest {

    @Test public void everyProviderFieldStaysEditable() {
        for (ProviderType t : ProviderType.values()) {
            assertTrue(t + " must keep its Base URL editable", t.baseUrlEditable());
        }
    }

    @Test public void anEmptyFieldSuggestsTheOfficialEndpoint() {
        assertEquals(ProviderType.GEMINI.defaultBaseUrl,
            ProviderType.resolveBaseUrlForUi(ProviderType.GEMINI, ""));
        assertEquals(ProviderType.ANTHROPIC.defaultBaseUrl,
            ProviderType.resolveBaseUrlForUi(ProviderType.ANTHROPIC, "   "));
        assertEquals("the open-compatible type has no official endpoint — stays empty",
            "", ProviderType.resolveBaseUrlForUi(ProviderType.OPENAI_COMPAT, ""));
    }

    @Test public void switchingProvidersReSuggestsTheNewOfficialEndpoint() {
        // field still held OpenAI's default (untouched by hand) — switching to
        // Anthropic must not keep pointing the key+model at OpenAI's servers.
        assertEquals(ProviderType.ANTHROPIC.defaultBaseUrl,
            ProviderType.resolveBaseUrlForUi(
                ProviderType.ANTHROPIC, ProviderType.OPENAI.defaultBaseUrl));
        assertEquals(ProviderType.GROK.defaultBaseUrl,
            ProviderType.resolveBaseUrlForUi(
                ProviderType.GROK, ProviderType.DEEPSEEK.defaultBaseUrl));
    }

    @Test public void aHandTypedUrlIsNeverReplaced() {
        String mine = "https://my-proxy.example.com/v1";
        assertEquals(mine, ProviderType.resolveBaseUrlForUi(ProviderType.OPENAI, mine));
        assertEquals("even when switching providers afterwards",
            mine, ProviderType.resolveBaseUrlForUi(ProviderType.ANTHROPIC, mine));
        assertEquals("trailing/leading spaces are tolerated",
            mine, ProviderType.resolveBaseUrlForUi(ProviderType.MISTRAL, "  " + mine + " "));
    }

    @Test public void theMatchingOfficialEndpointIsKeptAsIs() {
        assertEquals(ProviderType.OPENAI.defaultBaseUrl,
            ProviderType.resolveBaseUrlForUi(
                ProviderType.OPENAI, ProviderType.OPENAI.defaultBaseUrl));
    }

    @Test public void defaultDetectionCoversEveryOfficialEndpointAndOnlyThose() {
        for (ProviderType t : ProviderType.values()) {
            if (t.defaultBaseUrl.isEmpty()) continue;
            assertTrue(t.defaultBaseUrl,
                ProviderType.isAnyProviderDefault(t.defaultBaseUrl));
        }
        assertFalse(ProviderType.isAnyProviderDefault("https://my-proxy.example.com"));
        assertFalse(ProviderType.isAnyProviderDefault(""));
        assertFalse(ProviderType.isAnyProviderDefault(null));
    }
}
