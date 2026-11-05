package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.KvStore;
import java.util.ArrayList;
import java.util.List;

/** P21-review (independent §7 audit): the ONE rule-set for cross-provider
 *  fallback eligibility — consent, shape and privacy in a single testable place.
 *
 *  CONSENT: "assistant.fallback.enabled" (default ON — the owner flagged
 *  "no fallback" itself as a defect, and every hop is audited). The Settings
 *  toggle says plainly that a backup provider receives the content with its
 *  OWN key, billing and privacy mode.
 *
 *  PRIVACY GUARD: the built-in shared ReplyMate key (ProviderPrivacy.FREE —
 *  prompts may improve models by its terms) NEVER receives fallback traffic
 *  while the active provider runs on the owner's own key: silently downgrading
 *  a paid/private setup to a free shared key is a privacy regression. When the
 *  ACTIVE row is itself the built-in row, there is nothing to downgrade.
 *
 *  SHAPE: active row excluded; model-less rows excluded (unusable). */
public final class FallbackPolicy {

    public static final String KV_ENABLED = "assistant.fallback.enabled";

    private FallbackPolicy() { }

    /** Default ON (owner defect evidence); explicit "0" disables. */
    public static boolean enabled(KvStore kv) {
        return kv == null || !"0".equals(kv.get(KV_ENABLED, "1"));
    }

    /** Consent + privacy/shape filter over the stored rows. Key PRESENCE needs
     *  the vault — the app layer additionally drops needsKey rows with no usable
     *  credential. Order is preserved (store order = preference order). */
    public static List<ProviderDef> candidates(List<ProviderDef> all, ProviderDef active) {
        List<ProviderDef> out = new ArrayList<ProviderDef>();
        if (all == null) return out;
        boolean activeIsBuiltIn = active != null
            && ProviderPrivacy.BUILT_IN_KEY_REF.equals(active.keyRef);
        for (ProviderDef d : all) {
            if (d == null) continue;
            if (active != null && d.id == active.id) continue;
            if (d.modelName == null || d.modelName.trim().isEmpty()) continue;
            if (ProviderPrivacy.BUILT_IN_KEY_REF.equals(d.keyRef) && !activeIsBuiltIn) {
                continue;   // never expose an owner-key thread to the shared free key
            }
            out.add(d);
        }
        return out;
    }
}
