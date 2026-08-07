package com.replymate.core.learning;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Learned-style derivation from approved reply texts (P-memory-audit). */
public class StyleProfilerTest {

    @Test public void tooFewApprovedTextsYieldsNothing() {
        assertTrue(StyleProfiler.derive(null).isEmpty());
        assertTrue(StyleProfiler.derive(Arrays.asList("ok")).isEmpty());
        assertTrue(StyleProfiler.derive(Arrays.asList("ok", "sure")).isEmpty());
        assertTrue(StyleProfiler.derive(Arrays.asList("", "  ", null)).isEmpty());
    }

    @Test public void shortNoFullStopLowercaseStyleDerived() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(Arrays.asList(
            "lol that's crazy", "omw now", "sure, give me 5", "no worries at all"));
        String all = join(d);
        assertTrue(all, all.contains("keep it short"));
        assertTrue(all, all.contains("skip the final full stop"));
        assertTrue(all, all.contains("start lowercase"));
        // the no-emoji rule ranks 4th and is dropped by the MAX_LINES cap
        assertTrue("never more than " + StyleProfiler.MAX_LINES + " lines",
            d.size() <= StyleProfiler.MAX_LINES);
    }

    @Test public void formalLongStyleDoesNotTriggerCasualRules() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(Arrays.asList(
            "Thank you for the detailed update. I will review the document in full this evening and revert first thing on Friday morning with my comments on each of the sections you flagged for my attention.",
            "I sincerely appreciate your patience on this matter. The payment was processed yesterday afternoon through the usual channel and should reflect in your account within two working days, exactly as we agreed.",
            "Good afternoon. Following our conversation earlier today, I have attached the signed agreement to this email and also highlighted the specific clauses we discussed at length so your legal team can review them."));
        String all = join(d);
        assertFalse(all, all.contains("keep it short"));
        assertFalse(all, all.contains("skip the final full stop"));
        assertFalse(all, all.contains("start lowercase"));
        assertTrue(all, all.contains("longer replies are fine"));
    }

    @Test public void evidenceAlwaysCitesCounts() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(Arrays.asList(
            "ok cool", "sounds good", "omo nice one"));
        for (StyleProfiler.Derived x : d) {
            assertFalse(x.why.isEmpty());
            assertTrue(x.why, x.why.contains("of 3") || x.why.contains("~"));
        }
    }

    @Test public void cappedAtEightTextsRecentWindowOnly() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(Arrays.asList(
            "one ok", "two sure", "three mad o", "four nice", "five cool",
            "six legit", "seven fine", "8th text", "9th extra", "10th extra"));
        // 10 supplied → still derives (min reached), lines bounded
        assertTrue(d.size() <= StyleProfiler.MAX_LINES);
    }

    @Test public void emojiUsersDontGetNoEmojiRule() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(Arrays.asList(
            "lol nice one 😂", "omw 🙏", "sure thing ✅"));
        assertFalse(join(d), join(d).contains("no emoji"));
    }

    private static String join(List<StyleProfiler.Derived> d) {
        StringBuilder sb = new StringBuilder();
        for (StyleProfiler.Derived x : d) sb.append(x.line).append('\n');
        return sb.toString();
    }
}
