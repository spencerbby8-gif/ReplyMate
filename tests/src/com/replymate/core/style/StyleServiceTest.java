package com.replymate.core.style;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.StyleSignal;
import com.replymate.fakes.Fakes;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Composition contract (P4): global voice is the base → contact overrides →
 *  custom prompt → learned hints (only through the learning gate) — and every
 *  decision is mirrored into the audit "why" notes. */
public class StyleServiceTest {

    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private Fakes.KvStoreFake learnKv;
    private LearningService learning;
    private StyleService styles;

    @Before public void setUp() {
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learnKv = new Fakes.KvStoreFake();
        learning = Fakes.learningService(learningStore, learnKv);
        styles = Fakes.styleService(settings, learning);
    }

    private static Contact contact() {
        Contact c = new Contact();
        c.id = 7;
        c.displayName = "Amara";
        return c;
    }

    private static String joined(StyleService.ComposedVoice v) {
        return v.voiceLine + "\n" + v.extraLines + "\n" + v.why;
    }

    @Test public void defaultsProduceNaturalVoiceAndHonestWhy() {
        StyleService.ComposedVoice v = styles.compose(contact());
        assertTrue(v.voiceLine.startsWith("Voice: "));
        assertTrue(v.voiceLine.contains("natural length"));
        assertTrue(v.extraLines.isEmpty());
        assertTrue(v.why.toString().contains("defaults (not customized yet)"));
    }

    @Test public void globalUserVoiceIsTheBaseStyle() {
        settings.put(null, "tone", "2");
        settings.put(null, "emoji", "2");
        StyleService.ComposedVoice v = styles.compose(contact());
        assertTrue(v.voiceLine.contains("direct and to the point"));
        assertTrue(v.voiceLine.contains("plenty of emoji"));
        assertTrue(v.why.toString().contains("global voice applied"));
        assertTrue(v.why.toString().contains("Tone=direct"));
    }

    @Test public void contactOverrideBeatsGlobalAndIsExplained() {
        settings.put(null, "emoji", "2");                 // global: plenty
        settings.put(7L, "emoji", "0");                   // contact: none
        StyleService.ComposedVoice v = styles.compose(contact());
        assertTrue(v.voiceLine.contains("no emoji"));
        assertFalse(v.voiceLine.contains("plenty of emoji"));
        assertTrue(v.why.toString().contains("Emoji use: none (contact override of plenty)"));
    }

    @Test public void customPromptBoxFlowsAsExtraLine() {
        settings.put(7L, StyleSettings.CUSTOM_PROMPT_KEY, "always greet her by name");
        StyleService.ComposedVoice v = styles.compose(contact());
        assertEquals(1, v.extraLines.size());
        assertTrue(v.extraLines.get(0).contains("always greet her by name"));
        assertTrue(joined(v).contains("custom prompt for Amara applied"));
    }

    @Test public void learnedHintsAppearOnlyWhenGateOpen() {
        for (int i = 0; i < 5; i++) {
            learning.record(contact(), StyleSignal.Kind.APPROVED, "", null);
        }
        StyleService.ComposedVoice open = styles.compose(contact());
        assertFalse(open.extraLines.isEmpty());
        assertTrue(open.extraLines.get(0).contains("Learned from the owner's choices:"));
        assertTrue(joined(open).contains("learned:"));

        learning.setOff(7, true);
        StyleService.ComposedVoice closed = styles.compose(contact());
        assertTrue(closed.extraLines.isEmpty());
        assertTrue(joined(closed).contains("learning disabled"));
    }

    @Test public void privateContactGetsVoiceAndCustomButNeverLearning() {
        Contact p = contact();
        p.privateMode = true;
        p.aiEnabled = false;
        settings.put(7L, "tone", "0");
        settings.put(7L, StyleSettings.CUSTOM_PROMPT_KEY, "respect boundaries");
        for (int i = 0; i < 9; i++) learning.record(p, StyleSignal.Kind.APPROVED, "", null);
        StyleService.ComposedVoice v = styles.compose(p);
        assertTrue(v.voiceLine.contains("warm and friendly"));
        assertEquals(1, v.extraLines.size());             // custom prompt only
        assertTrue(joined(v).contains("learning off — private contact"));
    }

    @Test public void nullContactIsSafe() {
        StyleService.ComposedVoice v = styles.compose(null);
        assertEquals("", v.voiceLine);
        assertTrue(v.extraLines.isEmpty());
    }

    @Test public void rowsReadAreExactlyGlobalPlusThisContact() {
        // plant rows for contact 99 — they must never shape contact 7's voice
        Map<String, String> other = new java.util.HashMap<String, String>();
        other.put("tone", "2");
        for (Map.Entry<String, String> e : other.entrySet()) {
            settings.put(99L, e.getKey(), e.getValue());
        }
        settings.put(99L, StyleSettings.CUSTOM_PROMPT_KEY, "OTHER-CONTACT-SECRET");
        StyleService.ComposedVoice v = styles.compose(contact());
        assertFalse(joined(v).contains("OTHER-CONTACT-SECRET"));
        assertFalse(v.voiceLine.contains("direct and to the point"));
    }
}
