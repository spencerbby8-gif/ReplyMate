package com.replymate.core.style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The 9 owner-approved style controls (P4 customization system). Each control has
 *  three active levels (0/1/2) plus OFF ({@link #LEVEL_OFF}) with a UI label AND a
 *  prompt phrase — the phrase table is the entire contract with the model, so it
 *  lives here, pure and unit-tested.
 *  Levels stored as ints in style_setting; per-contact overrides inherit when unset.
 *  P-intelligence-3 (owner directive): every dimension can be turned OFF — no phrase
 *  is rendered for it, so the model receives NO direction for that dimension at all
 *  (vs level-0 "none", which is an ACTIVE instruction like "no emoji"). */
public final class StyleControls {

    /** Dimension disabled: phrase(3) is "" and the voice line drops it entirely. */
    public static final int LEVEL_OFF = 3;

    /** One control: stable storage key, UI label, per-level UI labels + prompt phrases.
     *  P-ux-fix: each level also carries a plain-English description and a concrete
     *  example reply so the picker shows exactly what the choice DOES. */
    public static final class Control {
        public final String key;
        public final String label;
        public final String[] levelLabels;
        final String[] phrases;
        final String[] descs;
        final String[] examples;

        Control(String key, String label, String[] levelLabels, String[] phrases,
                String[] descs, String[] examples) {
            this.key = key;
            this.label = label;
            this.levelLabels = levelLabels;
            this.phrases = phrases;
            this.descs = descs;
            this.examples = examples;
        }

        public String levelLabel(int level) {
            return level == LEVEL_OFF ? "off" : levelLabels[clamp(level)];
        }

        /** The prompt phrase for this level. OFF ⇒ EMPTY — the dimension must not
         *  appear in the voice line at all (disabled, not instructed). */
        public String phrase(int level) {
            return level == LEVEL_OFF ? "" : phrases[clamp(level)];
        }

        /** What this level makes the AI do, in owner's words. */
        public String levelDesc(int level) {
            return level == LEVEL_OFF
                ? "Off — ReplyMate gives the AI no instruction for " + label.toLowerCase()
                    + "; it just stays natural"
                : descs[clamp(level)];
        }

        /** A concrete sample reply at this level ("" for OFF — nothing to sample). */
        public String levelExample(int level) {
            return level == LEVEL_OFF ? "" : examples[clamp(level)];
        }

        static int clamp(int level) {
            return level < 0 ? 0 : (level > 2 ? 2 : level);
        }
    }

    public static final Control TONE = new Control("tone", "Tone",
        new String[] {"warm", "neutral", "direct"},
        new String[] {"warm and friendly", "even and neutral", "direct and to the point"},
        new String[] {"Kind and affectionate — replies feel caring",
                      "Even and plain — not extra sweet, not cold",
                      "Straight to the point — skips the softening"},
        new String[] {"awww that's really sweet of you 😊",
                      "ok, noted. thanks for telling me",
                      "got it — send the file today"});
    public static final Control LENGTH = new Control("length", "Reply length",
        new String[] {"short", "natural", "detailed"},
        new String[] {"keep replies short (1 short sentence when it works)",
                      "natural length (usually 1–3 short sentences)",
                      "allow fuller, more detailed replies when the topic needs it"},
        new String[] {"One short sentence when it works",
                      "1–3 short sentences, like normal chatting",
                      "Fuller, more detailed replies when the topic needs it"},
        new String[] {"on my way!",
                      "lol yes, I'll be there by 7 — want anything?",
                      "honestly I've thought about it all week, and here's where I landed: …"});
    public static final Control EMOJI = new Control("emoji", "Emoji use",
        new String[] {"none", "a few", "plenty"},
        new String[] {"no emoji", "a few well-placed emoji", "plenty of emoji when it feels natural"},
        new String[] {"Never use emoji",
                      "A few, only where they fit",
                      "Emoji freely, like excited texting"},
        new String[] {"that's great news.",
                      "that's great news 🎉",
                      "omg yesss 🎉🎉😂"});
    public static final Control FORMALITY = new Control("formality", "Formality",
        new String[] {"casual", "balanced", "formal"},
        new String[] {"casual chat register", "balanced register", "polished, formal register"},
        new String[] {"Everyday chat wording",
                      "Polite middle ground",
                      "Polished, respectful wording"},
        new String[] {"yeah no worries, I got you",
                      "sure, no problem at all",
                      "certainly — I'll have it ready by noon"});
    public static final Control HUMOR = new Control("humor", "Humor",
        new String[] {"serious", "light", "funny"},
        new String[] {"play it straight", "light humor is welcome", "be playful and funny"},
        new String[] {"Play it straight — no jokes",
                      "Light humor when it fits",
                      "Playful, joking tone"},
        new String[] {"I understand — let's sort it out",
                      "lol classic you 😄 okay, deal",
                      "lmaooo you never change 😂 fine, I'm in"});
    public static final Control CONFIDENCE = new Control("confidence", "Confidence",
        new String[] {"modest", "steady", "bold"},
        new String[] {"modest and soft-spoken", "steady and sure", "bold and decisive — not arrogant"},
        new String[] {"Soft, hedged wording",
                      "Sure and even",
                      "Decisive — never arrogant"},
        new String[] {"I might be wrong but I think Friday works?",
                      "Friday works for me",
                      "Friday. Lock it in."});
    public static final Control SLANG = new Control("slang", "Slang",
        new String[] {"none", "light", "heavy"},
        new String[] {"no slang", "light everyday slang", "heavy slang when it fits"},
        new String[] {"Standard words only",
                      "Everyday slang (lol, gonna)",
                      "Heavy slang when it fits the chat"},
        new String[] {"I am going to be a little late",
                      "gonna be a bit late, sorry!",
                      "lol my bad, dey come now"});
    public static final Control FLIRTING = new Control("flirting", "Flirting",
        new String[] {"never", "subtle", "open"},
        new String[] {"never flirty", "subtly warm-flirty only if the chat invites it",
                      "openly flirty when the chat invites it"},
        new String[] {"Never flirty — safe for anyone",
                      "Subtly warm, only if the chat clearly invites it",
                      "Openly flirty when the chat invites it"},
        new String[] {"had a good time. talk later",
                      "had a really good time with you ☺️",
                      "can't stop thinking about last night 😏"});
    public static final Control FOLLOW_UP = new Control("follow_up", "Follow-ups",
        new String[] {"rarely", "natural", "always"},
        new String[] {"rarely end with a question",
                      "ask a follow-up question when it flows naturally",
                      "keep the chat going — usually end with a natural question"},
        new String[] {"Rarely end with a question",
                      "Ask a question when it flows naturally",
                      "Keep the chat going — usually end with a question"},
        new String[] {"cool. talk tomorrow",
                      "cool — you still coming Saturday?",
                      "love it! so what are you wearing btw? 😄"});

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
