package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListenerFilterTest {

    private static NotifEvent ev(String text, boolean attach, boolean group) {
        NotifEvent e = new NotifEvent();
        e.text = text;
        e.hasAttachment = attach;
        e.group = group;
        return e;
    }

    @Test public void skipsNothingReadable() {
        assertEquals(ListenerFilter.Verdict.SKIP, ListenerFilter.verdict(ev(null, false, false)));
        assertEquals(ListenerFilter.Verdict.SKIP, ListenerFilter.verdict(ev("   ", false, false)));
        assertEquals(ListenerFilter.Verdict.SKIP, ListenerFilter.verdict(null));
    }

    @Test public void attachmentsAreStoreOnly() {
        assertEquals(ListenerFilter.Verdict.STORE_ONLY, ListenerFilter.verdict(ev(null, true, false)));
        assertEquals(ListenerFilter.Verdict.STORE_ONLY, ListenerFilter.verdict(ev("photo", true, false)));
    }

    @Test public void groupsAreStoreOnly() {
        assertEquals(ListenerFilter.Verdict.STORE_ONLY, ListenerFilter.verdict(ev("hello", false, true)));
    }

    @Test public void plainIncomingPings() {
        assertEquals(ListenerFilter.Verdict.STORE_AND_PING, ListenerFilter.verdict(ev("hello", false, false)));
    }
    // NOTE: package→channel mapping is covered by ParserRegistryTest (moved in P3).
}
