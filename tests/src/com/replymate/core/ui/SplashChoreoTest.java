package com.replymate.core.ui;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-splash-auth-polish: pins the owner brief as invariants — icon is the hero,
 *  pacing is human (not too fast, not too slow), every beat completes with rest
 *  time to spare, and the frame always stays up long enough to be seen. */
public final class SplashChoreoTest {

    @Test public void minShowIsLongEnoughToSeeAndShortEnoughToFeelSnappy() {
        assertTrue("min show must give users time to see the animation",
            SplashChoreo.MIN_SHOW_MS >= 1400);
        assertTrue("min show must never feel like a loading screen",
            SplashChoreo.MIN_SHOW_MS <= 2400);
    }

    @Test public void everyBeatCompletesWithRestBeforeMinShow() {
        for (long[] b : SplashChoreo.beats()) {
            assertTrue("delay must be non-negative", b[0] >= 0);
            assertTrue("duration must be human-paced (>= 200ms)", b[1] >= 200);
            assertTrue("duration must not drag (> 1100ms)", b[1] <= 1100);
            assertTrue("beat must land at least 300ms before the splash can route",
                b[0] + b[1] <= SplashChoreo.MIN_SHOW_MS - 300);
        }
    }

    @Test public void theIconIsTheAnchor() {
        assertEquals("glow starts the scene", 0, SplashChoreo.GLOW_DELAY);
        assertTrue("ring arrives with the icon, not after the texts",
            SplashChoreo.RING_DELAY <= SplashChoreo.ICON_DELAY);
        assertTrue("the hero icon enters before any text",
            SplashChoreo.ICON_DELAY < SplashChoreo.WORD_DELAY);
        assertTrue("the hero icon enters before the tagline",
            SplashChoreo.ICON_DELAY < SplashChoreo.TAG_DELAY);
    }

    @Test public void shimmerOnlySweepsAIconThatHasLanded() {
        assertTrue("the light sweep must wait for the icon entrance to (almost) finish",
            SplashChoreo.SHIMMER_DELAY
                >= SplashChoreo.ICON_DELAY + SplashChoreo.ICON_DUR - 100);
    }

    @Test public void textBeatsStaggerInReadingOrder() {
        assertTrue(SplashChoreo.WORD_DELAY < SplashChoreo.TAG_DELAY);
        assertTrue(SplashChoreo.TAG_DELAY < SplashChoreo.LINE_DELAY);
    }

    @Test public void lastBeatEndsInsideTheMinShow() {
        long end = SplashChoreo.lastBeatEnd();
        assertTrue("animation must actually play (>= 900ms of motion)", end >= 900);
        assertTrue("nothing may still be moving when routing is allowed",
            end <= SplashChoreo.MIN_SHOW_MS - 300);
    }
}
