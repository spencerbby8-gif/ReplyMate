package com.replymate.core.ports;

import com.replymate.core.model.ProviderDef;

/** Non-secret provider configuration persistence. API keys are NEVER here (SecretVault). */
public interface ProviderStore {
    /** Save config (one row per type) and make it the single active provider. Returns row id. */
    long upsertActive(ProviderDef def);
    ProviderDef active();      // null when nothing configured
}
