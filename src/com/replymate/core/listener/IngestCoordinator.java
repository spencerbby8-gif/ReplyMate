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

    /** P-bg-10: near-identity window. Messaging apps re-post the same message
     *  with a fresh notification (id flip from channel|title key to a native
     *  conversationId, or a MessagingStyle timestamp rebase) a beat after the
     *  first post; the content-hash key then differs though the message is the
     *  same. Same contact + channel + direction + exact body inside this window
     *  is treated as the same event. A deliberate repeat later than the window
     *  survives as its own row. */
    public static final long NEAR_DUP_WINDOW_MS = 120_000L;

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

            // P-intelligence-13: group policy resolved ONCE per event — master
            // switch with per-app override (GroupPolicy); default OFF.
            boolean groupsAllowed = GroupPolicy.allowed(kv, e.channel);
            ListenerFilter.Verdict v = ListenerFilter.verdict(e, groupsAllowed);
            if (v == ListenerFilter.Verdict.SKIP) { rep.filtered++; continue; }

            // P-background-9: reaction notices are not conversation — never stored,
            // never pinged, never able to anchor memory/bursts/drafts (any app).
            if (ContentSignals.isReactionNotice(e.text)) { rep.filtered++; continue; }

            // P-background-9: in-chat SYSTEM/SERVICE lines (encryption notices,
            // security-code changes, decryption "waiting" placeholders, and bare
            // missed-call cards that arrived with no call category) are dropped
            // BEFORE a contact/conversation can be created or touched, before any
            // row is stored, and long before a draft/ping/provider call. Canonical
            // whole-card shapes only — a person's sentence that mentions a missed
            // call or encryption is always a real message and passes.
            if (SystemLines.isSystemLine(e.text)) { rep.filtered++; continue; }

            // P-intelligence-7: broadcast-style items and missed-call notices are
            // stopped BEFORE any contact or message exists. P-intelligence-13:
            // groups specifically are OPT-IN (default off) — when the user enabled
            // them for this app, they become first-class conversations that store,
            // ping and draft like a 1:1 (sender attribution stays per member).
            NoiseGate.Drop noise = NoiseGate.evaluate(e, groupsAllowed);
            if (noise.drop) { rep.filtered++; continue; }

            String convTitle = IdentityResolver.firstNonEmpty(
                e.conversationTitle, e.senderName, "Unknown");
            java.util.List<String> keys = IdentityResolver.keyCandidates(
                e.conversationId, convTitle, e.group);
            String remoteKey = keys.get(0);

            // P-intelligence-7: a non-text notice (media/voice/poll/document echo)
            // may only ADD context to an EXISTING conversation — it must never
            // CREATE one. Real (human, readable-text) messages only create
            // ReplyMate conversations; nothing else may clutter the home list.
            com.replymate.core.model.ContentKind kind = e.contentKind != null
                ? e.contentKind
                : ContentSignals.classify(e.mediaMime, e.hasAttachment, e.text);
            if (!NoiseGate.isReadableText(kind, e.text)
                    && contactService.findChannelContact(e.channel, keys) == null) {
                rep.filtered++;
                continue;
            }

            Contact contact = contactService.ensureChannelContact(
                e.channel, remoteKey, IdentityResolver.displayNameFor(convTitle), keys);

            // P-intelligence-15 (schema v7): persist the capture-time GROUP fact —
            // MessagingStyle's isGroupConversation, never a downstream guess.
            if (Boolean.TRUE.equals(e.group) && !contact.isGroup) {
                contact.isGroup = true;
                contactService.update(contact);
            }

            Direction dir = MessageClassifier.directionFor(
                e.senderName, e.ownerName, e.senderKey, e.ownerKey);

            // Content kind decided ONCE, from notification evidence (P-audit-deep) —
            // never from which app sent it. Media-only items get an honest per-kind
            // placeholder body; a real caption (text with media) is kept verbatim.
            boolean emptyText = e.text == null || e.text.trim().isEmpty();
            String body;
            if (kind != null && kind.isUnreadable()
                    && (emptyText || ContentSignals.isFallbackShape(e.text))) {
                body = kind.placeholder();
            } else {
                body = emptyText ? ListenerFilter.MEDIA_PLACEHOLDER : e.text.trim();
            }

            long ts = e.timestampMs > 0 ? e.timestampMs : clock.now();
            // Dedupe key is built from the RAW text + kind (stable across app
            // versions), not from our placeholder wording.
            String keyBody = kind != null && kind.isUnreadable()
                ? "|" + kind.wire + "|" + (e.text == null ? "" : e.text.trim())
                : body;
            String notifKey = TextIds.computeNotifKey(
                e.channel.wire, remoteKey, e.senderName, keyBody, ts);

            if (messages.getByNotifKey(e.channel, notifKey) != null) {
                rep.duplicates++;
                continue;
            }

            // P-bg-10: remote-key drift defeats the content hash on re-posts —
            // (a) the first post keys channel|title|sender..., the re-post a
            // beat later carries a native conversationId so the key becomes
            // cid:...; (b) MessagingStyle entries without a per-message time
            // fall back to the post time, which moves on every re-post. Both
            // shapes re-store the same message and fire a second job for one
            // real event. Collapse them here: readable text only (media/voice
            // placeholders legitimately repeat: two photos in a row are two
            // messages), same contact+channel+direction+exact body within the
            // window. Two identical "ok"s a minute apart collapse to one row —
            // accepted, documented trade; a repeat outside the window survives.
            if (NoiseGate.isReadableText(kind, body)
                    && messages.findRecentSame(contact.id, e.channel, dir, body, ts, NEAR_DUP_WINDOW_MS) != null) {
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
            m.contentKind = (kind == null ? com.replymate.core.model.ContentKind.TEXT : kind).wire;
            if (kind != null && kind.isMedia()) {
                m.mediaMime = e.mediaMime == null ? "" : e.mediaMime;
                m.mediaUri = e.mediaUri == null ? "" : e.mediaUri;
            }
            // P-memory-audit (schema v6): keep WHO actually sent each incoming item —
            // 1:1 chats this equals the contact name; in groups it's the member, so
            // prompts/audit attribute words to the right person.
            m.senderName = dir == Direction.INCOMING && e.senderName != null
                ? e.senderName.trim() : "";
            // P-intelligence-16b (schema v8): keep the platform's STABLE sender id
            // too — display names collide/rename, Person keys do not.
            m.senderKey = dir == Direction.INCOMING && e.senderKey != null
                ? e.senderKey.trim() : "";
            messages.insertIgnore(m);
            rep.stored++;

            lastTitle = contact.displayName;
            lastSnippet = body;
            lastChannel = e.channel;

            // outgoing messages (ours) are context only; a group's messages ping
            // only when the verdict allowed it (GroupPolicy opt-in, default off).
            // P-intelligence-15: HISTORIC context stores (deduped) but NEVER pings —
            // it is grounding for the burst, not a new event.
            boolean pingEligible = v == ListenerFilter.Verdict.STORE_AND_PING
                && dir == Direction.INCOMING && !e.historic;
            if (dir == Direction.OUTGOING) {
                // P-intelligence-1: a freshly-stored owner-typed row MAY be a manual
                // answer to a live draft — flag the contact for the learner (once
                // per batch, dupes never reach this line).
                boolean seen = false;
                for (IngestReport.PingRequest pr : rep.outgoing) {
                    if (pr.contactId == contact.id) { seen = true; break; }
                }
                if (!seen) {
                    rep.outgoing.add(new IngestReport.PingRequest(
                        contact.id, contact.displayName, body, ts));
                }
            }
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
