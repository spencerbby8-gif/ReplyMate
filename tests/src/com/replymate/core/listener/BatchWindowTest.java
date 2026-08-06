package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatchWindowTest {

    @Test public void dueIsWindowAfterLastEvent() {
        assertEquals(7000L, BatchWindow.dueAt(2000L));
    }

    @Test public void delayNeverNegative() {
        long due = BatchWindow.dueAt(2000L);
        assertTrue(BatchWindow.delayFrom(3000L, due) > 0);
        assertEquals(0, BatchWindow.delayFrom(9000L, due));
        assertEquals(0, BatchWindow.delayFrom(due, due));
    }
}
