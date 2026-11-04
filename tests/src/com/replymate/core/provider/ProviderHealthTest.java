package com.replymate.core.provider;

import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §7: health marks are model-scoped (state|model|millis) — the
 *  Nov-4 defect was a model-agnostic "tested ✓" outliving the model it tested.
 *  These pins cover encode/decode, the LEGACY format, and every malformed input
 *  the renderer must shrug off instead of crashing a settings screen. */
public class ProviderHealthTest {

    @Test public void roundtripOkCarriesModelAndTimestamp() {
        String v = ProviderHealth.encode(true, "gemini-3.1-pro-high", 1730700000000L);
        ProviderHealth.Mark m = ProviderHealth.decode(v);
        assertNotNull(m);
        assertTrue(m.ok);
        assertEquals("gemini-3.1-pro-high", m.model);
        assertEquals(1730700000000L, m.millis);
    }

    @Test public void roundtripFailMarks() {
        ProviderHealth.Mark m = ProviderHealth.decode(
            ProviderHealth.encode(false, "llama3.1", 123L));
        assertNotNull(m);
        assertFalse(m.ok);
        assertEquals("llama3.1", m.model);
        assertEquals(123L, m.millis);
    }

    @Test public void legacyMarksDecodeWithNoModelAndMustReadAsUntrusted() {
        // pre-P21 format: "ok|<millis>" — the model that earned it is UNKNOWN.
        ProviderHealth.Mark m = ProviderHealth.decode("ok|1730700000000");
        assertNotNull(m);
        assertTrue(m.ok);
        assertEquals("", m.model);
        assertEquals(1730700000000L, m.millis);
        ProviderHealth.Mark f = ProviderHealth.decode("fail|1730700000000");
        assertNotNull(f);
        assertFalse(f.ok);
        assertEquals("", f.model);
    }

    @Test public void emptyAndNullDecodeToNoMark() {
        assertNull(ProviderHealth.decode(null));
        assertNull(ProviderHealth.decode(""));
        assertNull(ProviderHealth.decode("single"));
    }

    @Test public void malformedTimestampDegradesToZeroNeverThrows() {
        ProviderHealth.Mark m = ProviderHealth.decode("ok|some-model|not-a-number");
        assertNotNull(m);
        assertTrue(m.ok);
        assertEquals("some-model", m.model);
        assertEquals(0, m.millis);
    }

    @Test public void nullAndBlankModelsEncodeAsEmpty() {
        assertEquals("ok||5", ProviderHealth.encode(true, null, 5));
        assertEquals("fail||5", ProviderHealth.encode(false, "   ", 5));
    }

    @Test public void stateWordOutsideOkReadsAsFailure() {
        // defensive: anything that is not "ok" must NOT render as success.
        assertFalse(ProviderHealth.decode("yes|m|1").ok);
        assertFalse(ProviderHealth.decode("OK|m|1").ok);
    }

    @Test public void keyNamespaceIsStable() {
        assertEquals("provider.health.openai_compat", ProviderHealth.keyFor("openai_compat"));
    }
}
