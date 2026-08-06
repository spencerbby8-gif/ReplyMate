package com.replymate.core.model;

/** Message direction relative to the owner of the device. */
public enum Direction {
    INCOMING("in"),
    OUTGOING("out");

    public final String wire;
    Direction(String wire) { this.wire = wire; }

    public static Direction fromWire(String w) {
        for (Direction d : values()) if (d.wire.equals(w)) return d;
        throw new IllegalArgumentException("unknown direction: " + w);
    }
}
