package com.replymate.core.ports;

/** Keystore-backed secret store (API keys, lock PIN hash). Secrets exist only in memory
 *  momentarily and are never written to the database or logs (register them with ScrubLogger). */
public interface SecretVault {
    void putSecret(String alias, String value);
    String getSecret(String alias);      // null if absent
    boolean hasSecret(String alias);
    void deleteSecret(String alias);
}
