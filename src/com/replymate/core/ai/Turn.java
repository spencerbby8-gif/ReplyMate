package com.replymate.core.ai;

/** One conversational turn for a chat-style provider request. */
public final class Turn {
    public enum Role { USER, MODEL }

    public final Role role;
    public final String text;

    public Turn(Role role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
    }

    public static Turn user(String text) { return new Turn(Role.USER, text); }
    public static Turn model(String text) { return new Turn(Role.MODEL, text); }
}
