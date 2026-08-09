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
import com.replymate.core.model.Message;
import com.replymate.core.model.ProviderRef;
import com.replymate.core.search.WebEvidence;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** P-background-8: the background generation guards — a slow external lookup
 *  must never park the draft thread, and a job superseded while it was stuck in
 *  that lookup must NEVER burn the paid provider call or save a stale draft. */
public final class BackgroundDraftGuardTest {

    private static final class RecordingProvider implements com.replymate.core.ports.AiProvider {
        int calls;
        ChatRequest lastRequest;
        @Override public String type() { return "deepseek"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            lastRequest = request;
            return Result.ok(new ChatReply(new ArrayList<String>(
                Collections.singletonList("Sabi — I dey come.")), 9, 5, RateLimitInfo.NONE));
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(Collections.singletonList("deepseek-chat"));
        }
    }

    /** A lookup that crawls like the owner's network — and flips the latch while
     *  it crawls, exactly like a newer message superseding the job mid-lookup. */
    private static final class SlowRetrieval implements com.replymate.core.ports.RetrievalPort {
        final long sleepMs;
        final Runnable during;
        int calls;
        SlowRetrieval(long sleepMs, Runnable during) {
            this.sleepMs = sleepMs;
            this.during = during;
        }
        @Override public List<WebEvidence> lookup(String subject) {
            calls++;
            if (during != null) during.run();
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new ArrayList<WebEvidence>();
        }
    }

    private static final class Fixture {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        Fakes.KvStoreFake liveKv = new Fakes.KvStoreFake();
        Fakes.GatewayFake gateway;
        RecordingProvider provider = new RecordingProvider();
        DraftService service;
    }

    private static Fixture fixture(com.replymate.core.ports.RetrievalPort retrieval) {
        Fixture f = new Fixture();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        LearningService learning = Fakes.learningService(
            new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        f.gateway = new Fakes.GatewayFake(f.provider);
        // A provider WITHOUT native search → the encyclopedia fallback path runs.
        f.gateway.meta = new ProviderRef("deepseek", "DeepSeek",
            "https://api.deepseek.com", "deepseek-chat");
        f.service = new DraftService(f.contacts, f.messages, new Fakes.StyleStoreFake(),
            new ProfileService(kv), f.drafts, f.usage, f.gateway,
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(new Fakes.StyleSettingStoreFake(), learning), learning,
            new MemoryService(new Fakes.MemoryStoreFake(), f.messages, kv, Fakes.FIXED_CLOCK));
        f.service.setLiveKv(f.liveKv);
        f.service.setRetrieval(retrieval);
        f.contacts.put(Fakes.contact(1L, "Tobi"));
        return f;
    }

    private static void say(Fakes.MessageStoreFake messages, long contactId, String text) {
        Message m = Fakes.msg(contactId, Direction.INCOMING, text);
        m.sentAt = 1000L;
        messages.insert(m);
    }

    @Test public void supersededDuringTheLookupNeverBurnsTheProviderCall() {
        // Job starts, gate fires (meaning-ask), the SLOW lookup begins — and a
        // newer message supersedes the job while it waits. The OLD job must
        // abort BEFORE the paid provider call: no call, no draft, clean err.
        final boolean[] superseded = { false };
        SlowRetrieval slow = new SlowRetrieval(150, new Runnable() {
            @Override public void run() { superseded[0] = true; }
        });
        Fixture f = fixture(slow);
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L,
            new DraftService.AbortCheck() {
                @Override public boolean aborted() { return superseded[0]; }
            });
        assertEquals("the superseded job must never reach the paid provider",
            0, f.provider.calls);
        assertFalse("superseded generation is not a success", r.ok);
        assertTrue("supersede is an explicit, diagnosable outcome",
            String.valueOf(r.error).toLowerCase(java.util.Locale.US).contains("superseded"));
        assertTrue("no stale draft row may be saved", f.drafts.byContact(1L, 10).isEmpty());
    }

    @Test public void currentJobStillGeneratesAfterASlowLookup() {
        // The same slow lookup WITHOUT supersede: the draft survives — the guard
        // aborts only genuinely-stale work, never the current one.
        SlowRetrieval slow = new SlowRetrieval(80, null);
        Fixture f = fixture(slow);
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L,
            new DraftService.AbortCheck() {
                @Override public boolean aborted() { return false; }
            });
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, f.provider.calls);
        assertEquals(1, slow.calls);
    }

    @Test public void noAbortCheckKeepsLegacyBehavior() {
        // Legacy call sites (the one-arg form): zero behavior change.
        Fixture f = fixture(new SlowRetrieval(40, null));
        say(f.messages, 1L, "wetin be 'odogwu' abeg");
        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, f.provider.calls);
    }
}
