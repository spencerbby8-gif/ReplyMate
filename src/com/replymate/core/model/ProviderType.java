package com.replymate.core.model;

/** AI provider kinds. Gemini is the provider of record for v1; values are extensible. */
public enum ProviderType {
    GEMINI("gemini");

    public final String wire;
    ProviderType(String wire) { this.wire = wire; }

    public static ProviderType fromWire(String w) {
        for (ProviderType t : values()) if (t.wire.equals(w)) return t;
        throw new IllegalArgumentException("unknown provider type: " + w);
    }
}
