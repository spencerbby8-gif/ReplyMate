package com.replymate.core.prompt;

/** P-intelligence-14: INTENTIONAL generation kinds. ReplyMate drafts more than
 *  literal replies — the owner can intentionally ask for a follow-up bump, a
 *  clarifying question, a topic continuation or a fresh opener. Every kind runs
 *  the SAME pipeline as a reply (voice, memory, contact settings, learning,
 *  Search, reasoning) and lands as a draft that still needs explicit approval —
 *  nothing ever auto-sends. */
public enum ComposeKind {
    /** The classic: answer their latest message/burst. */
    REPLY("reply"),
    /** Their silence follows OUR last outgoing — re-surface it without nagging. */
    FOLLOW_UP("compose:follow_up"),
    /** Their latest is ambiguous — ask a natural, specific clarifying question. */
    CLARIFY("compose:clarify"),
    /** The exchange paused mid-topic — continue it naturally. */
    CONTINUE("compose:continue"),
    /** No topic required — open a fresh conversation in-voice. */
    OPENER("compose:opener");

    public final String wire;
    ComposeKind(String wire) { this.wire = wire; }

    public static ComposeKind fromWire(String wire) {
        for (ComposeKind k : values()) if (k.wire.equals(wire)) return k;
        return REPLY;
    }
}
