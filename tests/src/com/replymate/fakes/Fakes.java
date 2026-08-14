package com.replymate.fakes;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.RateLimitInfo;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.Scope;
import com.replymate.core.model.StyleProfile;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.ports.DraftStore;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.ports.ProviderGateway;
import com.replymate.core.ports.StyleStore;
import com.replymate.core.ports.UsageStore;
import com.replymate.core.util.Clock;
import com.replymate.core.util.IdGen;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory fakes for unit-testing core use-cases on the JVM. */
public final class Fakes {

    private Fakes() { }

    public static final long NOW = 1_752_000_000_000L;   // fixed test time

    public static Contact contact(long id, String name) {
        Contact c = new Contact();
        c.id = id;
        c.displayName = name;
        c.createdAt = NOW;
        c.updatedAt = NOW;
        return c;
    }

    public static Message msg(long contactId, com.replymate.core.model.Direction dir, String body) {
        Message m = new Message();
        m.contactId = contactId;
        m.channel = Channel.MANUAL;
        m.direction = dir;
        m.body = body;
        m.sentAt = NOW;
        m.source = com.replymate.core.model.Source.MANUAL;
        return m;
    }

    public static final class ContactStoreFake implements ContactStore {
        public final Map<Long, Contact> byId = new HashMap<Long, Contact>();
        public final List<ContactChannel> channels = new ArrayList<ContactChannel>();
        private long nextId = 100;
        private long nextChannelId = 1;
        public void put(Contact c) { byId.put(c.id, c); }
        @Override public long insert(Contact c) { if (c.id == 0) c.id = nextId++; byId.put(c.id, c); return c.id; }
        @Override public void update(Contact c) { byId.put(c.id, c); }
        @Override public Contact get(long contactId) { return byId.get(contactId); }
        @Override public List<Contact> all() { return new ArrayList<Contact>(byId.values()); }
        @Override public void delete(long contactId) { byId.remove(contactId); }
        @Override public List<ContactChannel> channelsByContact(long contactId) {
            List<ContactChannel> out = new ArrayList<ContactChannel>();
            for (ContactChannel ch : channels) if (ch.contactId == contactId) out.add(ch);
            return out;
        }
        @Override public ContactChannel findChannel(Channel channel, String remoteKey) {
            for (ContactChannel ch : channels) {
                if (ch.channel == channel && ch.remoteKey.equals(remoteKey)) return ch;
            }
            return null;
        }
        @Override public long upsertChannel(ContactChannel ch) {
            ContactChannel existing = findChannel(ch.channel, ch.remoteKey);
            if (existing != null) {
                existing.contactId = ch.contactId;
                existing.lastSeenAt = ch.lastSeenAt;
                return existing.id;
            }
            ch.id = nextChannelId++;
            channels.add(ch);
            return ch.id;
        }
        @Override public void touchChannel(long channelId, long lastSeenAt) {
            for (ContactChannel ch : channels) if (ch.id == channelId) ch.lastSeenAt = lastSeenAt;
        }
        /** Fork-heal: re-point the duplicate's channel rows; UNIQUE(channel, remote_key)
         *  collisions with the kept contact's rows are dropped (kept contact wins). */
        @Override public void reassignContact(long fromContactId, long toContactId) {
            java.util.Set<String> keepKeys = new java.util.HashSet<String>();
            for (ContactChannel ch : channels) {
                if (ch.contactId == toContactId) keepKeys.add(ch.channel.wire + "\u0001" + ch.remoteKey);
            }
            java.util.Iterator<ContactChannel> it = channels.iterator();
            while (it.hasNext()) {
                ContactChannel ch = it.next();
                if (ch.contactId != fromContactId) continue;
                if (keepKeys.contains(ch.channel.wire + "\u0001" + ch.remoteKey)) it.remove();
                else ch.contactId = toContactId;
            }
        }
    }

    public static final class MessageStoreFake implements MessageStore {
        public final Map<Long, List<Message>> byContact = new HashMap<Long, List<Message>>();
        private long nextId = 1;
        public void add(Message m) {
            if (!byContact.containsKey(m.contactId)) byContact.put(m.contactId, new ArrayList<Message>());
            m.id = nextId++;
            byContact.get(m.contactId).add(m);
        }
        @Override public long insert(Message m) { add(m); return m.id; }
        /** Fork-heal: move all messages (preserving order) onto the kept contact. */
        @Override public void reassignContact(long fromContactId, long toContactId) {
            List<Message> from = byContact.remove(fromContactId);
            if (from == null) return;
            List<Message> to = byContact.get(toContactId);
            if (to == null) { to = new ArrayList<Message>(); byContact.put(toContactId, to); }
            for (Message m : from) { m.contactId = toContactId; to.add(m); }
        }
        @Override public Message getByNotifKey(Channel channel, String notifKey) {
            if (notifKey == null) return null;
            for (List<Message> list : byContact.values()) {
                for (Message m : list) {
                    if (m.channel == channel && notifKey.equals(m.notifKey)) return m;
                }
            }
            return null;
        }
        /** Near-identity window check: same contact+channel+direction+exact body
         *  whose stored timestamp is within {@code windowMs} of {@code ts}. */
        @Override public Message findRecentSame(long contactId, Channel channel, Direction dir, String body, long ts, long windowMs) {
            if (body == null) return null;
            List<Message> all = byContact.get(contactId);
            if (all == null) return null;
            for (Message m : all) {
                if (m.channel == channel && m.direction == dir && body.equals(m.body)
                        && Math.abs(m.sentAt - ts) <= windowMs) return m;
            }
            return null;
        }
        @Override public List<Message> lastMessages(long contactId, int limit) {
            List<Message> all = byContact.get(contactId);
            if (all == null) return new ArrayList<Message>();
            int from = Math.max(0, all.size() - limit);
            return new ArrayList<Message>(all.subList(from, all.size()));
        }
        /** Rolling-summary boundary: strictly this contact's rows with id < beforeId,
         *  newest {@code limit} of them, oldest-first (ids assigned in insert order). */
        @Override public List<Message> olderThanId(long contactId, long beforeId, int limit) {
            List<Message> all = byContact.get(contactId);
            List<Message> out = new ArrayList<Message>();
            if (all == null) return out;
            for (Message m : all) if (m.id > 0 && m.id < beforeId) out.add(m);
            int from = Math.max(0, out.size() - Math.max(1, limit));
            return new ArrayList<Message>(out.subList(from, out.size()));
        }
        @Override public List<Message> searchByBody(String query, int limit) {
            List<Message> hits = new ArrayList<Message>();
            if (query == null || query.trim().isEmpty()) return hits;
            String needle = query.trim().toLowerCase(java.util.Locale.US);
            for (List<Message> list : byContact.values()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Message m = list.get(i);
                    if (m.body != null && m.body.toLowerCase(java.util.Locale.US).contains(needle)) {
                        hits.add(m);
                        if (hits.size() >= limit) return hits;
                    }
                }
            }
            return hits;
        }
        @Override public int countByContact(long contactId) {
            List<Message> all = byContact.get(contactId);
            return all == null ? 0 : all.size();
        }
        @Override public void deleteByContact(long contactId) { byContact.remove(contactId); }
    }

    public static final class StyleStoreFake implements StyleStore {
        public StyleProfile global;
        @Override public StyleProfile get(Scope scope, Long contactId) {
            return scope == Scope.GLOBAL ? global : null;
        }
        @Override public long upsert(StyleProfile p) { if (p.scope == Scope.GLOBAL) global = p; return 1; }
        @Override public void deleteByContact(long contactId) { }
    }

    public static final class DraftStoreFake implements DraftStore {
        public final List<Draft> saved = new ArrayList<Draft>();
        private long nextId = 1;
        @Override public long insert(Draft d) { d.id = nextId++; saved.add(d); return d.id; }
        @Override public void reassignContact(long fromContactId, long toContactId) {
            for (Draft d : saved) if (d.contactId == fromContactId) d.contactId = toContactId;
        }
        /** Contract honors the port: newest-first. */
        @Override public List<Draft> byContact(long contactId, int limit) {
            List<Draft> out = new ArrayList<Draft>();
            for (int i = saved.size() - 1; i >= 0 && out.size() < limit; i--) {
                Draft d = saved.get(i);
                if (d.contactId == contactId) out.add(d);
            }
            return out;
        }
        @Override public List<Draft> byVariantGroup(String variantGroup) { return saved; }
        @Override public void updateStatus(long draftId, DraftStatus status) {
            for (Draft d : saved) if (d.id == draftId) d.status = status;
        }
        @Override public void updateText(long draftId, String newText) {
            for (Draft d : saved) if (d.id == draftId) d.replyText = newText;
        }
        @Override public void updateFavorite(long draftId, boolean favorite) {
            for (Draft d : saved) if (d.id == draftId) d.favorite = favorite;
        }
        @Override public void delete(long draftId) {
            for (int i = saved.size() - 1; i >= 0; i--) if (saved.get(i).id == draftId) saved.remove(i);
        }
        @Override public void deleteByContact(long contactId) { }
    }

    public static final class MemoryStoreFake implements com.replymate.core.ports.MemoryStore {
        /** contactId -> facts (insertion order). */
        public final Map<Long, List<com.replymate.core.model.MemoryFact>> factsByContact =
            new HashMap<Long, List<com.replymate.core.model.MemoryFact>>();
        public final Map<Long, List<com.replymate.core.model.ContactSummary>> summariesByContact =
            new HashMap<Long, List<com.replymate.core.model.ContactSummary>>();
        private long nextId = 1;

        private List<com.replymate.core.model.MemoryFact> list(long contactId) {
            List<com.replymate.core.model.MemoryFact> l = factsByContact.get(contactId);
            if (l == null) {
                l = new ArrayList<com.replymate.core.model.MemoryFact>();
                factsByContact.put(contactId, l);
            }
            return l;
        }

        @Override public List<com.replymate.core.model.MemoryFact> activeFacts(long contactId) {
            List<com.replymate.core.model.MemoryFact> out =
                new ArrayList<com.replymate.core.model.MemoryFact>();
            for (com.replymate.core.model.MemoryFact f : list(contactId)) {
                if (!f.disabled) out.add(f);
            }
            return out;
        }
        @Override public List<com.replymate.core.model.MemoryFact> allFacts(long contactId) {
            return new ArrayList<com.replymate.core.model.MemoryFact>(list(contactId));
        }
        /** Fork-heal: facts move (text_norm collision -> kept contact's fact wins);
         *  the duplicate's rolling summaries are dropped (partial-history artifacts). */
        @Override public void reassignContact(long fromContactId, long toContactId) {
            List<com.replymate.core.model.MemoryFact> from = factsByContact.remove(fromContactId);
            if (from != null) {
                List<com.replymate.core.model.MemoryFact> to = list(toContactId);
                java.util.Set<String> have = new java.util.HashSet<String>();
                for (com.replymate.core.model.MemoryFact f : to) have.add(f.textNorm);
                for (com.replymate.core.model.MemoryFact f : from) {
                    if (have.contains(f.textNorm)) continue;
                    f.contactId = toContactId;
                    to.add(f);
                    have.add(f.textNorm);
                }
            }
            summariesByContact.remove(fromContactId);
        }
        /** Same merge key as SQLite: (contact_id, text_norm); id stable on update. */
        @Override public long upsertFact(com.replymate.core.model.MemoryFact f) {
            for (com.replymate.core.model.MemoryFact cur : list(f.contactId)) {
                if (cur.textNorm.equals(f.textNorm)) {
                    f.id = f.id > 0 ? f.id : cur.id;
                    list(f.contactId).remove(cur);
                    list(f.contactId).add(f);
                    return f.id;
                }
            }
            f.id = f.id > 0 ? f.id : nextId++;
            list(f.contactId).add(f);
            return f.id;
        }
        @Override public void setFactPinned(long factId, boolean pinned) {
            for (List<com.replymate.core.model.MemoryFact> l : factsByContact.values()) {
                for (com.replymate.core.model.MemoryFact f : l) if (f.id == factId) f.pinned = pinned;
            }
        }
        @Override public void setFactDisabled(long factId, boolean disabled) {
            for (List<com.replymate.core.model.MemoryFact> l : factsByContact.values()) {
                for (com.replymate.core.model.MemoryFact f : l) if (f.id == factId) f.disabled = disabled;
            }
        }
        @Override public void deleteFact(long factId) {
            for (List<com.replymate.core.model.MemoryFact> l : factsByContact.values()) {
                for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id == factId) l.remove(i);
            }
        }
        @Override public com.replymate.core.model.ContactSummary latestSummary(long contactId) {
            List<com.replymate.core.model.ContactSummary> l = summariesByContact.get(contactId);
            if (l == null || l.isEmpty()) return null;
            com.replymate.core.model.ContactSummary best = l.get(0);
            for (com.replymate.core.model.ContactSummary s : l) {
                if (s.version > best.version) best = s;
            }
            return best;
        }
        @Override public long insertSummary(com.replymate.core.model.ContactSummary s) {
            List<com.replymate.core.model.ContactSummary> l = summariesByContact.get(s.contactId);
            if (l == null) {
                l = new ArrayList<com.replymate.core.model.ContactSummary>();
                summariesByContact.put(s.contactId, l);
            }
            l.add(s);
            return l.size();
        }
        @Override public void deleteAllForContact(long contactId) {
            factsByContact.remove(contactId);
            summariesByContact.remove(contactId);
        }
    }

    public static final class StyleSettingStoreFake implements com.replymate.core.ports.StyleSettingStore {
        /** contactId (null=global) -> (key -> value). */
        public final Map<Long, Map<String, String>> rows = new HashMap<Long, Map<String, String>>();

        private Map<String, String> scope(Long contactId) {
            Map<String, String> m = rows.get(contactId);
            if (m == null) {
                m = new HashMap<String, String>();
                rows.put(contactId, m);
            }
            return m;
        }

        @Override public Map<String, String> all(Long contactId) {
            return new HashMap<String, String>(scope(contactId));
        }
        @Override public void put(Long contactId, String key, String value) {
            scope(contactId).put(key, value);
        }
        @Override public void remove(Long contactId, String key) {
            scope(contactId).remove(key);
        }
        /** Fork-heal: kept contact's value wins on key collision. */
        @Override public void reassignContact(long fromContactId, long toContactId) {
            Map<String, String> from = rows.remove(fromContactId);
            if (from == null) return;
            Map<String, String> to = scope(toContactId);
            for (Map.Entry<String, String> e : from.entrySet()) {
                if (!to.containsKey(e.getKey())) to.put(e.getKey(), e.getValue());
            }
        }
    }

    public static final class LearningStoreFake implements com.replymate.core.ports.LearningStore {
        public final Map<Long, List<StyleSignal>> byContact =
            new HashMap<Long, List<StyleSignal>>();
        private long nextId = 1;

        @Override public long insert(StyleSignal s) {
            List<StyleSignal> l = byContact.get(s.contactId);
            if (l == null) {
                l = new ArrayList<StyleSignal>();
                byContact.put(s.contactId, l);
            }
            s.id = nextId++;
            l.add(s);
            return s.id;
        }
        @Override public void reassignContact(long fromContactId, long toContactId) {
            List<StyleSignal> from = byContact.remove(fromContactId);
            if (from == null) return;
            List<StyleSignal> to = byContact.get(toContactId);
            if (to == null) { to = new ArrayList<StyleSignal>(); byContact.put(toContactId, to); }
            to.addAll(from);
        }
        @Override public List<StyleSignal> byContact(long contactId, int limit) {
            List<StyleSignal> l = byContact.get(contactId);
            List<StyleSignal> out = new ArrayList<StyleSignal>();
            if (l == null) return out;
            for (int i = l.size() - 1; i >= 0 && out.size() < limit; i--) out.add(l.get(i));
            return out;
        }
        @Override public void deleteForContact(long contactId) {
            byContact.remove(contactId);
        }
    }

    /** P4 convenience: a LearningService over fresh fakes. */
    public static com.replymate.core.learning.LearningService learningService(
            LearningStoreFake store, KvStoreFake kv) {
        return new com.replymate.core.learning.LearningService(store, kv, FIXED_CLOCK);
    }

    /** P4 convenience: a StyleService over fresh fakes. */
    public static com.replymate.core.style.StyleService styleService(
            StyleSettingStoreFake settings, com.replymate.core.learning.LearningService learning) {
        return new com.replymate.core.style.StyleService(settings, learning);
    }

    public static final class UsageStoreFake implements UsageStore {
        public final List<UsageEvent> events = new ArrayList<UsageEvent>();
        @Override public long insert(UsageEvent e) { events.add(e); return events.size(); }
        @Override public long totalTokensSince(long ts) {
            long t = 0;
            for (UsageEvent e : events) t += e.tokensIn + e.tokensOut;
            return t;
        }
        @Override public int countSince(long ts) { return events.size(); }
        @Override public List<UsageEvent> since(long ts) {
            List<UsageEvent> out = new ArrayList<UsageEvent>();
            for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).ts >= ts) out.add(events.get(i));
            return out;
        }
        @Override public void purgeBefore(long ts) { }
    }

    public static final class KvStoreFake implements KvStore {
        private final Map<String, String> map = new HashMap<String, String>();
        @Override public String get(String key, String defValue) {
            return map.containsKey(key) ? map.get(key) : defValue;
        }
        @Override public void put(String key, String value) { map.put(key, value); }
        @Override public void delete(String key) { map.remove(key); }
        @Override public boolean contains(String key) { return map.containsKey(key); }
    }

    public static final class FakeProvider implements AiProvider {
        private final Result<ChatReply> result;
        public ChatRequest lastRequest;
        public int calls;

        public static FakeProvider returning(String... variants) {
            return new FakeProvider(Result.ok(new ChatReply(
                new ArrayList<String>(Arrays.asList(variants)), 11, 7, RateLimitInfo.NONE)), null);
        }
        public static FakeProvider failing(String error) {
            return new FakeProvider(null, error);
        }
        private FakeProvider(Result<ChatReply> result, String error) {
            this.result = result != null ? result : Result.<ChatReply>err(error);
        }
        @Override public String type() { return "gemini"; }
        @Override public Result<ChatReply> generate(ChatRequest request) {
            calls++;
            lastRequest = request;
            return result;
        }
        @Override public Result<Boolean> validateKey() { return Result.ok(Boolean.TRUE); }
        @Override public Result<java.util.List<String>> listModels() {
            return Result.ok(java.util.Collections.singletonList("test-model"));
        }
    }

    public static final class GatewayFake implements ProviderGateway {
        public AiProvider provider;
        public String model = "test-model";
        public com.replymate.core.model.ProviderRef meta =
            new com.replymate.core.model.ProviderRef("gemini", "Google Gemini",
                "https://generativelanguage.googleapis.com", "test-model");
        public GatewayFake(AiProvider provider) { this.provider = provider; }
        @Override public AiProvider active() { return provider; }
        @Override public String activeModel() { return model; }
        @Override public com.replymate.core.model.ProviderRef activeMeta() { return provider == null ? null : meta; }
    }

    /** P-intelligence-3: in-memory ProviderStore (privacy-mode + built-in seed tests). */
    public static final class ProviderStoreFake implements com.replymate.core.ports.ProviderStore {
        public final java.util.List<com.replymate.core.model.ProviderDef> rows =
            new java.util.ArrayList<com.replymate.core.model.ProviderDef>();
        private long nextId = 1;
        @Override public long upsertActive(com.replymate.core.model.ProviderDef def) {
            if (def.id <= 0) def.id = nextId++;
            for (com.replymate.core.model.ProviderDef r : rows) r.isActive = false;
            rows.remove(def);
            def.isActive = true;
            rows.add(0, def);
            return def.id;
        }
        @Override public com.replymate.core.model.ProviderDef active() {
            for (com.replymate.core.model.ProviderDef r : rows) if (r.isActive) return r;
            return null;
        }
        @Override public java.util.List<com.replymate.core.model.ProviderDef> all() {
            return new java.util.ArrayList<com.replymate.core.model.ProviderDef>(rows);
        }
        @Override public void setActive(long id) {
            for (com.replymate.core.model.ProviderDef r : rows) r.isActive = r.id == id;
        }
        @Override public void delete(long id) {
            com.replymate.core.model.ProviderDef hit = null;
            for (com.replymate.core.model.ProviderDef r : rows) if (r.id == id) hit = r;
            if (hit != null) rows.remove(hit);
        }
    }

    /** P-intelligence-3: in-memory SecretVault (built-in key seeding tests). */
    public static final class SecretVaultFake implements com.replymate.core.ports.SecretVault {
        private final java.util.Map<String, String> map = new java.util.HashMap<String, String>();
        @Override public void putSecret(String alias, String value) { map.put(alias, value); }
        @Override public String getSecret(String alias) { return map.get(alias); }
        @Override public boolean hasSecret(String alias) { return map.containsKey(alias); }
        @Override public void deleteSecret(String alias) { map.remove(alias); }
    }

    public static final Clock FIXED_CLOCK = new Clock() {
        @Override public long now() { return NOW; }
    };

    public static final IdGen IDS = new IdGen() {
        private int n;
        @Override public String next() { return "group-" + (++n); }
    };

    public static final Logger NOOP_LOG = new Logger() {
        @Override public void d(String tag, String msg) { }
        @Override public void i(String tag, String msg) { }
        @Override public void w(String tag, String msg) { }
        @Override public void e(String tag, String msg) { }
        @Override public void e(String tag, String msg, Throwable t) { }
    };
}
