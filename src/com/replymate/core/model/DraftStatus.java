package com.replymate.core.model;

/** Lifecycle of a generated draft. */
public enum DraftStatus {
    GENERATED("generated"),
    EDITED("edited"),
    COPIED("copied"),
    SENT("sent");

    public final String wire;
    DraftStatus(String wire) { this.wire = wire; }

    public static DraftStatus fromWire(String w) {
        for (DraftStatus s : values()) if (s.wire.equals(w)) return s;
        throw new IllegalArgumentException("unknown draft status: " + w);
    }
}
