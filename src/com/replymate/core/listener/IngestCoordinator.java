package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.usecase.ContactService;
import com.replymate.core.util.Clock;
import com.replymate.core.util.Logger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Listener ingestion pipeline (P2 core). Rules (approved design):
 *  - messages are STORED immediately (durability gate: zero unlogged text messages);
 *    only the outgoing ping alert is debounced by the platform layer;
 *  - content-hash dedupe handles notifications re-posted with extended history;
 *  - per-contact isolation: every write goes through a contact-scoped store call;
 *  - watched channels gated by user toggles; groups/own messages never ping;
 *  - everything recorded to the diagnostics ring + counters. */
public final class IngestCoordinator {

    public static final String KV_RING = "listener.diag_ring";
    public static final String KV_STORED_TOTAL = "listener.stored_total";
    public static final String KV_LAST_EVENT = "listener.last_event";

    private final ContactService contactService;
    private final MessageStore messages;
    private final KvStore kv;
    private final Clock clock;
    private final Logger log;

    public IngestCoordinator(ContactService contactService, MessageStore messages,
                             KvStore kv, Clock clock, Logger log) {
        this.contactService = contactService;
        this.messages = messages;
        this.kv = kv;
        this.clock = clock;
        this.log = log;
    }

    /** Aggregate for building at most one ping per contact per batch. */
    private static final class PingAgg {
        String name;
        String snippet;
        long ts;
    }

    /** Store + aggregate parsed events. Watch-gating normally happens BEFORE parsing
     *  in ParserRegistry; the enabled-set check here is defence in depth for callers
     *  that bypass the registry. Passing null means "no gate" (all channels allowed). */
    public IngestReport handle(List<NotifEvent> events, Set<Channel> enabled) {
        IngestReport rep = new IngestReport();
        if (events == null || events.isEmpty()) return rep;

        Map<Long, PingAgg> aggByContact = new LinkedHashMap<Long, PingAgg>();
        String lastTitle = "";
        String lastSnippet = "";
        Channel lastChannel = null;

        for (NotifEvent e : events) {
            if (e == null || e.channel == null) { rep.filtered++; continue; }
            if (enabled != null && !enabled.contains(e.channel)) {
                rep.filtered++;
                continue;
            }

            ListenerFilter.Verdict v = ListenerFilter.verdict(e);
            if (v == ListenerFilter.Verdict.SKIP) { rep.filtered++; continue; }

            String convTitle = IdentityResolver.firstNonEmpty(
                e.conversationTitle, e.senderName, "Unknown");
            String remoteKey = IdentityResolver.remoteKeyFor(convTitle, e.group);
            Contact contact = contactService.ensureChannelContact(
                e.channel, remoteKey, IdentityResolver.displayNameFor(convTitle));

            Direction dir = MessageClassifier.directionFor(e.senderName, e.ownerName);
            boolean emptyText = e.text == null || e.text.trim().isEmpty();
            String body = emptyText ? ListenerFilter.MEDIA_PLACEHOLDER : e.text.trim();

            long ts = e.timestampMs > 0 ? e.timestampMs : clock.now();
            String notifKey = TextIds.computeNotifKey(
                e.channel.wire, remoteKey, e.senderName, body, ts);

            if (messages.getByNotifKey(e.channel, notifKey) != null) {
                rep.duplicates++;
                continue;
            }

            Message m = new Message();
            m.contactId = contact.id;
            m.channel = e.channel;
            m.direction = dir;
            m.body = body;
            m.sentAt = ts;
            m.notifKey = notifKey;
            m.source = Source.LISTENER;
            messages.insertIgnore(m);
            rep.stored++;

            lastTitle = contact.displayName;
            lastSnippet = body;
            lastChannel = e.channel;

            // outgoing messages (ours) are context only; groups are store-only by policy.
            boolean pingEligible = v == ListenerFilter.Verdict.STORE_AND_PING
                && dir == Direction.INCOMING;
            if (pingEligible) {
                PingAgg agg = aggByContact.get(contact.id);
                if (agg == null) {
                    agg = new PingAgg();
                    agg.name = contact.displayName;
                    aggByContact.put(contact.id, agg);
                }
                agg.snippet = body;
                agg.ts = ts;
            }
        }

        for (Map.Entry<Long, PingAgg> entry : aggByContact.entrySet()) {
            PingAgg agg = entry.getValue();
            rep.pings.add(new IngestReport.PingRequest(
                entry.getKey(), agg.name, agg.snippet, agg.ts));
        }

        if (rep.stored > 0 || rep.duplicates > 0) {
            bumpLong(KV_STORED_TOTAL, rep.stored);
            kv.put(KV_LAST_EVENT, String.valueOf(clock.now()));
            String chan = lastChannel == null ? "?" : lastChannel.wire;
            String line = chan + " · " + lastTitle + ": " + abbreviate(lastSnippet, 46)
                + " (" + rep.summary() + ")";
            kv.put(KV_RING, DiagnosticsRing.append(kv.get(KV_RING, ""), clock.now(), line));
            log.i("Ingest", rep.summary());
        }
        return rep;
    }

    private void bumpLong(String key, long delta) {
        long current;
        try {
            current = Long.parseLong(kv.get(key, "0"));
        } catch (NumberFormatException nfe) {
            current = 0;
        }
        kv.put(key, String.valueOf(current + delta));
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
