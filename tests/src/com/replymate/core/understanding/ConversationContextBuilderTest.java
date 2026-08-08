package com.replymate.core.understanding;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-1 (message understanding): the clean conversation object — who
 *  sent what on which app, burst state, owner's last reply, cold vs known — is
 *  assembled exactly, before generation, and isolates per-contact inputs. */
public final class ConversationContextBuilderTest {

    private static Contact contact(long id, String name) {
        Contact c = new Contact();
        c.id = id;
        c.displayName = name;
        return c;
    }

    private static Message msg(long contactId, Direction dir, String body, long at) {
        Message m = new Message();
        m.contactId = contactId;
        m.direction = dir;
        m.body = body;
        m.sentAt = at;
        m.channel = Channel.WHATSAPP;
        return m;
    }

    private static Map<String, String> none() {
        return new HashMap<String, String>();
    }

    @Test public void capturesSenderAppTypeBurstStateAndLastReply() {
        Contact c = contact(7, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(7, Direction.INCOMING, "you free?", 1000));
        thread.add(msg(7, Direction.OUTGOING, "on my way", 2000));
        thread.add(msg(7, Direction.INCOMING, "ok great", 3000));
        thread.add(msg(7, Direction.INCOMING, "no wait, 30 mins", 4000));
        List<String> burst = new ArrayList<String>();
        burst.add("ok great");
        burst.add("no wait, 30 mins");

        ConversationContext u = ConversationContextBuilder.build(
            c, thread, burst, none(), none(), null, null, 0);

        assertEquals(7, u.contactId);
        assertEquals("Ada", u.displayName);
        assertEquals("WhatsApp", u.appLabel);
        assertEquals(2, u.burstSize);
        assertTrue(u.burstDetected);
        assertEquals("no wait, 30 mins", u.newestText);
        assertEquals("text", u.newestContentKind);
        assertTrue(u.signals.hasCorrection());
        assertEquals("on my way", u.lastOutgoingText);
        assertEquals(2000, u.lastOutgoingAt);
        assertTrue("zero customization/memory/signals = cold start", u.coldStart);
        assertEquals(0, u.learningSignals);
    }

    @Test public void customizedContactIsNotColdStart() {
        Contact c = contact(9, "Tobi");
        c.toneOverride = "strictly formal";
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(9, Direction.INCOMING, "good morning sir", 1000));
        ConversationContext u = ConversationContextBuilder.build(
            c, thread, java.util.Collections.singletonList("good morning sir"),
            none(), none(), null, null, 0);
        assertFalse(u.coldStart);
        assertFalse(u.burstDetected);
    }

    @Test public void memoryOrSignalsOrExtrasEndColdStart() {
        Contact c = contact(11, "Musa");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(11, Direction.INCOMING, "abeg reply me", 1000));
        List<String> burst = java.util.Collections.singletonList("abeg reply me");
        Map<String, String> global = none();
        Map<String, String> rows = none();

        assertTrue(ConversationContextBuilder.build(
            c, thread, burst, global, rows, null, null, 0).coldStart);
        assertFalse("a learned signal means we know them",
            ConversationContextBuilder.build(
                c, thread, burst, global, rows, null, null, 2).coldStart);
        java.util.List<String> mem = new ArrayList<String>();
        mem.add("- he prefers evening calls");
        assertFalse("memory means we know them",
            ConversationContextBuilder.build(
                c, thread, burst, global, rows, null, mem, 0).coldStart);
        java.util.List<String> extra = new ArrayList<String>();
        extra.add("Learned from the owner's choices: keep replies noticeably shorter;");
        assertFalse("learned hints mean we know them",
            ConversationContextBuilder.build(
                c, thread, burst, global, rows, extra, null, 0).coldStart);
    }

    @Test public void whyLinesNameTheUnderstandingForPromptAudit() {
        Contact c = contact(7, "Ada");
        List<Message> thread = new ArrayList<Message>();
        thread.add(msg(7, Direction.INCOMING, "sure?", 1000));
        thread.add(msg(7, Direction.INCOMING, "no wait, forget it", 2000));
        List<String> burst = new ArrayList<String>();
        burst.add("sure?");
        burst.add("no wait, forget it");
        ConversationContext u = ConversationContextBuilder.build(
            c, thread, burst, none(), none(), null, null, 0);
        List<String> why = u.whyLines();
        assertFalse(why.isEmpty());
        String joined = why.toString();
        assertTrue(joined.contains("2-message burst"));
        assertTrue(joined.contains("WhatsApp"));
        assertTrue(joined.contains("self-correction"));
        assertTrue(joined.contains("cold start"));
    }

    @Test public void groupSenderIsAttributedNotTheGroupTitle() {
        Contact c = contact(5, "Family group");
        Message m = msg(5, Direction.INCOMING, "make we go 7am", 1000);
        m.senderName = "Uncle Chidi";
        List<Message> thread = new ArrayList<Message>();
        thread.add(m);
        ConversationContext u = ConversationContextBuilder.build(
            c, thread, java.util.Collections.singletonList("make we go 7am"),
            none(), none(), null, null, 0);
        assertEquals("Uncle Chidi", u.newestSender);
        assertTrue(u.whyLines().toString().contains("Uncle Chidi"));
    }
}
