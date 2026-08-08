package com.replymate.core.learning;

import com.replymate.core.model.StyleSignal;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic learning rules (P4): edit classification, counters, hint thresholds. */
public class LearningEngineTest {

    /* --------------------------------------------------------- classification */

    @Test public void classifyEditDetectsShorterLongerTweakedNone() {
        String base = "Hey, I would love to come over this evening if that still works for you";
        assertEquals("none", LearningEngine.classifyEdit(base, base));
        assertEquals("shorter", LearningEngine.classifyEdit(base, "Hey, I'd love to come"));
        assertEquals("longer", LearningEngine.classifyEdit(base,
            base + ", and I can bring the charger you asked about yesterday"));
        assertEquals("tweaked", LearningEngine.classifyEdit(base,
            "Hey, I would love to come over this evening if that works for you still"));
    }

    @Test public void classifyEditDetectsEmojiDirection() {
        // Removing emoji also shrinks UTF-16 length (surrogates count as 2), so the
        // fixture must stay above the ≤0.70× "shorter" threshold to isolate emoji.
        assertEquals("emoji-down", LearningEngine.classifyEdit(
            "that is amazing news indeed my brother 🎉🔥🎉",
            "that is amazing news indeed my brother!"));
        assertEquals("emoji-up", LearningEngine.classifyEdit("great news", "great news 🎉"));
        assertEquals("shorter+emoji-down",
            LearningEngine.classifyEdit("great news everyone 🎉🎉🎉 here", "great 🎉🎉"));
    }

    @Test public void emojiCountHandlesSurrogatesAndCommonSingles() {
        assertEquals(2, LearningEngine.emojiCount("hi 🎉🔥"));
        assertEquals(0, LearningEngine.emojiCount("plain"));
        assertEquals(1, LearningEngine.emojiCount("star ⭐"));
    }

    /* --------------------------------------------------------------- counters */

    private static StyleSignal sig(StyleSignal.Kind kind, String detail) {
        StyleSignal s = new StyleSignal();
        s.kind = kind;
        s.detail = detail;
        return s;
    }

    @Test public void countersParseDetailTokens() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        signals.add(sig(StyleSignal.Kind.APPROVED, ""));
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter+emoji-down"));
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.REJECTED, ""));
        LearningEngine.Counters c = LearningEngine.count(signals);
        assertEquals(1, c.approved);
        assertEquals(2, c.edited);
        assertEquals(1, c.rejected);
        assertEquals(2, c.shorter);
        assertEquals(1, c.emojiDown);
        assertEquals(4, c.total());
    }

    /* --------------------------------------------------------- hint thresholds */

    @Test public void noHintsBelowMinimumSignals() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        assertTrue(LearningEngine.deriveHints(LearningEngine.count(signals)).isEmpty());
    }

    @Test public void shorterHintNeedsThreeDominantShorterEdits() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.APPROVED, ""));
        List<LearningEngine.Hint> hints = LearningEngine.deriveHints(LearningEngine.count(signals));
        assertEquals(1, hints.size());
        assertTrue(hints.get(0).line.contains("shorter"));
        assertTrue(hints.get(0).why.contains("3 of 3 edits"));
    }

    @Test public void splitEditsYieldNoLengthHint() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        signals.add(sig(StyleSignal.Kind.EDITED, "shorter"));
        signals.add(sig(StyleSignal.Kind.EDITED, "longer"));
        signals.add(sig(StyleSignal.Kind.EDITED, "longer"));
        signals.add(sig(StyleSignal.Kind.APPROVED, ""));
        for (LearningEngine.Hint h : LearningEngine.deriveHints(LearningEngine.count(signals))) {
            assertFalse(h.line.contains("shorter"));
        }
    }

    @Test public void emojiAndRegenAndRejectHints() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        for (int i = 0; i < 3; i++) signals.add(sig(StyleSignal.Kind.EDITED, "emoji-down"));
        List<LearningEngine.Hint> hints = LearningEngine.deriveHints(LearningEngine.count(signals));
        assertEquals(1, hints.size());
        assertTrue(hints.get(0).line.contains("emoji"));

        signals.clear();
        for (int i = 0; i < 4; i++) signals.add(sig(StyleSignal.Kind.REGENERATED, ""));
        hints = LearningEngine.deriveHints(LearningEngine.count(signals));
        String regen = "";
        for (LearningEngine.Hint h : hints) regen += h.line + "\n";
        assertTrue(regen.contains("vary the wording"));

        signals.clear();
        for (int i = 0; i < 3; i++) signals.add(sig(StyleSignal.Kind.REJECTED, ""));
        hints = LearningEngine.deriveHints(LearningEngine.count(signals));
        String reject = "";
        for (LearningEngine.Hint h : hints) reject += h.line + "\n";
        assertTrue(reject.contains("conservative"));
    }

    @Test public void consistentApprovalsYieldPositiveSignal() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        for (int i = 0; i < 5; i++) signals.add(sig(StyleSignal.Kind.APPROVED, ""));
        List<LearningEngine.Hint> hints = LearningEngine.deriveHints(LearningEngine.count(signals));
        assertEquals(1, hints.size());
        assertTrue(hints.get(0).line.contains("consistent"));
    }

    /* --------------------------------- P-intelligence-3: provenance counters */

    @Test public void countersExposeCopiedQuickAndManualProvenance() {
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        signals.add(sig(StyleSignal.Kind.APPROVED, "copied-as-is"));
        signals.add(sig(StyleSignal.Kind.APPROVED, "copied-as-is"));
        signals.add(sig(StyleSignal.Kind.APPROVED, "sent-quick-reply"));
        signals.add(sig(StyleSignal.Kind.APPROVED, "sent-manually-same-words"));
        signals.add(sig(StyleSignal.Kind.APPROVED, ""));
        signals.add(sig(StyleSignal.Kind.EDITED, "manual:shorter"));
        signals.add(sig(StyleSignal.Kind.EDITED, "tweaked"));
        LearningEngine.Counters c = LearningEngine.count(signals);
        assertEquals(5, c.approved);
        assertEquals(2, c.copiedAsIs);
        assertEquals(1, c.quickSent);
        assertEquals(1, c.manualMatched);
        assertEquals(1, c.manualCorrected);
        assertEquals(2, c.edited);
        assertEquals(2, c.manualTotal());
        assertEquals(7, c.total());
    }

    @Test public void provenanceNeverChangesHintThresholds() {
        // copied-as-is approvals are REAL approvals for hint purposes (unchanged rule)
        List<StyleSignal> signals = new ArrayList<StyleSignal>();
        for (int i = 0; i < 5; i++) {
            signals.add(sig(StyleSignal.Kind.APPROVED, "copied-as-is"));
        }
        LearningEngine.Counters c = LearningEngine.count(signals);
        assertEquals(5, c.approved);
        assertEquals(5, c.copiedAsIs);
        assertEquals(1, LearningEngine.deriveHints(c).size());
        assertTrue(LearningEngine.deriveHints(c).get(0).line.contains("consistent"));
        // null details stay safe
        StyleSignal bare = new StyleSignal();
        bare.kind = StyleSignal.Kind.APPROVED;
        bare.detail = null;
        LearningEngine.Counters nc = LearningEngine.count(
            java.util.Collections.singletonList(bare));
        assertEquals(1, nc.approved);
        assertEquals(0, nc.copiedAsIs);
    }
}
