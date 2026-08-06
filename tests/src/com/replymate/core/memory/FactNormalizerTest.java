package com.replymate.core.memory;

import org.junit.Test;
import static org.junit.Assert.*;

/** text_norm is THE merge key — folding rules are contract, not cosmetics. */
public class FactNormalizerTest {

    @Test public void foldsCasePunctuationAndWhitespace() {
        assertEquals("amara hates voice notes",
            FactNormalizer.normalize("  Amara HATES  voice-notes!! "));
        assertEquals("loves tea", FactNormalizer.normalize("Loves: tea."));
        assertEquals("apostrophes and @ fold to spaces as designed",
            "i m free 7", FactNormalizer.normalize("I'm free @ 7"));
    }

    @Test public void equivalentTextsShareOneKey() {
        assertEquals(FactNormalizer.normalize("Amara likes tea"),
            FactNormalizer.normalize("amara LIKES TEA"));
        assertEquals(FactNormalizer.normalize("meeting on monday"),
            FactNormalizer.normalize("Meeting — on — Monday"));
    }

    @Test public void emptyAndPunctuationOnlyNormalizeToEmpty() {
        assertEquals("", FactNormalizer.normalize(null));
        assertEquals("", FactNormalizer.normalize("   "));
        assertEquals("", FactNormalizer.normalize("--- !!! ---"));
    }

    @Test public void clamps() {
        assertEquals(1, FactNormalizer.clampImportance(-3));
        assertEquals(5, FactNormalizer.clampImportance(9));
        assertEquals(3, FactNormalizer.clampImportance(3));
        assertEquals(0.0, FactNormalizer.clampConfidence(-0.5), 1e-9);
        assertEquals(1.0, FactNormalizer.clampConfidence(1.7), 1e-9);
        assertEquals(0.7, FactNormalizer.clampConfidence(0.7), 1e-9);
    }
}
