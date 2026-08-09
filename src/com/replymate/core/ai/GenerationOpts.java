package com.replymate.core.ai;

/** Tunables for one generation call. P-intelligence-6 adds two provider-agnostic
 *  capability REQUESTS (never provider-specific names in core):
 *    search    — "attach the provider's native live web search, if it has one"
 *    reasoning — "default" (provider decides) | "low" | "high" model thinking level.
 *  Both default off/dynamic so every existing call site stays byte-identical. */
public final class GenerationOpts {
    public final int candidates;
    public final double temperature;
    public final int maxOutputTokens;
    /** Ask the adapter to enable native live web search for THIS call. */
    public final boolean search;
    /** "default" | "low" | "high" — model reasoning level for THIS call. */
    public final String reasoning;

    private GenerationOpts(int candidates, double temperature, int maxOutputTokens,
                           boolean search, String reasoning) {
        this.candidates = candidates;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.search = search;
        this.reasoning = reasoning == null ? "default" : reasoning;
    }

    public static GenerationOpts defaults() {
        return new GenerationOpts(1, 0.8, 220, false, "default");
    }

    public static GenerationOpts of(int candidates, double temperature, int maxOutputTokens) {
        return new GenerationOpts(candidates, temperature, maxOutputTokens, false, "default");
    }

    public GenerationOpts withSearch(boolean s) {
        return new GenerationOpts(candidates, temperature, maxOutputTokens, s, reasoning);
    }

    public GenerationOpts withReasoning(String level) {
        return new GenerationOpts(candidates, temperature, maxOutputTokens, search, level);
    }
}
