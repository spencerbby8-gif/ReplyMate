package com.replymate.core.listener;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one ingestion batch. */
public final class IngestReport {

    /** A contact to surface as "new message" ping (POST notification). */
    public static final class PingRequest {
        public final long contactId;
        public final String displayName;
        public final String snippet;
        public final long latestTs;

        public PingRequest(long contactId, String displayName, String snippet, long latestTs) {
            this.contactId = contactId;
            this.displayName = displayName;
            this.snippet = snippet;
            this.latestTs = latestTs;
        }
    }

    public int stored;
    public int duplicates;
    public int filtered;
    public final List<PingRequest> pings = new ArrayList<PingRequest>();

    public String summary() {
        return "stored=" + stored + " dupes=" + duplicates + " filtered=" + filtered
            + " pings=" + pings.size();
    }
}
