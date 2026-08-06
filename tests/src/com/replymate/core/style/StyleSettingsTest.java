package com.replymate.core.style;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** The 9-control catalog + inheritance + prompt rendering (P4). */
public class StyleSettingsTest {

    private static Map<String, String> rows(Object... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], String.valueOf(kv[i + 1]));
        return m;
    }

    @Test public void catalogHasTheNineOwnerControls() {
        List<StyleControls.Control> all = StyleControls.all();
        assertEquals(9, all.size());
        for (StyleControls.Control c : all) {
            assertEquals("each control has 3 levels", 3, c.levelLabels.length);
            assertFalse(c.phrase(0).isEmpty());
            assertFalse(c.phrase(1).isEmpty());
            assertFalse(c.phrase(2).isEmpty());
            assertNotNull(StyleControls.byKey(c.key));
        }
        // flirting defaults to NEVER (safe default)
        assertEquals(0, StyleControls.defaultLevel("flirting"));
    }

    @Test public void contactOverrideWinsOverGlobalAndDefault() {
        int[] levels = StyleSettings.resolve(
            rows("emoji", "2", "length", "0"),      // global: plenty emoji, short
            rows("emoji", "0"));                    // contact: NO emoji
        assertEquals(0, levels[StyleControls.all().indexOf(StyleControls.EMOJI)]);
        assertEquals(0, levels[StyleControls.all().indexOf(StyleControls.LENGTH)]);   // inherited
        assertEquals(StyleControls.defaultLevel("humor"),
            levels[StyleControls.all().indexOf(StyleControls.HUMOR)]);                // default
    }

    @Test public void badValuesFallBackToInherit() {
        int[] levels = StyleSettings.resolve(rows("emoji", "bogus"), rows("emoji", "-9"));
        // bad global ignored; bad contact ignored → default
        assertEquals(StyleControls.defaultLevel("emoji"),
            levels[StyleControls.all().indexOf(StyleControls.EMOJI)]);
    }

    @Test public void voiceLineRendersChosenPhrases() {
        int[] levels = StyleSettings.resolve(rows("tone", "0", "emoji", "0"), null);
        String line = StyleSettings.renderVoiceLine(levels);
        assertTrue(line.startsWith("Voice: "));
        assertTrue(line.contains("warm and friendly"));
        assertTrue(line.contains("no emoji"));
        assertTrue(line.contains("never flirty"));        // safe default visible
        assertFalse(line.contains("plenty of emoji"));
    }

    @Test public void overrideNotesExplainEachEngagedOverride() {
        List<String> notes = StyleSettings.overrideNotes(
            rows("length", "2"), rows("length", "0", "emoji", "1"));
        String joined = notes.toString();
        assertTrue(joined.contains("Reply length: short (contact override of detailed)"));
        assertTrue(joined.contains("Emoji use: a few (contact, same as global)"));
        assertEquals(2, notes.size());
    }

    @Test public void customPromptIsTrimmedAndCapped() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append('x');
        assertEquals(StyleSettings.CUSTOM_PROMPT_MAX,
            StyleSettings.customPrompt(rows("custom.prompt", big.toString())).length());
        assertEquals("hi there", StyleSettings.customPrompt(rows("custom.prompt", "  hi there ")));
        assertEquals("", StyleSettings.customPrompt(null));
        assertEquals("", StyleSettings.customPrompt(rows()));
    }
}
