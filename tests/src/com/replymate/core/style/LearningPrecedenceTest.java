package com.replymate.core.style;

import com.replymate.core.learning.LearningEngine;
import com.replymate.core.learning.LearningService;
import com.replymate.core.learning.StyleProfiler;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.fakes.Fakes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-2 (items 4/8): EXPLICIT contact voice settings must beat learned
 *  guesses for the same dimension — in BOTH learned layers (signal hints and the
 *  approved-text style profiler) — and the suppression itself must appear in the
 *  audit trail, never a hint shown as applied when it did not reach the prompt. */
public final class LearningPrecedenceTest {

    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake store;
    private Fakes.KvStoreFake kv;
    private LearningService learning;
    private StyleService style;

    @Before public void setUp() {
        settings = new Fakes.StyleSettingStoreFake();
        store = new Fakes.LearningStoreFake();
        kv = new Fakes.KvStoreFake();
        learning = Fakes.learningService(store, kv);
        style = Fakes.styleService(settings, learning);
    }

    private static Contact ada() { return Fakes.contact(1, "Ada"); }

    private static void signal(Contact c, LearningService svc, StyleSignal.Kind kind,
                               String detail) {
        svc.record(c, kind, detail, null);
    }

    @Test public void explicitLengthSuppressesTheLearnedLengthHintWithAuditCredit() {
        Contact c = ada();
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");

        StyleService.ComposedVoice free = style.compose(c);
        assertTrue(join(free.extraLines).contains("keep replies noticeably shorter"));
        assertTrue(join(free.why).contains("learned: keep replies noticeably shorter"));

        settings.put(1L, "length", "2");   // owner explicitly chose the length here
        StyleService.ComposedVoice locked = style.compose(c);
        assertFalse("the learned guess must not reach the prompt against an explicit rule",
            join(locked.extraLines).contains("keep replies noticeably shorter"));
        String why = join(locked.why);
        assertTrue("the suppression itself is visible in the audit",
            why.contains("learned hint suppressed")
                && why.contains("explicit length setting for Ada wins"));
    }

    @Test public void explicitEmojiSuppressesTheLearnedEmojiHint() {
        Contact c = ada();
        signal(c, learning, StyleSignal.Kind.EDITED, "emoji-down");
        signal(c, learning, StyleSignal.Kind.EDITED, "emoji-down");
        signal(c, learning, StyleSignal.Kind.EDITED, "emoji-down");
        assertTrue(join(style.compose(c).extraLines).contains("skip emoji here"));

        settings.put(1L, "emoji", "0");
        assertFalse(join(style.compose(c).extraLines).contains("skip emoji here"));
    }

    @Test public void globalExplicitDoesNotSuppressContactLearning() {
        // the owner's global voice is the BASE; contact-level learning adapts from
        // it on purpose (that's the per-contact adaptation promise).
        Contact c = ada();
        settings.put(null, "length", "2");
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");
        signal(c, learning, StyleSignal.Kind.EDITED, "shorter");
        assertTrue(join(style.compose(c).extraLines)
            .contains("keep replies noticeably shorter"));
    }

    @Test public void hintControlTagsAreCorrect() {
        LearningEngine.Counters c = new LearningEngine.Counters();
        c.edited = 3; c.shorter = 3; c.emojiDown = 3; c.approved = 1;
        List<LearningEngine.Hint> hints = LearningEngine.deriveHints(c);
        boolean sawLength = false, sawEmoji = false;
        for (LearningEngine.Hint h : hints) {
            if (h.line.contains("shorter")) { sawLength = true;
                assertEquals("length", h.control); }
            if (h.line.contains("skip emoji")) { sawEmoji = true;
                assertEquals("emoji", h.control); }
        }
        assertTrue(sawLength && sawEmoji);
    }

    @Test public void styleProfilerLengthLineYieldsToExplicitLength() {
        Contact c = ada();
        MemoryService mem = new MemoryService(
            new Fakes.MemoryStoreFake(), new Fakes.MessageStoreFake(), kv,
            Fakes.FIXED_CLOCK);
        List<String> shortReplies =
            Arrays.asList("ok", "sure", "no wahala", "omw", "later");

        MemoryService.Recall open =
            mem.withLearnedStyle(null, c, shortReplies, new HashSet<String>());
        assertTrue(join(open.lines).contains("keep it short"));

        MemoryService.Recall locked =
            mem.withLearnedStyle(null, c, shortReplies,
                new HashSet<String>(Arrays.asList("length")));
        assertFalse("explicit length silences the learned length rule",
            join(locked.lines).contains("keep it short"));
        assertTrue(join(locked.why).contains("learned style suppressed"));
        // and un-setting the explicit control restores it on the NEXT call
        // (cache holds the full derivation; suppression is per-call)
        MemoryService.Recall openAgain =
            mem.withLearnedStyle(null, c, shortReplies, new HashSet<String>());
        assertTrue(join(openAgain.lines).contains("keep it short"));
    }

    @Test public void learnedStyleCacheIsVersionedV2WithControlTags() {
        assertTrue(MemoryService.styleKey(7).endsWith(".v2"));
        Contact c = Fakes.contact(7, "Uche");
        MemoryService mem = new MemoryService(
            new Fakes.MemoryStoreFake(), new Fakes.MessageStoreFake(), kv,
            Fakes.FIXED_CLOCK);
        mem.learnedStyleLines(c, Arrays.asList("a", "b", "c", "d"));
        String cached = kv.get(MemoryService.styleKey(7), "");
        assertFalse("full tagged derivation is cached for restart-proof replay",
            cached.isEmpty());
        assertTrue("cache lines carry control tags", cached.contains("\nlength"));
        // …and identical lines come back from the cache (deterministic replay)
        assertEquals(mem.learnedStyleLines(c, Arrays.asList("a", "b", "c", "d")),
            mem.learnedStyleLines(c, Arrays.asList("a", "b", "c", "d")));
    }

    @Test public void profilerTagsLengthAndEmojiOnly() {
        List<StyleProfiler.Derived> d = StyleProfiler.derive(
            Arrays.asList("ok", "sure", "no wahala", "omw", "later"));
        boolean lengthTagged = false;
        for (StyleProfiler.Derived x : d) {
            if (x.line.startsWith("keep it short")) {
                lengthTagged = "length".equals(x.control);
            }
        }
        assertTrue(lengthTagged);
        List<StyleProfiler.Derived> emoji = StyleProfiler.derive(
            Arrays.asList("ok fine", "sure thing", "no wahala at all", "way too long line here "
                + "and then some more to be long", "another long reply just to pass the "
                + "average length gate entirely"));
        for (StyleProfiler.Derived x : emoji) {
            if (x.line.startsWith("no emoji")) assertEquals("emoji", x.control);
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) sb.append(x).append('\n');
        return sb.toString();
    }
}
