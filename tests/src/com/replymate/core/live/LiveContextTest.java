package com.replymate.core.live;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import org.junit.Test;

import static org.junit.Assert.*;

/** Live context pins (P6 directives 2/7): the device clock reaches the prompt as
 *  a REAL moment in a REAL timezone; the static curated glossary is GONE (slang
 *  & meanings moved to automatic live search); off means off, honestly. */
public final class LiveContextTest {

    private static final long NOW = 1786189530000L;   // Sat 8 Aug 2026 (UTC-based)
    private static final TimeZone LAGOS = TimeZone.getTimeZone("Africa/Lagos");

    @Test public void deviceClockReachTheLineWithRealZoneText() {
        LiveContext.Snapshot s = LiveContext.build(NOW, LAGOS, true,
            Collections.<String>emptyList());
        assertTrue(s.promptLine.startsWith("Now (device clock):"));
        assertTrue("the real date travels: " + s.promptLine,
            s.promptLine.contains("2026") && s.promptLine.contains("Aug"));
        assertTrue("the real zone travels: " + s.promptLine,
            s.promptLine.contains("WAT") || s.promptLine.contains("Africa/Lagos"));
        assertTrue("relative words anchored: " + s.promptLine,
            s.promptLine.contains("tomorrow"));
        assertTrue("audit credits the clock read", s.whyLine.contains("device clock"));
    }

    @Test public void theStaticGlossaryIsGoneFromTheLine() {
        // The 1.5.5 curated "Word help" clause must not exist anymore — slang help
        // is a live-search outcome now, and this module never fakes freshness.
        LiveContext.Snapshot s = LiveContext.build(NOW, LAGOS, true,
            Arrays.asList("that fit ate fr, no cap"));
        assertFalse("no curated word-help clause", s.promptLine.contains("Word help"));
        assertFalse(s.promptLine.contains("curated"));
        assertFalse(s.whyLine.contains("word help"));
    }

    @Test public void disabledStateIsEmptyButHonest() {
        LiveContext.Snapshot off = LiveContext.build(NOW, LAGOS, false,
            Arrays.asList("ate fr"));
        assertEquals("", off.promptLine);
        assertTrue(off.whyLine.contains("switched off"));
        assertTrue(off.whyLine.contains("no device-clock line"));
    }

    @Test public void nullInputsAreSafe() {
        LiveContext.Snapshot s = LiveContext.build(NOW, null, true, null);
        assertFalse(s.promptLine.isEmpty());        // default zone fallback
        assertTrue(LiveContext.build(NOW, LAGOS, true,
            Collections.singletonList(null)).promptLine.contains("Now (device clock):"));
    }
}
