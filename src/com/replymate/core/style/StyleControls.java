package com.replymate.core.style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The 9 owner-approved style controls (P4 customization system). Each control has
 *  three levels (0/1/2) with a UI label AND a prompt phrase — the phrase table is the
 *  entire contract with the model, so it lives here, pure and unit-tested.
 *  Levels stored as ints in style_setting; per-contact overrides inherit when unset. */
public final class StyleControls {

    /** One control: stable storage key, UI label, per-level UI labels + prompt phrases. */
    public static final class Control {
        public final String key;
        public final String label;
        public final String[] levelLabels;
        final String[] phrases;

        Control(String key, String label, String[] levelLabels, String[] phrases) {
            this.key = key;
            this.label = label;
            this.levelLabels = levelLabels;
            this.phrases = phrases;
        }

        public String levelLabel(int level) {
            return levelLabels[clamp(level)];
        }

        public String phrase(int level) {
            return phrases[clamp(level)];
        }

        static int clamp(int level) {
            return level < 0 ? 0 : (level > 2 ? 2 : level);
        }
    }

    public static final Control TONE = new Control("tone", "Tone",
        new String[] {"warm", "neutral", "direct"},
        new String[] {"warm and friendly", "even and neutral", "direct and to the point"});
    public static final Control LENGTH = new Control("length", "Reply length",
        new String[] {"short", "natural", "detailed"},
        new String[] {"keep replies short (1 short sentence when it works)",
                      "natural length (usually 1–3 short sentences)",
                      "allow fuller, more detailed replies when the topic needs it"});
    public static final Control EMOJI = new Control("emoji", "Emoji use",
        new String[] {"none", "a few", "plenty"},
        new String[] {"no emoji", "a few well-placed emoji", "plenty of emoji when it feels natural"});
    public static final Control FORMALITY = new Control("formality", "Formality",
        new String[] {"casual", "balanced", "formal"},
        new String[] {"casual chat register", "balanced register", "polished, formal register"});
    public static final Control HUMOR = new Control("humor", "Humor",
        new String[] {"serious", "light", "funny"},
        new String[] {"play it straight", "light humor is welcome", "be playful and funny"});
    public static final Control CONFIDENCE = new Control("confidence", "Confidence",
        new String[] {"modest", "steady", "bold"},
        new String[] {"modest and soft-spoken", "steady and sure", "bold and decisive — not arrogant"});
    public static final Control SLANG = new Control("slang", "Slang",
        new String[] {"none", "light", "heavy"},
        new String[] {"no slang", "light everyday slang", "heavy slang when it fits"});
    public static final Control FLIRTING = new Control("flirting", "Flirting",
        new String[] {"never", "subtle", "open"},
        new String[] {"never flirty", "subtly warm-flirty only if the chat invites it",
                      "openly flirty when the chat invites it"});
    public static final Control FOLLOW_UP = new Control("follow_up", "Follow-ups",
        new String[] {"rarely", "natural", "always"},
        new String[] {"rarely end with a question",
                      "ask a follow-up question when it flows naturally",
                      "keep the chat going — usually end with a natural question"});

    private static final List<Control> ALL;
    static {
        List<Control> all = new ArrayList<Control>();
        all.add(TONE);
        all.add(LENGTH);
        all.add(EMOJI);
        all.add(FORMALITY);
        all.add(HUMOR);
        all.add(CONFIDENCE);
        all.add(SLANG);
        all.add(FLIRTING);
        all.add(FOLLOW_UP);
        ALL = Collections.unmodifiableList(all);
    }

    private StyleControls() { }

    public static List<Control> all() {
        return ALL;
    }

    public static Control byKey(String key) {
        for (Control c : ALL) if (c.key.equals(key)) return c;
        return null;
    }

    /** Default level for every control (the shipped "natural" middle). */
    public static int defaultLevel(String key) {
        Control c = byKey(key);
        if (c == null) return 1;
        if (c == FLIRTING) return 0;             // never flirty by default (safe)
        if (c == SLANG) return 1;
        return 1;
    }
}
