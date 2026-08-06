package com.replymate.core.model;

/** Style profile scope: global (owner-wide) or per-contact override. */
public enum Scope {
    GLOBAL("global"),
    CONTACT("contact");

    public final String wire;
    Scope(String wire) { this.wire = wire; }

    public static Scope fromWire(String w) {
        for (Scope s : values()) if (s.wire.equals(w)) return s;
        throw new IllegalArgumentException("unknown scope: " + w);
    }
}
