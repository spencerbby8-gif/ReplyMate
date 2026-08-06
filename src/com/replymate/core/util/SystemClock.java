package com.replymate.core.util;

/** Real clock implementation. */
public final class SystemClock implements Clock {
    @Override public long now() {
        return System.currentTimeMillis();
    }
}
