package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import com.replymate.core.model.Contact;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P4 prompt surface: voice line, custom prompt, learned hints, about-me toggles,
 *  and the "why" audit array — all inside the existing L0/L3/L4 architecture. */
public class P4PromptTest {

    private static ProfileService.Profile profile(String name) {
        return new ProfileService.Profile(name, "English, Pidgin", "Dev in PH", "football");
    }

    private static ChatRequest buildWithVoice(String voiceLine, List<String> extras,
                                              String aboutExtra) {
        return PromptBuilder.build(new PromptBundle(
            profile("Kelechi"), Fakes.contact(1, "Amara"), "",
            new ArrayList<com.replymate.core.model.Message>(),
            voiceLine, extras, aboutExtra));
    }

    @Test public void voiceLineJoinsWritingStyleSection() {
        ChatRequest req = buildWithVoice("Voice: warm and friendly; no emoji.", null, "");
        assertTrue(req.system.contains("Writing style:"));
        assertTrue(req.system.contains("Voice: warm and friendly; no emoji."));
        // task/turns/budget architecture untouched
        assertNotNull(req.task);
    }

    @Test public void customPromptAndLearnedHintsLandInContactBlock() {
        ChatRequest req = buildWithVoice("", Arrays.asList(
            "Special instruction for this chat: greet her by name",
            "Learned from the owner's choices: keep replies noticeably shorter;"), "");
        int partnerIdx = req.system.indexOf("Conversation partner:");
        assertTrue(partnerIdx >= 0);
        assertTrue(req.system.indexOf("greet her by name") > partnerIdx);
        assertTrue(req.system.indexOf("keep replies noticeably shorter") > partnerIdx);
    }

    @Test public void aboutExtraAppearsOnlyWhenProvided() {
        ChatRequest on = buildWithVoice("", null, "I coach local football on weekends");
        assertTrue(on.system.contains("I coach local football on weekends"));
        ChatRequest off = buildWithVoice("", null, "");
        assertFalse(off.system.contains("More about me:"));
    }

    @Test public void profileTogglesFilterSectionsAndAreAudited() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProfileService svc = new ProfileService(kv);
        svc.save(new ProfileService.Profile("Kelechi", "English", "Dev in PH", "football"));
        svc.saveExtra("secret hobby: chess");
        svc.setUse(ProfileService.USE_BIO, false);
        svc.setUse(ProfileService.USE_EXTRA, false);

        ProfileService.Profile filtered = svc.loadFiltered();
        assertEquals("Kelechi", filtered.name);
        assertEquals("", filtered.bio);                       // toggled off
        assertEquals("English", filtered.languages);
        assertEquals("", svc.extraFiltered());
        assertTrue(svc.excludedSections().contains("bio excluded by toggle"));
        assertTrue(svc.excludedSections().contains("about-me extra excluded by toggle"));

        svc.setUse(ProfileService.USE_BIO, true);
        assertEquals("Dev in PH", svc.loadFiltered().bio);
        assertFalse(svc.excludedSections().contains("bio excluded by toggle"));
    }

    @Test public void snapshotCarriesWhyArrayForTheAuditView() {
        ChatRequest req = buildWithVoice("Voice: direct and to the point.", null, "");
        String json = PromptBuilder.snapshot(req, "gemini-x", "reply",
            Arrays.asList("global voice applied (Tone=direct)",
                          "bio excluded by toggle",
                          "learned: keep replies noticeably shorter — 3 of 3 edits made the text shorter"));
        JsonObj parsed = Json.parseObj(json);
        assertEquals("reply", parsed.str("kind"));
        // Json.parseObj yields PLAIN java.util containers for nested values.
        List<?> why = (List<?>) parsed.raw("why");
        assertNotNull(why);
        String whyText = why.toString();
        assertTrue(whyText.contains("global voice applied"));
        assertTrue(whyText.contains("bio excluded by toggle"));
        assertTrue(whyText.contains("learned: keep replies noticeably shorter"));
        // why is audit-only: it must NOT leak into the model-facing system prompt
        assertFalse(parsed.str("system").contains("excluded by toggle"));
    }

    @Test public void emptyWhyProducesEmptyArrayNotAbsentKey() {
        ChatRequest req = buildWithVoice("", null, "");
        JsonObj parsed = Json.parseObj(PromptBuilder.snapshot(req, "m", "reply"));
        assertTrue(parsed.raw("why") instanceof List);
        assertTrue(((List<?>) parsed.raw("why")).isEmpty());
    }

    @Test public void legacyBundlesWithoutVoiceStillBuild() {
        // P1-P3 callers (5-arg bundle) must keep working — backward-compat gate.
        ChatRequest req = PromptBuilder.build(new PromptBundle(
            null, Fakes.contact(1, "Bo"), null, null));
        assertTrue(req.system.contains("Bo"));
        assertTrue(req.system.contains("natural, human"));
    }
}
