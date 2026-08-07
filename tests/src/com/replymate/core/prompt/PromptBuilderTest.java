package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.Turn;
import com.replymate.core.budget.TokenBudgeter;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PromptBuilderTest {

    private static ProfileService.Profile profile(String name) {
        return new ProfileService.Profile(name, "English, Pidgin", "Dev in PH", "football");
    }

    @Test public void systemContainsOwnerContactAndStyle() {
        Contact c = Fakes.contact(1, "Amara");
        c.relationshipType = "close friend";
        c.relationshipNotes = "we met at uni";
        c.toneOverride = "playful";
        PromptBundle b = new PromptBundle(profile("Kelechi"), c, "short slangy lines",
            new ArrayList<Message>());
        ChatRequest req = PromptBuilder.build(b);

        assertTrue(req.system.contains("Kelechi"));
        assertTrue(req.system.contains("Amara"));
        assertTrue(req.system.contains("close friend"));
        assertTrue(req.system.contains("we met at uni"));
        assertTrue(req.system.contains("playful"));
        assertTrue(req.system.contains("short slangy lines"));
        assertTrue(req.system.contains("English, Pidgin"));
        assertFalse(req.system.toLowerCase().contains("as an ai"));
    }

    @Test public void fallbackStyleWhenNoRules() {
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile("K"), Fakes.contact(1, "Bo"), "", new ArrayList<Message>()));
        assertTrue(req.system.contains("natural, human"));
    }

    @Test public void charterFoundationReplacesOldIdentity() {
        // P-polish: the owner's charter now founds every reply prompt (see
        // VoiceCharterTest for the byte-exact pin); the old hand-written identity
        // preamble is gone, plumbing (style/rules/contact) survives.
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile("K"), Fakes.contact(1, "Bo"), "", new ArrayList<Message>()));
        assertTrue(req.system.startsWith(VoiceCharter.TEXT));
        assertTrue(req.system.contains("Rules: reply in K's voice"));
        assertTrue(req.system.contains("Conversation partner: Bo"));
    }

    @Test public void turnsAreNamePrefixedAndRoleMapped() {
        Contact c = Fakes.contact(1, "Amara");
        List<Message> thread = new ArrayList<Message>();
        thread.add(Fakes.msg(1, Direction.INCOMING, "hey, you around?"));
        thread.add(Fakes.msg(1, Direction.OUTGOING, "yes o"));
        thread.add(Fakes.msg(1, Direction.INCOMING, "cool, call me"));
        ChatRequest req = PromptBuilder.build(new PromptBundle(profile("K"), c, "", thread));

        assertEquals(3, req.turns.size());
        assertEquals(Turn.Role.USER, req.turns.get(0).role);
        assertEquals("Amara: hey, you around?", req.turns.get(0).text);
        assertEquals(Turn.Role.MODEL, req.turns.get(1).role);
        assertEquals("yes o", req.turns.get(1).text);
        assertEquals("Amara: cool, call me", req.turns.get(2).text);
    }

    @Test public void taskTurnReferencesPartnerAndAsksForReplyOnly() {
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            profile("Kelechi"), Fakes.contact(1, "Amara"), "", new ArrayList<Message>()));
        assertNotNull(req.task);
        assertEquals(Turn.Role.USER, req.task.role);
        assertTrue(req.task.text.contains("Amara"));
        assertTrue(req.task.text.contains("Kelechi"));
        assertTrue(req.task.text.contains("only the reply text"));
    }

    @Test public void emptyAndNullProfileStillBuilds() {
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            null, Fakes.contact(1, "X"), null, null));
        assertTrue(req.system.contains("X"));
        assertEquals(0, req.turns.size());
    }

    @Test public void budgetTruncatesOldestButKeepsSystemAndTask() {
        Contact c = Fakes.contact(1, "Spam");
        List<Message> thread = new ArrayList<Message>();
        String big = big(2000);
        for (int i = 0; i < 40; i++) thread.add(Fakes.msg(1, Direction.INCOMING, big + " #" + i));
        ChatRequest req = PromptBuilder.build(new PromptBundle(profile("K"), c, "", thread));

        assertTrue("must fit budget", TokenBudgeter.estimate(req) <= TokenBudgeter.DEFAULT_MAX_INPUT);
        assertTrue("should have dropped oldest", req.turns.size() < 40);
        assertTrue("keeps newest", req.turns.get(req.turns.size() - 1).text.contains("#39"));
        assertTrue(req.system.contains("Spam"));
        assertNotNull(req.task);
    }

    /* ---------------- P-audit-deep: content-kind honesty in the task turn ---------- */

    @Test public void captionedPhotoTaskDisclosesUnseenMediaAndQuotesTheCaption() {
        Contact c = Fakes.contact(1, "Amara");
        List<Message> thread = new ArrayList<Message>();
        Message photo = Fakes.msg(1, Direction.INCOMING, "rate this fit");
        photo.contentKind = "image";
        photo.channel = com.replymate.core.model.Channel.WHATSAPP;
        thread.add(photo);
        ChatRequest req = PromptBuilder.build(new PromptBundle(profile("K"), c, "", thread));
        assertTrue(req.task.text.contains("rate this fit"));
        assertTrue("task must admit the photo cannot be seen",
            req.task.text.contains("cannot see"));
        assertTrue(req.task.text.contains("WhatsApp"));
    }

    @Test public void missedCallTaskAnswersWithoutInventingSpeech() {
        Contact c = Fakes.contact(1, "Amara");
        List<Message> thread = new ArrayList<Message>();
        Message call = Fakes.msg(1, Direction.INCOMING, "Missed voice call");
        call.contentKind = "call";
        call.channel = com.replymate.core.model.Channel.WHATSAPP;
        thread.add(call);
        ChatRequest req = PromptBuilder.build(new PromptBundle(profile("K"), c, "", thread));
        assertTrue(req.task.text.contains("Missed voice call"));
        assertTrue("never invent what was said on the call",
            req.task.text.contains("never invent what was said"));
    }

    @Test public void plainTextTaskCarriesNoMediaDisclosure() {
        Contact c = Fakes.contact(1, "Amara");
        List<Message> thread = new ArrayList<Message>();
        Message t = Fakes.msg(1, Direction.INCOMING, "you dey around?");
        t.channel = com.replymate.core.model.Channel.WHATSAPP;
        thread.add(t);
        ChatRequest req = PromptBuilder.build(new PromptBundle(profile("K"), c, "", thread));
        assertFalse(req.task.text.contains("cannot see"));
    }

    private static String big(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append('x');
        return sb.toString();
    }
}
