package com.replymate.provider.http;

import java.util.Random;

/** Exponential backoff with jitter (BLUEPRINT §5.5): sleep = 0.5s * 2^n +/- 20%, max 3 attempts,
 *  honoring Retry-After when the provider supplies one. */
public final class RetryPolicy {
    public static final int MAX_ATTEMPTS = 3;
    private static final long BASE_MS = 500;

    private final Random random;

    public RetryPolicy() { this(new Random()); }
    public RetryPolicy(Random seeded) { this.random = seeded; }

    /** Sleep duration before attempt {@code attemptIndex} (0-based, after a failure). */
    public long sleepMillis(int attemptIndex) {
        long base = BASE_MS * (1L << attemptIndex);          // 500, 1000, 2000
        double jitter = 0.2d * (random.nextDouble() * 2 - 1); // +/-20%
        return (long) (base * (1 + jitter));
    }

    /** If the provider told us how long to wait, prefer that (capped at 60s). */
    public long sleepMillis(int attemptIndex, long retryAfterSeconds) {
        if (retryAfterSeconds >= 0) return Math.min(retryAfterSeconds * 1000L, 60_000L);
        return sleepMillis(attemptIndex);
    }

    /** Should we try again after this error, given how many attempts already happened? */
    public boolean shouldRetry(ApiError error, int attemptsSoFar) {
        return error != null && error.retryable() && attemptsSoFar < MAX_ATTEMPTS;
    }
}
