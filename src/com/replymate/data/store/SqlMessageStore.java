package com.replymate.data.store;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Message;
import com.replymate.core.ports.MessageStore;
import com.replymate.data.dao.MessageDao;
import java.util.List;

public final class SqlMessageStore implements MessageStore {
    private final MessageDao dao;

    public SqlMessageStore(MessageDao dao) { this.dao = dao; }

    @Override public long insert(Message m) { return dao.insert(m); }
    @Override public long insertIgnore(Message m) { return dao.insertIgnore(m); }
    @Override public Message getByNotifKey(Channel channel, String notifKey) {
        return dao.getByNotifKey(channel, notifKey);
    }
    @Override public Message findRecentSame(long contactId, Channel channel,
                        com.replymate.core.model.Direction dir,
                        String body, long ts, long windowMs) {
        return dao.findRecentSame(contactId, channel, dir, body, ts, windowMs);
    }
    @Override public List<Message> lastMessages(long contactId, int limit) {
        return dao.lastMessages(contactId, limit);
    }
    @Override public List<Message> olderThanId(long contactId, long beforeId, int limit) {
        return dao.olderThanId(contactId, beforeId, limit);
    }
    @Override public List<Message> searchByBody(String query, int limit) {
        return dao.searchByBody(query, limit);
    }
    @Override public int countByContact(long contactId) { return dao.countByContact(contactId); }
    @Override public void deleteByContact(long contactId) { dao.deleteByContact(contactId); }

    @Override public void reassignContact(long fromContactId, long toContactId) {
        dao.reassignContact(fromContactId, toContactId);
    }
}
