package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-anr-startup regression pin for CONCURRENT capture: moving listener
 *  lifecycle I/O onto the capture lane must change nothing about what the lane
 *  produces. These asserts ride through the REAL IngestCoordinator on the
 *  monitor-serialized fakes (their synchronization mirrors SQLite's writer
 *  serialization — see Fakes' class javadoc), hammered from multiple threads:
 *  no loss, no double-store of identical deliveries, exact counters. */
public class ConcurrentCaptureTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
    }

    private static NotifEvent ev(String conv, String sender, String text, long ts) {
        NotifEvent e = new NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.conversationTitle = conv;
        e.senderName = sender;
        e.ownerName = "Me";
        e.text = text;
        e.timestampMs = ts;
        e.group = false;
        e.hasAttachment = false;
        return e;
    }

    @Test public void parallelDistinctConversations_nothingLost() throws Exception {
        // 4 threads × 10 DISTINCT conversations each — the burst-wall case:
        // every body must be stored exactly once, every ping counted.
        int threads = 4, perThread = 10;
        final AtomicReference<Throwable> boom = new AtomicReference<Throwable>();
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger storedSum = new AtomicInteger();
        final AtomicInteger pingSum = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int ti = t;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < 10; i++) {
                            IngestReport rep = engine.handle(new ArrayList<NotifEvent>(Arrays.asList(
                                ev("C" + ti + "-" + i, "S" + ti, "m" + ti + "-" + i, 1000 + i))), null);
                            storedSum.addAndGet(rep.stored);
                            pingSum.addAndGet(rep.pings.size());
                        }
                    } catch (Throwable t) {
                        boom.set(t);
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS));
        assertNull("ingest threw under concurrency: " + boom.get(), boom.get());
        assertEquals(threads * perThread, storedSum.get());          // nothing dropped
        assertEquals(threads * perThread, pingSum.get());            // one ping per new convo
        int msgTotal = 0;
        for (com.replymate.core.model.Contact c : contacts.all()) {
            msgTotal += messages.countByContact(c.id);
        }
        assertEquals(threads * perThread, msgTotal);                 // store rows match
        assertEquals(String.valueOf(threads * perThread),
            kv.get(IngestCoordinator.KV_STORED_TOTAL, "0"));         // durable counter exact
    }

    @Test public void racingDuplicateDelivery_storesOnce() throws Exception {
        // the same notification delivered twice nearly simultaneously (re-post +
        // reconcile overlap at a reconnect boundary) must dedupe to ONE row.
        final CountDownLatch fire = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        final List<IngestReport> reps = new ArrayList<IngestReport>();
        for (int t = 0; t < 2; t++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { fire.await(); } catch (InterruptedException ie) { done.countDown(); return; }
                    IngestReport rep = engine.handle(new ArrayList<NotifEvent>(Arrays.asList(
                        ev("Nia", "Nia", "same ping", 7777))), null);
                    synchronized (reps) { reps.add(rep); }
                    done.countDown();
                }
            }).start();
        }
        fire.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        int stored = 0, dupes = 0;
        for (IngestReport r : reps) { stored += r.stored; dupes += r.duplicates; }
        assertEquals(1, stored);
        assertEquals(1, dupes);
        assertEquals("1", kv.get(IngestCoordinator.KV_STORED_TOTAL, "0"));
    }
}
