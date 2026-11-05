package com.replymate.core.prompt;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.style.StyleControls;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;

import static org.junit.Assert.*;

/** P22 root-cause fix (owner directive: "fix cases where the tone system says one
 *  thing and the generation rules say another"). Confirmed defect: the hard Rules
 *  clause "usually 1–3 short sentences unless the context clearly needs more" was
 *  emitted UNCONDITIONALLY — contradicting LENGTH=detailed ("allow fuller, more
 *  detailed replies when the topic needs it") and making LENGTH=OFF a documented
 *  lie (OFF = "omitted from the prompt at all", yet the Rules still constrained
 *  length). These tests pin the repaired contract at two layers:
 *    1) composer level — the clause is derived from the EFFECTIVE length level;
 *    2) wire level — a REAL DraftService generation proves the dial now controls
 *       the Rules clause end to end (global + contact-override precedence). */
public final class LengthRulesConsistencyTest {

    private static final String LEGACY_CLAUSE =
        "usually 1–3 short sentences unless the context clearly needs more";
    private static final String SHORT_CLAUSE =
        "usually one short sentence unless the context clearly needs more";
    private static final String NEXT_RULE =
        "when the honest reply is to disagree";

    private static Contact contact() {
        Contact c = new Contact();
        c.displayName = "Amara";
        return c;
    }

    private static String compose(int lengthLevel) {
        return SystemComposer.compose(
            new ProfileService.Profile("Kelechi", "English", "", ""),
            contact(), "", "", null, "", null, null, lengthLevel);
    }

    /* ── composer level ─────────────────────────────────────────────── */

    @Test public void defaultAndUnspecifiedKeepTheLegacyClauseByteIdentical() {
        String unspecified = compose(-1);
        String explicitShort = compose(1);
        assertTrue(unspecified.contains(LEGACY_CLAUSE));
        assertTrue(explicitShort.contains(LEGACY_CLAUSE));
        // byte position is pinned too: clause sits between "'s voice;" and the
        // pushback rule with exactly the historic spacing
        assertTrue(unspecified.contains("'s voice; " + LEGACY_CLAUSE + "; " + NEXT_RULE));
    }

    @Test public void veryShortTightensTheClauseToOneSentence() {
        String s = compose(0);
        assertTrue(s.contains(SHORT_CLAUSE));
        assertFalse("level-0 must not carry the looser 1–3 clause",
            s.contains(LEGACY_CLAUSE));
        assertTrue(s.contains(NEXT_RULE));   // the remaining rules are intact
    }

    @Test public void detailedEmitsNoLengthRuleAtAll() {
        String s = compose(2);
        assertFalse("detailed must not be squeezed by a short-reply rule",
            s.contains(LEGACY_CLAUSE));
        assertFalse(s.contains(SHORT_CLAUSE));
        assertFalse("no length wording may survive anywhere in Rules",
            s.contains("sentences unless the context"));
        assertTrue("the rest of the Rules block survives", s.contains(NEXT_RULE));
    }

    @Test public void offHonorsTheContract_NoLengthInstructionAnywhere() {
        String s = compose(StyleControls.LEVEL_OFF);
        assertFalse("OFF = omitted from the prompt at all — the Rules clause lied",
            s.contains(LEGACY_CLAUSE));
        assertFalse(s.contains(SHORT_CLAUSE));
        assertFalse(s.contains("sentences unless the context"));
        assertTrue(s.contains(NEXT_RULE));
    }

    /* ── wire level: real DraftService generation ───────────────────── */

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

    private static Fx fixture() {
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

    private static void seed(Fx f, long id) {
        f.contacts.put(Fakes.contact(id, "Contact" + id));
        f.messages.add(Fakes.msg(id, Direction.INCOMING, "you still coming tonight?"));
    }

    private static String generate(Fx f, long id) {
        Result<DraftOutcome> r = f.service.generateForContact(id);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        return f.provider.lastRequest.system;
    }

    @Test public void globalLengthDialDrivesTheRulesClauseOnTheWire() {
        // default (no rows): legacy clause on the wire
        Fx def = fixture();
        seed(def, 1);
        assertTrue(generate(def, 1).contains(LEGACY_CLAUSE));

        // OFF: no length rule reaches the provider — the dial no longer lies
        Fx off = fixture();
        seed(off, 1);
        off.settings.put(null, "length", String.valueOf(StyleControls.LEVEL_OFF));
        String sysOff = generate(off, 1);
        assertFalse(sysOff.contains(LEGACY_CLAUSE));
        assertFalse(sysOff.contains(SHORT_CLAUSE));

        // detailed: same — no short-reply constraint may reach the provider
        Fx full = fixture();
        seed(full, 1);
        full.settings.put(null, "length", "2");
        String sysFull = generate(full, 1);
        assertFalse(sysFull.contains(LEGACY_CLAUSE));
        assertTrue("the voice line still carries the detailed phrase",
            sysFull.contains(StyleControls.byKey("length").phrase(2)));

        // very short: the tightened clause reaches the provider
        Fx vs = fixture();
        seed(vs, 1);
        vs.settings.put(null, "length", "0");
        assertTrue(generate(vs, 1).contains(SHORT_CLAUSE));
    }

    @Test public void contactOverrideFollowsTheSamePrecedenceAsTheVoiceLine() {
        Fx f = fixture();
        seed(f, 1);
        seed(f, 2);
        f.settings.put(null, "length", "1");                       // global: short
        f.settings.put(Long.valueOf(1L), "length", "2");           // contact 1: detailed
        String over = generate(f, 1);
        assertFalse("contact override must lift the short-reply rule", over.contains(LEGACY_CLAUSE));
        String other = generate(f, 2);
        assertTrue("other contacts still follow the global dial", other.contains(LEGACY_CLAUSE));
    }

    /* ── P22 previews: realistic sample conversations, same contract ── */

    @Test public void previewScenariosAreDistinctRealisticConversationsOnTheWire() {
        Fx f = fixture();
        String[] seen = new String[DraftService.scenarioCount()];
        assertTrue("previews must offer more than one sample conversation",
            DraftService.scenarioCount() >= 3);
        for (int i = 0; i < DraftService.scenarioCount(); i++) {
            Result<com.replymate.core.ai.ChatReply> r =
                f.service.previewVoice(-1, i);
            assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
            // the task turn quotes the answered message — distinct per scenario
            seen[i] = f.provider.lastRequest.task.text;
            for (int j = 0; j < i; j++) {
                assertFalse("scenario " + i + " must differ from scenario " + j,
                    seen[i].equals(seen[j]));
            }
        }
        // every scenario is a back-and-forth rendering the UI can display
        for (int i = 0; i < DraftService.scenarioCount(); i++) {
            assertFalse(DraftService.scenarioLabel(i).isEmpty());
        }
        assertFalse("multi-line scenarios must render both speakers",
            DraftService.scenarioLabel(1).indexOf('\n') < 0);
    }

    @Test public void previewMirrorsTheLengthDialRealContract() {
        Fx f = fixture();
        f.settings.put(null, "length", String.valueOf(StyleControls.LEVEL_OFF));
        Result<com.replymate.core.ai.ChatReply> r = f.service.previewVoice(-1, 0);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertFalse("the live preview must show what the dial really does",
            f.provider.lastRequest.system.contains(LEGACY_CLAUSE));
    }
}
