package com.replymate.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

/** The six approved tone transforms (P3) — complete set, stable wires, honest prompts. */
public class ToneTransformTest {

    @Test public void exactlyTheSixApprovedTones() {
        ToneTransform[] all = ToneTransform.values();
        assertEquals(6, all.length);
        assertEquals(ToneTransform.SHORTER, ToneTransform.fromWire("shorter"));
        assertEquals(ToneTransform.LONGER, ToneTransform.fromWire("longer"));
        assertEquals(ToneTransform.FRIENDLIER, ToneTransform.fromWire("friendlier"));
        assertEquals(ToneTransform.PROFESSIONAL, ToneTransform.fromWire("professional"));
        assertEquals(ToneTransform.CONFIDENT, ToneTransform.fromWire("confident"));
        assertEquals(ToneTransform.CASUAL, ToneTransform.fromWire("casual"));
    }

    @Test public void everyToneHasLabelAndNonEmptyInstruction() {
        for (ToneTransform t : ToneTransform.values()) {
            assertNotNull(t.label);
            assertFalse(t.label.trim().isEmpty());
            assertNotNull(t.instruction);
            assertTrue(t.instruction.length() >= 20);
            assertEquals(t, ToneTransform.fromWire(t.wire));
        }
    }

    @Test public void instructionsForbidInventingFacts() {
        assertTrue(ToneTransform.LONGER.instruction.toLowerCase().contains("do not invent facts"));
    }

    @Test public void unknownWireThrows() {
        try {
            ToneTransform.fromWire("aggressive");
            fail("unknown tone must throw");
        } catch (IllegalArgumentException expected) { }
    }
}
