package com.replymate.core.usecase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.Message;
import com.replymate.core.model.ProviderRef;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** P-background-10 (brief §4/§5): the audit trail must never claim Search or
 *  reasoning HAPPENED unless the provider's own response metadata says it did.
 *  A request is not an execution. These pins drive the real DraftService with a
 *  native-search-capable provider and read the EXACT why-lines that landed in the
 *  saved draft's prompt snapshot. */
public final class GenerationHonestyTest {

    /** Provider that returns a fixed reply with controlled execution metadata. */
    private static final class TunableProvider implements com.replymate.core.ports.AiProvider {
        final ChatReply reply;
        int calls;
        ChatRequest lastRequest;
        TunableProvider(ChatReply reply) { this.reply = reply; }
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            lastRequest = request;
            return Result.ok(reply);
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(Collections.singletonList("gemini-2.5-flash"));
        }
    }

    private static final class Fixture {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        Fakes.GatewayFake gateway;
        TunableProvider provider;
        DraftService service;
    }

    private static ChatReply reply(int searchQueries, List<String> sources,
                                   int reasoningTokens) {
        return new ChatReply(new ArrayList<String>(
                Collections.singletonList("Odogwu — big boss energy, na hail.")),
            25, 12, RateLimitInfo.NONE, searchQueries, sources, reasoningTokens, "");
    }

    private static Fixture fixture(ChatReply reply) {
        Fixture f = new Fixture();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        LearningService learning = Fakes.learningService(
            new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.provider = new TunableProvider(reply);
        f.gateway = new Fakes.GatewayFake(f.provider);
        // native-search-capable provider (Gemini) → the in-call search path runs
        f.gateway.meta = new ProviderRef("gemini", "Google Gemini",
            "https://generativelanguage.googleapis.com", "gemini-2.5-flash");
        f.gateway.model = "gemini-2.5-flash";
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), f.drafts, f.usage, f.gateway,
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), f.messages, kv,
                Fakes.FIXED_CLOCK));
        f.service.setLiveKv(f.liveKv);
        f.service.setRetrieval(null);          // native path: retrieval never used
        f.contacts.put(Fakes.contact(1L, "Tobi"));
        return f;
    }

    private static void say(Fakes.MessageStoreFake messages, long contactId, String text) {
        Message m = Fakes.msg(contactId, Direction.INCOMING, text);
        m.sentAt = 1000L;
        messages.insert(m);
    }

    private static String snapshotOf(Fixture f) {
        List<Draft> d = f.drafts.byContact(1L, 5);
        assertEquals("one draft saved", 1, d.size());
        return d.get(0).promptSnapshotJson == null ? "" : d.get(0).promptSnapshotJson;
    }

    @Test public void nativeSearchRequestedButNotExecutedIsRecordedHonestly() {
        Fixture f = fixture(reply(0, null, 0));
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertTrue("the search tool rode the request",
            f.provider.lastRequest != null && f.provider.lastRequest.opts.search);
        String snap = snapshotOf(f);
        assertTrue("the REQUEST is recorded as a request",
            snap.contains("requested the provider's native web search"));
        assertTrue("zero executed searches is said, plainly",
            snap.contains("ran NO web search"));
        assertFalse("execution is NEVER claimed when it didn't happen",
            snap.contains("web search(es) executed by the provider"));
    }

    @Test public void executedSearchIsCreditedWithItsOwnEvidence() {
        Fixture f = fixture(reply(2, Collections.singletonList("BBC Sport"), 0));
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String snap = snapshotOf(f);
        assertTrue("the provider-reported execution is what gets credited",
            snap.contains("2 web search(es) executed by the provider"));
        assertTrue("public source titles ride along", snap.contains("BBC Sport"));
        assertFalse(snap.contains("ran NO web search"));
    }

    @Test public void reasoningWithoutBilledTokensIsMarkedUnconfirmed() {
        Fixture f = fixture(reply(1, null, 0));       // search ran, thinking didn't
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String snap = snapshotOf(f);
        assertTrue("the decision to think deeper is recorded",
            snap.contains("deeper thinking ("));
        assertTrue("and its (missing) execution is flagged honestly",
            snap.contains("no billed reasoning tokens"));
    }

    @Test public void billedReasoningTokensAreTheOnlyConfirmationAccepted() {
        Fixture f = fixture(reply(1, null, 640));
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String snap = snapshotOf(f);
        assertTrue(snap.contains("640 reasoning tokens billed"));
        assertFalse(snap.contains("no billed reasoning tokens"));
    }

    @Test public void anOrdinaryMessageRequestsNoSearchAndClaimsNone() {
        Fixture f = fixture(reply(0, null, 0));
        say(f.messages, 1L, "you still coming tonight?");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertFalse("no search requested for banter",
            f.provider.lastRequest.opts.search);
        String snap = snapshotOf(f);
        assertFalse(snap.contains("web search(es) executed by the provider"));
        assertFalse(snap.contains("ran NO web search"));
    }
}
