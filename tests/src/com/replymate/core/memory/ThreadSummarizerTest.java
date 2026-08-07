package com.replymate.core.memory;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Rolling-summary determinism + honesty (P-memory-audit). */
public class ThreadSummarizerTest {

    private static List<Message> thread(String... bodies) {
        List<Message> out = new ArrayList<Message>();
        long ts = 1_000;
        boolean flip = false;
        for (String b : bodies) {
            Message m = Fakes.msg(1, flip ? Direction.OUTGOING : Direction.INCOMING, b);
            m.sentAt = ts += 60_000;
            out.add(m);
            flip = !flip;
        }
        return out;
    }

    @Test public void emptyAndNullYieldEmpty() {
        assertEquals("", ThreadSummarizer.summarize(null, "Ada").text);
        assertEquals("", ThreadSummarizer.summarize(new ArrayList<Message>(), "Ada").text);
        assertEquals(0, ThreadSummarizer.summarize(null, "Ada").mediaEvents);
    }

    @Test public void deterministicSameInputSameText() {
        List<Message> t = thread("are we still meeting tomorrow?",
            "yes o, 4pm works", "don't forget the ₦5,000 for delivery", "lol 😂",
            "cool cool", "sent you the address already");
        String a = ThreadSummarizer.summarize(t, "Ada").text;
        String b = ThreadSummarizer.summarize(t, "Ada").text;
        assertEquals(a, b);
        assertFalse(a.isEmpty());
    }

    @Test public void questionsPlansAmountsSurviveSmallTalkDropped() {
        List<Message> t = thread("hey", "lol", "haha 😂", "ok", "are we still meeting tomorrow?",
            "yes o, 4pm works", "cool", "don't forget the ₦5,000 for delivery");
        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(t, "Ada");
        assertTrue(s.text, s.text.contains("are we still meeting tomorrow?"));
        assertTrue(s.text, s.text.contains("₦5,000"));
        assertTrue(s.text, s.text.contains("4pm"));
        assertFalse(s.text, s.text.contains("lol"));
        assertEquals(8, s.msgCount);
    }

    @Test public void attributionIsExplicit() {
        List<Message> t = thread("what time is the meeting tomorrow?",
            "4pm works for me, let's do it");
        String text = ThreadSummarizer.summarize(t, "Ada").text;
        assertTrue(text, text.contains("Ada: what time is the meeting tomorrow?"));
        assertTrue(text, text.contains("you: 4pm works for me"));
    }

    @Test public void mediaIsCountedNeverParaphrased() {
        List<Message> t = thread("see this photo", "did you get the file?");
        Message photo = Fakes.msg(1, Direction.INCOMING,
            com.replymate.core.model.ContentKind.IMAGE.placeholder());
        photo.contentKind = com.replymate.core.model.ContentKind.IMAGE.wire;
        photo.sentAt = t.get(t.size() - 1).sentAt + 60_000;
        Message voice = Fakes.msg(1, Direction.INCOMING,
            com.replymate.core.model.ContentKind.VOICE.placeholder());
        voice.contentKind = com.replymate.core.model.ContentKind.VOICE.wire;
        voice.sentAt = photo.sentAt + 60_000;
        t.add(photo);
        t.add(voice);
        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(t, "Ada");
        assertEquals(2, s.mediaEvents);
        assertTrue(s.text, s.text.contains("2 media item(s)"));
        assertFalse("media placeholder must never be paraphrased as content",
            s.text.contains("open in chat app"));
    }

    @Test public void oldestKeptLinesAreLimitedByBudgetChronological() {
        List<String> bodies = new ArrayList<String>();
        for (int i = 1; i <= 60; i++) {
            bodies.add("plan item " + i + " tomorrow? delivery number " + (1000 + i));
        }
        List<Message> t = thread(bodies.toArray(new String[0]));
        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(t, "Ada");
        assertTrue("budget respected: " + s.text.length(),
            s.text.length() <= ThreadSummarizer.CHAR_BUDGET + 140);
        // chronological: the earliest kept index appears before the latest kept index
        int first = s.text.indexOf("plan item");
        int last = s.text.lastIndexOf("plan item");
        assertTrue(first >= 0 && last > first);
        assertTrue("newer items dominate under budget pressure",
            s.text.contains("plan item 60") || s.text.contains("plan item 59"));
    }

    @Test public void coversUntilTsIsLastOlderMessage() {
        List<Message> t = thread("meeting tomorrow?", "yes 4pm");
        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(t, "Ada");
        assertEquals(t.get(t.size() - 1).sentAt, s.coveredUntilTs);
    }

    @Test public void windowIsCappedAtMaxInput() {
        List<String> bodies = new ArrayList<String>();
        for (int i = 0; i < 500; i++) bodies.add("random small talk " + i);
        bodies.add("final plan tomorrow 3pm?");
        List<Message> t = thread(bodies.toArray(new String[0]));
        ThreadSummarizer.Summary s = ThreadSummarizer.summarize(t, "Ada");
        assertEquals(ThreadSummarizer.MAX_INPUT, s.msgCount);
        assertTrue(s.text, s.text.contains("final plan tomorrow 3pm?"));
    }
}
