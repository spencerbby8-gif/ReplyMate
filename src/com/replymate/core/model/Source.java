package com.replymate.core.model;

/** How a message row entered the database. */
public enum Source {
    LISTENER("listener"),
    MANUAL("manual"),
    IMPORT("import");

    public final String wire;
    Source(String wire) { this.wire = wire; }

    public static Source fromWire(String w) {
        for (Source s : values()) if (s.wire.equals(w)) return s;
        throw new IllegalArgumentException("unknown source: " + w);
    }
}
