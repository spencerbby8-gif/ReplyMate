package com.replymate.data.store;

import com.replymate.core.ports.KvStore;
import com.replymate.data.dao.KvDao;

/** KvStore port implementation over SQLite. */
public final class SqlKvStore implements KvStore {
    private final KvDao dao;

    public SqlKvStore(KvDao dao) {
        this.dao = dao;
    }

    @Override public String get(String key, String defValue) { return dao.get(key, defValue); }
    @Override public void put(String key, String value) { dao.put(key, value); }
    @Override public void delete(String key) { dao.delete(key); }
    @Override public boolean contains(String key) { return dao.contains(key); }
}
