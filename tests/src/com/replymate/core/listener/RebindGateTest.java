package com.replymate.core.listener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-anr-startup regression pins for the process-local, I/O-free rebind gate.
 *  These semantics are what let lifecycle callbacks decide WITHOUT touching
 *  SQLite: same bound as RebindPolicy (one claim per 30s), fail-open on
 *  unknown/skew, seed-from-persisted-stamp never lowers. */
public class RebindGateTest {

    private static final long MIN = RebindPolicy.MIN_INTERVAL_MS;   // 30s shared bound

    @Test public void coldStart_firstClaimAllowedImmediately() {
        // fresh process, nothing known yet — recovery must not be gated by storage
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(100_000L));
        assertEquals(100_000L, g.lastClaim());
    }

    @Test public void secondClaimInsideWindowBlocked_thenAllowedAfter() {
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(1_000_000L));
        assertFalse(g.tryClaim(1_000_000L + MIN - 1));
        assertTrue(g.tryClaim(1_000_000L + MIN));
        assertEquals(1_000_000L + MIN, g.lastClaim());
    }

    @Test public void skippedClaimDoesNotRearmWindow() {
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(2_000_000L));
        assertFalse(g.tryClaim(2_010_000L));                 // skipped — must NOT stamp
        assertTrue(g.tryClaim(2_000_000L + MIN));            // measured from the real claim
    }

    @Test public void smallBackwardJitterStaysBlocked_bigSkewFailsOpenAndRearms() {
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(5_000_000L));
        assertFalse(g.tryClaim(5_000_000L - 30_000L));       // mild skew: no storm
        assertTrue(g.tryClaim(5_000_000L - 61_000L));        // >60s: fail open
        assertEquals(5_000_000L - 61_000L, g.lastClaim());   // and re-armed immediately
        assertFalse(g.tryClaim(5_000_000L - 61_000L + MIN - 1));
    }

    @Test public void seedFromPersistedStamp_boundsTheNextProcess() {
        // "repeated process starts": previous process claimed at T, died; new
        // gate seeded from the durable stamp must honour the shared window.
        RebindGate dying = new RebindGate();
        assertTrue(dying.tryClaim(9_000_000L));
        RebindGate fresh = new RebindGate();
        fresh.seed(9_000_000L);
        assertFalse(fresh.tryClaim(9_000_000L + 5_000L));    // 5s later: still shared-bound
        assertTrue(fresh.tryClaim(9_000_000L + MIN));        // window expired: heal
    }

    @Test public void seedNeverLowers_andZeroSeedKeepsFailOpen() {
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(7_000_000L));
        g.seed(6_000_000L);                                  // stale stamp: must NOT lower
        assertEquals(7_000_000L, g.lastClaim());
        RebindGate g2 = new RebindGate();
        g2.seed(0L);                                         // unreadable/absent stamp
        assertTrue(g2.tryClaim(1L));                          // still fail-open
    }

    @Test public void disconnectAndProcessStartShareOneBound() {
        // the exact P-listener-foundation §4 scenario: a disconnect-triggered
        // request, then a process-start nudge inside the window — ONE request total.
        RebindGate g = new RebindGate();
        assertTrue(g.tryClaim(11_000_000L));                 // onListenerDisconnected path
        assertFalse(g.tryClaim(11_002_000L));                // process-start nudge 2s later
    }

    @Test public void concurrentCallers_singleWinnerPerWindow() throws Exception {
        // a reconnect↔disconnect flap or racing callers must never multiply the
        // request: N threads claiming at the same instant → exactly one wins.
        final RebindGate g = new RebindGate();
        assertEquals(1, raceClaims(g, 8, 42_000_000L));
        assertEquals(42_000_000L, g.lastClaim());
        // next window: exactly one new claim allowed — never more
        assertEquals(1, raceClaims(g, 8, 42_000_000L + MIN));
    }

    /** Fire `threads` concurrent tryClaim(now) at one instant; return win count. */
    private static int raceClaims(final RebindGate g, int threads, final long now)
            throws InterruptedException {
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch fire = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger wins = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try { fire.await(); } catch (InterruptedException ie) { done.countDown(); return; }
                    if (g.tryClaim(now)) wins.incrementAndGet();
                    done.countDown();
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        fire.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return wins.get();
    }
}
