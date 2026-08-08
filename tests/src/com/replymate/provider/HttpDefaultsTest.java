package com.replymate.provider;

import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.RetryPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P-background-10 (item 4 — speed on slow networks): pin the network patience
 *  contract. Background generation must not give up early on slow mobile data
 *  (read timeout generous), must never hang forever (bounded connect/read), and
 *  retries stay bounded so failures surface fast (backoff behavior itself is
 *  pinned in RetryPolicyTest). */
public final class HttpDefaultsTest {

    @Test public void connectTimeoutIsBoundedButPatient() {
        assertEquals(15_000, HttpClient.DEFAULT_CONNECT_MS);
    }

    @Test public void readTimeoutWaitsLongEnoughForSlowNetworks() {
        assertEquals(45_000, HttpClient.DEFAULT_READ_MS);
        assertTrue("read timeout must never undercut a slow link",
            HttpClient.DEFAULT_READ_MS >= HttpClient.DEFAULT_CONNECT_MS);
    }

    @Test public void retryAttemptsAreBoundedSoFailuresSurfaceFast() {
        assertEquals(3, RetryPolicy.MAX_ATTEMPTS);
    }
}
