package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextIdsTest {

    @Test public void deterministicForSameInput() {
        String a = TextIds.computeNotifKey("whatsapp", "amara", "Amara", "hello", 1000L);
        String b = TextIds.computeNotifKey("whatsapp", "amara", "Amara", "hello", 1000L);
        assertEquals(a, b);
    }

    @Test public void changesOnAnyField() {
        String base = TextIds.computeNotifKey("whatsapp", "amara", "Amara", "hello", 1000L);
        assertNotEquals(base, TextIds.computeNotifKey("telegram", "amara", "Amara", "hello", 1000L));
        assertNotEquals(base, TextIds.computeNotifKey("whatsapp", "amara2", "Amara", "hello", 1000L));
        assertNotEquals(base, TextIds.computeNotifKey("whatsapp", "amara", "Bo", "hello", 1000L));
        assertNotEquals(base, TextIds.computeNotifKey("whatsapp", "amara", "Amara", "hellp", 1000L));
        assertNotEquals(base, TextIds.computeNotifKey("whatsapp", "amara", "Amara", "hello", 1001L));
    }

    @Test public void hex40Format() {
        String k = TextIds.computeNotifKey("w", "r", "s", "b", 1L);
        assertEquals(40, k.length());
        assertTrue(k.matches("[0-9a-f]{40}"));
    }

    @Test public void nullsAreSafe() {
        String k = TextIds.computeNotifKey(null, null, null, null, 0L);
        assertNotNull(k);
        assertTrue(k.length() >= 8);
    }
}
