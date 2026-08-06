package com.replymate.core.listener;

/** Debounce math for our outgoing ping notifications (messages are STORED immediately;
 *  only the user-visible alert is debounced). Rapid bursts collapse into one ping per
 *  contact, WINDOW_MS after the latest event. */
public final class BatchWindow {

    public static final long WINDOW_MS = 5000;

    private BatchWindow() { }

    /** When a ping for this burst should fire. */
    public static long dueAt(long latestEventTs) {
        return latestEventTs + WINDOW_MS;
    }

    /** Millis to wait before posting (never negative). */
    public static long delayFrom(long now, long dueAt) {
        return Math.max(0, dueAt - now);
    }
}
