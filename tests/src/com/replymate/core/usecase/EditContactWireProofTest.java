package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-15, topic 1: every Edit Contact control, end-to-end. The 9
 *  dials are pinned dial-by-dial in AllDialsWireProofTest and the custom
 *  instruction / About Them / learned-style gates in VoicePromptProofTest; this
 *  suite closes the remaining controls the mandate names — contact LANGUAGE,
 *  the per-contact TONE NOTE, RELATIONSHIP, the AI / PRIVATE switches, and the
 *  MEMORY switch — each proven on the EXACT ChatRequest handed to the provider
 *  (or on the honest refusal when a switch forbids generation). */
public final class EditContactWireProofTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private LearningService learning;
    private com.replymate.core.memory.MemoryService memory;

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
        memory = new com.replymate.core.memory.MemoryService(
            new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memory);
    }

    private void seed(long id, String name) {
        contacts.put(Fakes.contact(id, name));
        messages.add(Fakes.msg(id, Direction.INCOMING, "you still coming tonight?"));
    }

    /* 1 ─ contact LANGUAGE reaches the wire verbatim, per contact, no leakage. */
    @Test public void contactLanguageReachesTheWireScopedToTheContact() {
        seed(1, "Ada");
        seed(2, "Bode");
        contacts.get(1).languagePref = "Naija pidgin";
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        assertTrue("the language preference is on the wire",
            p.lastRequest.system.contains("Reply language: Naija pidgin"));

        assertTrue(svc.generateForContact(2).ok);
        assertFalse("a contact without the setting never gets someone else's language",
            p.lastRequest.system.contains("Naija pidgin"));
        assertTrue("the honest default still applies: match their latest message",
            p.lastRequest.system.contains("Reply language: match the language of their latest message."));
    }

    /* 2 ─ the per-contact TONE NOTE (free text) reaches the wire verbatim. */
    @Test public void contactToneNoteReachesTheWireVerbatim() {
        seed(1, "Ada");
        contacts.get(1).toneOverride = "playful but never rude";
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        assertTrue(p.lastRequest.system.contains("Tone with them: playful but never rude"));
    }

    /* 3 ─ relationship + About Them ride together (re-pinned beside the freedials). */
    @Test public void relationshipAndAboutThemRideTogether() {
        seed(1, "Ada");
        contacts.get(1).relationshipType = "elder sister";
        contacts.get(1).relationshipNotes = "pays my rent sometimes, respect her";
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        String sys = p.lastRequest.system;
        assertTrue(sys.contains("elder sister"));
        assertTrue(sys.contains("pays my rent sometimes, respect her"));
    }

    /* 4 ─ PRIVATE and AI-OFF switches refuse generation honestly, no provider call,
       no draft, nothing stored — on both the reply and the intentional paths. */
    @Test public void privateAndAiOffRefuseEverything() {
        seed(1, "Ada");
        seed(2, "Bode");
        contacts.get(1).privateMode = true;
        contacts.get(1).aiEnabled = false;
        contacts.get(2).aiEnabled = false;
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r1 = svc.generateForContact(1);
        assertFalse(r1.ok);
        assertTrue(r1.error.contains("private"));
        assertFalse(svc.composeForContact(1,
            com.replymate.core.prompt.ComposeKind.OPENER).ok);
        assertFalse(svc.generateForContact(2).ok);
        assertEquals("the provider is never called for a refused contact", 0, p.calls);
        assertTrue(drafts.byContact(1, 10).isEmpty());
        assertTrue(drafts.byContact(2, 10).isEmpty());
    }

    /* 5 ─ MEMORY OFF: pinned facts exist but never reach the prompt. */
    @Test public void memoryOffKeepsFactsStoredButOffTheWire() {
        seed(1, "Ada");
        contacts.get(1).memoryEnabled = false;
        memory.replacePinnedFacts(1, "ADA-FACT: mum's shop is in Wuse 2");
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(svc.generateForContact(1).ok);
        assertFalse("memory off ⇒ stored facts stay off the wire",
            p.lastRequest.system.contains("ADA-FACT"));
        assertTrue("the audit says honestly that memory was not used",
            drafts.byContact(1, 10).get(0).promptSnapshotJson
                .contains("memory disabled for this contact"));

        // flipping the switch back restores the memory block on the wire
        contacts.get(1).memoryEnabled = true;
        assertTrue(svc.generateForContact(1).ok);
        assertTrue(p.lastRequest.system.contains("ADA-FACT: mum's shop is in Wuse 2"));
    }
}
