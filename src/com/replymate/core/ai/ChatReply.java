package com.replymate.core.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-agnostic generation result. P-intelligence-6 adds SAFE METADATA ONLY:
 *  how many web searches the provider ran, their public source titles, how many
 *  reasoning tokens were billed, and any honest degradation note. Chain-of-thought,
 *  thought summaries and raw reasoning fields NEVER live here — parsers drop them. */
public final class ChatReply {
    public final List<String> variants;
    public final int tokensIn;
    public final int tokensOut;
    public final RateLimitInfo limits;
    /** Web searches the provider actually executed for this call (0 = none). */
    public final int searchQueries;
    /** Public source TITLES the search grounded on (≤3, never bodies). */
    public final List<String> searchSources;
    /** Reasoning tokens the provider billed (reasoning_content itself is discarded). */
    public final int reasoningTokens;
    /** Honest degradation note ("" = none) — e.g. "this model doesn't support live
     *  search, so the reply used built-in knowledge". Surfaced in the audit trail. */
    public final String note;

    public ChatReply(List<String> variants, int tokensIn, int tokensOut, RateLimitInfo limits) {
        this(variants, tokensIn, tokensOut, limits, 0, null, 0, "");
    }

    public ChatReply(List<String> variants, int tokensIn, int tokensOut, RateLimitInfo limits,
                     int searchQueries, List<String> searchSources,
                     int reasoningTokens, String note) {
        List<String> copy = new ArrayList<String>();
        if (variants != null) copy.addAll(variants);
        this.variants = Collections.unmodifiableList(copy);
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.limits = limits == null ? RateLimitInfo.NONE : limits;
        this.searchQueries = Math.max(0, searchQueries);
        List<String> srcs = new ArrayList<String>();
        if (searchSources != null) {
            for (String s : searchSources) {
                if (s != null && !s.trim().isEmpty() && srcs.size() < 3) srcs.add(s.trim());
            }
        }
        this.searchSources = Collections.unmodifiableList(srcs);
        this.reasoningTokens = Math.max(0, reasoningTokens);
        this.note = note == null ? "" : note;
    }

    /** Copy with a different note (used when a capability is gracefully stripped). */
    public ChatReply withNote(String newNote) {
        return new ChatReply(variants, tokensIn, tokensOut, limits,
            searchQueries, searchSources, reasoningTokens, newNote == null ? "" : newNote);
    }
}
