package com.replymate.core.usecase;

import com.replymate.core.model.Draft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of one successful generation: the freshly persisted draft variants. */
public final class DraftOutcome {
    public final String variantGroup;
    public final List<Draft> drafts;
    public final long latencyMs;
    public final int tokensIn;
    public final int tokensOut;

    public DraftOutcome(String variantGroup, List<Draft> drafts, long latencyMs,
                        int tokensIn, int tokensOut) {
        this.variantGroup = variantGroup;
        List<Draft> copy = new ArrayList<Draft>();
        if (drafts != null) copy.addAll(drafts);
        this.drafts = Collections.unmodifiableList(copy);
        this.latencyMs = latencyMs;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
    }
}
