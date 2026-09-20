package com.replymate.core.listener;

import com.replymate.core.util.Clock;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-listener-foundation §4: the shared rebind throttle. requestRebind is the
 *  ONLY officially safe call before onListenerConnected/after
 *  onListenerDisconnected (developer.android.com reference) — but it must never
 *  run unbounded, so every caller in the app shares this stamped 30s bound.
 *  These pins prove: first request is due, stamped requests suppress for the
 *  interval, the window re-arms it, malformed state fails OPEN (recovery over
 *  paralysis), and the stamp itself is idempotent-safe. */
public final class RebindPolicyTest {

    /** Mutable test clock — Fakes.FIXED_CLOCK cannot advance. */
    private static final class ManualClock implements Clock {
        long now;
        ManualClock(long t) { now = t; }
        @Override public long now() { return now; }
    }

    @Test public void firstRequestIsDue() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualClock clk = new ManualClock(1_000_000L);
        assertTrue(RebindPolicy.due(kv, clk));
    }

    @Test public void stampedRequestSuppressesWithinInterval() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualClock clk = new ManualClock(1_000_000L);
        assertTrue(RebindPolicy.due(kv, clk));
        RebindPolicy.stamp(kv, clk);
        assertFalse(RebindPolicy.due(kv, clk));
        clk.now += RebindPolicy.MIN_INTERVAL_MS - 1;
        assertFalse(RebindPolicy.due(kv, clk));
    }

    @Test public void windowRearmsAfterInterval() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualClock clk = new ManualClock(1_000_000L);
        RebindPolicy.stamp(kv, clk);
        clk.now += RebindPolicy.MIN_INTERVAL_MS;
        assertTrue(RebindPolicy.due(kv, clk));
        // …and re-stamping suppresses again — a loop is possible ONLY at the
        // 30s cadence, never faster, no matter how many callers exist.
        RebindPolicy.stamp(kv, clk);
        assertFalse(RebindPolicy.due(kv, clk));
    }

    @Test public void malformedOrMissingStateFailsOpen() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualClock clk = new ManualClock(5_000L);
        kv.put(RebindPolicy.KV_LAST_ATTEMPT, "not-a-number");
        assertTrue(RebindPolicy.due(kv, clk));
        assertEquals(0L, RebindPolicy.lastAttempt(kv));
        assertTrue(RebindPolicy.due(null, clk));   // no kv at all ⇒ recovery wins
    }

    @Test public void clockJumpingBackwardFailsOpenNotStuck() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualClock clk = new ManualClock(10_000_000L);
        RebindPolicy.stamp(kv, clk);
        clk.now = 1_000L;               // wall-clock correction far backwards
        assertTrue(RebindPolicy.due(kv, clk));
    }

    @Test public void stampNeverThrowsOnBrokenStore() {
        // stamp must bound even a kv that refuses writes — worst case the next
        // caller re-asks (still at most once per successful stamp).
        RebindPolicy.stamp(new KvBoom(), new ManualClock(0L));
    }

    private static final class KvBoom implements com.replymate.core.ports.KvStore {
        @Override public String get(String key, String defValue) { return defValue; }
        @Override public void put(String key, String value) { throw new RuntimeException("io"); }
        @Override public void delete(String key) { }
        @Override public boolean contains(String key) { return false; }
    }
}
