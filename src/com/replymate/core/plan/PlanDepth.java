package com.replymate.core.plan;

/** P-intelligence-5: the Planning Depth product setting (Settings → Planning depth).
 *  Parsed-tolerant enum + the single kv key + the audit-phrasing, so DraftService,
 *  SettingsActivity and the tests can never drift on spelling.
 *
 *  Semantics (all zero extra provider calls — the planner is LOCAL):
 *    BASIC  — no plan block at all; the legacy task text ships byte-identical.
 *    NORMAL — one compact plan line (moment + intent + length fit).
 *    DEEP   — the full plan block: situation, topic words, burst focus, ignore
 *             list and the moment-adjusted length fit. */
public final class PlanDepth {

    private PlanDepth() { }

    public static final String KV_KEY = "plan.depth";
    public static final String BASIC = "basic";
    public static final String NORMAL = "normal";
    public static final String DEEP = "deep";

    /** Tolerant parse — anything unknown reads as NORMAL (the product default). */
    public static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.US);
        if (BASIC.equals(v)) return BASIC;
        if (DEEP.equals(v)) return DEEP;
        return NORMAL;
    }

    /** Prompt Audit line for any depth — the directive: audit must SHOW the depth. */
    public static String auditLine(String depth) {
        String d = normalize(depth);
        if (BASIC.equals(d)) {
            return "planning depth: Basic — no planning block injected (legacy task)";
        }
        if (DEEP.equals(d)) {
            return "planning depth: Deep — full planning block injected (situation,"
                + " topic, burst focus, ignores, length fit)";
        }
        return "planning depth: Normal — compact plan line injected (moment, intent,"
            + " length fit)";
    }
}
