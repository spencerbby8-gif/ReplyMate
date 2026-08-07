package com.replymate.core.ports;

import com.replymate.core.model.ProviderDef;
import java.util.List;

/** Non-secret provider configuration persistence. API keys are NEVER here (SecretVault).
 *  P-polish: multiple configured providers, one active. */
public interface ProviderStore {
    /** Save config (one row per type) and make it the single active provider. Returns row id. */
    long upsertActive(ProviderDef def);
    ProviderDef active();      // null when nothing configured
    /** Every configured provider (active flag included), newest first. */
    List<ProviderDef> all();
    /** Switch the active provider; deactivates the rest. */
    void setActive(long id);
    /** Drop a provider config (callers delete its vault key too). */
    void delete(long id);
}
