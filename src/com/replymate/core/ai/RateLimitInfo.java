package com.replymate.core.ai;

/** Rate-limit hints parsed from provider error responses (e.g. Retry-After). */
public final class RateLimitInfo {
    public static final RateLimitInfo NONE = new RateLimitInfo(-1);

    public final long retryAfterSeconds;   // -1 = unknown

    public RateLimitInfo(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
