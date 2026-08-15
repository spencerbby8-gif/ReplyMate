package com.replymate.core.convo;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: topic = dominant burst terms, mechanically derived. */
public final class TopicTrackerTest {

    @Test public void dominantContentTermsWin() {
        List<String> t = TopicTracker.topTerms(Arrays.asList(
            "match tickets are out", "did you get the match tickets?", "match was mad"), 2);
        assertEquals("match", t.get(0));
        assertTrue(t.contains("tickets"));
    }

    @Test public void stopwordsAndShortTokensNeverBecomeTopics() {
        List<String> t = TopicTracker.topTerms(Arrays.asList(
            "okay lol yeah", "haha really very", "lol okay haha", "really okay"), 3);
        assertTrue("pure filler produces no topic", t.isEmpty());
    }

    @Test public void labelJoinsTheTopTwo() {
        assertEquals("", TopicTracker.label(java.util.Collections.<String>emptyList()));
        assertEquals("match", TopicTracker.label(Arrays.asList("match")));
        assertEquals("match, tickets", TopicTracker.label(Arrays.asList("match", "tickets")));
    }

    @Test public void overlapKeepsItTheSameTopic() {
        assertTrue(TopicTracker.sameTopic(
            Arrays.asList("match", "tickets"), Arrays.asList("match", "stadium")));
        assertFalse(TopicTracker.sameTopic(
            Arrays.asList("match", "tickets"), Arrays.asList("dinner", "sushi")));
        assertFalse(TopicTracker.sameTopic(
            java.util.Collections.<String>emptyList(), Arrays.asList("match")));
    }

    @Test public void subtopicNarrowsFromTheNewestLine() {
        List<String> burst = Arrays.asList("match", "tickets");
        assertEquals("stadium", TopicTracker.subtopic("the stadium parking is terrible", burst));
        // the newest line's own first non-burst content term leads ("sold" = the
        // new information); pure echoes of the burst terms produce nothing
        assertEquals("sold", TopicTracker.subtopic("tickets are sold out now", burst));
        assertEquals("", TopicTracker.subtopic("tickets please tickets", burst));
    }

    @Test public void emptyBurstsHaveNoTopic() {
        assertTrue(TopicTracker.topTerms(null, 2).isEmpty());
        assertEquals("", TopicTracker.subtopic(null, null));
    }
}
