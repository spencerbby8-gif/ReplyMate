package com.replymate.core.learning;

import java.util.ArrayList;
import java.util.List;

/** Learned-style layer (P-memory-audit): derives concrete style rules from the TEXT
 *  of the owner's approved replies (copied-as-is or edited-then-copied) for ONE
 *  contact. Pure + deterministic: same approved texts → same lines, so the derived
 *  style can be cached per contact (kv) and survives app restarts byte-for-byte.
 *
 *  Unlike LearningEngine (which counts signal EVENTS), this reads WHAT the owner
 *  actually accepted — the strongest evidence of their real texting voice.
 *  Every line is thresholded and carries its evidence into the audit "why". */
public final class StyleProfiler {

    /** No derived style below this many approved replies (avoid hair-trigger rules). */
    public static final int MIN_APPROVED = 3;
    /** Cap the evidence window (recency dominates; keeps derivation cheap). */
    public static final int MAX_TEXTS = 8;
    /** Never emit more than this many derived lines. */
    public static final int MAX_LINES = 3;

    private StyleProfiler() { }

    /** A derived style line + its human-readable evidence (for the audit view). */
    public static final class Derived {
        public final String line;   // prompt-ready sentence
        public final String why;    // evidence, e.g. "4 of 5 approved replies end without a full stop"
        public Derived(String line, String why) { this.line = line; this.why = why; }
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
                "keep it short — your approved replies here average ~" + avg + " characters",
                "approved replies average ~" + avg + " chars across the last " + n));
        } else if (avg >= 160) {
            out.add(new Derived(
                "longer replies are fine here — your approved replies average ~" + avg + " characters",
                "approved replies average ~" + avg + " chars across the last " + n));
        }

        // 2) trailing full stop — the charter's signature imperfection
        if (noFinalPunct * 10 >= n * 7) {          // ≥70%
            out.add(new Derived(
                "usually skip the final full stop, like your approved replies here",
                noFinalPunct + " of " + n + " approved replies end without a full stop"));
        }

        // 3) lowercase openings
        if (startsLower * 10 >= n * 7) {
            out.add(new Derived(
                "start lowercase like your approved replies in this chat",
                startsLower + " of " + n + " approved replies start lowercase"));
        }

        // 4) emoji posture (only when unanimous either way)
        if (withEmoji == 0) {
            out.add(new Derived(
                "no emoji — none of your approved replies in this chat use them",
                "0 of " + n + " approved replies contain emoji"));
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
