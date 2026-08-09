package com.replymate.core.ports;

import com.replymate.core.search.WebEvidence;
import java.util.List;

/** P-intelligence-6: lookup of live public facts for ONE subject, for providers
 *  whose dialect has no native search tool. Implementations (provider/retrieval)
 *  only use official, documented, keyless endpoints and return bounded evidence —
 *  an empty list honestly means "couldn't verify", never an invented answer. */
public interface RetrievalPort {
    /** Live evidence for a subject (term or question), or an EMPTY list when
     *  nothing verifiable was found. Implementations must never throw. */
    List<WebEvidence> lookup(String subject);
}
