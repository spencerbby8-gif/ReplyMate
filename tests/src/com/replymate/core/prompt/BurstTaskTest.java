package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-6: rapid multiple texts from the same chat are ONE burst —
 *  summarized into one topic, answered by ONE draft — never N separate replies. */
public class BurstTaskTest {

    private static ProfileService.Profile profile() {
        return new ProfileService.Profile("Kelechi", "English", "", "");
    }

    private static Message in(long cid, String body) {
        return Fakes.msg(cid, Direction.INCOMING, body);
    }

    @Test public void unreadTailCollectsBackToTheLastOutgoingOnly() {
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "older context"));
        thread.add(Fakes.msg(1, Direction.OUTGOING, "my reply"));
        thread.add(in(1, "are you coming"));
        thread.add(in(1, "?"));
        thread.add(in(1, "need an answer by 5"));
        List<String> tail = PromptBuilder.burstTailUsableIncoming(thread, 6);
        assertEquals(3, tail.size());
        assertEquals("are you coming", tail.get(0));       // oldest-first
        assertEquals("need an answer by 5", tail.get(2));
    }

    @Test public void burstTaskQuotesEveryMessageAndDemandsOneReply() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "are you coming"));
        thread.add(in(1, "?"));
        thread.add(in(1, "need an answer by 5"));
        ChatRequest req = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread));
        String task = req.task.text;
        assertTrue(task.contains("a burst"));
        assertTrue(task.contains("are you coming"));
        assertTrue(task.contains("?"));
        assertTrue(task.contains("need an answer by 5"));
        assertTrue(task.contains("single point"));
        assertTrue(task.contains("ONE reply"));
        assertTrue(task.contains("Do NOT answer each message separately"));
        assertTrue("filler repeats are ignored", task.contains("filler"));
        assertTrue(task.contains("Output only the reply text."));
    }

    /* -------------------------------------------- P-background-8 human-burst rules */

    @Test public void burstTaskForbidsPerMessageRepliesAndNamesHumanRules() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "are you coming"));
        thread.add(in(1, "?"));
        thread.add(in(1, "need an answer by 5"));
        String task = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread)).task.text;
        assertTrue("one natural reply only", task.contains("ONE reply"));
        assertTrue("the burst is summarized, not enumerated",
            task.contains("single point"));
        assertTrue("corrections win", task.contains("the correction wins"));
        assertTrue("topic shifts anchor on the newest",
            task.contains("answer the newest topic"));
        assertTrue("filler ignored", task.contains("filler"));
    }

    @Test public void questionFollowedByACorrectionKeepsBothQuoted() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "can you pick up 4 bottles"));
        thread.add(in(1, "no wait, 6"));
        ChatRequest req = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread));
        assertTrue(req.task.text.contains("can you pick up 4 bottles"));
        assertTrue(req.task.text.contains("no wait, 6"));
        assertTrue("the correction rule must reach the prompt",
            req.task.text.contains("correction wins"));
    }

    @Test public void midBurstTopicChangeIsStillOneReply() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "did you see the match"));
        thread.add(in(1, "crazy game"));
        thread.add(in(1, "anyway can you send that document"));
        String task = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread)).task.text;
        assertTrue(task.contains("did you see the match"));
        assertTrue(task.contains("anyway can you send that document"));
        assertTrue("topic-shift rule must reach the prompt",
            task.contains("newest topic"));
        assertTrue(task.contains("ONE reply"));
    }

    @Test public void repeatedMessagesCollapseIntoOneBurstTask() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "you there"));
        thread.add(in(1, "hello"));
        thread.add(in(1, "hello"));
        thread.add(in(1, "??"));
        String task = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread)).task.text;
        assertTrue("repeats are still ONE burst, not 4 replies",
            task.contains("a burst"));
        assertTrue("filler-repeat rule must reach the prompt",
            task.contains("Ignore pure filler repeats"));
    }

    @Test public void singleIncomingKeepsTheOriginalTaskShape() {
        Contact c = Fakes.contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(Fakes.msg(1, Direction.OUTGOING, "on it"));
        thread.add(in(1, "thanks"));
        ChatRequest req = PromptBuilder.build(
            new PromptBundle(profile(), c, "", thread));
        assertFalse(req.task.text.contains("a burst"));
        assertTrue(req.task.text.contains("\"thanks\""));
        assertTrue(req.task.text.contains("Answer THAT message"));
    }

    @Test public void outgoingInsideTheTailEndsTheBurst() {
        // "ok got it" answered mid-burst → only what came AFTER is still a burst
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "first part"));
        thread.add(Fakes.msg(1, Direction.OUTGOING, "ok got it"));
        thread.add(in(1, "second part"));
        List<String> tail = PromptBuilder.burstTailUsableIncoming(thread, 6);
        assertEquals(1, tail.size());
        assertEquals("second part", tail.get(0));
    }

    @Test public void mediaPlaceholdersInsideABurstAreSkipped() {
        List<Message> thread = new ArrayList<Message>();
        thread.add(in(1, "look at this"));
        thread.add(in(1, com.replymate.core.model.ContentKind.IMAGE.placeholder()));
        thread.add(in(1, "what do you think"));
        List<String> tail = PromptBuilder.burstTailUsableIncoming(thread, 6);
        assertEquals(2, tail.size());
        assertTrue(tail.get(0).equals("look at this"));
        assertTrue(tail.get(1).equals("what do you think"));
    }
}
