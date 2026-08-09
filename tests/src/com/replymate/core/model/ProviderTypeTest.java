package com.replymate.core.model;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider catalog sanity (P-polish true provider abstraction): all 10 kinds wired,
 *  unique wire ids, official-doc default base URLs, correct auth needs, graceful
 *  unknown-wire fallback — and NOT A SINGLE hardcoded model name anywhere. */
public class ProviderTypeTest {

    @Test public void allOwnerListedProvidersExist() {
        Set<String> wires = new HashSet<String>();
        for (ProviderType t : ProviderType.values()) {
            assertTrue("duplicate wire: " + t.wire, wires.add(t.wire));
        }
        for (String w : new String[] {"gemini", "openai", "openrouter", "anthropic",
                "deepseek", "grok", "kimi", "mistral", "ollama", "openai_compat"}) {
            assertTrue("missing provider kind: " + w, wires.contains(w));
        }
        assertEquals(10, ProviderType.values().length);
    }

    @Test public void dialectsMatchOfficialApiSurfaces() {
        assertEquals(ProviderType.ApiStyle.GEMINI, ProviderType.GEMINI.apiStyle);
        assertEquals(ProviderType.ApiStyle.ANTHROPIC, ProviderType.ANTHROPIC.apiStyle);
        for (ProviderType t : new ProviderType[] {ProviderType.OPENAI,
                ProviderType.OPENROUTER, ProviderType.DEEPSEEK, ProviderType.GROK,
                ProviderType.KIMI, ProviderType.MISTRAL, ProviderType.OLLAMA,
                ProviderType.OPENAI_COMPAT}) {
            assertEquals(t.wire, ProviderType.ApiStyle.OPENAI, t.apiStyle);
        }
    }

    @Test public void defaultBaseUrlsAreOfficialAndPresent() {
        assertEquals("https://generativelanguage.googleapis.com", ProviderType.GEMINI.defaultBaseUrl);
        assertEquals("https://api.openai.com/v1", ProviderType.OPENAI.defaultBaseUrl);
        assertEquals("https://openrouter.ai/api/v1", ProviderType.OPENROUTER.defaultBaseUrl);
        assertEquals("https://api.anthropic.com", ProviderType.ANTHROPIC.defaultBaseUrl);
        assertEquals("https://api.deepseek.com", ProviderType.DEEPSEEK.defaultBaseUrl);
        assertEquals("https://api.x.ai/v1", ProviderType.GROK.defaultBaseUrl);
        assertEquals("https://api.moonshot.ai/v1", ProviderType.KIMI.defaultBaseUrl);
        assertEquals("https://api.mistral.ai/v1", ProviderType.MISTRAL.defaultBaseUrl);
        assertEquals("http://localhost:11434/v1", ProviderType.OLLAMA.defaultBaseUrl);
        assertEquals("", ProviderType.OPENAI_COMPAT.defaultBaseUrl); // user must provide
    }

    @Test public void authNeedsMatchTheProviders() {
        assertFalse("ollama is keyless", ProviderType.OLLAMA.needsKey);
        for (ProviderType t : ProviderType.values()) {
            if (t != ProviderType.OLLAMA) assertTrue(t.wire, t.needsKey);
        }
    }

    @Test public void legacyAndUnknownWiresDegradeGracefully() {
        assertEquals(ProviderType.GEMINI, ProviderType.fromWire("gemini"));
        assertEquals("unknown stored types map to the generic adapter, never crash",
            ProviderType.OPENAI_COMPAT, ProviderType.fromWire("acme-future-ai"));
    }

    @Test public void everyProviderHasALabelAndNoneShipsAModel() {
        for (ProviderType t : ProviderType.values()) {
            assertFalse(t.wire, t.label.isEmpty());
            assertNotNull(t.apiStyle);
        }
        // model names live in the DB from discovery/user input — ProviderDef default:
        assertEquals("", new ProviderDef().modelName);
        assertEquals("", new ProviderDef().baseUrl);
    }

    /* -------------------- P-editor-url: base URL policy for the provider editor -------------------- */

    @Test public void baseUrlStaysEditableForEveryProvider() {
        // P-intelligence-6 directive 4: every provider page keeps its Base URL
        // fully editable — the selector SUGGESTS the official endpoint, it can
        // never lock the field (proxies, gateways and self-hosted endpoints are
        // first-class use-cases for any provider family).
        for (ProviderType t : ProviderType.values()) {
            assertTrue(t.wire + " must keep its base URL editable",
                t.baseUrlEditable());
        }
    }

    @Test public void switchingProvidersAlwaysYieldsTheOfficialBaseUrl() {
        // leaving a previous provider's URL in the field is the exact bug this fixes
        String lastProvidersUrl = "https://api.openai.com/v1";
        assertEquals("https://api.anthropic.com",
            ProviderType.resolveBaseUrlForUi(ProviderType.ANTHROPIC, lastProvidersUrl));
        assertEquals("https://generativelanguage.googleapis.com",
            ProviderType.resolveBaseUrlForUi(ProviderType.GEMINI, lastProvidersUrl));
        assertEquals("https://api.x.ai/v1",
            ProviderType.resolveBaseUrlForUi(ProviderType.GROK, "   "));
        // custom keeps the user's own endpoint, falling back to default only when empty
        assertEquals(lastProvidersUrl,
            ProviderType.resolveBaseUrlForUi(ProviderType.OPENAI_COMPAT, lastProvidersUrl));
    }
}
