package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.model.UsageKind;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-5 end-to-end pins (directives 1, 8): an explicit meaning-ask with
 *  Live Research ON makes EXACTLY ONE extra provider call whose result reaches the
 *  reply prompt, is cached for 7 days (repeat = free), is metered as
 *  UsageKind.RESEARCH, and is credited in the audit snapshot. Toggle OFF ⇒ no
 *  lookup and an honest breadcrumb. Ordinary messages ⇒ zero lookups. A failed
 *  lookup never blocks the reply. */
public final class LiveResearchPromptTest {

    /** Provider that answers queued replies in order (research call first, draft second). */
    private static final class QueueProvider implements AiProvider {
        final List<Result<ChatReply>> queue = new ArrayList<Result<ChatReply>>();
        int calls;
        ChatRequest lastRequest;
        ChatRequest firstRequest;
        QueueProvider(Result<ChatReply>... ordered) {
            queue.addAll(Arrays.asList(ordered));
        }
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            if (firstRequest == null) firstRequest = request;
            lastRequest = request;
            return queue.isEmpty()
                ? Result.<ChatReply>err("no queued reply") : queue.remove(0);
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(java.util.Collections.singletonList("test-model"));
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
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        QueueProvider provider;
        DraftService service;
    }

    private static Fixture fixture(long contactId, String name,
                                   QueueProvider provider, String... incoming) {
        Fixture f = new Fixture();
        f.provider = provider;
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        LearningService learning = Fakes.learningService(
            new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), f.drafts, f.usage, new Fakes.GatewayFake(f.provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), f.messages, kv, Fakes.FIXED_CLOCK));
        f.service.setLiveKv(f.liveKv);
        f.contacts.put(Fakes.contact(contactId, name));
        for (String body : incoming) {
            f.messages.add(Fakes.msg(contactId, Direction.INCOMING, body));
        }
        return f;
    }

    private static String snapOf(Result<DraftOutcome> r) {
        Draft d = r.value.drafts.get(0);
        return d.promptSnapshotJson;
    }

    private static String lastSnap(Fixture f, long contactId) {
        List<Draft> ds = f.drafts.byContact(contactId, 1);
        return ds.isEmpty() ? "" : ds.get(0).promptSnapshotJson;
    }

    @Test public void explicitAskMakesExactlyOneLookupWhoseMeaningReachesThePrompt() {
        Fixture f = fixture(1, "Amara",
            new QueueProvider(
                reply("big man, a respected boss"),
                reply("lol it means you've got charm", "lessgo")),
            "what does odogwu even mean bro");
        f.liveKv.put(com.replymate.core.live.TermResearch.KV_ENABLED, "1");

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals("ONE research call + the draft call, never more", 2, f.provider.calls);
        assertTrue("the lookup ran first", f.provider.firstRequest.task.text.contains("\"odogwu\""));
        assertTrue("the meaning rides the reply prompt",
            f.provider.lastRequest.system.contains(
                "Word help (researched on-device, cached): \"odogwu\" means:"
                    + " big man, a respected boss."));
        assertTrue(snapOf(r).contains("live research: 'odogwu' looked up once"));
        boolean sawResearch = false;
        for (UsageEvent e : f.usage.events) sawResearch |= e.kind == UsageKind.RESEARCH;
        assertTrue("the lookup is metered as its own kind (cost transparency)", sawResearch);
    }

    @Test public void the7DayCacheMakesRepeatsFree() {
        Fixture f = fixture(1, "Amara",
            new QueueProvider(
                reply("big man, a respected boss"),
                reply("first draft"),
                reply("second draft")),
            "what does odogwu even mean bro");
        f.liveKv.put(com.replymate.core.live.TermResearch.KV_ENABLED, "1");

        assertTrue(f.service.generateForContact(1L).ok);
        assertTrue(f.service.generateForContact(1L).ok);
        assertEquals("lookup + 2 generations (the repeat used the cache, free)",
            3, f.provider.calls);
        assertTrue("audit credits the cache on the repeat",
            lastSnap(f, 1).contains("7-day cache"));
    }

    @Test public void toggleOffMeansNoLookupAndAnHonestBreadcrumb() {
        Fixture f = fixture(1, "Amara",
            new QueueProvider(reply("draft with no research")),
            "what does odogwu even mean bro");
        // toggle defaults OFF — nothing else needed
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals(1, f.provider.calls);
        assertFalse(f.provider.lastRequest.system.contains("researched on-device"));
        assertTrue(snapOf(r).contains("Live Research is off in Settings"));
    }

    @Test public void ordinaryMessagesNeverTriggerAnything() {
        Fixture f = fixture(3, "Tobi",
            new QueueProvider(reply("yes the delivery came")),
            "did the delivery come yet?", "and the pickup code, you get am?");
        f.liveKv.put(com.replymate.core.live.TermResearch.KV_ENABLED, "1");
        Result<DraftOutcome> r = f.service.generateForContact(3L);
        assertTrue(r.ok);
        assertEquals("ordinary text ⇒ provider called ONCE (the reply only)",
            1, f.provider.calls);
        assertFalse(f.provider.lastRequest.system.contains("researched on-device"));
    }

    @Test public void aFailedLookupNeverBlocksTheReply() {
        Fixture f = fixture(5, "Efe",
            new QueueProvider(
                Result.<ChatReply>err("429 quota exhausted"),
                reply("honestly i no even sabi")),
            "what does odogwu mean?");
        f.liveKv.put(com.replymate.core.live.TermResearch.KV_ENABLED, "1");
        Result<DraftOutcome> r = f.service.generateForContact(5L);
        assertTrue("the reply lands even when the lookup 429s", r.ok);
        assertFalse(f.provider.lastRequest.system.contains("researched on-device"));
        assertTrue(snapOf(r).contains("lookup for 'odogwu' failed"));
    }
}
