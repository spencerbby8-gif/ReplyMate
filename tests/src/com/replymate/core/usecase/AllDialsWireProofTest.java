package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.style.StyleControls;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-14, topic 3 (owner mandate): EVERY tone/customization control
 *  must actually reach generation — all NINE dials, not a sampled pair. Each
 *  dial is fired through the real {@link DraftService#generateForContact} entry
 *  point and asserted on the EXACT ChatRequest handed to the provider:
 *    • a global level change is visible on the wire (and changes what the model sees)
 *    • OFF removes every phrase of that dimension and ONLY that dimension
 *    • a per-contact override beats the global level — with zero leakage to others
 *  (UI→stored-value is pinned by Settings/Edit-page flows; request→OUTPUT
 *  variation end-to-end is pinned by VoicePromptProofTest's echo provider.) */
public final class AllDialsWireProofTest {

    private static final class Fx {
        final Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        final Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        final Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        final Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        final Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        final Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        ProfileService profiles;
        LearningService learning;
        Fakes.FakeProvider provider;
        DraftService service;
    }

    private Fx fixture() {
        Fx f = new Fx();
        f.profiles = new ProfileService(f.kv);
        f.learning = Fakes.learningService(new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.provider = Fakes.FakeProvider.returning("a reply");
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            f.profiles, f.drafts, f.usage, new Fakes.GatewayFake(f.provider), Fakes.IDS,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG, Fakes.styleService(f.settings, f.learning),
            f.learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), f.messages, f.kv, Fakes.FIXED_CLOCK));
        return f;
    }

    private void seed(Fx f, long id) {
        f.contacts.put(Fakes.contact(id, "Contact" + id));
        f.messages.add(Fakes.msg(id, Direction.INCOMING, "you still coming tonight?"));
    }

    private static String generate(Fx f, long id) {
        Result<DraftOutcome> r = f.service.generateForContact(id);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        return f.provider.lastRequest.system;
    }

    /* 1 ─ every dial: a global level change is visible on the wire, end to end. */
    @Test public void everyDialReachesTheWireThroughRealGeneration() {
        for (StyleControls.Control ctl : StyleControls.all()) {
            Fx a = fixture();
            seed(a, 1);
            a.settings.put(null, ctl.key, "0");
            String sys0 = generate(a, 1);
            assertTrue(ctl.key + " level-0 phrase must reach the provider",
                sys0.contains(ctl.phrase(0)));

            Fx b = fixture();
            seed(b, 1);
            b.settings.put(null, ctl.key, "2");
            String sys2 = generate(b, 1);
            assertTrue(ctl.key + " level-2 phrase must reach the provider",
                sys2.contains(ctl.phrase(2)));
            assertFalse(ctl.key + " must not carry the old level after the switch",
                sys2.contains(ctl.phrase(0)));
            assertFalse(ctl.key + ": the provider-request must actually change",
                sys2.equals(sys0));
        }
    }

    /* 2 ─ every dial: OFF is surgical, and a contact override beats global. */
    @Test public void offStripsAndContactOverrideWinsForEveryDial() {
        for (StyleControls.Control ctl : StyleControls.all()) {
            // OFF removes EVERY phrase of the dimension — and only that dimension
            Fx f = fixture();
            seed(f, 1);
            f.settings.put(null, ctl.key, String.valueOf(StyleControls.LEVEL_OFF));
            String sys = generate(f, 1);
            for (int lvl = 0; lvl <= 2; lvl++) {
                assertFalse(ctl.key + " OFF must not leak its level-" + lvl + " phrase",
                    sys.contains(ctl.phrase(lvl)));
            }
            StyleControls.Control sentinel =
                ctl == StyleControls.TONE ? StyleControls.LENGTH : StyleControls.TONE;
            assertTrue(ctl.key + " OFF must leave the other dials on the wire",
                sys.contains(sentinel.phrase(StyleControls.defaultLevel(sentinel.key))));

            // contact override beats global; other contacts still follow global
            Fx g = fixture();
            seed(g, 1);
            seed(g, 2);
            g.settings.put(null, ctl.key, "0");
            g.settings.put(Long.valueOf(1L), ctl.key, "2");
            String over = generate(g, 1);
            assertTrue(ctl.key + ": the contact override phrase is on the wire",
                over.contains(ctl.phrase(2)));
            assertFalse(ctl.key + ": global must not override the contact",
                over.contains(ctl.phrase(0)));
            String other = generate(g, 2);
            assertTrue(ctl.key + ": other contacts still follow global",
                other.contains(ctl.phrase(0)));
            assertFalse(ctl.key + ": the override must not leak to other contacts",
                other.contains(ctl.phrase(2)));
        }
    }
}
