package com.replymate.core.prompt;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 (context expiry): burstTailUsableIncoming's watermark cut —
 *  messages at/below the newest already-answered message id are never re-answered
 *  in a new burst, while watermark 0 and synthetic id-0 threads stay legacy. */
public final class BurstWatermarkTest {

    private static Message msg(long contactId, long id, Direction dir, String body) {
        Message m = Fakes.msg(contactId, dir, body);
        m.id = id;
        return m;
    }

    private static List<Message> thread(Object... spec) {
        List<Message> out = new ArrayList<Message>();
        for (int i = 0; i + 2 < spec.length; i += 3) {
            out.add(msg(1L, (Long) spec[i], (Direction) spec[i + 1], (String) spec[i + 2]));
        }
        return out;
    }

    @Test public void theWatermarkCutsAlreadyAnsweredMessagesOutOfTheNewBurst() {
        List<Message> t = thread(
            1L, Direction.INCOMING, "bro your new profile pic is aura farming",
            2L, Direction.INCOMING, "did you watch the Arsenal match last night?");
        List<String> full = PromptBuilder.burstTailUsableIncoming(t, 6);
        assertEquals("without drafts both unread lines form one burst (legacy)", 2, full.size());
        List<String> scoped = PromptBuilder.burstTailUsableIncoming(t, 6, 1L);
        assertEquals(1, scoped.size());
        assertEquals("only the NEW message is being answered",
            "did you watch the Arsenal match last night?", scoped.get(0));
    }

    @Test public void aFullyAnsweredThreadLeavesNothingToReAnswer() {
        List<Message> t = thread(
            1L, Direction.INCOMING, "bro your new profile pic is aura farming",
            2L, Direction.INCOMING, "did you watch the Arsenal match last night?");
        assertTrue(PromptBuilder.burstTailUsableIncoming(t, 6, 2L).isEmpty());
    }

    @Test public void theOutgoingStopStillEndsTheBurstBeforeTheWatermarkMatters() {
        List<Message> t = thread(
            1L, Direction.INCOMING, "you never replied about the jersey",
            2L, Direction.OUTGOING, "lol chill I was busy",
            3L, Direction.INCOMING, "anyway. Arsenal tomorrow?");
        List<String> scoped = PromptBuilder.burstTailUsableIncoming(t, 6, 1L);
        assertEquals(1, scoped.size());
        assertEquals("anyway. Arsenal tomorrow?", scoped.get(0));
    }

    @Test public void syntheticIdZeroMessagesAreNeverExcluded() {
        // previews/transforms fabricate threads whose ids are all 0: the watermark
        // must not wipe them (0 means "no real row", not "answered").
        List<Message> t = thread(
            0L, Direction.INCOMING, "first synthetic line",
            0L, Direction.INCOMING, "second synthetic line");
        assertEquals(2, PromptBuilder.burstTailUsableIncoming(t, 6, 99L).size());
    }

    @Test public void aMediaOnlyItemAboveTheWatermarkDoesNotBreakTheWalk() {
        List<Message> t = thread(
            1L, Direction.INCOMING, "this one is still fresh",
            2L, Direction.INCOMING, "📷 Photo",
            3L, Direction.INCOMING, "did you see it though");
        // "📷 Photo" is usable text in fixtures, so use the real placeholder:
        t.get(1).body = "";
        List<String> scoped = PromptBuilder.burstTailUsableIncoming(t, 6, 0L);
        assertEquals(2, scoped.size());
        assertEquals("did you see it though", scoped.get(1));
    }
}
