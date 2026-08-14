package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-12: the duplicate-generation battery. One real incoming message
 *  must produce exactly ONE stored message (IngestCoordinator pins that side),
 *  ONE generation job (JobCoalescer/after-call abort pin that side) and — THIS
 *  suite — ONE current draft, even when the provider over-delivers variants and
 *  even when two generations for the same conversation truly overlap in time. */
public final class OneDraftPerMessageTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        Contact a = Fakes.contact(1, "Amara");
        a.relationshipType = "close friend";
        contacts.put(a);
        contacts.put(Fakes.contact(2, "Chidi"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "yes o! just dey settle"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "send the address abeg"));
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(new Fakes.LearningStoreFake(), new Fakes.KvStoreFake());
        Fakes.StyleSettingStoreFake styleSettings = new Fakes.StyleSettingStoreFake();
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(styleSettings, learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
    }

    /** Provider whose variants + latency are fully scripted (for the overlap hammer). */
    private static final class ScriptedProvider implements com.replymate.core.ports.AiProvider {
        final List<String> variants;
        final long sleepMs;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        int calls;
        ChatRequest lastRequest;
        ScriptedProvider(long sleepMs, String... variants) {
            this.sleepMs = sleepMs;
            this.variants = new ArrayList<String>(Arrays.asList(variants));
        }
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            lastRequest = request;
            int now = inFlight.incrementAndGet();
            maxInFlight.set(Math.max(maxInFlight.get(), now));
            try {
                if (sleepMs > 0) Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return Result.ok(new ChatReply(variants, 11, 7, RateLimitInfo.NONE));
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<List<String>> listModels() {
            return Result.ok(java.util.Collections.singletonList("test-model"));
        }
    }

    @Test public void backgroundGenerationAsksForOneCandidateAndPersistsOneDraft() {
        // A provider returning THREE identical takes — the exact on-device shape
        // of "2–3 identical AI drafts" for one message.
        ScriptedProvider p = new ScriptedProvider(0, "no wahala", "no wahala", "no wahala");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals("the REQUEST asks for one candidate (background path)",
            1, p.lastRequest.opts.candidates);
        assertEquals("exactly ONE draft row", 1, r.value.drafts.size());
        assertEquals(1, drafts.byContact(1, 10).size());
        assertEquals(1, usage.events.size());
        assertTrue(p.lastRequest != null);
        // the audit snapshot records what was actually requested
        assertTrue(drafts.byContact(1, 10).get(0).promptSnapshotJson
            .contains("\"candidateCount\":1"));
    }

    @Test public void distinctVariantsBeyondTheFirstAreNotPersistedAsDrafts() {
        ScriptedProvider p = new ScriptedProvider(0, "take one", "take two", "take three");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(p)).generateForContact(1);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertEquals(1, r.value.drafts.size());
        assertEquals("take one", drafts.byContact(1, 10).get(0).replyText);
        assertEquals(1, drafts.byContact(1, 10).size());
    }

    @Test public void chatReplyDropsByteIdenticalVariants() {
        ChatReply r = new ChatReply(
            new ArrayList<String>(Arrays.asList("same ", "same", " different", "different")),
            1, 1, RateLimitInfo.NONE);
        assertEquals(Arrays.asList("same", "different"), r.variants);
    }

    @Test public void concurrentGenerationsForOneContactNeverStackDrafts() throws Exception {
        // THE on-device race: two triggers for the same conversation running
        // truly concurrently (GEN pool is multi-threaded; a catch-up sweep can
        // fire while a scheduled job is mid-provider-call). With the per-contact
        // generation lock their purge+insert sequences cannot interleave, so the
        // final store must contain exactly ONE current draft — and the provider
        // calls must never overlap in time for the same contact.
        final ScriptedProvider p = new ScriptedProvider(120, "first take", "second take");
        final DraftService svc = service(new Fakes.GatewayFake(p));
        final CountDownLatch done = new CountDownLatch(2);
        final Result<DraftOutcome>[] results = new Result[2];
        Thread t1 = new Thread(new Runnable() {
            @Override public void run() {
                results[0] = svc.generateForContact(1);
                done.countDown();
            }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override public void run() {
                results[1] = svc.generateForContact(1);
                done.countDown();
            }
        });
        t1.start(); t2.start();
        assertTrue("both generations complete", done.await(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(results[0].ok ? "" : results[0].error), results[0].ok);
        assertTrue(String.valueOf(results[1].ok ? "" : results[1].error), results[1].ok);
        assertEquals("the two generations must NEVER run inside each other",
            1, p.maxInFlight.get());
        assertEquals("one message ⇒ one current draft after the race settles",
            1, drafts.byContact(1, 10).size());
    }

    @Test public void concurrentGenerationsForDifferentContactsStayParallel() throws Exception {
        // The counter-guard, made DETERMINISTIC (no wall-clock): the provider
        // blocks on a 2-slot barrier. If per-contact serialization ever collapsed
        // into GLOBAL serialization, the first generation could never trip the
        // barrier while holding a global lock, the 4s await would expire, and the
        // replies would come back marked SERIALIZED — failing the draft-text
        // assertions below with the actual stored text as evidence.
        final CountDownLatch both = new CountDownLatch(2);
        com.replymate.core.ports.AiProvider barrier = new com.replymate.core.ports.AiProvider() {
            @Override public String type() { return "gemini"; }
            @Override public Result<ChatReply> generate(ChatRequest request) {
                both.countDown();
                boolean overlapped;
                try {
                    overlapped = both.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    overlapped = false;
                }
                return Result.ok(new ChatReply(
                    java.util.Collections.singletonList(overlapped ? "ok" : "SERIALIZED"),
                    11, 7, RateLimitInfo.NONE));
            }
            @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
            @Override public Result<List<String>> listModels() {
                return Result.ok(java.util.Collections.singletonList("test-model"));
            }
        };
        final DraftService svc = service(new Fakes.GatewayFake(barrier));
        final CountDownLatch done = new CountDownLatch(2);
        Thread t1 = new Thread(new Runnable() {
            @Override public void run() { svc.generateForContact(1); done.countDown(); }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override public void run() { svc.generateForContact(2); done.countDown(); }
        });
        t1.start(); t2.start();
        assertTrue("both generations complete", done.await(15, TimeUnit.SECONDS));
        assertEquals("contact 1's provider call truly overlapped contact 2's",
            "ok", drafts.byContact(1, 10).get(0).replyText);
        assertEquals("contact 2's provider call truly overlapped contact 1's",
            "ok", drafts.byContact(2, 10).get(0).replyText);
        assertEquals(1, drafts.byContact(1, 10).size());
        assertEquals(1, drafts.byContact(2, 10).size());
    }

    @Test public void previewBundlesStillAskForSeveralSamples() {
        // the interactive preview is the ONLY place allowed to raise candidates
        com.replymate.core.prompt.PromptBundle b = new com.replymate.core.prompt.PromptBundle(
            null, com.replymate.fakes.Fakes.contact(9, "Tobi"), "",
            new ArrayList<com.replymate.core.model.Message>(), null, null, null, null);
        assertEquals(1, b.candidates);   // background default
        b.candidates = com.replymate.core.prompt.PromptBuilder.PREVIEW_VARIANTS;
        com.replymate.core.ai.ChatRequest req =
            com.replymate.core.prompt.PromptBuilder.build(b);
        assertEquals(3, req.opts.candidates);
    }
}
