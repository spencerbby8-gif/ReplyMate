package com.replymate.core.understanding;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-1: mechanical burst reading — corrections, filler, multi-question.
 *  Conservative signals, exactly test-pinned: they must fire on real markers and
 *  NEVER fire on ordinary conversation. */
public final class BurstSignalsTest {

    @Test public void correctionsFireOnRealMarkers() {
        BurstSignals.Result r = BurstSignals.detect(Arrays.asList(
            "let's do tuesday 8pm",
            "no wait, wednesday!",
            "I meant 9pm not 8",
            "*wednesday",
            "scratch that — can we move to next week"));
        assertTrue(r.hasCorrection());
        assertEquals(Arrays.asList(2, 3, 4, 5), r.correctionLines);
    }

    @Test public void bareFillerWordsAreNotCorrections() {
        BurstSignals.Result r = BurstSignals.detect(Arrays.asList(
            "wait have you eaten", "i mean it's whatever", "sorry for late reply"));
        assertFalse(r.hasCorrection());
    }

    @Test public void fillerCountsOnlyPurePings() {
        BurstSignals.Result r = BurstSignals.detect(Arrays.asList(
            "you there?", "??", "hey", "are we still doing tomorrow's outing"));
        assertEquals(4, r.size);
        assertEquals(3, r.fillers);            // the real question is NOT filler
        assertTrue(r.fillerHeavy);
    }

    @Test public void singleRealLineIsNotFillerHeavy() {
        BurstSignals.Result r = BurstSignals.detect(
            Collections.singletonList("please send the account number"));
        assertEquals(0, r.fillers);
        assertFalse(r.fillerHeavy);
        assertFalse(r.multiQuestion);
    }

    @Test public void multiQuestionNeedsAtLeastTwoQuestionLines() {
        assertTrue(BurstSignals.detect(Arrays.asList(
            "you coming?", "which day?")).multiQuestion);
        assertFalse(BurstSignals.detect(Arrays.asList(
            "one question?", "statement")).multiQuestion);
    }

    @Test public void greetingsAreFillerButEmptyHandlerInputsAreSafe() {
        assertTrue(BurstSignals.detect(Arrays.asList("hello", "hi", "yo")).fillerHeavy);
        BurstSignals.Result empty = BurstSignals.detect(null);
        assertEquals(0, empty.size);
        assertFalse(empty.hasCorrection());
        assertFalse(empty.fillerHeavy);
        BurstSignals.Result blanks = BurstSignals.detect(Arrays.asList("", null, "  "));
        assertEquals(3, blanks.size);
        assertEquals(0, blanks.fillers);
    }
}
