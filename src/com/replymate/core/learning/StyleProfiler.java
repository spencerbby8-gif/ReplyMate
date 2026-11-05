package com.replymate.core.learning;

import java.util.ArrayList;
import java.util.List;

/** Learned-style layer (P-memory-audit): derives concrete style rules from
 *  EVIDENCE TEXTS in the owner's own hand for ONE contact — P22 adds
 *  user-provided WRITING SAMPLES (explicit, typed/pasted by the owner) ahead of
 *  the approved replies (copied-as-is or edited-then-copied). Pure +
 *  deterministic: same evidence texts → same lines, so the derived style can be
 *  cached per contact (kv) and survives app restarts byte-for-byte.
 *
 *  Unlike LearningEngine (which counts signal EVENTS), this reads WHAT the owner
 *  actually wrote or accepted — the strongest evidence of their real texting
 *  voice. Every line is thresholded, provenance-neutral in wording ("your own
 *  messages" — true for samples and approved replies alike), and carries its
 *  evidence into the audit "why". */
public final class StyleProfiler {

    /** No derived style below this many evidence texts (avoid hair-trigger rules). */
    public static final int MIN_APPROVED = 3;
    /** Cap the evidence window (recency dominates; keeps derivation cheap). */
    public static final int MAX_TEXTS = 8;
    /** Never emit more than this many derived lines. */
    public static final int MAX_LINES = 3;

    private StyleProfiler() { }

    /** A derived style line + its human-readable evidence (for the audit view). */
    public static final class Derived {
        public final String line;   // prompt-ready sentence
        public final String why;    // evidence, e.g. "4 of 5 learned texts end without a full stop"
        /** voice-control dimension this line touches ("length"/"emoji"/"") — lets an
         *  EXPLICIT user setting for that dimension take precedence (P-intelligence-2). */
        public final String control;
        public Derived(String line, String why) { this(line, why, ""); }
        public Derived(String line, String why, String control) {
            this.line = line;
            this.why = why;
            this.control = control == null ? "" : control;
        }
    }

    /** Derive the contact's approved-reply style. Empty when evidence is thin. */
    public static List<Derived> derive(List<String> approvedTexts) {
        List<Derived> out = new ArrayList<Derived>();
        if (approvedTexts == null) return out;
        List<String> texts = new ArrayList<String>();
        for (String t : approvedTexts) {
            if (t != null && !t.trim().isEmpty()) texts.add(t.trim());
            if (texts.size() >= MAX_TEXTS) break;
        }
        int n = texts.size();
        if (n < MIN_APPROVED) return out;

        // stats
        long totalLen = 0;
        int noFinalPunct = 0;
        int startsLower = 0;
        int withEmoji = 0;
        int withExclaim = 0;
        for (String t : texts) {
            totalLen += t.length();
            char last = t.charAt(t.length() - 1);
            if (last != '.' && last != '!' && last != '?') noFinalPunct++;
            char first = firstLetter(t);
            if (first != 0 && Character.isLowerCase(first)) startsLower++;
            if (LearningEngine.emojiCount(t) > 0) withEmoji++;
            if (t.indexOf('!') >= 0) withExclaim++;
        }
        int avg = (int) (totalLen / n);

        // 1) length rule (strongest signal first)
        if (avg <= 60) {
            out.add(new Derived(
                "keep it short — your own messages here average ~" + avg + " characters",
                "learned texts average ~" + avg + " chars across the last " + n,
                "length"));
        } else if (avg >= 160) {
            out.add(new Derived(
                "longer replies are fine here — your own messages average ~" + avg + " characters",
                "learned texts average ~" + avg + " chars across the last " + n,
                "length"));
        }

        // 2) trailing full stop — the charter's signature imperfection
        if (noFinalPunct * 10 >= n * 7) {          // ≥70%
            out.add(new Derived(
                "usually skip the final full stop, like your own messages here",
                noFinalPunct + " of " + n + " learned texts end without a full stop"));
        }

        // 3) lowercase openings
        if (startsLower * 10 >= n * 7) {
            out.add(new Derived(
                "start lowercase like your own messages in this chat",
                startsLower + " of " + n + " learned texts start lowercase"));
        }

        // 4) emoji posture (only when unanimous either way)
        if (withEmoji == 0) {
            out.add(new Derived(
                "no emoji — none of your own messages in this chat use them",
                "0 of " + n + " learned texts contain emoji",
                "emoji"));
        }

        return out.size() <= MAX_LINES ? out
            : new ArrayList<Derived>(out.subList(0, MAX_LINES));
    }

    private static char firstLetter(String t) {
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetter(ch)) return ch;
        }
        return 0;
    }
}
