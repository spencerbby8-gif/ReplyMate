package com.replymate.core.learning;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;

import static org.junit.Assert.*;

/** P22 §2 (owner directive): the personal-writing profile must learn from
 *  USER-PROVIDED SAMPLES — "messages the user wrote themselves" — in addition to
 *  approved-edited-rejected feedback. Pins:
 *    • local kv storage, bounded + deduped, per-contact isolation
 *    • the SAME gate as every other learning write (private mode hard-blocks)
 *    • reset/clear actually clear
 *    • end-to-end: samples alone (zero approved drafts) shape the REAL system
 *      prompt via the StyleProfiler, and clearing them re-derives honestly
 *      (content-hashed v3 cache — same-count edits cannot go stale). */
public final class WritingSamplesTest {

    private LearningService learning;
    private Fakes.KvStoreFake kv;

    private LearningService svc() {
        kv = new Fakes.KvStoreFake();
        learning = Fakes.learningService(new Fakes.LearningStoreFake(), kv);
        return learning;
    }

    @Test public void addListDedupeCapAndRefusals() {
        LearningService l = svc();
        Contact c = Fakes.contact(1, "Amara");
        assertNull(l.addSample(c, "  omw now, gimme 5  "));       // trimmed
        assertNull(l.addSample(c, "lol you already know"));
        assertEquals(2, l.samplesFor(1).size());
        assertEquals("omw now, gimme 5", l.samplesFor(1).get(0));

        assertNotNull("exact duplicates are refused", l.addSample(c, "omw now, gimme 5"));
        assertNotNull("empty is refused", l.addSample(c, "   "));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < LearningService.MAX_SAMPLE_CHARS + 1; i++) big.append('x');
        assertNotNull("over-length is refused", l.addSample(c, big.toString()));
        assertEquals(2, l.samplesFor(1).size());

        for (int i = 0; i < LearningService.MAX_SAMPLES - 2; i++) {
            assertNull(l.addSample(c, "sample number " + i));
        }
        assertNotNull("the cap is enforced", l.addSample(c, "one too many"));
        assertEquals(LearningService.MAX_SAMPLES, l.samplesFor(1).size());

        assertEquals("per-contact isolation", 0, l.samplesFor(2).size());
    }

    @Test public void theLearningGateHardBlocksSamplesToo() {
        LearningService l = svc();
        Contact priv = Fakes.contact(1, "Priv");
        priv.privateMode = true;
        assertNotNull(l.addSample(priv, "secret"));
        assertTrue(l.samplesFor(1).isEmpty());

        Contact memOff = Fakes.contact(2, "MemOff");
        memOff.memoryEnabled = false;
        assertNotNull(l.addSample(memOff, "nothing stored"));
        assertTrue(l.samplesFor(2).isEmpty());

        Contact off = Fakes.contact(3, "Off");
        l.setOff(3, true);
        assertNotNull(l.addSample(off, "ignored"));
        assertTrue(l.samplesFor(3).isEmpty());

        Contact paused = Fakes.contact(4, "Paused");
        l.setPaused(4, true);
        assertNotNull(l.addSample(paused, "frozen"));
        assertTrue(l.samplesFor(4).isEmpty());
    }

    @Test public void clearAndResetActuallyWipe() {
        LearningService l = svc();
        Contact c = Fakes.contact(1, "Amara");
        assertNull(l.addSample(c, "one"));
        l.clearSamples(1);
        assertTrue(l.samplesFor(1).isEmpty());

        assertNull(l.addSample(c, "two"));
        l.reset(1);                          // reset = "clear what has been learned"
        assertTrue("reset wipes samples too", l.samplesFor(1).isEmpty());
    }

    @Test public void corruptedStorageFailsSafe() {
        LearningService l = svc();
        kv.put("learn.1.samples", "not-json{{");
        assertTrue(l.samplesFor(1).isEmpty());   // no throw, self-heals
        Contact c = Fakes.contact(1, "Amara");
        assertNull(l.addSample(c, "fresh start"));
        assertEquals(1, l.samplesFor(1).size());
    }

    /* ── wire level: samples alone drive the REAL prompt ────────────────── */

    @Test public void samplesAloneShapeTheRealSystemPrompt_AndClearingReDerives() {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.KvStoreFake kv2 = new Fakes.KvStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        LearningService learning = Fakes.learningService(new Fakes.LearningStoreFake(), kv2);
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("a reply");
        DraftService service = new DraftService(contacts, messages,
            new Fakes.StyleStoreFake(), new ProfileService(kv2), drafts,
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(provider), Fakes.IDS,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv2, Fakes.FIXED_CLOCK));

        Contact c = Fakes.contact(1, "Amara");
        contacts.put(c);
        messages.add(Fakes.msg(1, Direction.INCOMING, "you still coming tonight?"));

        // no approved drafts at all — ONLY owner-provided samples (all lowercase,
        // no trailing full stop, short)
        assertNull(learning.addSample(c, "omw now gimme 5"));
        assertNull(learning.addSample(c, "lol you already know how it goes"));
        assertNull(learning.addSample(c, "i'm not coming today abeg"));

        Result<DraftOutcome> r = service.generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String sys = provider.lastRequest.system;
        assertTrue("sample-derived lowercase rule reaches the provider",
            sys.contains("start lowercase like your own messages in this chat"));
        assertTrue("sample-derived punctuation rule reaches the provider",
            sys.contains("usually skip the final full stop"));

        // clearing samples re-derives honestly — the derived lines disappear
        learning.clearSamples(1);
        Result<DraftOutcome> r2 = service.generateForContact(1);
        assertTrue(String.valueOf(r2.ok ? "" : r2.error), r2.ok);
        assertFalse("cleared samples must stop shaping the prompt",
            provider.lastRequest.system.contains("start lowercase like your own"));

        // same-count swap: 3 new Capitalised samples must ALSO re-derive
        // (the v3 content-hash head — count alone would have gone stale)
        assertNull(learning.addSample(c, "Yes I am on my way."));
        assertNull(learning.addSample(c, "Sure, give me five minutes."));
        assertNull(learning.addSample(c, "That works for me."));
        Result<DraftOutcome> r3 = service.generateForContact(1);
        assertTrue(String.valueOf(r3.ok ? "" : r3.error), r3.ok);
        assertFalse("same-count sample edits must not serve a stale derivation",
            provider.lastRequest.system.contains("start lowercase like your own"));
    }
}
