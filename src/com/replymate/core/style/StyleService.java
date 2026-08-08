package com.replymate.core.style;

import com.replymate.core.learning.LearningEngine;
import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.ports.StyleSettingStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Composes the effective voice for ONE contact (P4 customization system):
 *  global user voice (the 9 controls) as the base style → per-contact overrides →
 *  contact custom prompt box → learned hints (only when learning is open).
 *  Every decision is recorded into ComposedVoice.why — the audit view renders it
 *  verbatim ("why a reply sounded the way it did"). Contact-isolated by construction:
 *  reads are exactly (global rows, THIS contact's rows, THIS contact's signals). */
public final class StyleService {

    /** Fully-resolved voice + extras + audit notes for one composition. */
    public static final class ComposedVoice {
        /** "Voice: …" rule line; empty when nothing deviates from defaults? — never:
         *  the user voice ALWAYS shapes replies when any control differs from natural. */
        public final String voiceLine;
        /** Extra prompt lines (custom prompt + learned hints), appended in contact block. */
        public final List<String> extraLines;
        /** Audit notes: exactly what shaped this voice and where it came from. */
        public final List<String> why;

        ComposedVoice(String voiceLine, List<String> extraLines, List<String> why) {
            this.voiceLine = voiceLine == null ? "" : voiceLine;
            this.extraLines = extraLines == null
                ? Collections.<String>emptyList() : extraLines;
            this.why = why == null ? Collections.<String>emptyList() : why;
        }
    }

    private final StyleSettingStore settings;
    private final LearningService learning;

    public StyleService(StyleSettingStore settings, LearningService learning) {
        this.settings = settings;
        this.learning = learning;
    }

    public Map<String, String> globalRows() {
        return settings.all(null);
    }

    /** The owner's global custom style instruction ("" when unset). */
    public String globalCustomInstruction() {
        return StyleSettings.customPrompt(settings.all(null));
    }

    public Map<String, String> contactRows(long contactId) {
        return settings.all(contactId);
    }

    public ComposedVoice compose(Contact contact) {
        if (contact == null) {
            return new ComposedVoice("", null, null);
        }
        Map<String, String> global = settings.all(null);
        Map<String, String> contactRows = settings.all(contact.id);
        return composeFromRows(contact, global, contactRows);
    }

    /** Pure-ish composition once rows are loaded (seam for tests). */
    public ComposedVoice composeFromRows(Contact contact, Map<String, String> global,
                                         Map<String, String> contactRows) {
        List<String> why = new ArrayList<String>();
        List<String> extra = new ArrayList<String>();

        // 1) effective levels: global user voice is the base; contact overrides win.
        int[] levels = StyleSettings.resolve(global, contactRows);
        List<String> overrides = StyleSettings.overrideNotes(global, contactRows);
        String voiceLine = StyleSettings.renderVoiceLine(levels);
        if (anyNonDefault(global)) {
            why.add("global voice applied (" + overrideSummary(global) + ")");
        } else {
            why.add("global voice: defaults (not customized yet)");
        }
        why.addAll(overrides);

        // 2) owner's own standing style instruction (global, applies to every chat),
        //    then the custom prompt box (this contact only). P-background-8: the
        //    contact box carries the HARD rules for this chat ("never call her bro")
        //    — it must be framed as overriding the global voice, not as a soft hint.
        String globalCustom = StyleSettings.customPrompt(global);
        if (!globalCustom.isEmpty()) {
            extra.add("The owner's own standing style instruction (applies to every chat): "
                + globalCustom);
            why.add("global custom instruction applied (" + globalCustom.length() + " chars)");
        }
        String custom = StyleSettings.customPrompt(contactRows);
        if (!custom.isEmpty()) {
            extra.add("This contact's own rules (they override the global voice wherever"
                + " the two clash — follow them exactly): " + custom);
            why.add("custom prompt for " + contact.displayName + " applied ("
                + custom.length() + " chars)");
        }

        // 2.5) P-background-8: About Them + contact profile must be visible in the
        //      audit trail too — Prompt Audit shows exactly which context applied.
        why.addAll(contactProfileNotes(contact));

        // 3) learned hints — through the SAME gate as recording.
        if (learning != null && learning.openFor(contact)) {
            List<LearningEngine.Hint> hints = learning.hintsFor(contact);
            if (!hints.isEmpty()) {
                StringBuilder learned = new StringBuilder("Learned from the owner's choices:");
                for (LearningEngine.Hint h : hints) {
                    learned.append(' ').append(h.line).append(';');
                    why.add("learned: " + h.line + " — " + h.why);
                }
                extra.add(learned.toString());
            } else {
                why.add("learning on, not enough signals yet");
            }
        } else if (learning != null) {
            why.add(learningGateNote(contact));
        }

        return new ComposedVoice(voiceLine, extra, why);
    }

    /** Audit credits for the About-Them / contact-profile block (when non-default).
     *  The block text itself is written by SystemComposer — these notes make the
     *  same facts visible in Prompt Audit's "why". */
    static List<String> contactProfileNotes(Contact contact) {
        List<String> notes = new ArrayList<String>();
        if (contact == null) return notes;
        if (!contact.relationshipType.isEmpty() || !contact.relationshipNotes.isEmpty()) {
            StringBuilder n = new StringBuilder("contact profile applied (");
            if (!contact.relationshipType.isEmpty()) {
                n.append("relationship: ").append(contact.relationshipType);
            }
            if (!contact.relationshipNotes.isEmpty()) {
                if (n.charAt(n.length() - 1) != '(') n.append("; ");
                n.append("about them: ").append(contact.relationshipNotes.trim().length())
                 .append(" chars");
            }
            n.append(')');
            notes.add(n.toString());
        }
        if (!contact.toneOverride.isEmpty()) {
            notes.add("contact tone note applied (" + contact.toneOverride + ")");
        }
        if (!contact.languagePref.isEmpty()) {
            notes.add("contact language preference applied (" + contact.languagePref + ")");
        }
        return notes;
    }

    private String learningGateNote(Contact contact) {
        if (contact.privateMode) return "learning off — private contact";
        if (!contact.memoryEnabled) return "learning off — memory disabled for this contact";
        if (learning.isOff(contact.id)) return "learning disabled for this contact";
        if (learning.isPaused(contact.id)) return "learning paused for this contact";
        return "learning closed";
    }

    private static boolean anyNonDefault(Map<String, String> global) {
        for (StyleControls.Control c : StyleControls.all()) {
            Integer v = StyleSettings.level(global, c.key);
            if (v != null && v.intValue() != StyleControls.defaultLevel(c.key)) return true;
        }
        return false;
    }

    private static String overrideSummary(Map<String, String> global) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (StyleControls.Control c : StyleControls.all()) {
            Integer v = StyleSettings.level(global, c.key);
            if (v == null || v.intValue() == StyleControls.defaultLevel(c.key)) continue;
            if (!first) sb.append(", ");
            sb.append(c.label).append('=').append(c.levelLabel(v));
            first = false;
        }
        return sb.toString();
    }
}
