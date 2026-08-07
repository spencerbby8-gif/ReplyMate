package com.replymate.data.store;

import com.replymate.core.ports.StyleSettingStore;
import com.replymate.data.dao.StyleSettingDao;
import java.util.Map;

public final class SqlStyleSettingStore implements StyleSettingStore {
    private final StyleSettingDao dao;
    private final com.replymate.core.util.Clock clock;

    public SqlStyleSettingStore(StyleSettingDao dao, com.replymate.core.util.Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    @Override public Map<String, String> all(Long contactId) { return dao.all(contactId); }
    @Override public void put(Long contactId, String key, String value) {
        dao.put(contactId, key, value, clock.now());
    }
    @Override public void remove(Long contactId, String key) { dao.remove(contactId, key); }

    @Override public void reassignContact(long fromContactId, long toContactId) {
        dao.reassignContact(fromContactId, toContactId);
    }
}
