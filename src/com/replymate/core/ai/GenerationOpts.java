package com.replymate.core.ai;

/** Tunables for one generation call. */
public final class GenerationOpts {
    public final int candidates;
    public final double temperature;
    public final int maxOutputTokens;

    private GenerationOpts(int candidates, double temperature, int maxOutputTokens) {
        this.candidates = candidates;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
    }

    public static GenerationOpts defaults() {
        return new GenerationOpts(1, 0.8, 220);
    }

    public static GenerationOpts of(int candidates, double temperature, int maxOutputTokens) {
        return new GenerationOpts(candidates, temperature, maxOutputTokens);
    }
}
