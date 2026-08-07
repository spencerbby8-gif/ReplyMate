package com.replymate.core.model;

/** Lean, secret-free reference to the configured AI provider, safe to embed in
 *  prompt-audit snapshots (P-context-honesty). Never carries keys or key refs. */
public final class ProviderRef {
    public final String wire;       // "gemini", "deepseek", …
    public final String label;      // "Google Gemini", …
    public final String baseUrl;
    public final String modelName;

    public ProviderRef(String wire, String label, String baseUrl, String modelName) {
        this.wire = wire == null ? "" : wire;
        this.label = label == null ? "" : label;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.modelName = modelName == null ? "" : modelName;
    }

    public static ProviderRef from(ProviderDef d) {
        if (d == null) return null;
        return new ProviderRef(d.type == null ? "" : d.type.wire,
            d.type == null ? "" : d.type.label, d.baseUrl, d.modelName);
    }
}
