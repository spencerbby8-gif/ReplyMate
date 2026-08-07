package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-2: pins the exactly-once job identity — newer work for the same
 *  conversation cancels older work automatically, older tokens can never run or
 *  post, and independent conversations never interfere. */
public final class JobCoalescerTest {

    @Test public void newerTokenSupersedesOlderForSameKey() {
        JobCoalescer j = new JobCoalescer();
        long t1 = j.begin(7);
        assertTrue(j.isCurrent(7, t1));
        long t2 = j.begin(7);
        assertFalse("older job cancelled automatically", j.isCurrent(7, t1));
        assertTrue(j.isCurrent(7, t2));
    }

    @Test public void unknownOrFinishedTokensNeverRun() {
        JobCoalescer j = new JobCoalescer();
        long t1 = j.begin(9);
        j.finish(9, t1);
        assertFalse(j.isCurrent(9, t1));
        assertFalse(j.isCurrent(9, 999));
    }

    @Test public void finishKeepsNewerJobAlive() {
        JobCoalescer j = new JobCoalescer();
        long t1 = j.begin(3);
        long t2 = j.begin(3);
        j.finish(3, t1);                       // stale job ending must not clear t2
        assertTrue(j.isCurrent(3, t2));
        j.finish(3, t2);
        assertEquals(0, j.pendingCount());
    }

    @Test public void keysAreIndependent() {
        JobCoalescer j = new JobCoalescer();
        long a = j.begin(1);
        long b = j.begin(2);
        assertTrue(j.isCurrent(1, a));
        assertTrue(j.isCurrent(2, b));
        assertFalse(j.isCurrent(1, b));
        assertEquals(2, j.pendingCount());
    }

    @Test public void tokensAreMonotonicSoOrderingIsProvable() {
        JobCoalescer j = new JobCoalescer();
        long t1 = j.begin(1);
        long t2 = j.begin(2);
        assertTrue(t2 > t1);
    }
}
