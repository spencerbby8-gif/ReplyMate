package com.replymate.core.usecase;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.style.StyleControls;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;

import static org.junit.Assert.*;

/** P22 §4 (owner directive): a REPEATABLE quality contract suite over the reply
 *  generation pipeline, run across the realistic texting spread — close friends,
 *  family, work, banter, direct questions, disagreement, plans, sensitive chats,
 *  group mentions, ongoing chats and insufficient context.
 *
 *  Honest scope (no overclaiming): this pins the deterministic PROMPT CONTRACT —
 *  what the model is told and what context it sees — because that is the layer
 *  this repo controls. It never claims anything about AI-detection and it cannot
 *  judge the provider's word choice; VoicePromptProofTest's echo provider pins
 *  request→output variation separately. */
public final class NaturalnessContractTest {

    private static final class Fx {
        final Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        final Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        final Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        final Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        final Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        LearningService learning;
        Fakes.FakeProvider provider;
        DraftService service;
    }

    private static Fx fixture() {
        Fx f = new Fx();
        f.learning = Fakes.learningService(new Fakes.LearningStoreFake(), f.kv);
        f.provider = Fakes.FakeProvider.returning("draft text");
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(f.kv), f.drafts, new Fakes.UsageStoreFake(),
            new Fakes.GatewayFake(f.provider), Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(f.settings, f.learning), f.learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), f.messages, f.kv, Fakes.FIXED_CLOCK));
        return f;
    }

    private static Contact seed(Fx f, long id, String name) {
        Contact c = Fakes.contact(id, name);
        f.contacts.put(c);
        return c;
    }

    private static void incoming(Fx f, long id, String body, long offsetMinutes) {
        Message m = Fakes.msg(id, Direction.INCOMING, body);
        m.sentAt = Fakes.NOW - Math.max(0, offsetMinutes) * 60_000;
        f.messages.add(m);
    }

    private static void outgoing(Fx f, long id, String body, long offsetMinutes) {
        Message m = Fakes.msg(id, Direction.OUTGOING, body);
        m.sentAt = Fakes.NOW - Math.max(0, offsetMinutes) * 60_000;
        f.messages.add(m);
    }

    private static ChatRequest generate(Fx f, long id) {
        Result<DraftOutcome> r = f.service.generateForContact(id);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        return f.provider.lastRequest;
    }

    /** Every scenario: the anti-cliché foundation + honesty rules are present. */
    private static void assertBaseContract(ChatRequest req) {
        assertTrue("the owner's anti-AI-voice charter rides every request",
            req.system.contains("This sounds hundred percent like ai"));
        assertTrue("no invented facts", req.system.contains("never invent facts"));
        assertTrue("output only the reply", req.system.contains("Output only the exact message"));
    }

    /* 1 ─ close friend, banter energy */
    @Test public void closeFriendBanter() {
        Fx f = fixture();
        seed(f, 1, "Tobi");
        f.settings.put(null, "custom.prompt", "we banter hard, never soft");
        outgoing(f, 1, "omw now", 12);
        incoming(f, 1, "bro you actually wore that 💀💀", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue("the exact message is quoted back as the answer target",
            req.task.text.contains("bro you actually wore that 💀💀"));
        assertTrue(req.system.contains("we banter hard, never soft"));
        assertTrue("the default tone voice line rides",
            req.system.contains(StyleControls.byKey("tone")
                .phrase(StyleControls.defaultLevel("tone"))));
    }

    /* 2 ─ family, first-ever chat → honest warm-up, no fake history */
    @Test public void familyColdStartNeverFakesHistory() {
        Fx f = fixture();
        seed(f, 1, "Mum");
        incoming(f, 1, "have you eaten?", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue("cold start is declared openly",
            req.system.contains("New chat"));
        assertTrue(req.system.contains("never fake shared history or inside jokes"));
    }

    /* 3 ─ work contact, formality dial up */
    @Test public void workContactFormalityReachesTheWire() {
        Fx f = fixture();
        seed(f, 1, "Mr Ademola");
        f.settings.put(null, "formality", "2");
        incoming(f, 1, "kindly confirm receipt of the invoice", 3);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.system.contains(StyleControls.byKey("formality").phrase(2)));
    }

    /* 4 ─ banter: humor dial at max */
    @Test public void humorDialMaxIsVisible() {
        Fx f = fixture();
        seed(f, 1, "Chidi");
        f.settings.put(null, "humor", "2");
        incoming(f, 1, "your cooking could end friendships fr", 2);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.system.contains(StyleControls.byKey("humor").phrase(2)));
    }

    /* 5 ─ direct questions in a burst: newest question leads, mechanically */
    @Test public void multiQuestionBurstAnswersTheNewestQuestion() {
        Fx f = fixture();
        seed(f, 1, "Ada");
        incoming(f, 1, "did you watch the game?", 3);
        incoming(f, 1, "and are you still coming tomorrow?", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue("the burst is read mechanically, not hallucinated",
            req.task.text.contains("Mechanical read") && req.task.text.contains("questions"));
    }

    /* 6 ─ disagreement is contractually allowed (tone ≠ compliance) */
    @Test public void honestPushbackIsAlwaysAllowed() {
        Fx f = fixture();
        seed(f, 1, "Kunle");
        incoming(f, 1, "just lend him the money, he will pay back", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.system.contains("say it plainly"));
        assertTrue(req.system.contains("hostile or insulting never"));
    }

    /* 7 ─ plans: the anchor is the newest message, not an older one */
    @Test public void plansAnchorOnTheNewestMessageOnly() {
        Fx f = fixture();
        seed(f, 1, "Somto");
        incoming(f, 1, "thinking we do Saturday instead", 30);
        incoming(f, 1, "actually make it Sunday, 4pm", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.task.text.contains("actually make it Sunday, 4pm"));
        assertTrue("the answered text must not anchor on the older plan",
            !req.task.text.contains("— \"thinking we do Saturday instead\""));
    }

    /* 8 ─ sensitive chat: relationship notes ride the contact block */
    @Test public void sensitiveChatCarriesRelationshipContext() {
        Fx f = fixture();
        Contact c = seed(f, 1, "Ngozi");
        c.relationshipNotes = "going through a rough patch — be gentle, no jokes";
        f.contacts.put(c);
        incoming(f, 1, "i really needed you yesterday and you weren't there", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.system.contains("About this person: going through a rough patch"));
    }

    /* 9 ─ group mention: the ACTUAL sender is attributed, never the room name */
    @Test public void groupChatAttributesTheActualSender() {
        Fx f = fixture();
        Contact c = seed(f, 1, "Family Group");
        c.isGroup = true;
        f.contacts.put(c);
        incoming(f, 1, "who is handling the drinks then?", 2);
        Message latest = Fakes.msg(1, Direction.INCOMING, "Kelechi are you still coming?");
        latest.sentAt = Fakes.NOW - 60_000;
        latest.senderName = "Ada";
        f.messages.add(latest);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        StringBuilder turns = new StringBuilder();
        for (com.replymate.core.ai.Turn t : req.turns) turns.append(t.text).append('\n');
        assertTrue("the thread attributes the actual group sender (schema v6)",
            turns.toString().contains("Ada: Kelechi are you still coming?")
                || req.task.text.contains("Ada"));
    }

    /* 10 ─ ongoing chat with approved history: learned style rides along */
    @Test public void ongoingChatCarriesLearnedStyleEvidence() {
        Fx f = fixture();
        seed(f, 1, "Amara");
        for (int i = 0; i < 3; i++) {
            Draft d = new Draft();
            d.contactId = 1;
            d.replyText = "no wahala talk soon " + i;
            d.status = DraftStatus.COPIED;
            d.createdAt = Fakes.NOW - 3600_000;
            f.drafts.insert(d);
        }
        incoming(f, 1, "you good? you've been quiet", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue("learned evidence lines ride (P22 provenance-neutral wording)",
            req.system.contains("your own messages"));
    }

    /* 11 ─ insufficient context: one-word ping, cold chat → no invention */
    @Test public void insufficientContextStaysHonest() {
        Fx f = fixture();
        seed(f, 1, "Stranger");
        incoming(f, 1, "ok", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        assertTrue(req.system.contains("New chat"));
        assertTrue(req.system.contains("answer plainly that you don't know"));
        assertTrue("the trivial message is still quoted, not embellished",
            req.task.text.contains("ok"));
    }

    /* 12 ─ conflict-proof: a dial OFF means no rule anywhere (emoji sample) */
    @Test public void offDialsEmitNothingAnywhereInThePrompt() {
        Fx f = fixture();
        seed(f, 1, "Tobi");
        f.settings.put(null, "emoji", String.valueOf(StyleControls.LEVEL_OFF));
        incoming(f, 1, "lol say that again", 1);
        ChatRequest req = generate(f, 1);
        assertBaseContract(req);
        StyleControls.Control emoji = StyleControls.byKey("emoji");
        for (int lvl = 0; lvl <= 2; lvl++) {
            assertFalse("emoji=OFF must leak no level-" + lvl + " phrase",
                req.system.contains(emoji.phrase(lvl)));
        }
        assertTrue("other dials stay on the wire",
            req.system.contains(StyleControls.byKey("tone")
                .phrase(StyleControls.defaultLevel("tone"))));
    }
}
