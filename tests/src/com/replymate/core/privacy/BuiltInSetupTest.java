package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-3: solo-builder seeding is opt-in by build contents, runs at most
 *  once, NEVER overrides a user's own provider setup, never hardcodes a model, and
 *  the seeded row is pinned to FREE mode by its key ref. */
public final class BuiltInSetupTest {

    @Test public void seedsOnceWithBundledKey() {
        Fakes.ProviderStoreFake store = new Fakes.ProviderStoreFake();
        Fakes.SecretVaultFake vault = new Fakes.SecretVaultFake();
        boolean seeded = BuiltInSetup.maybeSeed(store, vault, "gemini", "real-key-123");
        assertTrue(seeded);
        ProviderDef active = store.active();
        assertNotNull(active);
        assertEquals(ProviderType.GEMINI, active.type);
        assertEquals("https://generativelanguage.googleapis.com", active.baseUrl);
        assertEquals(ProviderPrivacy.BUILT_IN_KEY_REF, active.keyRef);
        assertEquals("real-key-123", vault.getSecret(ProviderPrivacy.BUILT_IN_KEY_REF));
        assertEquals("a model must be discovered live, never hardcoded", "",
            active.modelName);
        // and the seeded row must detect as FREE mode, permanently
        assertEquals(ProviderPrivacy.Mode.FREE,
            ProviderPrivacy.modeFor(active, new Fakes.KvStoreFake()));

        // second run: provider exists now → no-op
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "gemini", "other-key"));
        assertEquals("real-key-123", vault.getSecret(ProviderPrivacy.BUILT_IN_KEY_REF));
    }

    @Test public void noBundledKeyIsAPureNoOp() {
        Fakes.ProviderStoreFake store = new Fakes.ProviderStoreFake();
        Fakes.SecretVaultFake vault = new Fakes.SecretVaultFake();
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "gemini", ""));
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "gemini", null));
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "", "key"));
        assertFalse(BuiltInSetup.maybeSeed(store, vault, null, "key"));
        assertNull(store.active());
        assertFalse(vault.hasSecret(ProviderPrivacy.BUILT_IN_KEY_REF));
    }

    @Test public void userSetupAlwaysWins() {
        Fakes.ProviderStoreFake store = new Fakes.ProviderStoreFake();
        Fakes.SecretVaultFake vault = new Fakes.SecretVaultFake();
        ProviderDef mine = new ProviderDef();
        mine.type = ProviderType.OPENAI;
        mine.baseUrl = "https://api.openai.com/v1";
        mine.keyRef = "provider.openai.key";
        mine.modelName = "chosen-live";
        store.upsertActive(mine);
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "gemini", "built-in-key"));
        assertFalse("the built-in secret must NOT be written over a user setup",
            vault.hasSecret(ProviderPrivacy.BUILT_IN_KEY_REF));
        assertEquals("my provider is still the only, still active row",
            1, store.all().size());
        assertTrue(store.active().isActive);
        assertEquals("chosen-live", store.active().modelName);
    }

    @Test public void customWireTypeWithoutBaseUrlCannotSeed() {
        // openai_compat / unknown wires need a manual base URL — honest no-op.
        Fakes.ProviderStoreFake store = new Fakes.ProviderStoreFake();
        Fakes.SecretVaultFake vault = new Fakes.SecretVaultFake();
        assertFalse(BuiltInSetup.maybeSeed(store, vault, "openai_compat", "k"));
        assertNull(store.active());
    }
}
