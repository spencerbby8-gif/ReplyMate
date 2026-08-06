package com.replymate.core.model;

/** Tone transforms offered on draft cards (P3). The instruction is the ENTIRE
 *  contract sent to the provider — it rewrites without adding facts. */
public enum ToneTransform {
    SHORTER("shorter", "Shorter", "Make it noticeably shorter while keeping the meaning and tone."),
    LONGER("longer", "Longer", "Expand it a little with natural, friendly detail — do not invent facts."),
    FRIENDLIER("friendlier", "Friendlier", "Make it warmer and more friendly, still natural, not gushing."),
    PROFESSIONAL("professional", "Professional", "Make it polished and professional — clear, respectful, no slang."),
    CONFIDENT("confident", "Confident", "Make it sound confident and decisive, without sounding arrogant."),
    CASUAL("casual", "Casual", "Make it more casual and relaxed, like a quick chat between friends.");

    public final String wire;
    public final String label;
    public final String instruction;

    ToneTransform(String wire, String label, String instruction) {
        this.wire = wire;
        this.label = label;
        this.instruction = instruction;
    }

    public static ToneTransform fromWire(String w) {
        for (ToneTransform t : values()) if (t.wire.equals(w)) return t;
        throw new IllegalArgumentException("unknown tone: " + w);
    }
}
