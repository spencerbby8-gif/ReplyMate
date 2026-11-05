package com.replymate.core.ports;

/** Read-only access to the currently configured AI provider (built by the app layer
 *  from provider_def + the SecretVault). Returns null when nothing is configured. */
public interface ProviderGateway {
    AiProvider active();        // null when no working provider+key
    String activeModel();       // null when not configured
    /** Secret-free description of the active provider for prompt-audit snapshots
     *  (null when nothing is configured). */
    com.replymate.core.model.ProviderRef activeMeta();

    /** P21-production §7: the OTHER providers eligible for fallback when the
     *  active one definitively fails a call — preference order, active excluded,
     *  only rows that carry BOTH a model and any required credential. Empty means
     *  there is honestly no fallback and a failure stands as final. The caller
     *  walks this list in order and records every hop for audit. */
    java.util.List<com.replymate.core.model.ProviderDef> fallbackDefs();

    /** Build the adapter for one def (credential resolved by the app layer;
     *  null when the def is unusable right now). */
    AiProvider buildFor(com.replymate.core.model.ProviderDef def);
}
