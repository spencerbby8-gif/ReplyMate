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
    /** P-intelligence-1: contacts where a NEW outgoing (owner-typed, e.g. sent by
     *  hand inside the chat app) row was just stored — the manual-send learning
     *  hook evaluates exactly these contacts (one entry per contact per batch). */
    public final List<PingRequest> outgoing = new ArrayList<PingRequest>();

    public String summary() {
        return "stored=" + stored + " dupes=" + duplicates + " filtered=" + filtered
            + " pings=" + pings.size();
    }
}
