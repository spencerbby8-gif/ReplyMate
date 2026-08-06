package com.replymate.core.ports;

/** Non-secret key-value settings (app_kv). Secrets must NEVER be stored here — use SecretVault. */
public interface KvStore {
    String get(String key, String defValue);
    void put(String key, String value);
    void delete(String key);
    boolean contains(String key);
}
