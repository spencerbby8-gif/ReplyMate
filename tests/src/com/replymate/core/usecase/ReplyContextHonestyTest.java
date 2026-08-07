package com.replymate.core.usecase;

import com.replymate.core.listener.ListenerFilter;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-context-honesty regression tests — the mandate:
 *  (a) the model must NEVER generate a reply from only the contact name — the latest
 *      incoming message MUST reach the provider request (these tests FAIL if it doesn't);
 *  (b) media-only / empty latest notifications get an honest explanation and a safe
 *      fallback — with NO provider call (no hallucination, no money burned);
 *  (c) older media in the HISTORY stays honestly labeled and never blocks generation;
 *  (d) the audit snapshot proves all of it (latest incoming, app, provider, endpoint). */
public class ReplyContextHonestyTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        Contact a = Fakes.contact(1, "Amara");
        contacts.put(a);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        Fakes.LearningStoreFake learningStore = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, new Fakes.UsageStoreFake(), gateway, Fakes.IDS, Fakes.FIXED_CLOCK,
            Fakes.NOOP_LOG, Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning),
            learning);
    }

    private void seed(String... incomingThenOutgoing) {
        // helper: alternate IN/OUT messages, first entry INCOMING
        boolean in = true;
        for (String body : incomingThenOutgoing) {
            messages.add(Fakes.msg(1, in ? Direction.INCOMING : Direction.OUTGOING, body));
            in = !in;
        }
    }

    // ---------- (a) the latest incoming message MUST reach the model ----------

    @Test public void latestIncomingReachesProviderRequestAndTask() {
        seed("first text from her", "my earlier reply", "this is her LATEST question?");
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("on my way");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);

        assertTrue("generation failed: " + r.error, r.ok);
        assertEquals(1, provider.calls);

        // 1) it appears in the conversation turns
        StringBuilder allTurns = new StringBuilder();
        for (com.replymate.core.ai.Turn t : provider.lastRequest.turns) allTurns.append(t.text).append('\n');
        assertTrue("latest incoming missing from provider conversation turns",
            allTurns.toString().contains("Amara: this is her LATEST question?"));
        assertTrue("whole history must go too",
            allTurns.toString().contains("first text from her")
                && allTurns.toString().contains("my earlier reply"));

        // 2) AND it is quoted in the final task turn (anti-drift anchor)
        assertTrue("task does not quote the latest message",
            provider.lastRequest.task.text.contains("this is her LATEST question?"));
        assertTrue(provider.lastRequest.task.text.contains("Output only the reply text"));
    }

    @Test public void senderAndAppAndContactTravelWithTheRequestAndAudit() {
        messages.add(Fakes.msg(1, Direction.INCOMING, "you still coming on Saturday?"));
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("yes, pulling up");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertTrue(r.ok);

        // sender name prefixes the incoming turn; contact name shapes system + task
        assertTrue(provider.lastRequest.turns.get(0).text.startsWith("Amara: "));
        assertTrue(provider.lastRequest.system.contains("Amara"));
        assertTrue(provider.lastRequest.task.text.contains("Amara"));

        // audit snapshot carries the mandate's fields
        String snap = drafts.saved.get(0).promptSnapshotJson;
        assertTrue(snap.contains("\"latestIncoming\""));
        assertTrue(snap.contains("you still coming on Saturday?"));
        assertTrue(snap.contains("\"provider\""));
        assertTrue(snap.contains("Google Gemini"));                    // label
        assertTrue(snap.contains("test-model"));                       // model
        assertTrue(snap.contains("v1beta/models/test-model:generateContent")); // endpoint
        assertTrue(snap.contains("\"contextTurns\""));
        assertTrue(snap.contains("\"maxInputTokens\""));
    }

    @Test public void auditEndpointMatchesRealGeminiWirePath() {
        // the audit display string must equal the REAL request endpoint
        assertEquals(
            "POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            com.replymate.core.prompt.AuditContext.endpointFor("gemini",
                "https://generativelanguage.googleapis.com", "gemini-3.5-flash-lite"));
        assertEquals("POST https://api.deepseek.com/chat/completions",
            com.replymate.core.prompt.AuditContext.endpointFor("deepseek",
                "https://api.deepseek.com", "deepseek-chat"));
        assertEquals("POST https://api.anthropic.com/v1/messages",
            com.replymate.core.prompt.AuditContext.endpointFor("anthropic",
                "https://api.anthropic.com", "claude-sonnet-5"));
    }

    // ---------- (b) media / empty latest → honest explanation, NO provider call ----------

    @Test public void mediaOnlyLatestBlocksGenerationWithExplanation() {
        seed("we still on for tonight?", "yes definitely");
        // a media notification arrives last (stored with the placeholder body)
        messages.add(Fakes.msg(1, Direction.INCOMING, ListenerFilter.MEDIA_PLACEHOLDER));
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("nice photo!");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);

        assertFalse(r.ok);
        assertTrue("must explain the media situation, got: " + r.error,
            r.error.contains("media"));
        assertTrue("must say it can't read the content, got: " + r.error,
            r.error.contains("can't read"));
        assertTrue("must refuse to invent, got: " + r.error,
            r.error.contains("won't invent"));
        assertEquals("provider must NOT be called (no hallucination, no tokens burned)",
            0, provider.calls);
        assertTrue("no draft may be written", drafts.saved.isEmpty());
    }

    @Test public void emptyLatestBlocksGenerationWithExplanation() {
        seed("you there?", "yes");
        messages.add(Fakes.msg(1, Direction.INCOMING, "   "));   // whitespace-only
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("hello??");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);

        assertFalse(r.ok);
        assertTrue(r.error.contains("no readable text"));
        assertTrue(r.error.contains("won't invent"));
        assertEquals(0, provider.calls);
        assertTrue(drafts.saved.isEmpty());
    }

    @Test public void appNameAppearsInMediaExplanationAndTask() {
        com.replymate.core.model.Message media = Fakes.msg(1, Direction.INCOMING, ListenerFilter.MEDIA_PLACEHOLDER);
        media.channel = Channel.WHATSAPP;
        messages.add(media);
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(
            Fakes.FakeProvider.returning("x"))).generateForContact(1);
        assertFalse(r.ok);
        assertTrue("explanation should name the app, got: " + r.error,
            r.error.contains("WhatsApp"));
    }

    // ---------- (c) older media in history: honest context, never a blocker ----------

    @Test public void olderMediaStaysVisibleButDoesNotAnsweringLatestText() {
        seed("send me the pic when you can", "will do");
        messages.add(Fakes.msg(1, Direction.INCOMING, ListenerFilter.MEDIA_PLACEHOLDER));
        messages.add(Fakes.msg(1, Direction.INCOMING, "did you see it? what do you think"));
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("looks great!");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);

        assertTrue("generation must proceed — latest incoming IS text: " + r.error, r.ok);
        StringBuilder allTurns = new StringBuilder();
        for (com.replymate.core.ai.Turn t : provider.lastRequest.turns) allTurns.append(t.text).append('\n');
        assertTrue("the task answers the latest TEXT message",
            provider.lastRequest.task.text.contains("did you see it? what do you think"));
        assertTrue("older media stays honestly labeled in history (no invented content)",
            allTurns.toString().contains(ListenerFilter.MEDIA_PLACEHOLDER));
    }

    // ---------- (d) the gate vs contact name only ----------

    @Test public void noIncomingAtAllKeepsTheExistingGate() {
        // nothing seeded: only the setUp contact exists
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("hi");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error.contains("Add at least one message"));
        assertEquals(0, provider.calls);
    }
}
