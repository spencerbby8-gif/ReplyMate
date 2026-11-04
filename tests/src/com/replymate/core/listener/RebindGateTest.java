package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-anr-startup: regression pins for the memory-first rebind gate.
 *
 *  The proven defect (emulator runs 41386158198 / 41404621261 / 41407382911,
 *  pulled procexitstore stack): RebindPolicy.due(kv, clock) — called from
 *  ReplyMateApp.onCreate on the MAIN thread — performed the process's FIRST
 *  SQLite open synchronously (onConfigure → setJournalMode WAL → fdatasync),
 *  stalling the listener-service bind window into an "executing service" ANR
 *  and a deferred AMS kill. These tests pin the lifecycle-correct replacement:
 *
 *    cold startup              — first acquire allowed WITHOUT any caller-thread kv IO
 *    repeated process starts   — durable stamp still throttles across "restarts"
 *    listener disconnect path  — same gate, stamp claimed BEFORE the request
 *    concurrent capture        — N racing callers, exactly one window winner
 *
 *  The GuardedKv + ManualExecutor pair is the direct anti-ANR instrument: any
 *  synchronous kv touch from a caller thread fails the suite outright. */
public final class RebindGateTest {

    private static final class ManualClock implements Clock {
        long now;
        ManualClock(long t) { now = t; }
        @Override public long now() { return now; }
    }

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<Runnable>();
        @Override public void execute(Runnable r) { queue.add(r); }
        void runAll() {
            List<Runnable> todo = new ArrayList<Runnable>(queue);
            queue.clear();
            for (Runnable r : todo) r.run();
            // tasks may enqueue more (persist after seed is ordered by lane)
            while (!queue.isEmpty()) {
                todo = new ArrayList<Runnable>(queue);
                queue.clear();
                for (Runnable r : todo) r.run();
            }
        }
    }

    /** Throws on ANY kv access while locked — simulating "this must never be
     *  touched from the caller thread". Tests unlock only before manually
     *  draining the IO lane. */
    private static final class GuardedKv implements KvStore {
        final Fakes.KvStoreFake inner = new Fakes.KvStoreFake();
        boolean locked = true;
        private void gate() { if (locked) throw new IllegalStateException("kv touched from caller thread"); }
        @Override public String get(String key, String defValue) { gate(); return inner.get(key, defValue); }
        @Override public void put(String key, String value) { gate(); inner.put(key, value); }
        @Override public void delete(String key) { gate(); inner.delete(key); }
        @Override public boolean contains(String key) { gate(); return inner.contains(key); }
    }

    @Test public void coldStartAcquiresWithZeroCallerThreadIo() {
        GuardedKv kv = new GuardedKv();
        ManualExecutor io = new ManualExecutor();
        ManualClock clk = new ManualClock(1_000_000L);
        RebindGate gate = new RebindGate(kv, clk, io);
        gate.seedAsync();

        // kv locked: simulating the main thread during a lifecycle callback.
        assertTrue(gate.tryAcquire());               // cold start → due
        assertFalse(gate.tryAcquire());              // immediate repeat → memory-throttled
        assertEquals(1_000_000L, gate.memStampForTest());  // stamp claimed BEFORE request
        // nothing touched kv yet (still locked, no throw) and work is queued
        assertFalse(io.queue.isEmpty());

        kv.locked = false;
        io.runAll();                                 // IO lane drains
        assertEquals("1000000",
            kv.inner.get(RebindPolicy.KV_LAST_ATTEMPT, "-1"));  // durable write-through landed
    }

    @Test public void processRestartWithinWindowStaysThrottledAfterSeed() {
        Fakes.KvStoreFake cold = new Fakes.KvStoreFake();
        cold.put(RebindPolicy.KV_LAST_ATTEMPT, "999000");     // previous "process" stamped 5s ago
        GuardedKv kv = new GuardedKv();
        kv.inner.put(RebindPolicy.KV_LAST_ATTEMPT, "999000");
        ManualExecutor io = new ManualExecutor();
        ManualClock clk = new ManualClock(1_004_000L);
        RebindGate gate = new RebindGate(kv, clk, io);
        gate.seedAsync();
        kv.locked = false;
        io.runAll();                                          // durable state read on IO lane
        kv.locked = true;                                     // back on the "main thread"
        assertFalse(gate.tryAcquire());                       // 5s < 30s → throttled, no kv touch
        assertEquals(999000L, gate.memStampForTest());
        assertTrue(io.queue.isEmpty());                       // throttled ⇒ no persist enqueued
    }

    @Test public void windowRearmsAndSkewFailsOpenOnMemoryPath() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        kv.put(RebindPolicy.KV_LAST_ATTEMPT, "1000000");
        ManualExecutor io = new ManualExecutor();
        ManualClock clk = new ManualClock(1_000_000L + RebindPolicy.MIN_INTERVAL_MS);
        RebindGate gate = new RebindGate(kv, clk, io);
        gate.seedAsync();
        io.runAll();
        assertTrue(gate.tryAcquire());                        // window elapsed → due again
        // clock-skew backward >60s → fail open
        clk.now = 1_000_000L - 61_000L;
        assertTrue(gate.tryAcquire());
    }

    @Test public void pureLongOverloadMatchesDocumentedSemantics() {
        long t = 10_000_000L;
        assertTrue(RebindPolicy.due(0L, t));                                   // never stamped
        assertFalse(RebindPolicy.due(t, t + RebindPolicy.MIN_INTERVAL_MS - 1));
        assertTrue(RebindPolicy.due(t, t + RebindPolicy.MIN_INTERVAL_MS));
        assertTrue(RebindPolicy.due(t, t - 60_001L));                          // skew fail-open
        assertFalse(RebindPolicy.due(t, t - 60_000L));                         // boundary stays closed
    }

    @Test public void malformedDurableStampFailsOpenOnceSeeded() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        kv.put(RebindPolicy.KV_LAST_ATTEMPT, "corrupted-by-oem");
        ManualExecutor io = new ManualExecutor();
        RebindGate gate = new RebindGate(kv, new ManualClock(5_000_000L), io);
        gate.seedAsync();
        io.runAll();
        assertTrue(gate.tryAcquire());                        // recovery over paralysis
    }

    @Test public void concurrentAcquiresYieldExactlyOneWinnerPerWindow() throws Exception {
        final Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        final ManualClock clk = new ManualClock(1_000_000L);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            final RebindGate gate = new RebindGate(kv, clk, io);
            gate.seedAsync();
            io.submit(new Runnable() { @Override public void run() { } }).get();   // fence: seed drained
            for (int round = 0; round < 2; round++) {
                final int threads = 24;
                final AtomicInteger winners = new AtomicInteger();
                final CountDownLatch go = new CountDownLatch(1);
                final CountDownLatch done = new CountDownLatch(threads);
                for (int i = 0; i < threads; i++) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            if (gate.tryAcquire()) winners.incrementAndGet();
                            done.countDown();
                        }
                    }).start();
                }
                go.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertEquals("exactly one winner per window (round " + round + ")",
                    1, winners.get());
                clk.now += RebindPolicy.MIN_INTERVAL_MS + 1;    // next window
            }
            io.submit(new Runnable() { @Override public void run() { } }).get();   // fence
        } finally {
            io.shutdownNow();
        }
        // two windows ⇒ the durable stamp holds the SECOND window's claim;
        // wait out any re-armed drain racing the fence (bounded, not flaky)
        long target = 1_000_000L + RebindPolicy.MIN_INTERVAL_MS + 1;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            long stored = Long.parseLong(kv.get(RebindPolicy.KV_LAST_ATTEMPT, "0"));
            if (stored >= target) return;
            try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        fail("durable stamp never advanced past the first window: "
            + kv.get(RebindPolicy.KV_LAST_ATTEMPT, "0"));
    }
}
