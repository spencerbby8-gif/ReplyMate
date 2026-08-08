package com.replymate.core.style;

import com.replymate.core.style.StyleControls;
import com.replymate.core.style.StyleService;
import com.replymate.core.style.StyleSettings;
import com.replymate.core.model.Contact;
import com.replymate.fakes.Fakes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-3 (owner directive 1 & 9): every voice dimension can be switched
 *  OFF (dimension disabled = NO instruction sent — different from level-0 "none",
 *  which is an ACTIVE instruction like "no emoji"). OFF must (a) parse from storage,
 *  (b) vanish from the voice line with no stray separators, (c) read honestly in
 *  the audit why-notes, and (d) keep behavior byte-compatible for 0..2 users. */
public final class VoiceControlsOffTest {

    @Test public void offLevelIsUniformAcrossAllNineControls() {
        for (StyleControls.Control ctl : StyleControls.all()) {
            assertEquals(ctl.key + ": OFF means NO phrase at all",
                "", ctl.phrase(StyleControls.LEVEL_OFF));
            assertEquals(ctl.key + ": OFF label", "off",
                ctl.levelLabel(StyleControls.LEVEL_OFF));
            assertTrue(ctl.key + ": OFF desc explains it disables the dial",
                ctl.levelDesc(StyleControls.LEVEL_OFF).startsWith("Off — ReplyMate gives the AI no instruction"));
            assertEquals("", ctl.levelExample(StyleControls.LEVEL_OFF));
            // the 0..2 contract is untouched
            assertFalse(ctl.phrase(0).isEmpty());
            assertFalse(ctl.phrase(1).isEmpty());
            assertFalse(ctl.phrase(2).isEmpty());
        }
    }

    @Test public void storageParsingAcceptsOffRejectsCorruption() {
        Map<String, String> rows = new HashMap<String, String>();
        rows.put("tone", "3");
        rows.put("emoji", "0");
        rows.put("length", "4");      // out of range → invalid → inherit
        rows.put("humor", "-1");
        rows.put("slang", "banana");
        assertEquals(Integer.valueOf(3), StyleSettings.level(rows, "tone"));
        assertEquals(Integer.valueOf(0), StyleSettings.level(rows, "emoji"));
        assertNull(StyleSettings.level(rows, "length"));
        assertNull(StyleSettings.level(rows, "humor"));
        assertNull(StyleSettings.level(rows, "slang"));
        assertNull(StyleSettings.level(rows, "missing"));
        assertNull(StyleSettings.level(null, "tone"));
    }

    @Test public void resolvePassesOffThroughUntouched() {
        Map<String, String> global = new HashMap<String, String>();
        global.put("tone", "3");
        Map<String, String> contact = new HashMap<String, String>();
        contact.put("emoji", "3");
        int[] levels = StyleSettings.resolve(global, contact);
        List<StyleControls.Control> all = StyleControls.all();
        assertEquals(StyleControls.LEVEL_OFF, levels[all.indexOf(StyleControls.TONE)]);
        assertEquals(StyleControls.LEVEL_OFF, levels[all.indexOf(StyleControls.EMOJI)]);
        // untouched dials still resolve to their shipped defaults
        assertEquals(StyleControls.defaultLevel("length"),
            levels[all.indexOf(StyleControls.LENGTH)]);
    }

    @Test public void voiceLineDropsOffDimensionsWithNoStraySyntax() {
        Map<String, String> global = new HashMap<String, String>();
        global.put("tone", "0");           // warm — active
        global.put("emoji", "3");          // OFF
        global.put("slang", "3");          // OFF
        int[] levels = StyleSettings.resolve(global, null);
        String line = StyleSettings.renderVoiceLine(levels);
        assertTrue(line.startsWith("Voice: warm and friendly"));
        assertFalse("OFF dimension must not appear at all", line.contains("no emoji"));
        assertFalse("OFF dimension must not appear at all", line.contains("slang"));
        assertFalse("no double separators from skipped phrases", line.contains(";;"));
        assertFalse("no empty segments", line.contains("; .") || line.contains(": ."));
    }

    @Test public void allControlsOffEmitsNoVoiceLineAtAll() {
        Map<String, String> global = new HashMap<String, String>();
        for (StyleControls.Control ctl : StyleControls.all()) global.put(ctl.key, "3");
        int[] levels = StyleSettings.resolve(global, null);
        assertEquals("", StyleSettings.renderVoiceLine(levels));

        StyleService svc = allDefaultsService();
        Contact c = Fakes.contact(9, "Tobi");
        StyleService.ComposedVoice voice = svc.composeFromRows(c, global,
            new HashMap<String, String>());
        assertEquals("compose must hand the prompt NO voice line", "", voice.voiceLine);
        boolean credited = false;
        for (String w : voice.why) {
            if (w.contains("every voice control is Off")) credited = true;
        }
        assertTrue("audit must say exactly that no style directions were sent", credited);
    }

    @Test public void oneOffControlIsCreditedHonestly() {
        StyleService svc = allDefaultsService();
        Map<String, String> global = new HashMap<String, String>();
        global.put("confidence", "3");
        StyleService.ComposedVoice voice =
            svc.composeFromRows(Fakes.contact(9, "Tobi"), global,
                new HashMap<String, String>());
        assertFalse(voice.voiceLine.isEmpty());   // the rest still speaks
        assertFalse(voice.voiceLine.contains("steady and sure"));
        boolean credited = false;
        for (String w : voice.why) {
            if (w.contains("switched off") && w.contains("Confidence")) credited = true;
        }
        assertTrue(credited);
    }

    @Test public void overrideNotesReadsOffAsAnExplicitChoice() {
        Map<String, String> global = new HashMap<String, String>();
        global.put("tone", "0");
        Map<String, String> contact = new HashMap<String, String>();
        contact.put("tone", "3");
        List<String> notes = StyleSettings.overrideNotes(global, contact);
        boolean found = false;
        for (String n : notes) {
            if (n.startsWith("Tone: off (contact override of warm)")) found = true;
        }
        assertTrue("OFF must present as an explicit contact choice", found);
    }

    @Test public void explicitOffStillSuppressesTheLearnedGuess() {
        // owner rule (P-intelligence-2, kept): EXPLICIT > LEARNED. An explicit OFF
        // for length is still an explicit setting — it must kill the learned length
        // hint rather than let the guess leak back in.
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(signals, new Fakes.KvStoreFake());
        StyleService svc = Fakes.styleService(settings, learning);
        Contact c = Fakes.contact(1, "Amara");
        for (int i = 0; i < 3; i++) {
            learning.record(c, com.replymate.core.model.StyleSignal.Kind.EDITED,
                "shorter", null);
        }
        settings.put(1L, "length", "3");      // explicit: length OFF
        StyleService.ComposedVoice voice = svc.compose(c);
        String joined = joinLines(voice.extraLines);
        assertFalse("explicit OFF must suppress the learned length guess",
            joined.contains("keep replies noticeably shorter"));
        boolean suppressed = false;
        for (String w : voice.why) if (w.contains("learned hint suppressed")) suppressed = true;
        assertTrue(suppressed);
    }

    private static StyleService allDefaultsService() {
        Fakes.LearningStoreFake signals = new Fakes.LearningStoreFake();
        return Fakes.styleService(new Fakes.StyleSettingStoreFake(),
            Fakes.learningService(signals, new Fakes.KvStoreFake()));
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
