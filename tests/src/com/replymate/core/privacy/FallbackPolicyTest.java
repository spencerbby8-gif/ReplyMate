package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-review §7-audit: the consent + privacy rules for cross-provider fallback,
 *  pinned in core so no app-layer rewrite can silently reopen them. */
public class FallbackPolicyTest {

    private static ProviderDef def(long id, ProviderType type, String model, String keyRef) {
        ProviderDef d = new ProviderDef();
        d.id = id; d.type = type; d.label = type.label; d.modelName = model; d.keyRef = keyRef;
        return d;
    }

    @Test public void consentDefaultsOnAndExplicitZeroDisables() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertTrue("default ON (owner defect: 'no fallback')", FallbackPolicy.enabled(kv));
        assertTrue("missing kv = ON", FallbackPolicy.enabled(null));
        kv.put(FallbackPolicy.KV_ENABLED, "0");
        assertFalse("explicit off", FallbackPolicy.enabled(kv));
        kv.put(FallbackPolicy.KV_ENABLED, "anything-else");
        assertTrue("only a real \"0\" disables (fail-informed)", FallbackPolicy.enabled(kv));
    }

    @Test public void candidatesExcludeActiveAndModellessAndKeepOrder() {
        ProviderDef a = def(1, ProviderType.GEMINI, "g-2.5", "provider.gemini.key");
        ProviderDef b = def(2, ProviderType.DEEPSEEK, "ds-chat", "provider.deepseek.key");
        ProviderDef c = def(3, ProviderType.KIMI, "", "provider.kimi.key");          // no model
        List<ProviderDef> out = FallbackPolicy.candidates(
            java.util.Arrays.asList(a, b, c), a);
        assertEquals(1, out.size());
        assertSame(b, out.get(0));
        assertTrue("null list yields empty, never throws",
            FallbackPolicy.candidates(null, a).isEmpty());
    }

    @Test public void builtInSharedKeyIsNeverAFallbackForAnOwnerKeyedActive() {
        ProviderDef active = def(1, ProviderType.GEMINI, "g-2.5", "provider.gemini.key");
        ProviderDef builtIn = def(2, ProviderType.GEMINI, "g-2.5-flash",
            ProviderPrivacy.BUILT_IN_KEY_REF);
        ProviderDef ownKey = def(3, ProviderType.GROK, "grok-3", "provider.grok.key");
        List<ProviderDef> out = FallbackPolicy.candidates(
            java.util.Arrays.asList(builtIn, ownKey), active);
        assertEquals("the shared free key must never quietly receive an owner thread",
            1, out.size());
        assertSame(ownKey, out.get(0));
    }

    @Test public void builtInActiveKeepsTheBuiltInFallbackLegal() {
        // when the ACTIVE row is itself the built-in one the owner already
        // accepted FREE terms — no further privacy downgrade is possible.
        ProviderDef activeBuiltIn = def(1, ProviderType.GEMINI, "g-2.5",
            ProviderPrivacy.BUILT_IN_KEY_REF);
        ProviderDef otherBuiltIn = def(2, ProviderType.GEMINI, "g-2.5-flash",
            ProviderPrivacy.BUILT_IN_KEY_REF);
        List<ProviderDef> out = FallbackPolicy.candidates(
            java.util.Arrays.asList(otherBuiltIn, activeBuiltIn), activeBuiltIn);
        assertEquals("the guard lifts (active excluded by id, not by key)",
            1, out.size());
        assertSame(otherBuiltIn, out.get(0));
    }

    @Test public void activeNullStillFiltersModellessRows() {
        ProviderDef noModel = def(7, ProviderType.MISTRAL, "", "provider.mistral.key");
        ProviderDef ok = def(8, ProviderType.MISTRAL, "mistral-large", "provider.mistral.key");
        List<ProviderDef> out = FallbackPolicy.candidates(
            java.util.Arrays.asList(noModel, ok), null);
        assertEquals(1, out.size());
        assertSame(ok, out.get(0));
    }
}
