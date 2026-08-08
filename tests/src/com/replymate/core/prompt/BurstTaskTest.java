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
        assertTrue(task.contains("Output only the reply text."));
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
