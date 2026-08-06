package com.replymate.core.ports;

import com.replymate.core.model.UsageEvent;
import java.util.List;

/** Token/cost metering for the usage dashboard + daily budget guard. */
public interface UsageStore {
    long insert(UsageEvent e);

    /** Sum of (tokensIn + tokensOut) since the given timestamp. */
    long totalTokensSince(long ts);

    /** Count of events since the given timestamp. */
    int countSince(long ts);

    /** Events since the given timestamp, newest-first (dashboard). */
    List<UsageEvent> since(long ts);

    /** Remove events older than the given timestamp (housekeeping). */
    void purgeBefore(long ts);
}
