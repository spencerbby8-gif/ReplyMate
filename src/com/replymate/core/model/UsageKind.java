package com.replymate.core.model;

/** What an AI usage event was for. */
public enum UsageKind {
    REPLY("reply"),
    SUMMARY("summary"),
    EXTRACT("extract"),
    STYLE("style"),
    /** P-intelligence-5 (legacy): the removed TermResearch metered lookup. Kept so
     *  historical rows still decode; nothing writes it since 1.5.6. */
    RESEARCH("research"),
    /** P-intelligence-6: an automatic live-search generation (native provider
     *  search tokens, or a 0-token encyclopedia fallback) — priced honestly on the
     *  usage dashboard, separate from plain replies. */
    SEARCH("search");

    public final String wire;
    UsageKind(String wire) { this.wire = wire; }

    public static UsageKind fromWire(String w) {
        for (UsageKind k : values()) if (k.wire.equals(w)) return k;
        throw new IllegalArgumentException("unknown usage kind: " + w);
    }
}
