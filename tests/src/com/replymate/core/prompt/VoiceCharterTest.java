package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

/** The owner's charter is the reply-engine FOUNDATION: it must ship VERBATIM
 *  (owner's explicit instruction — do not rewrite, summarize or "fix" it), it must
 *  lead every reply-generation system prompt, and the voice/contact/learning layers
 *  must be appended AFTER it without altering it. */
public class VoiceCharterTest {

    /** Verbatim copy of the owner-provided text (2026-08-06). The test pins it so an
     *  accidental edit anyone makes in VoiceCharter.java fails loudly. */
    private static final String CHARTER =
        "This sounds hundred percent like ai, so scripted planed. Humans always type while they're thinking about what to join to it. So it's not always computer acurate. Ai tries to make it ryme and align accurately like a novel. But human just writes it down as it's coming to they head. It's doesn't always flow correctly. They have some imperfections and does not always be acurate in coma, full stop and punctuation marks. They sometimes over space. That's what differentiates us from computer, AI does something when crafting messages to send to other person it makes the text sound like Ai assisted message, like audition questions. The person will have a feeling he's talking to a machine and I hate it fr. AI tries to sound very sweet and accurate to please the user and it then ruins everything by making the user to think and start observing the messages he's been replying to is not from a human that its kinda automated, artificial and unreal. There's this way human type and text that I don't know why it's hard for you to copy and go with. Please don't ever sound that way. I don't want her to ever get even half percent instincts that she's talking to an AI.";

    @Test public void charterIsByteIdenticalToTheOwnersWords() {
        assertEquals(CHARTER, VoiceCharter.TEXT);
        assertEquals(CHARTER.length(), VoiceCharter.TEXT.length());
    }

    @Test public void charterLeadsEveryReplySystemPrompt() {
        Contact c = Fakes.contact(1, "Amara");
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            new ProfileService.Profile("Kelechi", "English", "dev", "football"),
            c, "", new ArrayList<Message>()));
        assertTrue(req.system.startsWith(VoiceCharter.TEXT));
    }

    @Test public void voiceAndContactLayersAppendAfterTheCharter() {
        Contact c = Fakes.contact(1, "Amara");
        c.relationshipType = "close friend";
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            new ProfileService.Profile("Kelechi", "English", "", ""),
            c, "", new ArrayList<Message>(),
            "Voice: warm and friendly; no emoji.",
            java.util.Arrays.asList("The owner's own standing style instruction (applies to every chat): keep it short"),
            ""));
        int charterEnd = VoiceCharter.TEXT.length();
        assertTrue("voice line must come after the charter",
            req.system.indexOf("Voice: warm and friendly") > charterEnd);
        assertTrue("custom instruction must come after the charter",
            req.system.indexOf("standing style instruction") > charterEnd);
        assertTrue("contact block must come after the charter",
            req.system.indexOf("Conversation partner: Amara") > charterEnd);
        assertTrue("rules must come after the charter",
            req.system.indexOf("Rules: reply in Kelechi") > charterEnd);
    }

    @Test public void legacyIdentityHashtagIsGoneButOutputOnlyRuleStays() {
        Contact c = Fakes.contact(1, "Amara");
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            new ProfileService.Profile("Kelechi", "", "", ""),
            c, "", new ArrayList<Message>()));
        assertTrue(req.system.contains("Output only the exact message Kelechi would send"));
        assertTrue(req.system.contains("stay in character at all times"));
    }
}
