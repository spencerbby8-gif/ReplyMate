package com.replymate.core.model;

/** Categories for durable per-contact memory facts. */
public enum FactCategory {
    PERSON("person"),
    PREFERENCE("preference"),
    EVENT("event"),
    RELATION("relation"),
    COMM_STYLE("comm_style"),
    BOUNDARY("boundary");

    public final String wire;
    FactCategory(String wire) { this.wire = wire; }

    public static FactCategory fromWire(String w) {
        for (FactCategory c : values()) if (c.wire.equals(w)) return c;
        throw new IllegalArgumentException("unknown fact category: " + w);
    }
}
