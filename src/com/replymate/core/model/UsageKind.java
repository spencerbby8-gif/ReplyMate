package com.replymate.core.model;

/** What an AI usage event was for. */
public enum UsageKind {
    REPLY("reply"),
    SUMMARY("summary"),
    EXTRACT("extract"),
    STYLE("style");

    public final String wire;
    UsageKind(String wire) { this.wire = wire; }

    public static UsageKind fromWire(String w) {
        for (UsageKind k : values()) if (k.wire.equals(w)) return k;
        throw new IllegalArgumentException("unknown usage kind: " + w);
    }
}
