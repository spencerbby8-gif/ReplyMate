package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.style.StyleControls;
import com.replymate.core.style.StyleSettings;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-12: VOICE END-TO-END PROOF. The owner rejects stored values and
 *  audit labels as evidence — so every assertion here fires through the REAL
 *  generation pipeline ({@link DraftService#generateForContact}, the same entry
 *  point the UI and the background runner call) and inspects the EXACT
 *  {@link ChatRequest} handed to the provider. PromptBuilder-direct pins already
 *  exist (CustomizationEffectTest); this suite closes the last gap: the request
 *  the provider ACTUALLY receives — and the reply it ACTUALLY sends back —
 *  must change when a setting changes. */
public final class VoicePromptProofTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private LearningService learning;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
    }

    private void seed(long id, String name, String incoming) {
        contacts.put(Fakes.contact(id, name));
        messages.add(Fakes.msg(id, Direction.INCOMING, incoming));
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
    }

    /** Provider that records every request it receives and DERIVES its reply from
     *  the tone phrase in that request — so the stored draft can only come out as
     *  "VERSION-DIRECT"/"VERSION-WARM" if the provider truly saw that prompt. */
    private static final class PromptEchoProvider implements com.replymate.core.ports.AiProvider {
        final List<ChatRequest> requests = new ArrayList<ChatRequest>();
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            requests.add(request);
            String sys = request.system;
            String reply = sys.contains(StyleControls.TONE.phrase(2)) ? "VERSION-DIRECT"
                : sys.contains(StyleControls.TONE.phrase(0)) ? "VERSION-WARM"
                : "VERSION-NEUTRAL";
            return Result.ok(new ChatReply(
                Collections.singletonList(reply), 11, 7, RateLimitInfo.NONE));
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(Collections.singletonList("test-model"));
        }
    }

    private static final class FlatProvider implements com.replymate.core.ports.AiProvider {
        final List<ChatRequest> requests = new ArrayList<ChatRequest>();
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            requests.add(request);
            return Result.ok(new ChatReply(
                Collections.singletonList("ok, noted"), 11, 7, RateLimitInfo.NONE));
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(Collections.singletonList("test-model"));
        }
    }

    private static ChatRequest last(FlatProvider p) {
        return p.requests.get(p.requests.size() - 1);
    }

    /* 1 ───────────── the headline proof: change a setting ⇒ the prompt on the
       wire changes ⇒ the reply the provider sends back (and we store) changes. */
    @Test public void levelChangeReachesTheWireAndChangesTheStoredReply() {
        seed(1, "Ada", "you still coming tonight?");
        seed(2, "Bode", "you still coming tonight?");
        PromptEchoProvider echo = new PromptEchoProvider();
        DraftService svc = service(new Fakes.GatewayFake(echo));

        settings.put(null, "tone", "0");                       // warm
        Result<DraftOutcome> r1 = svc.generateForContact(1);
        assertTrue(String.valueOf(r1.ok ? "" : r1.error), r1.ok);
        String sysWarm = echo.requests.get(echo.requests.size() - 1).system;
        assertTrue("warm phrase on the wire", sysWarm.contains(StyleControls.TONE.phrase(0)));
        assertFalse("direct phrase absent", sysWarm.contains(StyleControls.TONE.phrase(2)));
        assertEquals("the STORED reply reflects what the provider saw",
            "VERSION-WARM", drafts.byContact(1, 10).get(0).replyText);

        settings.put(null, "tone", "2");                       // direct — SETTING CHANGED
        Result<DraftOutcome> r2 = svc.generateForContact(2);
        assertTrue(String.valueOf(r2.ok ? "" : r2.error), r2.ok);
        String sysDirect = echo.requests.get(echo.requests.size() - 1).system;
        assertTrue("direct phrase on the wire", sysDirect.contains(StyleControls.TONE.phrase(2)));
        assertFalse("warm phrase absent", sysDirect.contains(StyleControls.TONE.phrase(0)));
        assertFalse("the two prompts must differ", sysDirect.equals(sysWarm));
        assertEquals("the STORED reply changed with the prompt",
            "VERSION-DIRECT", drafts.byContact(2, 10).get(0).replyText);
    }

    /* 2 ───────────── OFF means NO instruction for that dimension on the wire. */
    @Test public void offLevelSendsNoInstructionForThatDimensionThroughThePipeline() {
        seed(1, "Ada", "bring the charger abeg");
        seed(2, "Bode", "bring the charger abeg");
        FlatProvider p = new FlatProvider();
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        String sysDefault = last(p).system;
        int emojiDefault = StyleControls.defaultLevel("emoji");
        assertTrue("control run: the default emoji dial IS on the wire",
            sysDefault.contains(StyleControls.EMOJI.phrase(emojiDefault)));

        settings.put(null, "emoji", String.valueOf(StyleControls.LEVEL_OFF));
        assertTrue(svc.generateForContact(2).ok);
        String sysOff = last(p).system;
        for (int lvl = 0; lvl <= 2; lvl++) {
            assertFalse("OFF must remove every emoji instruction from the wire",
                sysOff.contains(StyleControls.EMOJI.phrase(lvl)));
        }
        // OFF is surgical: the rest of the voice survives on the same wire prompt
        assertTrue("the other dials still reach the provider",
            sysOff.contains(StyleControls.TONE.phrase(StyleControls.defaultLevel("tone"))));
        // …and the model-facing reply still generates normally
        assertEquals(1, drafts.byContact(2, 10).size());
    }

    /* 3 ───────────── a contact override beats the global setting — on the wire. */
    @Test public void contactOverrideBeatsGlobalOnTheWire() {
        seed(1, "Ada", "so is 4pm still on?");
        seed(2, "Bode", "so is 4pm still on?");
        FlatProvider p = new FlatProvider();
        DraftService svc = service(new Fakes.GatewayFake(p));

        settings.put(null, "tone", "0");       // global: warm
        settings.put(1L, "tone", "2");         // Ada override: direct
        assertTrue(svc.generateForContact(1).ok);
        String sysAda = last(p).system;
        assertTrue("override phrase wins on the wire",
            sysAda.contains(StyleControls.TONE.phrase(2)));
        assertFalse(sysAda.contains(StyleControls.TONE.phrase(0)));

        assertTrue(svc.generateForContact(2).ok);
        String sysBode = last(p).system;
        assertTrue("global setting still governs other contacts",
            sysBode.contains(StyleControls.TONE.phrase(0)));
        assertFalse("the override must not leak",
            sysBode.contains(StyleControls.TONE.phrase(2)));
    }

    /* 4 ───────────── My Voice instruction + About Them, on the wire, verbatim. */
    @Test public void customInstructionAndAboutThemReachTheWireThroughRealGeneration() {
        seed(1, "Ada", "send the address abeg");
        Contact ada = contacts.get(1);
        ada.relationshipType = "close friend";
        ada.relationshipNotes = "met at uni, loves jollof rice";
        FlatProvider p = new FlatProvider();
        DraftService svc = service(new Fakes.GatewayFake(p));

        settings.put(null, StyleSettings.CUSTOM_PROMPT_KEY,
            "always open with the person's first name");
        assertTrue(svc.generateForContact(1).ok);
        String sys = last(p).system;
        assertTrue("the custom instruction is on the wire, verbatim",
            sys.contains("always open with the person's first name"));
        assertTrue("the relationship context is on the wire",
            sys.contains("close friend"));
        assertTrue("the About-Them note is on the wire",
            sys.contains("met at uni, loves jollof rice"));
    }

    /* 5 ───────────── learned style reaches the wire ONLY past its gate. */
    @Test public void learnedHintsFlowThroughRealGenerationOnlyPastTheGate() {
        seed(1, "Ada", "you still coming tonight?");
        seed(2, "Bode", "you still coming tonight?");
        FlatProvider p = new FlatProvider();
        DraftService svc = service(new Fakes.GatewayFake(p));

        // Ada: at/over the gate (3 edit signals, deterministic threshold)
        for (int i = 0; i < 3; i++) {
            learning.record(contacts.get(1), StyleSignal.Kind.EDITED, "shorter", null);
        }
        // Bode: below the gate (2 signals — LearningEngine.MIN_SIGNALS = 3)
        for (int i = 0; i < 2; i++) {
            learning.record(contacts.get(2), StyleSignal.Kind.EDITED, "shorter", null);
        }

        assertTrue(svc.generateForContact(1).ok);
        assertTrue("a matured learned hint is on the wire",
            last(p).system.contains("keep replies noticeably shorter"));

        assertTrue(svc.generateForContact(2).ok);
        String sysBode = last(p).system;
        assertFalse("below the gate NOTHING leaks to the provider",
            sysBode.contains("keep replies noticeably shorter"));
        assertFalse(sysBode.contains("Learned from the owner's choices"));
    }

    /* 6 ───────────── precedence: an explicit setting silences the learned guess
       for that dimension — proven on the wire, not in stored state. */
    @Test public void explicitSettingSuppressesTheLearnedHintThroughRealGeneration() {
        seed(1, "Ada", "bring the charger abeg");
        seed(2, "Bode", "bring the charger abeg");
        FlatProvider p = new FlatProvider();
        DraftService svc = service(new Fakes.GatewayFake(p));

        for (int i = 0; i < 3; i++) {
            learning.record(contacts.get(1), StyleSignal.Kind.EDITED, "shorter", null);
            learning.record(contacts.get(2), StyleSignal.Kind.EDITED, "shorter", null);
        }
        settings.put(2L, "length", "2");     // Bode: owner explicitly chose length here

        assertTrue(svc.generateForContact(1).ok);
        assertTrue("no explicit rule ⇒ the learned hint IS used",
            last(p).system.contains("keep replies noticeably shorter"));

        assertTrue(svc.generateForContact(2).ok);
        String sysBode = last(p).system;
        assertFalse("the explicit length dial silences the learned guess on the wire",
            sysBode.contains("keep replies noticeably shorter"));
        assertTrue("the explicit dial's own phrase is on the wire instead",
            sysBode.contains(StyleControls.LENGTH.phrase(2)));
    }
}
