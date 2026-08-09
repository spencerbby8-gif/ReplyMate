package com.replymate.core.live;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-4 pins (directive 6): device clock reaches the prompt line as a
 *  REAL moment in a REAL timezone; the dated glossary rides along ONLY when the
 *  partner actually used a listed term; off means off — and every state is honest
 *  in the audit breadcrumb (curated stamp, never "live research"). */
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
        assertFalse("no incoming slang ⇒ no glossary clause", s.promptLine.contains("Word help"));
        assertTrue("audit credits the clock read", s.whyLine.contains("device clock"));
        assertFalse(s.whyLine.contains("word help"));
    }

    @Test public void glossaryRidesOnlyWhenThePartnerUsedTheTerm() {
        LiveContext.Snapshot s = LiveContext.build(NOW, LAGOS, true,
            Arrays.asList("that fit ate fr, no cap"));
        assertTrue(s.promptLine.contains("Word help"));
        assertTrue(s.promptLine.contains("ate = did amazingly"));
        assertTrue(s.promptLine.contains("fr = for real"));
        assertTrue(s.promptLine.contains("no cap = no lie"));
        assertFalse("unrelated terms stay out", s.promptLine.contains("rizz"));
        assertTrue("the stamp is honest and visible: " + s.promptLine,
            s.promptLine.contains(LiveContext.GLOSSARY_STAMP)
                && s.promptLine.contains("not a live lookup"));
        assertTrue(s.whyLine.contains("word help for"));
        assertTrue(s.whyLine.contains("not live"));
    }

    @Test public void commonWordsNeverFalseMatch() {
        // "w" (a win) must not fire on "what's up, wyd later?"
        LiveContext.Snapshot s = LiveContext.build(NOW, LAGOS, true,
            Arrays.asList("what's up, wyd later?"));
        assertFalse(s.promptLine.contains("Word help"));
    }

    @Test public void disabledStateIsEmptyButHonest() {
        LiveContext.Snapshot off = LiveContext.build(NOW, LAGOS, false,
            Arrays.asList("ate fr"));
        assertEquals("", off.promptLine);
        assertTrue(off.whyLine.contains("switched off"));
        assertTrue(off.whyLine.contains("no clock line"));
    }

    @Test public void glossaryIsCappedAndBounded() {
        LiveContext.Snapshot s = LiveContext.build(NOW, LAGOS, true,
            Arrays.asList("ate bet mid rizz delulu sus aura cooked stan ratio goated"));
        String clause = s.promptLine.substring(s.promptLine.indexOf("Word help"));
        int entries = clause.split(";").length;
        assertTrue("at most six glossary entries ride one prompt, got " + entries,
            entries <= 6);
    }

    @Test public void nullInputsAreSafe() {
        LiveContext.Snapshot s = LiveContext.build(NOW, null, true, null);
        assertFalse(s.promptLine.isEmpty());        // default zone fallback
        assertTrue(LiveContext.build(NOW, LAGOS, true,
            Collections.singletonList(null)).promptLine.contains("Now (device clock):"));
    }
}
