package com.replymate.provider;

import com.replymate.provider.http.ApiError;
import com.replymate.provider.http.RetryPolicy;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class RetryPolicyTest {

    @Test public void backoffScheduleWithinJitter() {
        RetryPolicy rp = new RetryPolicy(new Random(42));
        long[] centers = {500, 1000, 2000};
        for (int attempt = 0; attempt < centers.length; attempt++) {
            for (int i = 0; i < 200; i++) {
                long s = rp.sleepMillis(attempt);
                double lo = centers[attempt] * 0.8, hi = centers[attempt] * 1.2;
                assertTrue("sleep " + s + " outside [" + lo + "," + hi + "]", s >= lo && s <= hi);
            }
        }
    }

    @Test public void retryAfterTakesPrecedenceAndCaps() {
        RetryPolicy rp = new RetryPolicy(new Random(1));
        assertEquals(3000, rp.sleepMillis(0, 3));        // honored
        assertEquals(60_000, rp.sleepMillis(0, 600));    // capped at 60s
        assertTrue(rp.sleepMillis(0, -1) <= 600);        // unknown → normal backoff
    }

    @Test public void onlyRetryableErrorsRetry() {
        RetryPolicy rp = new RetryPolicy(new Random(1));
        assertTrue(rp.shouldRetry(new ApiError(ApiError.Type.QUOTA, "", -1), 1));
        assertTrue(rp.shouldRetry(new ApiError(ApiError.Type.SERVER, "", -1), 1));
        assertTrue(rp.shouldRetry(new ApiError(ApiError.Type.NETWORK, "", -1), 1));
        assertFalse(rp.shouldRetry(new ApiError(ApiError.Type.AUTH, "", -1), 1));
        assertFalse(rp.shouldRetry(new ApiError(ApiError.Type.PARSE, "", -1), 1));
        assertFalse(rp.shouldRetry(null, 1));
    }

    @Test public void attemptsCappedAtMax() {
        RetryPolicy rp = new RetryPolicy(new Random(1));
        ApiError quota = new ApiError(ApiError.Type.QUOTA, "", -1);
        assertTrue(rp.shouldRetry(quota, RetryPolicy.MAX_ATTEMPTS - 1));
        assertFalse(rp.shouldRetry(quota, RetryPolicy.MAX_ATTEMPTS));
    }
}
