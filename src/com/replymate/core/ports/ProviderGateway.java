package com.replymate.core.ports;

/** Read-only access to the currently configured AI provider (built by the app layer
 *  from provider_def + the SecretVault). Returns null when nothing is configured. */
public interface ProviderGateway {
    AiProvider active();        // null when no working provider+key
    String activeModel();       // null when not configured
    /** Secret-free description of the active provider for prompt-audit snapshots
     *  (null when nothing is configured). */
    com.replymate.core.model.ProviderRef activeMeta();
}
