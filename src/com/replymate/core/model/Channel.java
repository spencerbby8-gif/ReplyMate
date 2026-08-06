package com.replymate.core.model;

/** Where a message/channels identity comes from. Wire values match DB CHECK constraints. */
public enum Channel {
    WHATSAPP("whatsapp"),
    TELEGRAM("telegram"),
    MANUAL("manual"),
    SIGNAL("signal"),
    GOOGLE_MESSAGES("gmessages"),
    MESSENGER("messenger"),
    SLACK("slack"),
    DISCORD("discord"),
    INSTAGRAM("instagram"),
    X("x"),
    TIKTOK("tiktok");

    public final String wire;
    Channel(String wire) { this.wire = wire; }

    public static Channel fromWire(String w) {
        for (Channel c : values()) if (c.wire.equals(w)) return c;
        throw new IllegalArgumentException("unknown channel: " + w);
    }
}
