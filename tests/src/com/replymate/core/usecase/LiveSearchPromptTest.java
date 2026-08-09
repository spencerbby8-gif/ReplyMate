package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.ProviderRef;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.model.UsageKind;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.search.WebEvidence;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 8 — the automatic live-intelligence PIPELINE,
 *  end to end through DraftService: the gate listens, the capability map picks
 *  native-vs-fallback, evidence actually rides into the generation, the cache
 *  makes repeats free, failures stay honest, ordinary messages never trigger,
 *  and reasoning depth rides the SAME request as search. */
public final class LiveSearchPromptTest {

    private static final class QueueProvider implements AiProvider {
        final List<Result<ChatReply>> queue = new ArrayList<Result<ChatReply>>();
        int calls;
        ChatRequest lastRequest;
        QueueProvider(Result<ChatReply>... ordered) {
            queue.addAll(Arrays.asList(ordered));
        }
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            lastRequest = request;
            return queue.isEmpty()
                ? Result.<ChatReply>err("no queued reply") : queue.remove(0);
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(Collections.singletonList("test-model"));
        }
    }

    private static final class RetrievalFake
            implements com.replymate.core.ports.RetrievalPort {
        int calls;
        String lastSubject;
        List<WebEvidence> answer = new ArrayList<WebEvidence>();
        @Override public List<WebEvidence> lookup(String subject) {
            calls++;
            lastSubject = subject;
            return answer;
        }
    }

    private static Result<ChatReply> reply(String... variants) {
        return Result.ok(new ChatReply(
            new ArrayList<String>(Arrays.asList(variants)), 9, 5, RateLimitInfo.NONE));
    }

    private static final class Fixture {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        Fakes.GatewayFake gateway;
        QueueProvider provider;
        RetrievalFake retrieval = new RetrievalFake();
        DraftService service;
    }

    private static Fixture fixture(String wire, String model,
                                   QueueProvider provider) {
        Fixture f = new Fixture();
        f.provider = provider;
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        LearningService learning = Fakes.learningService(
            new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.gateway = new Fakes.GatewayFake(f.provider);
        f.gateway.meta = new ProviderRef(wire, "Test Provider",
            "https://example.test/v1", model);
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), f.drafts, f.usage, f.gateway,
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), f.messages, kv, Fakes.FIXED_CLOCK));
        f.service.setLiveKv(f.liveKv);
        f.service.setRetrieval(f.retrieval);
        f.contacts.put(Fakes.contact(1L, "Tobi"));
        return f;
    }

    private static int searchEvents(Fixture f) {
        int n = 0;
        for (UsageEvent e : f.usage.events) if (e.kind == UsageKind.SEARCH) n++;
        return n;
    }

    private static UsageEvent searchEvent(Fixture f) {
        for (UsageEvent e : f.usage.events) if (e.kind == UsageKind.SEARCH) return e;
        return null;
    }

    private static String snapshot(Result<DraftOutcome> r) {
        return r.value.drafts.get(0).promptSnapshotJson;
    }

    /* ------------------------------------------------------------ native path */

    @Test public void nativeSearchProvidersAttachTheToolOnTheSameCall() {
        Fixture f = fixture("openai", "gpt-5",
            new QueueProvider(reply("Saka sealed it late on")));
        f.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "who won the arsenal game last night?"));

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals("no pre-generation lookup for native providers — search runs"
            + " INSIDE the provider call", 0, f.retrieval.calls);
        assertTrue("the search REQUEST rides the generation opts",
            f.provider.lastRequest.opts.search);
        assertEquals("the hard moment deepens automatically",
            "low", f.provider.lastRequest.opts.reasoning);
        String snap = snapshot(r);
        assertTrue(snap.contains("native web search"));
        assertTrue("metered as its own kind (provider billing applies)",
            searchEvents(f) == 1);
        UsageEvent su = searchEvent(f);
        assertNotNull(su);
        assertEquals("native search generations report the provider's real tokens",
            9, su.tokensIn);
    }

    /* ---------------------------------------------------------- fallback path */

    @Test public void toolLessProvidersGetEvidenceInjectedBeforeGeneration() {
        Fixture f = fixture("deepseek", "deepseek-chat",
            new QueueProvider(reply("big man tins, respect")));
        f.retrieval.answer.add(new WebEvidence("Odogwu",
            "Odogwu is an Igbo title for a man of great esteem.", "Wiktionary"));
        f.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "wetin be odogwu abeg"));

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals(1, f.retrieval.calls);
        assertFalse("no native tool exists — nothing is attached to the wire",
            f.provider.lastRequest.opts.search);
        String system = f.provider.lastRequest.system;
        assertTrue("the evidence ACTUALLY influences the reply (system channel)",
            system.contains("Live facts (looked up just now)"));
        assertTrue(system.contains("Odogwu is an Igbo title"));
        assertTrue("the source is attributed, never ghost-written",
            system.contains("Wiktionary"));
        String snap = snapshot(r);
        assertTrue(snap.contains("free encyclopedia"));
        assertEquals("free paths are metered as zero-token searches",
            1, searchEvents(f));
        UsageEvent su = searchEvent(f);
        assertNotNull(su);
        assertEquals(0, su.tokensIn);
        assertEquals(0, su.tokensOut);
    }

    @Test public void aRepeatLookupInsideAWeekIsFreeAndSaysSo() {
        Fixture f = fixture("deepseek", "deepseek-chat",
            new QueueProvider(reply("first"), reply("second")));
        f.retrieval.answer.add(new WebEvidence("Odogwu",
            "Odogwu is an Igbo title for a man of great esteem.", "Wiktionary"));
        f.messages.add(Fakes.msg(1L, Direction.INCOMING, "wetin be odogwu abeg"));

        assertTrue(f.service.generateForContact(1L).ok);
        assertEquals(1, f.retrieval.calls);

        Result<DraftOutcome> second = f.service.generateForContact(1L);
        assertTrue(second.ok);
        assertEquals("the cache served the repeat — no second lookup",
            1, f.retrieval.calls);
        String system = f.provider.lastRequest.system;
        assertTrue("freshness is never lied about",
            system.contains("cached on-device"));
        assertTrue(snapshot(second).contains("on-device cache"));
    }

    @Test public void aFailedLookupStaysHonestAndNeverInvents() {
        Fixture f = fixture("mistral", "mistral-large-latest",
            new QueueProvider(reply("plain honest reply")));
        // retrieval.answer stays EMPTY — the honest miss.
        f.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "who won the arsenal game last night?"));

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals(1, f.retrieval.calls);
        String system = f.provider.lastRequest.system;
        assertFalse("no fabricated 'Live facts' EVIDENCE block when nothing verified"
                + " (the standing anti-hallucination rule only NAMES the block)",
            system.contains("Live facts ("));
        assertTrue("the audit trail says exactly what happened",
            snapshot(r).contains("live lookup failed or found nothing"));
    }

    /* ------------------------------------------------------------- the gate */

    @Test public void ordinaryRepliesNeverTriggerAnyLookup() {
        Fixture f = fixture("openai", "gpt-5", new QueueProvider(reply("see you at 7")));
        f.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "are we still on for dinner at 7"));

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals(0, f.retrieval.calls);
        assertFalse(f.provider.lastRequest.opts.search);
        assertEquals("an ordinary moment stays fast",
            "default", f.provider.lastRequest.opts.reasoning);
        assertEquals(0, searchEvents(f));
        assertFalse(snapshot(r).contains("live search"));
    }

    /* ------------------------------------------------- search × reasoning × E2E */

    @Test public void searchAndReasoningRideTheSameRequestTogether() {
        Fixture f = fixture("openai", "gpt-5",
            new QueueProvider(reply("Arsenal took it, City drew")));
        f.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "who won the arsenal game last night? and what about city?"));

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertTrue(f.provider.lastRequest.opts.search);
        assertEquals("search + a multi-part question scales the thinking HIGH",
            "high", f.provider.lastRequest.opts.reasoning);
        String snap = snapshot(r);
        assertTrue(snap.contains("deeper thinking: HIGH"));
        assertTrue("thinking is credited as metadata, never as content",
            snap.contains("never shown or stored"));
    }

    @Test public void switchingProvidersSwitchesTheTransportNotTheGate() {
        // Same question, two capability sheets: Gemini attaches natively,
        // Ollama retrieves from the encyclopedia. The NEED detection is shared.
        Fixture g = fixture("gemini", "gemini-2.5-flash", new QueueProvider(reply("g")));
        g.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "who won the arsenal game last night?"));
        assertTrue(g.service.generateForContact(1L).ok);
        assertTrue(g.provider.lastRequest.opts.search);
        assertEquals(0, g.retrieval.calls);

        Fixture o = fixture("ollama", "llama3.2", new QueueProvider(reply("o")));
        o.retrieval.answer.add(new WebEvidence("Arsenal F.C.",
            "Arsenal won the fixture two goals to one.", "Wikipedia"));
        o.messages.add(Fakes.msg(1L, Direction.INCOMING,
            "who won the arsenal game last night?"));
        assertTrue(o.service.generateForContact(1L).ok);
        assertFalse(o.provider.lastRequest.opts.search);
        assertEquals(1, o.retrieval.calls);
        assertTrue(o.provider.lastRequest.system.contains("Live facts"));
    }
}
