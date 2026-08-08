package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.style.StyleControls;
import com.replymate.core.style.StyleService;
import com.replymate.core.style.StyleSettings;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * P-background-7: CUSTOMIZATION IS CORE — every user-controlled setting must
 * actually reach the final provider prompt (with contact isolation) and be visible
 * in Prompt Audit. These pins audit: the 9 voice controls, My Voice instruction,
 * per-contact overrides, About Them, contact custom instructions, learned style,
 * memory, and the audit trail itself. If a control ever silently stops affecting
 * output, these tests fail before a user does.
 */
public class CustomizationEffectTest {

    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake lstore;
    private StyleService style;

    @Before public void setUp() {
        settings = new Fakes.StyleSettingStoreFake();
        lstore = new Fakes.LearningStoreFake();
        LearningService learning = Fakes.learningService(lstore, new Fakes.KvStoreFake());
        style = Fakes.styleService(settings, learning);
    }

    private static ProfileService.Profile profile() {
        return new ProfileService.Profile("Kelechi", "English, Pidgin", "", "");
    }

    private static StyleService learningStyle(Fakes.StyleSettingStoreFake s,
                                              Fakes.LearningStoreFake l) {
        return Fakes.styleService(s, Fakes.learningService(l, new Fakes.KvStoreFake()));
    }

    private ChatRequest buildFor(Contact c, List<String> memoryLines) {
        StyleService.ComposedVoice v = style.compose(c);
        return PromptBuilder.build(new PromptBundle(
            profile(), c, "", new ArrayList<com.replymate.core.model.Message>(),
            v.voiceLine, v.extraLines, "", memoryLines));
    }

    /* ------------------------------------------------ the 9 voice controls */

    @Test public void everyOneOfTheNineControlsReachesThePrompt() {
        int tested = 0;
        for (StyleControls.Control ctl : StyleControls.all()) {
            int def = StyleControls.defaultLevel(ctl.key);
            for (int lvl : new int[] {0, 1, 2}) {
                if (lvl == def) continue;
                settings.put(null, ctl.key, String.valueOf(lvl));
                StyleService.ComposedVoice v = style.compose(Fakes.contact(1, "Ada"));
                assertTrue(ctl.key + " L" + lvl + " must phrase the voice line",
                    v.voiceLine.contains(ctl.phrase(lvl)));
                ChatRequest req = PromptBuilder.build(new PromptBundle(
                    profile(), Fakes.contact(1, "Ada"), "",
                    new ArrayList<com.replymate.core.model.Message>(),
                    v.voiceLine, v.extraLines, ""));
                assertTrue(ctl.key + " L" + lvl + " must reach the provider prompt",
                    req.system.contains(ctl.phrase(lvl)));
                tested++;
            }
            settings.remove(null, ctl.key);
        }
        assertEquals("two non-default levels per control × 9 controls", 18, tested);
    }

    @Test public void myVoiceGlobalCustomInstructionLandsInPromptAndAudit() {
        settings.put(null, StyleSettings.CUSTOM_PROMPT_KEY, "always open with the person's first name");
        Contact c = Fakes.contact(1, "Ada");
        StyleService.ComposedVoice v = style.compose(c);
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile(), c, "", new ArrayList<com.replymate.core.model.Message>(),
            v.voiceLine, v.extraLines, ""));
        assertTrue(req.system.contains("always open with the person's first name"));
        String why = join(v.why);
        assertTrue("Prompt Audit must credit the instruction:\n" + why,
            why.contains("global custom instruction applied"));
    }

    /* ------------------------------------------------ per-contact overrides */

    @Test public void contactVoiceOverrideChangesOutputForThatContactOnly() {
        settings.put(1L, "tone", "2");   // Ada → direct
        Contact a = Fakes.contact(1, "Ada");
        Contact b = Fakes.contact(2, "Bode");
        ChatRequest ra = buildFor(a, null);
        ChatRequest rb = buildFor(b, null);
        assertTrue("override phrase in Ada's prompt",
            ra.system.contains(StyleControls.TONE.phrase(2)));
        assertFalse("override must not leak to Bode",
            rb.system.contains(StyleControls.TONE.phrase(2)));
        StyleService.ComposedVoice va = style.compose(a);
        assertTrue("audit names the override:\n" + join(va.why),
            join(va.why).contains("Tone") && join(va.why).contains("contact override"));
    }

    @Test public void aboutThemFieldsReachThePrompt() {
        Contact c = Fakes.contact(1, "Ada");
        c.relationshipType = "close friend";
        c.relationshipNotes = "met at uni, loves jollof rice";
        c.toneOverride = "playful";
        c.languagePref = "Pidgin";
        ChatRequest req = buildFor(c, null);
        assertTrue(req.system.contains("close friend"));
        assertTrue(req.system.contains("met at uni, loves jollof rice"));
        assertTrue(req.system.contains("playful"));
        assertTrue(req.system.contains("Pidgin"));
    }

    @Test public void contactCustomInstructionAppliesToThatContactOnly() {
        settings.put(1L, StyleSettings.CUSTOM_PROMPT_KEY, "never use the word busy");
        ChatRequest ra = buildFor(Fakes.contact(1, "Ada"), null);
        ChatRequest rb = buildFor(Fakes.contact(2, "Bode"), null);
        assertTrue(ra.system.contains("never use the word busy"));
        assertFalse(rb.system.contains("never use the word busy"));
    }

    /* ------------------------------------------------ P-background-8: rules matter */

    @Test public void contactRulesAreFramedAsOverridingTheGlobalVoice() {
        // the owner's scenario: "never call her bro" must be a hard rule, not a hint
        settings.put(1L, StyleSettings.CUSTOM_PROMPT_KEY, "never call her bro");
        ChatRequest req = buildFor(Fakes.contact(1, "Ada"), null);
        assertTrue("the rule text reaches the provider prompt",
            req.system.contains("never call her bro"));
        assertTrue("framed as overriding the global voice, not a soft note",
            req.system.contains("This contact's own rules"));
        assertTrue("override framing explicit",
            req.system.contains("override the global voice"));
    }

    @Test public void relationshipTypeDifferentiatesPromptsAcrossContacts() {
        // professional vs friend: the relationship line must visibly differ
        Contact client = Fakes.contact(1, "Mr. Balogun");
        client.relationshipType = "client";
        client.toneOverride = "respectful and precise";
        Contact friend = Fakes.contact(2, "Chidi");
        friend.relationshipType = "best friend";
        ChatRequest rc = buildFor(client, null);
        ChatRequest rf = buildFor(friend, null);
        assertTrue(rc.system.contains("client"));
        assertTrue(rc.system.contains("respectful and precise"));
        assertTrue(rf.system.contains("best friend"));
        assertFalse("client facts must not bleed into the friend's prompt",
            rf.system.contains("Mr. Balogun"));
        StyleService.ComposedVoice vc = style.compose(client);
        assertTrue("audit credits the contact profile:\n" + join(vc.why),
            join(vc.why).contains("contact profile applied (relationship: client)"));
        assertTrue("audit credits the tone note:\n" + join(vc.why),
            join(vc.why).contains("contact tone note applied"));
    }

    @Test public void directnessIsPermittedButHostilityIsForbidden() {
        // P-background-8 item 6: disagreement/refusal must be available in the
        // default voice — without randomly turning hostile.
        ChatRequest req = buildFor(Fakes.contact(1, "Ada"), null);
        assertTrue("the honest-pushback rule reaches the prompt",
            req.system.contains("disagree, correct Ada or say no"));
        assertTrue("firmness bounded explicitly",
            req.system.contains("firm is fine, hostile or insulting never"));
    }

    /* --------------------------------------- P-background-9: cold start + parrot */

    @Test public void unknownContactColdStartIsHonestInTheAudit() {
        // brand-new contact: no custom voice, no profile, no rules, no signals —
        // generation must still work from neutral defaults AND say so in the audit
        StyleService.ComposedVoice v = style.compose(Fakes.contact(7, "Stranger"));
        ChatRequest req = buildFor(Fakes.contact(7, "Stranger"), null);
        assertFalse("generation still runs without any customization", req.system.isEmpty());
        assertTrue("default style line stands in for a new contact",
            req.system.contains(SystemComposer.FALLBACK_STYLE) || req.system.contains("Voice:"));
        assertTrue("the audit must LABEL the cold start:\n" + join(v.why),
            join(v.why).contains("cold start — new contact, neutral assumptions"));
        // …and as soon as ANYTHING is customized, the cold-start label retires
        settings.put(7L, StyleSettings.CUSTOM_PROMPT_KEY, "keep it playful");
        StyleService.ComposedVoice v2 = style.compose(Fakes.contact(7, "Stranger"));
        assertFalse("a customized contact is no longer cold",
            join(v2.why).contains("cold start"));
    }

    @Test public void memoryInformsButNeverRecyclesOldReplies() {
        List<String> mem = new ArrayList<String>();
        mem.add("- Ada once replied: lol bet");
        ChatRequest req = buildFor(Fakes.contact(1, "Ada"), mem);
        assertTrue("memory block is present", req.system.contains("What you remember about Ada"));
        assertTrue("the no-parrot rule reaches the prompt",
            req.system.contains("never recycle an older reply"));
        assertTrue("memory informs, never dictates",
            req.system.contains("write every message fresh in the moment"));
    }

    /* ------------------------------------------------ memory + learned style */

    @Test public void memoryLinesReachOnlyTheirContactsPrompt() {
        List<String> mem = new ArrayList<String>();
        mem.add("- Ada's mom is recovering from surgery");
        ChatRequest ra = buildFor(Fakes.contact(1, "Ada"), mem);
        ChatRequest rb = buildFor(Fakes.contact(2, "Bode"), null);
        assertTrue(ra.system.contains("Ada's mom is recovering from surgery"));
        assertTrue(ra.system.contains("What you remember about Ada"));
        assertFalse("memory must NEVER cross contact boundaries",
            rb.system.contains("Ada's mom is recovering from surgery"));
    }

    @Test public void learnedStyleHintsReachThePromptForTheRightContact() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        LearningService learning = Fakes.learningService(lstore, kv);
        Contact a = Fakes.contact(1, "Ada");
        // deterministic: total signals ≥ MIN_SIGNALS ⇒ deriveHints fires
        for (int i = 0; i < 5; i++) {
            learning.record(a, StyleSignal.Kind.REGENERATED, "", null);
        }
        StyleService styleWithLearning = Fakes.styleService(settings, learning);
        StyleService.ComposedVoice v = styleWithLearning.compose(a);
        String extra = join(v.extraLines);
        assertFalse("learning open ⇒ hints composed for this contact", extra.isEmpty());
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile(), a, "", new ArrayList<com.replymate.core.model.Message>(),
            v.voiceLine, v.extraLines, ""));
        assertTrue("learned hints reach the provider prompt",
            req.system.contains("Learned from the owner's choices"));
        String why = join(v.why);
        assertTrue("audit credits learning:\n" + why, why.contains("learned:"));
    }

    /* ------------------------------------------------ the audit trail itself */

    @Test public void promptAuditCarriesExactlyWhatWasApplied() {
        settings.put(null, "emoji", "0");
        settings.put(1L, "tone", "2");
        settings.put(1L, StyleSettings.CUSTOM_PROMPT_KEY, "keep it very short always");
        Contact c = Fakes.contact(1, "Ada");
        StyleService.ComposedVoice v = style.compose(c);
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile(), c, "", new ArrayList<com.replymate.core.model.Message>(),
            v.voiceLine, v.extraLines, ""));

        String audit = PromptBuilder.snapshot(req, "test-model", "reply", v.why, null);
        assertTrue(audit.contains("\"task\""));
        assertTrue(audit.contains("\"system\""));
        assertTrue(audit.contains(StyleControls.EMOJI.phrase(0)));
        // the WHY section credits every active customization
        assertTrue("global voice credited:\n" + audit, audit.contains("global voice applied"));
        assertTrue("contact override credited:\n" + audit, audit.contains("contact override"));
        assertTrue("custom prompt credited:\n" + audit, audit.contains("custom prompt for Ada applied"));
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) sb.append(x).append('\n');
        return sb.toString();
    }
}
