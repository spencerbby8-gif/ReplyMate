package com.replymate.core.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-agnostic generation result. */
public final class ChatReply {
    public final List<String> variants;
    public final int tokensIn;
    public final int tokensOut;
    public final RateLimitInfo limits;

    public ChatReply(List<String> variants, int tokensIn, int tokensOut, RateLimitInfo limits) {
        List<String> copy = new ArrayList<String>();
        if (variants != null) copy.addAll(variants);
        this.variants = Collections.unmodifiableList(copy);
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.limits = limits == null ? RateLimitInfo.NONE : limits;
    }
}
