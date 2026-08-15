package com.replymate.core.convo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: the classifier decides BEFORE any provider call —
 *  REQUIRED / OPTIONAL / WAIT / NO_REPLY — with honest reasons and targets. */
public final class EngagementClassifierTest {

    private static final long NOW = 1_000_000_000L;

    private ConversationState group(long now, String ownerName, long lastOutAt,
                                    String lastOutText, String[][] lines) {
        ParticipantRegistry reg = new ParticipantRegistry();
        List<ConversationState.Line> burst = new ArrayList<ConversationState.Line>();
        List<String> speakers = new ArrayList<String>();
        for (String[] l : lines) {
            String sender = l[0], text = l[1];
            long ts = Long.parseLong(l[2]);
            Participant p = reg.observe(null, null, sender, ts);
            burst.add(new ConversationState.Line(
                p == null ? "" : p.stableId, sender, text, ts, "key-" + ts));
            if (!speakers.contains(sender)) speakers.add(sender);
        }
        List<String> texts = new ArrayList<String>();
        for (ConversationState.Line l : burst) texts.add(l.text);
        List<String> terms = TopicTracker.topTerms(texts, 2);
        return new ConversationState(7L, true, "Family group", ownerName, reg, speakers,
            burst, 0, TopicTracker.label(terms), "", "", terms,
            lastOutText, lastOutAt, now);
    }

    @Test public void directChatsAreAlwaysRequired() {
        ConversationState st = new ConversationState(3L, false, "Ada", "Spencer",
            new ParticipantRegistry(), Arrays.asList("Ada"),
            Arrays.asList(new ConversationState.Line("n:ada", "Ada", "you dey?",
                NOW - 1000, "k1")),
            0, "", "", "", java.util.Collections.<String>emptyList(), "", 0L, NOW);
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.REPLY_REQUIRED, e.verdict);
        assertEquals("DIRECT_CHAT", e.reason);
        assertEquals("you dey?", e.target.snippet);
    }

    @Test public void emptyBurstMeansNoReply() {
        ConversationState st = group(NOW, "Spencer", NOW - 10, "noted",
            new String[0][]);
        assertEquals(Engagement.Verdict.NO_REPLY,
            EngagementClassifier.evaluate(st, false).verdict);
    }

    @Test public void nameMentionIsRequiredWithHighConfidenceTarget() {
        ConversationState st = group(NOW, "Spencer", 0L, "", new String[][]{
            {"Musa", "match moved to 4pm", String.valueOf(NOW - 60_000)},
            {"Chidi", "Spencer are you still coming?", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.REPLY_REQUIRED, e.verdict);
        assertEquals("MENTIONED", e.reason);
        assertEquals(ReplyTarget.Confidence.HIGH, e.target.confidence);
        assertEquals("Chidi", e.target.senderLabel);
        assertTrue(e.target.snippet.contains("Spencer"));
    }

    @Test public void exactNameMatchOnly_noFuzzyFalsePositives() {
        ConversationState st = group(NOW, "Cass", 0L, "", new String[][]{
            {"Musa", "Cassie said the same thing", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertFalse("Cassie must not match Cass", "MENTIONED".equals(e.reason));
    }

    @Test public void atMentionCountsAsMention() {
        ConversationState st = group(NOW, "Spencer", 0L, "", new String[][]{
            {"Musa", "@spencer did you see this?", String.valueOf(NOW - 5000)}});
        assertEquals("MENTIONED", EngagementClassifier.evaluate(st, false).reason);
    }

    @Test public void emptyOwnerNameDisablesMentionDetectionHonestly() {
        ConversationState st = group(NOW, "", 0L, "", new String[][]{
            {"Musa", "Spencer are you there?", String.valueOf(NOW - 5000)}});
        assertFalse("MENTIONED".equals(EngagementClassifier.evaluate(st, false).reason));
    }

    @Test public void questionRightAfterOwnersMessageIsRequired() {
        ConversationState st = group(NOW, "Spencer", NOW - 30_000, "I can bring the drinks",
            new String[][]{{"Musa", "so who is bringing them?", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.REPLY_REQUIRED, e.verdict);
        assertEquals("REPLIED_TO_YOURS", e.reason);
        assertEquals(ReplyTarget.Confidence.MEDIUM, e.target.confidence);
    }

    @Test public void freshRoomQuestionWaitsFirst() {
        ConversationState st = group(NOW, "Spencer", NOW - 3_600_000L, "morning fam",
            new String[][]{{"Musa", "who has the charger?", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.WAIT, e.verdict);
        assertEquals("ROOM_QUESTION_FRESH", e.reason);
        assertNull(e.target);
    }

    @Test public void roomQuestionAfterWaitIsOptionalWithTarget() {
        ConversationState st = group(NOW, "Spencer", NOW - 3_600_000L, "morning fam",
            new String[][]{{"Musa", "who has the charger?", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, true);   // wait exhausted
        assertEquals(Engagement.Verdict.REPLY_OPTIONAL, e.verdict);
        assertEquals("ROOM_QUESTION", e.reason);
        assertEquals("Musa", e.target.senderLabel);
    }

    @Test public void oldRoomQuestionIsOptionalImmediately() {
        ConversationState st = group(NOW, "Spencer", 0L, "",
            new String[][]{{"Musa", "who has the charger?", String.valueOf(NOW - 300_000)}});
        assertEquals(Engagement.Verdict.REPLY_OPTIONAL,
            EngagementClassifier.evaluate(st, false).verdict);
    }

    @Test public void fillerPingWaitsOnceThenStaysSilent() {
        String[][] lines = {{"Musa", "you there?", String.valueOf(NOW - 5000)}};
        ConversationState st = group(NOW, "Spencer", 0L, "", lines);
        assertEquals(Engagement.Verdict.WAIT, EngagementClassifier.evaluate(st, false).verdict);
        ConversationState st2 = group(NOW, "Spencer", 0L, "", lines);
        assertEquals(Engagement.Verdict.NO_REPLY, EngagementClassifier.evaluate(st2, true).verdict);
    }

    @Test public void activeOwnerGetsAnOptionalDraftWithoutForcedTarget() {
        ConversationState st = group(NOW, "Spencer", NOW - 600_000, "that match was wild",
            new String[][]{
                {"Musa", "next match is saturday morning", String.valueOf(NOW - 60_000)},
                {"Chidi", "hope the pitch is dry this time", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.REPLY_OPTIONAL, e.verdict);
        assertEquals("ACTIVE_MEMBER", e.reason);
        assertNull("general conversation — no quote is forced", e.target);
    }

    @Test public void substantiveBurstWithoutTheOwnerStaysSilent() {
        ConversationState st = group(NOW, "Spencer", 0L, "", new String[][]{
            {"Musa", "the report is finally out", String.valueOf(NOW - 60_000)},
            {"Chidi", "reading it tonight", String.valueOf(NOW - 5000)}});
        Engagement e = EngagementClassifier.evaluate(st, false);
        assertEquals(Engagement.Verdict.NO_REPLY, e.verdict);
        assertEquals("NOT_ADDRESSED", e.reason);
    }
}
