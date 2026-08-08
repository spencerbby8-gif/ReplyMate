package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.understanding.ConversationContext;
import com.replymate.core.understanding.ConversationContextBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-1: the understanding layer's effect on the ACTUAL generation
 *  prompt — cold-start system line, burst mechanical annotations, and the hard
 *  byte-compat guarantee: contacts without signals get the legacy prompt verbatim. */
public final class PromptUnderstandingTest {

    private static Contact contact(long id, String name) {
        Contact c = new Contact();
        c.id = id;
        c.displayName = name;
        return c;
    }

    private static Message msg(long cid, Direction dir, String body, long at) {
        Message m = new Message();
        m.contactId = cid;
        m.direction = dir;
        m.body = body;
        m.sentAt = at;
        m.channel = Channel.WHATSAPP;
        return m;
    }

    private static Map<String, String> none() { return new HashMap<String, String>(); }

    private static PromptBundle bundle(Contact c, List<Message> thread,
                                       ConversationContext u) {
        PromptBundle b = new PromptBundle(null, c, "", thread);
        b.understanding = u;
        return b;
    }

    @Test public void coldStartAddsAnExplicitSituationLineToTheSystemPrompt() {
        Contact c = contact(1, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(1, Direction.INCOMING, "hi, do you sell data?", 1000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            Collections.singletonList("hi, do you sell data?"), none(), none(),
            null, null, 0);
        ChatRequest req = PromptBuilder.build(bundle(c, thread, u));
        assertTrue(req.system.contains("New chat — you barely know Ada yet"));
        assertTrue(req.system.contains("never fake shared history"));

        ChatRequest legacy = PromptBuilder.build(new PromptBundle(null, c, "", thread));
        assertFalse("legacy path never invents a situation line",
            legacy.system.contains("New chat —"));
    }

    @Test public void knownContactGetsNoColdStartLine() {
        Contact c = contact(2, "Tobi");
        c.toneOverride = "playful";
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(2, Direction.INCOMING, "guy where you dey?", 1000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            Collections.singletonList("guy where you dey?"), none(), none(),
            null, null, 0);
        ChatRequest req = PromptBuilder.build(bundle(c, thread, u));
        assertFalse(req.system.contains("New chat —"));
    }

    @Test public void correctionSignalAddsAGroundedBurstAnnotation() {
        Contact c = contact(3, "Musa");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(3, Direction.INCOMING, "come by 7", 1000));
        thread.add(msg(3, Direction.INCOMING, "no wait, 8", 2000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            promptBurst(thread), none(), none(), null, null, 0);
        ChatRequest req = PromptBuilder.build(bundle(c, thread, u));
        assertTrue(req.task.text.contains("Mechanical read"));
        assertTrue(req.task.text.contains("self-correction"));
        assertTrue("annotation lands before the closing instruction",
            req.task.text.indexOf("self-correction")
                < req.task.text.indexOf("Output only the reply text."));
    }

    @Test public void noSignalsMeansByteIdenticalLegacyTask() {
        Contact c = contact(4, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(4, Direction.INCOMING, "see you at 5", 1000));
        thread.add(msg(4, Direction.INCOMING, "bring the charger", 2000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            promptBurst(thread), none(), none(), null, null, 0);
        assertTrue(ConversationContextBuilder.burstAnnotations(u).isEmpty());
        ChatRequest modern = PromptBuilder.build(bundle(c, thread, u));
        ChatRequest legacy = PromptBuilder.build(new PromptBundle(null, c, "", thread));
        assertEquals("no fired signal ⇒ the task is the legacy task, verbatim",
            legacy.task.text, modern.task.text);
    }

    @Test public void singleMessagePathIsUntouchedEvenWithUnderstanding() {
        Contact c = contact(5, "Zainab");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(5, Direction.INCOMING, "send the invoice please", 1000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            Collections.singletonList("send the invoice please"), none(), none(),
            null, null, 0);
        ChatRequest modern = PromptBuilder.build(bundle(c, thread, u));
        ChatRequest legacy = PromptBuilder.build(new PromptBundle(null, c, "", thread));
        assertEquals(legacy.task.text, modern.task.text);
    }

    @Test public void multiQuestionBurstIsAnnotated() {
        Contact c = contact(6, "Chidi");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(6, Direction.INCOMING, "you landing today?", 1000));
        thread.add(msg(6, Direction.INCOMING, "and are we meeting at the venue?", 2000));
        ConversationContext u = ConversationContextBuilder.build(c, thread,
            promptBurst(thread), none(), none(), null, null, 0);
        ChatRequest req = PromptBuilder.build(bundle(c, thread, u));
        assertTrue(req.task.text.contains("2 of the 2 burst lines are questions"));
    }

    /** P-intelligence-2 (item 1): starting in the MIDDLE of a conversation — older
     *  context stays as attributed history, the owner's last reply is a model turn,
     *  the burst tail never crosses back across it, and the task leads with the
     *  newest message. */
    @Test public void middleOfConversationKeepsRolesContextAndBurstBoundaries() {
        Contact c = contact(7, "Ada");
        List<Message> thread = new ArrayList<Message>();
        Message oldIn = msg(7, Direction.INCOMING, "how far, the price?", 1000);
        Message mine = msg(7, Direction.OUTGOING, "150k last price", 2000);
        Message b1 = msg(7, Direction.INCOMING, "okay I hear you", 3000);
        Message b2 = msg(7, Direction.INCOMING, "can you do 120?", 4000);
        thread.add(oldIn); thread.add(mine); thread.add(b1); thread.add(b2);

        ConversationContext u = ConversationContextBuilder.build(c, thread,
            promptBurst(thread), none(), none(), null, null, 0);
        assertEquals("burst stops at the owner's last reply — never swallows older turns",
            2, u.burstSize);
        assertEquals("150k last price", u.lastOutgoingText);

        ChatRequest req = PromptBuilder.build(bundle(c, thread, u));
        // roles: older incoming is an attributed USER turn, owner's reply is MODEL,
        // then the two new incoming lines follow in order.
        java.util.List<com.replymate.core.ai.Turn> turns = req.turns;
        assertEquals(4, turns.size());
        assertEquals(com.replymate.core.ai.Turn.Role.USER, turns.get(0).role);
        assertTrue(turns.get(0).text.startsWith("Ada: "));
        assertTrue(turns.get(0).text.contains("the price"));
        assertEquals(com.replymate.core.ai.Turn.Role.MODEL, turns.get(1).role);
        assertEquals("150k last price", turns.get(1).text);
        assertEquals(com.replymate.core.ai.Turn.Role.USER, turns.get(2).role);
        assertEquals(com.replymate.core.ai.Turn.Role.USER, turns.get(3).role);
        assertTrue(turns.get(3).text.contains("can you do 120"));
        // the task is the burst task leading with the newest money question, not
        // the settled older turn
        assertTrue(req.task.text.contains("fired 2 messages"));
        assertTrue(req.task.text.contains("can you do 120?"));
        assertFalse("the older, already-answered question is never re-quoted as the point",
            req.task.text.contains("The message you're replying to"));
    }

    private static List<String> promptBurst(List<Message> thread) {
        return PromptBuilder.burstTailUsableIncoming(thread, 6);
    }
}
