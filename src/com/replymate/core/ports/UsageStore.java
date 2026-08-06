package com.replymate.core.ports;

import com.replymate.core.model.UsageEvent;

/** Token/cost metering for the usage dashboard + daily budget guard. */
public interface UsageStore {
    long insert(UsageEvent e);

    /** Sum of (tokensIn + tokensOut) since the given timestamp. */
    long totalTokensSince(long ts);

    /** Count of events since the given timestamp. */
    int countSince(long ts);

    /** Remove events older than the given timestamp (housekeeping). */
    void purgeBefore(long ts);
}
