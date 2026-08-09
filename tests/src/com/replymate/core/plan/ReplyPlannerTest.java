package com.replymate.core.plan;

import com.replymate.core.understanding.BurstSignals;
import com.replymate.core.understanding.ConversationContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-5 pins (directive 2 — reply planning): the planner must decide
 *  what the moment is, what the reply must DO (answer/follow-up/refusal/correction/
 *  comfort/joke/disagreement/acknowledgment), which burst lines matter and which to
 *  skip, and what length fits — deterministically, conservatively, audibly. */
public final class ReplyPlannerTest {

    private static ConversationContext ctx(String newest, List<String> burst) {
        return new ConversationContext(1L, "Amara", "WhatsApp",
            burst.size(), burst.size(), newest, "text", "", 1000L,
            BurstSignals.detect(burst), "", 0L, false, 0);
    }

    private static ReplyPlanner.Plan plan(String newest, List<String> burst) {
        return ReplyPlanner.plan(ctx(newest, burst), burst, null, null);
    }

    /* ------------------------------------------------------------- intents --- */

    @Test public void directQuestionMeansAnswerFirst() {
        ReplyPlanner.Plan p = plan("are you still coming tonight?",
            Collections.singletonList("are you still coming tonight?"));
        assertEquals(ReplyPlanner.Intent.ANSWER, p.intent);
        assertTrue(p.situation.contains("asked"));
        assertTrue(p.compactLine().contains("answer their question first"));
        assertTrue(p.why.get(0).contains("plan (answer)"));
    }

    @Test public void badNewsMeansComfortFirst() {
        ReplyPlanner.Plan p = plan("they rushed dad to the hospital this morning",
            Collections.singletonList("they rushed dad to the hospital this morning"));
        assertEquals(ReplyPlanner.Intent.COMFORT, p.intent);
        assertTrue(p.intent.instruction.contains("comfort first"));
        assertTrue("warmth beats the short-control brevity",
            ReplyPlanner.plan(ctx("dad is sick", Collections.singletonList("dad is sick")),
                Collections.singletonList("dad is sick"), "short", null)
                .lengthPlan.contains("warmth"));
    }

    @Test public void theirCorrectionMeansAcceptAndAdjust() {
        ReplyPlanner.Plan p = plan("no I said next tuesday, not thursday",
            Collections.singletonList("no I said next tuesday, not thursday"));
        assertEquals(ReplyPlanner.Intent.ACCEPT_CORRECTION, p.intent);
        assertTrue(p.intent.instruction.contains("without sulking"));
    }

    @Test public void theirDisagreementMeansAnswerTheObjectionHonestly() {
        ReplyPlanner.Plan p = plan("i disagree, that's not how it happened",
            Collections.singletonList("i disagree, that's not how it happened"));
        assertEquals(ReplyPlanner.Intent.DISAGREE, p.intent);
        assertTrue(p.intent.instruction.contains("never attack"));
    }

    @Test public void theirNoMeansRespectItGracefully() {
        ReplyPlanner.Plan p = plan("can't make it, rain check?",
            Collections.singletonList("can't make it, rain check?"));
        assertEquals(ReplyPlanner.Intent.RESPECT_NO, p.intent);
        assertTrue(p.intent.instruction.contains("never argue the refusal"));
    }

    @Test public void theirNewsMeansReactThenOneFollowUp() {
        ReplyPlanner.Plan p = plan("guess what — I got the job!",
            Collections.singletonList("guess what — I got the job!"));
        assertEquals(ReplyPlanner.Intent.FOLLOW_UP, p.intent);
        assertTrue(p.intent.instruction.contains("ONE natural follow-up"));
    }

    @Test public void theirJokeMeansMatchTheEnergy() {
        ReplyPlanner.Plan p = plan("lol you always do this 😂",
            Collections.singletonList("lol you always do this 😂"));
        assertEquals(ReplyPlanner.Intent.JOKE, p.intent);
        assertTrue(p.lengthPlan.contains("punchy"));
    }

    @Test public void noQuestionNoNewsMeansAcknowledgeAndContinue() {
        ReplyPlanner.Plan p = plan("just got back from the gym",
            Collections.singletonList("just got back from the gym"));
        assertEquals(ReplyPlanner.Intent.ACKNOWLEDGE, p.intent);
        assertTrue(p.situation.contains("no direct question"));
    }

    /* ---------------------------------------------------- burst focus/ignore - */

    @Test public void fillerLinesAreConsciouslyIgnoredAndCorrectionsFocused() {
        List<String> burst = Arrays.asList("you there", "actually make it 7pm not 8", "??",
            "see you then");
        ConversationContext ud = ctx("see you then", burst);
        ReplyPlanner.Plan p = ReplyPlanner.plan(ud, burst, null, null);
        assertTrue("filler ping ignored", p.ignored.toString().contains("you there"));
        assertTrue("'??' ignored", p.ignored.toString().contains("??"));
        assertTrue("the correction is in focus: " + p.focus,
            p.focus.toString().contains("make it 7pm not 8"));
        assertTrue("the newest line is always in focus",
            p.focus.toString().contains("see you then"));
        assertTrue(p.why.get(0).contains("skipping 2 filler line(s)"));
        assertTrue(p.fullBlock().contains("don't answer these"));
    }

    /* -------------------------------------------------------- length fit ----- */

    @Test public void explicitShortWinsAndOffKeepsPlannerSilent() {
        List<String> one = Collections.singletonList("how far, you ate?");
        assertTrue(ReplyPlanner.plan(ctx("how far, you ate?", one), one, "short", null)
                .lengthPlan.contains("short"));
        assertEquals("off = the planner says nothing about length",
            "", ReplyPlanner.plan(ctx("how far", one), one, "", null).lengthPlan);
        assertTrue(ReplyPlanner.plan(ctx("how far", one), one, "", null)
                .why.get(1).contains("Off"));
    }

    @Test public void topicWordsSkipStopwordsAndRankByWeight() {
        ReplyPlanner.Plan p = plan("flights to abuja for the wedding are climbing",
            Collections.singletonList("flights to abuja for the wedding are climbing"));
        assertTrue(p.topicWords.contains("flights"));
        assertTrue(p.topicWords.contains("wedding"));
        assertFalse(p.topicWords.contains("the"));
        assertTrue(p.fullBlock().contains("This is about:"));
    }

    @Test public void nullsAndEmptiesNeverThrow() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(null, null, null, null);
        assertNotNull(p.intent);
        assertFalse(p.compactLine().isEmpty());
    }

    /* ------------------------------------------------------------- depth ----- */

    @Test public void planDepthParsingAndAuditLinesAreHonest() {
        assertEquals(PlanDepth.NORMAL, PlanDepth.normalize(null));
        assertEquals(PlanDepth.NORMAL, PlanDepth.normalize("junk"));
        assertEquals(PlanDepth.BASIC, PlanDepth.normalize(" BASIC "));
        assertEquals(PlanDepth.DEEP, PlanDepth.normalize("deep"));
        assertTrue(PlanDepth.auditLine(PlanDepth.BASIC).contains("no planning block"));
        assertTrue(PlanDepth.auditLine(PlanDepth.NORMAL).contains("compact plan line"));
        assertTrue(PlanDepth.auditLine(PlanDepth.DEEP).contains("full planning block"));
    }
}
