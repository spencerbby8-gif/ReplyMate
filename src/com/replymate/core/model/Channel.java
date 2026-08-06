package com.replymate.core.model;

/** Where a message/channels identity comes from. Wire values match DB CHECK constraints. */
public enum Channel {
    WHATSAPP("whatsapp"),
    TELEGRAM("telegram"),
    MANUAL("manual");

    public final String wire;
    Channel(String wire) { this.wire = wire; }

    public static Channel fromWire(String w) {
        for (Channel c : values()) if (c.wire.equals(w)) return c;
        throw new IllegalArgumentException("unknown channel: " + w);
    }
}
