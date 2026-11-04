package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-anr-startup: regression pins for the async diagnostic writer.
 *
 *  What this replaces: ListenerTrace.line(...) ran kv read-modify-write
 *  SYNCHRONOUSLY inside onListenerConnected / onListenerDisconnected /
 *  ListenerRebind — main-thread SQLite (the proven startup-ANR class), and a
 *  cross-thread lost-update race against the ingest lane writing the same ring.
 *  Pins here:
 *    cold caller thread      — trace/put enqueue with ZERO synchronous kv IO
 *    bounded ring            — order + 12-cap preserved after async drain
 *    connect/disconnect      — the lifecycle line sequence survives queueing
 *    concurrent capture      — N producer threads: no loss, no dup, one writer
 *    failure containment     — a throwing kv can never wedge the queue        */
public final class DiagQueueTest {

    private static final class ManualClock implements Clock {
        long now;
        ManualClock(long t) { now = t; }
        @Override public long now() { return now; }
    }

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<Runnable>();
        @Override public void execute(Runnable r) { queue.add(r); }
        void runAll() {
            while (!queue.isEmpty()) {
                Runnable r = queue.remove(0);
                r.run();
            }
        }
    }

    private static final class GuardedKv implements KvStore {
        final Fakes.KvStoreFake inner = new Fakes.KvStoreFake();
        boolean locked = true;
        private void gate() { if (locked) throw new IllegalStateException("kv touched from caller thread"); }
        @Override public String get(String key, String defValue) { gate(); return inner.get(key, defValue); }
        @Override public void put(String key, String value) { gate(); inner.put(key, value); }
        @Override public void delete(String key) { gate(); inner.delete(key); }
        @Override public boolean contains(String key) { gate(); return inner.contains(key); }
    }

    @Test public void callerThreadNeverTouchesKv() {
        GuardedKv kv = new GuardedKv();
        ManualExecutor io = new ManualExecutor();
        DiagQueue q = new DiagQueue(kv, new ManualClock(1000L), io);
        q.trace("bound=true (onListenerConnected)");     // locked kv: must not throw
        q.put("listener.connected_at", "1000");
        q.trace("callback→lane · com.whatsapp · k#abc · bound=true");
        assertEquals(3, q.pendingForTest());
        assertEquals(1, io.queue.size());                // one drain scheduled, not three
        kv.locked = false;
        io.runAll();
        List<String> lines = DiagnosticsRing.lines(
            kv.inner.get(ListenerTrace.KV_TRACE, ""));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("callback→lane"));
        assertTrue(lines.get(1).contains("bound=true"));
        assertEquals("1000", kv.inner.get("listener.connected_at", ""));
    }

    @Test public void ringKeepsNewestTwelveInOrderAfterDrain() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualExecutor io = new ManualExecutor();
        ManualClock clk = new ManualClock(1000L);
        DiagQueue q = new DiagQueue(kv, clk, io);
        for (int i = 0; i < 25; i++) {
            clk.now += 10;
            q.trace("line-" + i);
        }
        io.runAll();
        List<String> lines = DiagnosticsRing.lines(kv.get(ListenerTrace.KV_TRACE, ""));
        assertEquals(12, lines.size());                  // bounded ring
        assertTrue("newest first", lines.get(0).contains("line-24"));
        assertTrue(lines.get(11).contains("line-13"));
        assertTrue(lines.get(5).contains("line-19"));    // strict ordering preserved
    }

    @Test public void connectDisconnectReconnectSequenceSurvivesQueueing() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ManualExecutor io = new ManualExecutor();
        ManualClock clk = new ManualClock(5000L);
        DiagQueue q = new DiagQueue(kv, clk, io);
        // the exact lifecycle sequence a ColorOS unbind + 1.7.2 recovery produces
        q.put("listener.disconnected_at", "5000");
        q.trace("bound=false (onListenerDisconnected)");
        q.trace("rebind requested · onListenerDisconnected");
        clk.now += 1200;
        q.put("listener.connected_at", "6200");
        q.trace("bound=true (onListenerConnected)");
        io.runAll();
        List<String> lines = DiagnosticsRing.lines(kv.get(ListenerTrace.KV_TRACE, ""));
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("bound=true"));
        assertTrue(lines.get(1).contains("rebind requested"));
        assertTrue(lines.get(2).contains("bound=false"));
        assertEquals("5000", kv.get("listener.disconnected_at", ""));
        assertEquals("6200", kv.get("listener.connected_at", ""));
    }

    @Test public void concurrentProducersNoLossNoDupSingleDrainWriter() throws Exception {
        final Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        final CountingKv counting = new CountingKv(kv);
        ExecutorService io = Executors.newSingleThreadExecutor();
        final AtomicLong seq = new AtomicLong();         // surrogate logical clock
        final AtomicLong puts = new AtomicLong();
        final DiagQueue q = new DiagQueue(counting, new Clock() {
            @Override public long now() { return seq.incrementAndGet(); }
        }, io);
        final int producers = 8, perProducer = 40;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(producers);
        for (int i = 0; i < producers; i++) {
            final int id = i;
            new Thread(new Runnable() {
                @Override public void run() {
                    try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    for (int j = 0; j < perProducer; j++) q.trace("p" + id + "-" + j);
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        // wait out any re-armed drain racing a fixed fence — bounded, not flaky
        long deadline = System.currentTimeMillis() + 10_000L;
        while (counting.puts.get() < 320 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        io.shutdownNow();

        // 320 appends ⇒ exactly 320 ring puts on the IO lane: no loss, no dup
        assertEquals(320, counting.puts.get());
        List<String> lines = DiagnosticsRing.lines(kv.get(ListenerTrace.KV_TRACE, ""));
        assertEquals(12, lines.size());                  // cap under contention
        Set<String> seen = new HashSet<String>(lines);
        assertEquals("no duplicated lines in the ring", 12, seen.size());
    }

    @Test public void throwingKvNeverWedgesTheQueue() {
        final Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        final AtomicLong calls = new AtomicLong();
        KvStore flaky = new KvStore() {
            @Override public String get(String key, String defValue) { return kv.get(key, defValue); }
            @Override public void put(String key, String value) {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("oem disk hiccup");
                kv.put(key, value);
            }
            @Override public void delete(String key) { kv.delete(key); }
            @Override public boolean contains(String key) { return kv.contains(key); }
        };
        ManualExecutor io = new ManualExecutor();
        DiagQueue q = new DiagQueue(flaky, new ManualClock(7000L), io);
        q.trace("first");
        q.trace("second (dies on put 2)");
        q.trace("third");
        io.runAll();                                     // drain must not hang/stick
        q.trace("fourth");
        io.runAll();
        List<String> lines = DiagnosticsRing.lines(kv.get(ListenerTrace.KV_TRACE, ""));
        assertEquals(3, lines.size());                   // first + third + fourth; "second" threw once
        assertTrue(lines.get(0).contains("fourth"));
        assertTrue(lines.get(1).contains("third"));
        assertTrue(lines.get(2).contains("first"));
    }

    /** Counts puts while delegating to the fake (aggregation for the loss pin). */
    private static final class CountingKv implements KvStore {
        final Fakes.KvStoreFake inner;
        final AtomicLong puts = new AtomicLong();
        CountingKv(Fakes.KvStoreFake inner) { this.inner = inner; }
        @Override public String get(String key, String defValue) { return inner.get(key, defValue); }
        @Override public void put(String key, String value) { puts.incrementAndGet(); inner.put(key, value); }
        @Override public void delete(String key) { inner.delete(key); }
        @Override public boolean contains(String key) { return inner.contains(key); }
    }
}
