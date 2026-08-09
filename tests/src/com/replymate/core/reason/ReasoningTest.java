package com.replymate.core.reason;

import com.replymate.core.plan.ReplyPlanner;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-6 directive 3: automatic reasoning DEPTH is deterministic —
 *  simple messages stay fast (DEFAULT, provider decides), verified-hard moments
 *  scale up, search results always weigh in, and the audit line never carries
 *  chain-of-thought (metadata only). */
public final class ReasoningTest {

    private static ReplyPlanner.Plan plan(ReplyPlanner.Intent intent,
                                          String... topicWords) {
        ReplyPlanner.Plan p = new ReplyPlanner.Plan();
        p.intent = intent;
        for (String w : topicWords) p.topicWords.add(w);
        return p;
    }

    @Test public void anOrdinaryMessageStaysFast() {
        Reasoning.Decision d = Reasoning.decide(
            plan(ReplyPlanner.Intent.ANSWER, "dinner"), 1, false, 0);
        assertEquals(Reasoning.DEFAULT, d.level);
        assertEquals(0, d.score);
        assertNull("no credit line when nothing special was asked", d.whyLine());
    }

    @Test public void aFiredSearchGateAlwaysDeepens() {
        Reasoning.Decision d = Reasoning.decide(
            plan(ReplyPlanner.Intent.ANSWER, "arsenal"), 1, true, 0);
        assertEquals("search results need weighing", Reasoning.LOW, d.level);
        assertTrue(d.whyLine().contains("deeper thinking: LOW"));
        assertTrue(d.whyLine().contains("live search"));
        assertTrue("the audit line is metadata, never the reasoning itself",
            d.whyLine().contains("never shown or stored"));
    }

    @Test public void searchPlusACarefulIntentScalesHigh() {
        Reasoning.Decision d = Reasoning.decide(
            plan(ReplyPlanner.Intent.ACCEPT_CORRECTION, "meeting"), 1, true, 0);
        assertEquals(Reasoning.HIGH, d.level);
        assertEquals(3, d.score);
        assertTrue(d.whyLine().contains("HIGH"));
        assertTrue(d.whyLine().contains("correction/disagreement/no"));
    }

    @Test public void searchAlongsideALongBurstScalesHigh() {
        Reasoning.Decision d = Reasoning.decide(
            plan(ReplyPlanner.Intent.FOLLOW_UP, "news"), 3, true, 0);
        assertEquals(Reasoning.HIGH, d.level);
    }

    @Test public void multipleHardSignalsWithoutSearchStillScale() {
        Reasoning.Decision d = Reasoning.decide(
            plan(ReplyPlanner.Intent.DISAGREE, "loan"), 3, false, 2);
        assertEquals(Reasoning.HIGH, d.level);
        assertEquals(3, d.score);
    }

    @Test public void oneSoftSignalStaysLow() {
        Reasoning.Decision twoQuestions = Reasoning.decide(
            plan(ReplyPlanner.Intent.ANSWER, "prices"), 1, false, 2);
        assertEquals(Reasoning.LOW, twoQuestions.level);
        Reasoning.Decision bigBurst = Reasoning.decide(
            plan(ReplyPlanner.Intent.ANSWER, "party"), 4, false, 0);
        assertEquals(Reasoning.LOW, bigBurst.level);
        Reasoning.Decision ambiguous = Reasoning.decide(plan(ReplyPlanner.Intent.ANSWER),
            1, false, 0);
        assertEquals("an empty topic on a real burst is harder to read",
            Reasoning.LOW, ambiguous.level);
    }

    @Test public void missingPlanNeverCrashes() {
        Reasoning.Decision d = Reasoning.decide(null, 2, true, 0);
        assertEquals(Reasoning.LOW, d.level);   // search alone still weighs in
    }
}
