package com.replymate.data.store;

import com.replymate.core.model.UsageEvent;
import com.replymate.core.ports.UsageStore;
import com.replymate.data.dao.UsageDao;

public final class SqlUsageStore implements UsageStore {
    private final UsageDao dao;

    public SqlUsageStore(UsageDao dao) { this.dao = dao; }

    @Override public long insert(UsageEvent e) { return dao.insert(e); }
    @Override public long totalTokensSince(long ts) { return dao.totalTokensSince(ts); }
    @Override public int countSince(long ts) { return dao.countSince(ts); }
    @Override public java.util.List<com.replymate.core.model.UsageEvent> since(long ts) {
        return dao.since(ts);
    }
    @Override public void purgeBefore(long ts) { dao.purgeBefore(ts); }
}
