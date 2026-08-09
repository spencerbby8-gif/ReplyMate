package com.replymate.core.reason;

import com.replymate.core.plan.ReplyPlanner;

/** P-intelligence-6: HOW HARD should the model think? — an automatic, local,
 *  deterministic difficulty score mapped onto each provider's own documented
 *  reasoning control (docs/provider-capability-map.md). Simple messages stay
 *  fast (DEFAULT — provider chooses); genuinely hard or ambiguous bursts get
 *  deeper thinking. This file only produces a LEVEL and an audit reason — never
 *  chain-of-thought, never a second "reasoning model" call. */
public final class Reasoning {

    public static final String DEFAULT = "default";
    public static final String LOW = "low";
    public static final String HIGH = "high";

    private Reasoning() { }

    /** Difficulty inputs, all local & explainable. */
    public static final class Decision {
        public final String level;      // DEFAULT | LOW | HIGH
        public final int score;
        public final String whyDetail;  // audit-safe bullets ("search needed"…)

        Decision(String level, int score, String whyDetail) {
            this.level = level;
            this.score = score;
            this.whyDetail = whyDetail;
        }

        /** The credited why-line (metadata only — the thinking itself stays hidden). */
        public String whyLine() {
            return DEFAULT.equals(level)
                ? null
                : "deeper thinking: " + level.toUpperCase(java.util.Locale.US)
                    + " (because " + whyDetail + ")"
                    + " — the model's private reasoning is never shown or stored";
        }
    }

    /** @param plan the deterministic pre-plan (intent tells us a lot)
     *  @param burstSize messages being answered at once
     *  @param searchNeeded whether the gate already fired (search ⇒ think about evidence)
     *  @param questionMarks '?' count in the burst (multi-part questions are harder) */
    public static Decision decide(ReplyPlanner.Plan plan, int burstSize,
                                  boolean searchNeeded, int questionMarks) {
        int score = 0;
        StringBuilder why = new StringBuilder();
        if (searchNeeded) { score += 2; append(why, "live search results need weighing"); }
        if (plan != null && (plan.intent == ReplyPlanner.Intent.ACCEPT_CORRECTION
                || plan.intent == ReplyPlanner.Intent.DISAGREE
                || plan.intent == ReplyPlanner.Intent.RESPECT_NO)) {
            score += 1; append(why, "the exchange needs care (correction/disagreement/no)");
        }
        if (burstSize >= 3) { score += 1; append(why, "a long burst to weigh as one"); }
        if (questionMarks >= 2) { score += 1; append(why, "several questions at once"); }
        if (plan != null && plan.topicWords.isEmpty() && burstSize > 0) {
            score += 1; append(why, "the topic is ambiguous");
        }
        if (score >= 3) return new Decision(HIGH, score, why.toString());
        if (score >= 1) return new Decision(LOW, score, why.toString());
        return new Decision(DEFAULT, score, why.toString());
    }

    private static void append(StringBuilder b, String s) {
        if (b.length() > 0) b.append("; ");
        b.append(s);
    }
}
