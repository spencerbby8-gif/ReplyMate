package com.replymate.core.util;

/** Time source port — faked in unit tests. All timestamps are UTC epoch millis. */
public interface Clock {
    long now();
}
