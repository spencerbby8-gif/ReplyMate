package com.replymate.core.understanding;

import java.util.ArrayList;
import java.util.List;

/** P-intelligence-1: mechanical burst reading (pure JVM — every rule test-pinned).
 *  Runs BEFORE generation over the burst tail the composer already uses, and marks
 *  ONLY signals we can verify character-by-character (correction markers, filler
 *  pings, multiple questions). Semantic topic decisions stay with the model's burst
 *  instructions in TaskComposer — these flags merely add grounded annotations, they
 *  never rewrite, drop or re-order any message text. */
public final class BurstSignals {

    /** Lines that CORRECT an earlier line ("no wait", "i meant", "*Tuesday", …).
     *  Conservative on purpose: bare "i mean"/"sorry"/"wait" are common filler, not
     *  corrections. A leading '*' is the classic chat typo-fix convention. */
    private static final java.util.regex.Pattern CORRECTION = java.util.regex.Pattern.compile(
        "(?is)^\\s*(no[,.! ]+wait\\b.*|wait[,.!]+\\s.*|i meant\\b.*|scratch that\\b.*"
        + "|my bad\\b.*|typo\\b.*|\\*\\s*[a-z0-9].*)");

    /** Pure filler pings: "you there", "hey", "??", "alive?"… (short lines only). */
    private static final java.util.regex.Pattern FILLER = java.util.regex.Pattern.compile(
        "(?is)^[\\s\\p{Punct}]*"
        + "(yo+|hi+|hey+|hello+|you there|u there|still there|you awake|u awake|alive"
        + "|bruh|bro+|bros|ping|woi|abeg|hiya)"
        + "[\\s\\p{Punct}?!]*$");

    private static final int FILLER_MAX_LEN = 17;

    /** What the mechanics found over one burst (never null flags). */
    public static final class Result {
        public final int size;
        public final int questions;      // lines containing '?'
        public final int fillers;        // FILLER-matching lines
        public final boolean multiQuestion;
        public final boolean fillerHeavy; // majority of lines are pure filler
        public final List<Integer> correctionLines = new ArrayList<Integer>(); // 1-based
        public Result(int size, int questions, int fillers, List<Integer> corrections) {
            this.size = size;
            this.questions = questions;
            this.fillers = fillers;
            this.multiQuestion = questions >= 2;
            this.fillerHeavy = size > 0 && fillers * 2 > size;
            if (corrections != null) this.correctionLines.addAll(corrections);
        }
        public boolean hasCorrection() { return !correctionLines.isEmpty(); }
    }

    private BurstSignals() { }

    /** P-intelligence-5: expose the same pure-filler verdict detect() uses, so the
     *  planner's focus/ignore split can never drift from burst mechanics. */
    public static boolean isFiller(String line) {
        String t = line == null ? "" : line.trim();
        return !t.isEmpty() && t.length() <= FILLER_MAX_LEN
            && (FILLER.matcher(t).matches() || t.matches("^\\s*\\?+\\s*$"))
            && !CORRECTION.matcher(t).matches();
    }

    public static Result detect(List<String> burstTexts) {
        int questions = 0;
        int fillers = 0;
        List<Integer> corrections = new ArrayList<Integer>();
        int size = burstTexts == null ? 0 : burstTexts.size();
        for (int i = 0; i < size; i++) {
            String line = burstTexts.get(i) == null ? "" : burstTexts.get(i).trim();
            if (line.indexOf('?') >= 0) questions++;
            if (CORRECTION.matcher(line).matches()) {
                corrections.add(Integer.valueOf(i + 1));
            } else if (line.length() <= FILLER_MAX_LEN
                    && (FILLER.matcher(line).matches() || line.matches("^\\s*\\?+\\s*$"))) {
                fillers++;
            }
        }
        return new Result(size, questions, fillers, corrections);
    }
}
