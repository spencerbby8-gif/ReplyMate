package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.learning.LearningService;
import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 1 — THE ON-DEVICE BUG, pinned exactly:
 *  ReplyMate drafted an answer about "aura farming", the draft was never approved,
 *  then the contact asked about Arsenal — and the next draft kept using the OLD
 *  topic (both unread lines merged into one burst, the stale topic steering the
 *  read). A pending draft must never become permanent active context; a clearly
 *  unrelated message must reset the active topic while long-term history stays. */
public final class PendingDraftContextTest {

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
        Fakes.UsageStoreFake usage = new Fakes.UsageStoreFake();
        QueueProvider provider;
        DraftService service;
    }

    private static Fixture fixture(long contactId, String name, QueueProvider provider) {
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
        f.service.setLiveKv(new Fakes.KvStoreFake());
        f.contacts.put(Fakes.contact(contactId, name));
        return f;
    }

    private static void seedAuraThenArsenal(Fixture f, long contactId) {
        // insert order assigns ids 1 and 2 — the exact on-device ordering.
        f.messages.add(Fakes.msg(contactId, Direction.INCOMING,
            "bro your new profile pic is aura farming fr"));
        f.messages.add(Fakes.msg(contactId, Direction.INCOMING,
            "did you watch the Arsenal match last night?"));
    }

    private static void pendingDraftAnswering(Fixture f, long contactId, long messageId) {
        Draft d = new Draft();
        d.contactId = contactId;
        d.inReplyToId = messageId;
        d.replyText = "lol stop it, it's giving main character";
        d.model = "test-model";
        f.drafts.insert(d);   // status GENERATED = never approved, never used
    }

    @Test public void theArsenalQuestionGetsItsOwnTopicNotTheStaleAuraTopic() {
        Fixture f = fixture(1, "Tobi",
            new QueueProvider(reply("yeah I caught it, Saka was different gravy")));
        seedAuraThenArsenal(f, 1L);
        pendingDraftAnswering(f, 1L, 1L);   // the unapproved aura-farming draft

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        assertEquals("one generation call, no extra lookups", 1, f.provider.calls);

        String task = f.provider.firstRequest.task.text;
        assertTrue("the new question is what gets answered", task.contains(
            "did you watch the Arsenal match last night?"));
        assertFalse("the stale topic is NOT re-answered as active context",
            task.toLowerCase().contains("aura farming"));

        boolean historyKeepsIt = false;
        for (com.replymate.core.ai.Turn t : f.provider.firstRequest.turns) {
            if (t.text != null && t.text.toLowerCase().contains("aura farming")) {
                historyKeepsIt = true;
            }
        }
        assertTrue("the old message stays in conversation HISTORY (not deleted,"
            + " just not the active topic)", historyKeepsIt);

        Draft made = r.value.drafts.get(0);
        assertEquals("the new draft answers the NEW message",
            Long.valueOf(2L), made.inReplyToId);
        String snap = made.promptSnapshotJson;
        assertTrue("the audit trail says what happened to the old burst",
            snap.contains("already covered by a previous draft"));
        assertTrue("the plan reads the NEW topic",
            snap.toLowerCase().contains("arsenal"));
        assertFalse("the plan is free of the stale topic words",
            snap.toLowerCase().contains("about: aura"));
    }

    @Test public void withoutAPendingDraftBothUnreadLinesStayOneBurst() {
        Fixture f = fixture(1, "Tobi", new QueueProvider(reply("bro stop")));
        seedAuraThenArsenal(f, 1L);   // no draft at all — legacy burst behavior

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        String task = f.provider.firstRequest.task.text;
        assertTrue(task.contains("2 messages in quick succession"));
        assertTrue(task.contains("aura farming"));
        assertTrue(task.contains("Arsenal match last night?"));
        assertFalse("nothing was already answered, so no expiry credit",
            r.value.drafts.get(0).promptSnapshotJson.contains("already covered"));
    }

    @Test public void aManualRegenerateOnAFullyAnsweredThreadStillTargetsOnlyTheLatest() {
        Fixture f = fixture(1, "Tobi",
            new QueueProvider(reply("Saka balling again honestly")));
        seedAuraThenArsenal(f, 1L);
        pendingDraftAnswering(f, 1L, 2L);   // even the Arsenal line has a pending draft

        Result<DraftOutcome> r = f.service.generateForContact(1L);
        assertTrue(r.ok);
        String task = f.provider.firstRequest.task.text;
        assertTrue("the LATEST message is still the subject of a manual regen",
            task.contains("Arsenal match last night?"));
        assertFalse("the older covered line is never dragged back in",
            task.toLowerCase().contains("aura farming"));
        assertTrue("the expiry credit names the covered lines",
            r.value.drafts.get(0).promptSnapshotJson.contains("already covered"));
    }
}
