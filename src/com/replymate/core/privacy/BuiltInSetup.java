package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.core.ports.ProviderStore;
import com.replymate.core.ports.SecretVault;

/** P-intelligence-3: solo-builder mode. When the shipped build bundles the owner's
 *  built-in key (injected at release-package time from build secrets — never in the
 *  repo), the FIRST launch seeds a ready-made provider row so the app works out of
 *  the box. Rules:
 *    - no bundled key ⇒ no-op (pure BYOK build);
 *    - ANY provider already configured ⇒ no-op (never override the user's setup);
 *    - seeded once only (a missing vault secret is the seed marker);
 *    - the model is deliberately left EMPTY and chosen later via live discovery —
 *      model names are never hardcoded (owner mandate).
 *  Detection note: a row seeded here uses ProviderPrivacy.BUILT_IN_KEY_REF, which
 *  pins it to FREE mode forever (see ProviderPrivacy). */
public final class BuiltInSetup {

    private BuiltInSetup() { }

    /** @return true when a built-in provider was seeded by this call. */
    public static boolean maybeSeed(ProviderStore providers, SecretVault vault,
                                    String providerWire, String bundledKey) {
        if (providers == null || vault == null) return false;
        if (providerWire == null || providerWire.trim().isEmpty()) return false;
        if (bundledKey == null || bundledKey.trim().isEmpty()) return false;
        if (!providers.all().isEmpty()) return false;                 // user's setup wins
        if (vault.hasSecret(ProviderPrivacy.BUILT_IN_KEY_REF)) return false;  // seeded once

        ProviderDef d = new ProviderDef();
        d.type = ProviderType.fromWire(providerWire.trim());
        d.baseUrl = d.type.defaultBaseUrl;
        if (d.baseUrl == null || d.baseUrl.isEmpty()) return false;   // needs manual URL
        d.label = d.type.label + " (built-in ReplyMate key)";
        d.keyRef = ProviderPrivacy.BUILT_IN_KEY_REF;
        d.modelName = "";   // picked live (Settings → AI providers → Discover & test)
        vault.putSecret(ProviderPrivacy.BUILT_IN_KEY_REF, bundledKey.trim());
        providers.upsertActive(d);
        return true;
    }
}
