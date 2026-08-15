package com.replymate.core.usecase;

import com.replymate.core.ai.ChatReply;
import com.replymate.core.ai.ChatRequest;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.Scope;
import com.replymate.core.model.StyleProfile;
import com.replymate.core.model.ToneTransform;
import java.util.Collections;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.model.UsageKind;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.ports.DraftStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.ports.ProviderGateway;
import com.replymate.core.ports.StyleStore;
import com.replymate.core.ports.UsageStore;
import com.replymate.core.prompt.PromptBuilder;
import com.replymate.core.prompt.PromptBundle;
import com.replymate.core.util.Clock;
import com.replymate.core.util.IdGen;
import com.replymate.core.util.Logger;
import com.replymate.core.util.Result;
import java.util.ArrayList;
import java.util.List;

/** Draft generation orchestration (BLUEPRINT §6). Synchronous — the app layer wraps
 *  calls on the background executor. Every read is scoped to ONE contact (decision #5);
 *  private-mode contacts fail CLOSED here in addition to the store choke point. */
public final class DraftService {

    private final ContactStore contacts;
    private final MessageStore messages;
    private final StyleStore styles;
    private final ProfileService profiles;
    private final DraftStore drafts;
    private final UsageStore usage;
    private final ProviderGateway gateway;
    private final IdGen ids;
    private final Clock clock;
    private final Logger log;
    private final com.replymate.core.style.StyleService styleService;
    private final com.replymate.core.learning.LearningService learningService;
    private final com.replymate.core.memory.MemoryService memory;   // null = legacy tests
    /** P-intelligence-4: optional Settings-toggle source for LiveContext (the
     *  "livectx.enabled" key). Wired by AppContainer; null ⇒ default ON. */
    private com.replymate.core.ports.KvStore liveKv;
    private com.replymate.core.ports.RetrievalPort retrieval;

    /** Wire the Settings store after construction (same optional-setter pattern as
     *  ContactService.setMerger — legacy call sites keep their behavior). */
    public void setLiveKv(com.replymate.core.ports.KvStore kv) { this.liveKv = kv; }

    /** P-intelligence-6: the encyclopedia fallback for providers without native
     *  search (AppContainer wires WikimediaRetrieval; tests wire a fake). Null =
     *  fallback transparently degrades to the anti-hallucination honesty line. */
    public void setRetrieval(com.replymate.core.ports.RetrievalPort r) { this.retrieval = r; }

    /** P-intelligence-16b: the ConversationState engine. Wired by AppContainer;
     *  when set, GROUP conversations are engagement-gated before any research or
     *  paid call (1:1 behavior untouched; null ⇒ legacy pre-gate behavior). */
    private com.replymate.core.usecase.ConversationStateService convoStates;
    public void setConversationStateService(
            com.replymate.core.usecase.ConversationStateService s) { this.convoStates = s; }

    /** Runner-decodeable error prefix for an engagement-gated refusal:
     *  {@code ENGAGEMENT_SKIP_PREFIX + VERDICT + ":" + reason} — no draft, no
     *  provider call. WAIT defers one re-check; NO_REPLY stays silent. */
    public static final String ENGAGEMENT_SKIP_PREFIX = "engagement-skip:";

    /** P-background-8: per-call staleness probe (the shared instance stays
     *  stateless — two conversations can generate concurrently). The background
     *  runner passes its JobCoalescer token; manual flows pass nothing. */
    public interface AbortCheck {
        boolean aborted();
    }

    /** The diagnosable outcome a superseded job returns BEFORE the paid call. */
    public static final String SUPERSEDED_ERROR =
        "superseded by a newer message before the provider call";

    /** P-bg-10: the pre-call gate cannot see a message that lands DURING the
     *  provider call. A job whose abort flag trips while the call is in flight
     *  returns this instead of purging/saving/alerting a stale draft; the newer
     *  job owns the conversation. The interrupted call was paid once — the
     *  why-lines + diag say so honestly rather than hiding the cost. */
    public static final String SUPERSEDED_AFTER_CALL_ERROR =
        "superseded by a newer message after the provider call";

    private static String joinAudit(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size() && i < 3; i++) {
            if (i > 0) b.append(", ");
            b.append(items.get(i));
        }
        return b.toString();
    }

    public DraftService(ContactStore contacts, MessageStore messages, StyleStore styles,
                        ProfileService profiles, DraftStore drafts, UsageStore usage,
                        ProviderGateway gateway, IdGen ids, Clock clock, Logger log,
                        com.replymate.core.style.StyleService styleService,
                        com.replymate.core.learning.LearningService learningService,
                        com.replymate.core.memory.MemoryService memory) {
        this.contacts = contacts;
        this.messages = messages;
        this.styles = styles;
        this.profiles = profiles;
        this.drafts = drafts;
        this.usage = usage;
        this.gateway = gateway;
        this.ids = ids;
        this.clock = clock;
        this.log = log;
        this.styleService = styleService;
        this.learningService = learningService;
        this.memory = memory;
    }

    public Result<DraftOutcome> generateForContact(long contactId) {
        return generateForContact(contactId, null);
    }

    /** P-background-8 full form: {@code abort} is consulted ONCE — after all the
     *  (possibly slow) preparation, immediately before the paid provider call —
     *  so a job superseded while it was stuck in research never burns a request
     *  and never saves a stale draft. Null = legacy behavior, byte-identical. */
    public Result<DraftOutcome> generateForContact(long contactId, AbortCheck abort) {
        // P-background-12: per-contact generation MUTEX. The JobCoalescer cancels
        // superseded jobs, but two independently-triggered generations for the
        // SAME conversation can still overlap on the 2-thread GEN lane (a catch-up
        // sweep firing while a live job is mid-provider-call, then finishing its
        // own gates before the live job's hash commit lands). Their purge/insert
        // sequences would interleave and STACK identical draft rows. Serializing
        // here makes purge→insert atomic per contact — the second run sees the
        // first run's complete committed state and (de)duplicates correctly.
        synchronized (lockFor(contactId)) {
            return generateForContactLocked(contactId, abort);
        }
    }

    /** Striped locks keyed by contact — distinct conversations never wait on
     *  each other (that would be the blocking the owner forbade). */
    private static final Object[] CONTACT_LOCKS = new Object[32];
    static {
        for (int i = 0; i < CONTACT_LOCKS.length; i++) CONTACT_LOCKS[i] = new Object();
    }
    private static Object lockFor(long contactId) {
        int slot = (int) ((contactId ^ (contactId >>> 32)) & 0x7fffffff)
            % CONTACT_LOCKS.length;
        return CONTACT_LOCKS[slot];
    }

    private Result<DraftOutcome> generateForContactLocked(long contactId, AbortCheck abort) {
        Contact c = contacts.get(contactId);
        if (c == null) return Result.err("Contact not found.");
        if (c.privateMode) return Result.err("This contact is private — AI generation is disabled.");
        if (!c.aiEnabled) return Result.err("AI replies are disabled for this contact.");

        AiProvider provider = gateway.active();
        if (provider == null) {
            return Result.err("Set up an AI provider first (Settings → AI providers) — add your API key.");
        }

        List<Message> thread = messages.lastMessages(contactId, 30);
        // lastMessages is OLDEST-first → the loop ends at the LATEST incoming message.
        Message lastIncoming = null;
        for (Message m : thread) {
            if (m.direction == Direction.INCOMING) lastIncoming = m;
        }
        if (lastIncoming == null) {
            return Result.err("Add at least one message from " + c.displayName
                + " first, so I know what to reply to.");
        }

        // P-context-honesty (extended P-audit-deep): the reply MUST answer the latest
        // incoming message, so it has to be readable text. Media-only / empty items
        // get an honest, KIND-SPECIFIC explanation + safe fallback — never a
        // hallucinated reply and never a provider call (no money burned guessing).
        if (!PromptBuilder.usableText(lastIncoming.body)) {
            String app = com.replymate.core.listener.WatchedApps.labelFor(lastIncoming.channel);
            com.replymate.core.model.ContentKind kind = lastIncoming.effectiveKind();
            String what = kind == null || kind == com.replymate.core.model.ContentKind.TEXT
                ? " has no readable text."
                : " is " + kind.label() + " — ReplyMate "
                    + kindExplanation(kind) + ".";
            boolean mediaRef = kind != null && kind.isMedia()
                && !lastIncoming.mediaUri.trim().isEmpty();
            return Result.err("The latest " + app + " item from " + c.displayName + what
                + (mediaRef
                    ? " The notification did share a file reference; it stays on this phone —"
                        + " ReplyMate never opens or uploads media."
                    : " The notification also carried no usable media reference.")
                + " I won't invent a reply without their actual words. Open " + app
                + " to check it, or type their message into this chat and generate again.");
        }

        // P-intelligence-16b: GROUP ENGAGEMENT GATE — a group draft is never
        // generated merely because a notification arrived. ConversationState
        // (participants/burst/topic/addressing) is evaluated BEFORE the voice/
        // memory research below and long before the paid provider call; a WAIT
        // or NO_REPLY verdict exits with the Runner-decodeable skip error —
        // zero drafts, zero provider calls, full diag trail.
        com.replymate.core.usecase.ConversationStateService.Evaluation convEval = null;
        if (c.isGroup && convoStates != null) {
            String ownerName = profiles.load() == null ? "" : profiles.load().name;
            String incomingHash = com.replymate.core.assistant.AssistantPlanner.hashOf(
                lastIncoming.body + "|" + lastIncoming.sentAt + "|" + lastIncoming.id);
            convEval = convoStates.evaluate(c, thread, ownerName, incomingHash);
            if (!convEval.engagement.shouldGenerate()) {
                return Result.err(ENGAGEMENT_SKIP_PREFIX
                    + convEval.engagement.verdict.name() + ":"
                    + convEval.engagement.reason);
            }
        }

        String styleRules = "";
        StyleProfile global = styles.get(Scope.GLOBAL, null);
        if (global != null && global.derivedRules != null) styleRules = global.derivedRules;

        // P4: global user voice + per-contact overrides + custom prompt + learned hints.
        com.replymate.core.style.StyleService.ComposedVoice voice =
            styleService == null ? null : styleService.compose(c);
        java.util.List<String> why = new java.util.ArrayList<String>();
        why.addAll(profiles.excludedSections());
        if (voice != null) why.addAll(voice.why);

        // P-memory-audit: long-term memory for THIS contact only — pinned facts,
        // rolling summary of everything older than the hot window, learned style
        // from approved replies. All local; nothing crosses contact boundaries.
        com.replymate.core.memory.MemoryService.Recall mem = null;
        if (memory != null) {
            // P-intelligence-2 precedence: voice dimensions the owner set EXPLICITLY
            // for this contact beat the learned derived-style guesses for them.
            java.util.Set<String> explicitControls = new java.util.HashSet<String>();
            if (styleService != null) {
                java.util.Map<String, String> crows = styleService.contactRows(contactId);
                if (com.replymate.core.style.StyleSettings.level(crows, "length") != null) {
                    explicitControls.add("length");
                }
                if (com.replymate.core.style.StyleSettings.level(crows, "emoji") != null) {
                    explicitControls.add("emoji");
                }
            }
            mem = memory.withLearnedStyle(memory.recall(c, thread), c,
                approvedTextsFor(contactId), explicitControls);
            why.addAll(mem.why);
            if (!c.memoryEnabled) {
                why.add("memory disabled for this contact — no summary, facts"
                    + " or learned style were used");
            }
        }

        // P-intelligence-2 (Prompt Audit accuracy): the raw FEEDBACK counters behind
        // every learned hint are also credited, verbatim — never a hint without its
        // evidence trail.
        com.replymate.core.learning.LearningEngine.Counters feedback =
            learningService == null ? null : learningService.counters(contactId);
        int signalsTotal = feedback == null ? 0 : feedback.total();
        if (feedback != null) {
            StringBuilder fb = new StringBuilder("feedback so far for ")
                .append(c.displayName).append(": ")
                .append(feedback.approved).append(" approved");
            if (feedback.approved > 0) {
                fb.append(" (");
                boolean firstPart = true;
                if (feedback.copiedAsIs > 0) {
                    fb.append(feedback.copiedAsIs).append(" copied as-is");
                    firstPart = false;
                }
                if (feedback.quickSent > 0) {
                    if (!firstPart) fb.append(", ");
                    fb.append(feedback.quickSent).append(" sent via quick-reply");
                    firstPart = false;
                }
                if (feedback.manualMatched > 0) {
                    if (!firstPart) fb.append(", ");
                    fb.append(feedback.manualMatched).append(" manual matches");
                }
                fb.append(')');
            }
            fb.append(" · ").append(feedback.edited).append(" edited");
            if (feedback.manualTotal() > 0) {
                fb.append(" (manual sends: ").append(feedback.manualMatched)
                  .append(" matched word-for-word, ").append(feedback.manualCorrected)
                  .append(" corrected)");
            }
            fb.append(" · ").append(feedback.regenerated).append(" regenerated · ")
              .append(feedback.rejected).append(" rejected (recent window)");
            if (signalsTotal == 0) fb.append(" — no signals yet");
            why.add(fb.toString());
        }

        // P-intelligence-6 (context-expiry fix, the aura-farming→Arsenal bug): the
        // newest message ANY existing draft (pending or used) was generated against
        // marks "already answered". The burst — active topic — is only what came
        // AFTER it. A pending draft is therefore never permanent active context,
        // and a clearly unrelated new message naturally resets the topic while the
        // older conversation stays in history + long-term memory untouched.
        long answeredWatermark = 0;
        for (com.replymate.core.model.Draft prior : drafts.byContact(contactId, 50)) {
            if (prior.inReplyToId != null && prior.inReplyToId > answeredWatermark) {
                answeredWatermark = prior.inReplyToId;
            }
        }
        java.util.List<String> unscopedTail = PromptBuilder.burstTailUsableIncoming(thread, 6);
        java.util.List<String> burstTail = PromptBuilder.burstTailUsableIncoming(
            thread, 6, answeredWatermark);
        if (unscopedTail.size() > burstTail.size()) {
            why.add((unscopedTail.size() - burstTail.size())
                + " earlier message(s) already covered by a previous draft —"
                + " answering only what's new (old topic kept in history, not re-answered)");
        }

        // P-intelligence-1 (message understanding): the model consumes a CLEAN
        // conversation object, not raw notification text — sender, app, message type,
        // burst state + mechanics, the owner's last reply and the cold-start flag are
        // assembled once here, shape the prompt, and are credited in Prompt Audit.
        com.replymate.core.understanding.ConversationContext understanding =
            com.replymate.core.understanding.ConversationContextBuilder.build(
                c, thread, burstTail,
                styleService == null
                    ? java.util.Collections.<String, String>emptyMap()
                    : styleService.globalRows(),
                styleService == null
                    ? java.util.Collections.<String, String>emptyMap()
                    : styleService.contactRows(contactId),
                voice == null ? null : voice.extraLines,
                mem == null ? null : mem.lines,
                signalsTotal);
        why.addAll(understanding.whyLines());

        // P-intelligence-5 (reply planning): a DETERMINISTIC plan before the model
        // writes anything — what the moment is, what the reply must do, which burst
        // lines count, what to skip, how long it should be. No extra provider call
        // at any depth (researched boundary: per-call cost + mobile latency +
        // free-tier RPM make reasoning chains the wrong default for a chat app).
        String depth = liveKv == null ? "normal"
            : liveKv.get(com.replymate.core.plan.PlanDepth.KV_KEY, "normal");
        String planText = null;
        com.replymate.core.plan.ReplyPlanner.Plan plan = null;
        if (com.replymate.core.plan.PlanDepth.BASIC.equals(depth)) {
            why.add(com.replymate.core.plan.PlanDepth.auditLine(depth));
        } else {
            plan = com.replymate.core.plan.ReplyPlanner.plan(
                understanding, burstTail, planLengthLabel(contactId), null);
            why.add(com.replymate.core.plan.PlanDepth.auditLine(depth));
            why.addAll(plan.why);
            planText = com.replymate.core.plan.PlanDepth.DEEP.equals(depth)
                ? plan.fullBlock() : plan.compactLine();
        }

        PromptBundle bundle = new PromptBundle(
            profiles.loadFiltered(), c, styleRules, thread,
            voice == null ? "" : voice.voiceLine,
            voice == null ? null : voice.extraLines,
            profiles.extraFiltered(),
            mem == null ? null : mem.lines);
        bundle.understanding = understanding;
        // P-intelligence-16b: ride the (successful) group engagement evaluation —
        // topic/participants/target situation lines and the exact reply target.
        if (convEval != null) {
            bundle.groupExtraLines = com.replymate.core.prompt.GroupPrompt.lines(
                convEval.state, convEval.engagement);
            if (convEval.engagement.target != null
                    && convEval.engagement.target.hasIdentity()) {
                bundle.replyTarget = convEval.engagement.target;
            }
            convoStates.markLast(contactId, convEval.engagement);
        }
        bundle.planText = planText;
        bundle.answeredWatermark = answeredWatermark;

        // Live context: the device clock only (P6 removed the dated glossary —
        // word/current questions are automatic live search now, handled below).
        com.replymate.core.live.LiveContext.Snapshot live = liveFor(thread);
        bundle.liveLine = live.promptLine;
        why.add(live.whyLine);

        // ================= P-intelligence-6: AUTOMATIC LIVE INTELLIGENCE ========
        // Search is a capability, not a toggle (directives 2/7): the gate listens
        // to the ACTIVE burst only (stale topics never trigger), decides locally
        // whether live facts are actually needed, and the result RIDES INTO the
        // generation — natively (the provider's own search tool) or as bounded
        // encyclopedia evidence. Ordinary replies must never trigger a lookup.
        java.util.List<String> searchHearing = burstTail;
        if (searchHearing.isEmpty() && lastIncoming != null
                && PromptBuilder.usableText(lastIncoming.body)) {
            searchHearing = java.util.Collections.singletonList(lastIncoming.body.trim());
        }
        boolean gateFired = applyLiveSearchGate(c, provider, searchHearing, thread,
            bundle, why);
        // P-intelligence-6 directive 3: automatic reasoning depth — simple stays
        // fast; hard/ambiguous/search-grounded moments think deeper via the
        // provider's OFFICIAL control. Level + reasons are audit metadata only.
        com.replymate.core.reason.Reasoning.Decision think =
            applyReasoning(plan, burstTail.size(), gateFired, searchHearing,
                bundle, why);

        ChatRequest request = PromptBuilder.build(bundle);

        // P-background-8: the LAST gate before money moves. Everything above —
        // style, memory, the search gate, the external lookup — is prep; a job
        // that went stale during it stops here with an explicit outcome.
        if (abort != null && abort.aborted()) {
            return Result.err(SUPERSEDED_ERROR);
        }

        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        long latency = clock.now() - t0;
        if (!reply.ok) {
            log.w("DraftService", "generation failed for contact " + contactId + ": " + reply.error);
            return Result.err(reply.error);
        }

        // P-bg-10: the pre-call gate above cannot see a message that arrived
        // WHILE the provider was generating. Check the flag again now — before
        // any purge, save, or alert — so the stale job leaves no trace and the
        // newer job's job answers. The interrupted call was paid once.
        if (abort != null && abort.aborted()) {
            return Result.err(SUPERSEDED_AFTER_CALL_ERROR);
        }

        ChatReply r = reply.value;
        if (r.variants.isEmpty()) return Result.err("The provider returned no reply text.");

        // P-intelligence-6 post-call: capability outcomes are audit metadata.
        if (!r.note.isEmpty()) why.add(r.note);   // honest degradation, verbatim
        appendCapabilityAudit(why, bundle, gateFired, think, r, provider);

        // P-ux-fix: (Re)generate REPLACES the current draft instead of stacking duplicate
        // cards. Only untouched drafts are cleared — anything the owner explicitly kept
        // (⭐ favorite) or already used (copied/edited/sent) stays. Old drafts are
        // purged only AFTER the provider succeeded, so a failed regen never wipes
        // the draft the owner still has.
        int replaced = purgeUnsavedDrafts(contactId);
        if (replaced > 0) why.add("regenerate replaced " + replaced + " unsaved draft(s)");

        long now = clock.now();
        String group = ids.next();
        String model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        String snapshot = PromptBuilder.snapshot(request, model, "reply", why,
            auditContextFor(c, lastIncoming, mem));
        int outEach = r.tokensOut > 0 ? Math.max(1, r.tokensOut / r.variants.size()) : 0;

        // P-background-12: ONE current draft per message — persist the FIRST
        // usable variant only. The request now asks for a single candidate, but
        // some providers still return extras (or the multi-candidate fallback
        // path produced them); persisting every variant was the on-device
        // "2–3 identical drafts" report. Extra variants are dropped (the draft
        // card gets Regenerate for a different take; interactive previews keep
        // showing all samples without persisting anything).
        List<Draft> saved = new ArrayList<Draft>();
        for (String variant : r.variants) {
            String text = variant == null ? "" : variant.trim();
            if (text.isEmpty()) continue;
            if (!saved.isEmpty()) break;    // one message ⇒ one current draft
            Draft d = new Draft();
            d.contactId = contactId;
            d.inReplyToId = lastIncoming.id > 0 ? lastIncoming.id : null;
            d.promptSnapshotJson = snapshot;
            d.replyText = text;
            d.model = model;
            d.variantGroup = group;
            d.status = DraftStatus.GENERATED;
            d.latencyMs = latency;
            d.tokensIn = r.tokensIn;
            d.tokensOut = outEach;
            d.createdAt = now;
            d.id = drafts.insert(d);
            saved.add(d);
        }
        if (saved.isEmpty()) return Result.err("The provider returned empty replies.");

        UsageEvent u = new UsageEvent();
        u.ts = now;
        u.model = model;
        u.tokensIn = r.tokensIn;
        u.tokensOut = r.tokensOut;
        u.kind = UsageKind.REPLY;
        usage.insert(u);

        log.i("DraftService", "generated " + saved.size() + " variants for contact " + contactId
            + " in " + latency + "ms");
        return Result.ok(new DraftOutcome(group, saved, latency, r.tokensIn, r.tokensOut));
    }

    /** P-intelligence-14: INTENTIONAL generation — follow-up bump / clarifying
     *  question / topic continuation / fresh opener. Runs the SAME pipeline as a
     *  reply (voice, memory layers, contact settings, learning, Search gate,
     *  reasoning decision, capability audit, one-current-draft save, usage
     *  metering) with the kind's own deterministic task. The result is a DRAFT:
     *  it lands in the same approve/edit/copy flow — nothing ever auto-sends. */
    public Result<DraftOutcome> composeForContact(long contactId,
            com.replymate.core.prompt.ComposeKind kind) {
        return composeForContact(contactId, kind, null);
    }

    public Result<DraftOutcome> composeForContact(long contactId,
            com.replymate.core.prompt.ComposeKind kind, AbortCheck abort) {
        if (kind == null) kind = com.replymate.core.prompt.ComposeKind.REPLY;
        if (kind == com.replymate.core.prompt.ComposeKind.REPLY) {
            return generateForContact(contactId, abort);
        }
        synchronized (lockFor(contactId)) {
            return composeForContactLocked(contactId, kind, abort, null);
        }
    }

    /** P-intelligence-14 (owner mandate): AUTO FOLLOW-UP after an approved reply.
     *  Called by every approval path (share-sheet copy/edit, quick-reply send,
     *  notification copy). The per-contact control defaults OFF; every "not now"
     *  case is a named skip from {@link FollowUpPolicy} — returned as an honest
     *  Result.err so callers can silently ignore it while the Prompt Audit /
     *  Diagnostics trail stays truthful. PREPARE runs the exact same
     *  {@link ComposeKind#FOLLOW_UP} pipeline and lands as a GENERATED draft —
     *  it NEVER sends by itself.
     *  @param approved the draft the owner just approved (may be null when the
     *                  caller only has an id — the anchor then falls back to the
     *                  owner's last outgoing) */
    public Result<DraftOutcome> maybePrepareFollowUp(long contactId, Draft approved) {
        Contact c = contacts.get(contactId);
        if (c == null) return Result.err("follow-up skipped: contact not found");
        boolean on = styleService != null
            && com.replymate.core.style.StyleSettings.autoFollowOn(
                styleService.contactRows(contactId));

        List<Message> thread = messages.lastMessages(contactId, 30);
        Message newestUsable = null;
        Long lastOutgoingId = null;
        for (int i = thread.size() - 1; i >= 0; i--) {
            Message m = thread.get(i);
            if (m == null || !PromptBuilder.usableText(m.body)) continue;
            if (newestUsable == null) newestUsable = m;
            if (m.direction == Direction.OUTGOING && lastOutgoingId == null) {
                lastOutgoingId = Long.valueOf(m.id);
            }
        }
        boolean waiting = false;
        for (Draft d : drafts.byContact(contactId, 5)) {
            if (d != null && d.status == DraftStatus.GENERATED) { waiting = true; break; }
        }
        Long answeredId = approved == null ? null : approved.inReplyToId;
        boolean approvedIsIntentional = approved != null
            && approved.promptSnapshotJson != null
            && approved.promptSnapshotJson.contains("\"kind\":\"compose:");
        String anchorKey = "followup.auto.anchor." + contactId;
        long doneAnchor = 0L;
        if (liveKv != null) {
            try {
                doneAnchor = Long.parseLong(liveKv.get(anchorKey, "0"));
            } catch (NumberFormatException nfe) {
                doneAnchor = 0L;
            }
        }

        FollowUpPolicy.Verdict v = FollowUpPolicy.decide(on, c.privateMode, c.aiEnabled,
            newestUsable, lastOutgoingId, answeredId, approvedIsIntentional, waiting,
            doneAnchor);
        if (!v.prepare) return Result.err("follow-up skipped: " + v.skipped);

        // The follow-up bumps what the owner ACTUALLY just sent. A quick-reply
        // send (or copy-paste) never lands in our store, so the approved draft's
        // final text is passed as the anchor override — the bump quotes IT, and
        // the thread-tail "their message is fresh" rejection is bypassed (the
        // policy already proved that incoming is the one just answered).
        String approvedText = approved == null || approved.replyText == null
            ? null : approved.replyText.trim();
        Result<DraftOutcome> r;
        synchronized (lockFor(contactId)) {
            r = composeForContactLocked(contactId,
                com.replymate.core.prompt.ComposeKind.FOLLOW_UP, null, approvedText);
        }
        if (r != null && r.ok && liveKv != null) {
            liveKv.put(anchorKey, String.valueOf(
                FollowUpPolicy.anchorOf(answeredId, lastOutgoingId)));
        }
        return r;
    }

    private Result<DraftOutcome> composeForContactLocked(long contactId,
            com.replymate.core.prompt.ComposeKind kind, AbortCheck abort,
            String followUpAnchorOverride) {
        Contact c = contacts.get(contactId);
        if (c == null) return Result.err("Contact not found.");
        if (c.privateMode) return Result.err("This contact is private — AI generation is disabled.");
        if (!c.aiEnabled) return Result.err("AI replies are disabled for this contact.");

        AiProvider provider = gateway.active();
        if (provider == null) {
            return Result.err("Set up an AI provider first (Settings → AI providers) — add your API key.");
        }

        List<Message> thread = messages.lastMessages(contactId, 30);

        // ---- admission: each kind needs its honest anchor ----------------
        String lastOutgoing = null;
        Message lastIncoming = null;
        String lastAny = null;
        for (int i = thread.size() - 1; i >= 0; i--) {
            Message m = thread.get(i);
            if (m == null || !PromptBuilder.usableText(m.body)) continue;
            if (lastAny == null) lastAny = m.body.trim();
            if (m.direction == Direction.OUTGOING && lastOutgoing == null) {
                lastOutgoing = m.body.trim();
            }
            if (m.direction == Direction.INCOMING && lastIncoming == null) lastIncoming = m;
        }
        switch (kind) {
            case FOLLOW_UP:
                if (lastOutgoing == null && followUpAnchorOverride == null) {
                    return Result.err("Follow-up needs a message FROM YOU that "
                        + c.displayName + " hasn't answered yet — nothing to bump.");
                }
                if (followUpAnchorOverride == null
                        && lastIncoming != null && lastIncoming.body != null) {
                    // an incoming message NEWER than our last outgoing means it is
                    // THEIR turn, not a bump — say so honestly. (The override form —
                    // an approved reply that never landed in our store — was already
                    // vetted by FollowUpPolicy: the fresh incoming IS what that
                    // reply answered.)
                    boolean incomingIsNewer = false;
                    for (int i = thread.size() - 1; i >= 0; i--) {
                        Message m = thread.get(i);
                        if (m == null || !PromptBuilder.usableText(m.body)) continue;
                        incomingIsNewer = m.direction == Direction.INCOMING;
                        break;
                    }
                    if (incomingIsNewer) {
                        return Result.err("Their latest message is still fresh — reply to"
                            + " it instead of bumping (use Generate).");
                    }
                }
                break;
            case CLARIFY:
                if (lastIncoming == null) {
                    return Result.err("Nothing from " + c.displayName
                        + " to clarify yet — wait for their message.");
                }
                break;
            case CONTINUE:
                if (lastAny == null) {
                    return Result.err("No conversation to continue with " + c.displayName
                        + " yet — use Opener for a fresh start.");
                }
                break;
            default: break;   // OPENER: always admissible
        }

        String styleRules = "";
        StyleProfile global = styles.get(Scope.GLOBAL, null);
        if (global != null && global.derivedRules != null) styleRules = global.derivedRules;
        com.replymate.core.style.StyleService.ComposedVoice voice =
            styleService == null ? null : styleService.compose(c);
        java.util.List<String> why = new java.util.ArrayList<String>();
        why.addAll(profiles.excludedSections());
        if (voice != null) why.addAll(voice.why);
        why.add("intentional generation: " + kind.wire
            + " (task composed for the intention; reply planner is reply-scoped)");

        com.replymate.core.memory.MemoryService.Recall mem = null;
        if (memory != null) {
            java.util.Set<String> explicitControls = new java.util.HashSet<String>();
            if (styleService != null) {
                java.util.Map<String, String> crows = styleService.contactRows(contactId);
                if (com.replymate.core.style.StyleSettings.level(crows, "length") != null) {
                    explicitControls.add("length");
                }
                if (com.replymate.core.style.StyleSettings.level(crows, "emoji") != null) {
                    explicitControls.add("emoji");
                }
            }
            mem = memory.withLearnedStyle(memory.recall(c, thread), c,
                approvedTextsFor(contactId), explicitControls);
            why.addAll(mem.why);
            if (!c.memoryEnabled) {
                why.add("memory disabled for this contact — no summary, facts"
                    + " or learned style were used");
            }
        }

        // P-intelligence-14 (audit parity with the reply path): an intentional
        // draft credits the same raw FEEDBACK counters — never a learned hint
        // without its evidence trail, whatever the generation intention.
        com.replymate.core.learning.LearningEngine.Counters feedback =
            learningService == null ? null : learningService.counters(contactId);
        int signalsTotal = feedback == null ? 0 : feedback.total();
        if (feedback != null) {
            StringBuilder fb = new StringBuilder("feedback so far for ")
                .append(c.displayName).append(": ")
                .append(feedback.approved).append(" approved");
            if (feedback.approved > 0) {
                fb.append(" (");
                boolean firstPart = true;
                if (feedback.copiedAsIs > 0) {
                    fb.append(feedback.copiedAsIs).append(" copied as-is");
                    firstPart = false;
                }
                if (feedback.quickSent > 0) {
                    if (!firstPart) fb.append(", ");
                    fb.append(feedback.quickSent).append(" sent via quick-reply");
                    firstPart = false;
                }
                if (feedback.manualMatched > 0) {
                    if (!firstPart) fb.append(", ");
                    fb.append(feedback.manualMatched).append(" manual matches");
                }
                fb.append(')');
            }
            fb.append(" · ").append(feedback.edited).append(" edited");
            if (feedback.manualTotal() > 0) {
                fb.append(" (manual sends: ").append(feedback.manualMatched)
                  .append(" matched word-for-word, ").append(feedback.manualCorrected)
                  .append(" corrected)");
            }
            fb.append(" · ").append(feedback.regenerated).append(" regenerated · ")
              .append(feedback.rejected).append(" rejected (recent window)");
            if (signalsTotal == 0) fb.append(" — no signals yet");
            why.add(fb.toString());
        }

        PromptBundle bundle = new PromptBundle(
            profiles.loadFiltered(), c, styleRules, thread,
            voice == null ? "" : voice.voiceLine,
            voice == null ? null : voice.extraLines,
            profiles.extraFiltered(),
            mem == null ? null : mem.lines);
        bundle.composeKind = kind;
        bundle.followUpAnchorOverride = followUpAnchorOverride;

        com.replymate.core.live.LiveContext.Snapshot live = liveFor(thread);
        bundle.liveLine = live.promptLine;
        why.add(live.whyLine);

        // Same Search gate + reasoning decision as a reply — the intentional
        // kinds hear their ANCHOR (an opener has no anchor: no search, default
        // reasoning — an honest nothing-to-look-up).
        java.util.List<String> hearing = new java.util.ArrayList<String>();
        switch (kind) {
            case FOLLOW_UP:
                if (followUpAnchorOverride != null) {
                    hearing.add(followUpAnchorOverride.trim());
                } else if (lastOutgoing != null) {
                    hearing.add(lastOutgoing);
                }
                break;
            case CLARIFY:
                if (lastIncoming != null) hearing.add(lastIncoming.body.trim());
                break;
            case CONTINUE:
                if (lastAny != null) hearing.add(lastAny);
                break;
            default: break;
        }
        boolean gateFired = applyLiveSearchGate(c, provider, hearing, thread,
            bundle, why);
        com.replymate.core.reason.Reasoning.Decision think =
            applyReasoning(null, 0, gateFired, hearing, bundle, why);

        ChatRequest request = PromptBuilder.build(bundle);
        if (abort != null && abort.aborted()) {
            return Result.err(SUPERSEDED_ERROR);
        }
        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        long latency = clock.now() - t0;
        if (!reply.ok) {
            log.w("DraftService", "compose(" + kind.wire + ") failed for contact "
                + contactId + ": " + reply.error);
            return Result.err(reply.error);
        }
        if (abort != null && abort.aborted()) {
            return Result.err(SUPERSEDED_AFTER_CALL_ERROR);
        }

        ChatReply r = reply.value;
        if (r.variants.isEmpty()) return Result.err("The provider returned no reply text.");
        if (!r.note.isEmpty()) why.add(r.note);
        appendCapabilityAudit(why, bundle, gateFired, think, r, provider);

        int replaced = purgeUnsavedDrafts(contactId);
        if (replaced > 0) why.add("compose replaced " + replaced + " unsaved draft(s)");

        long now = clock.now();
        String group = ids.next();
        String model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        String snapshot = PromptBuilder.snapshot(request, model, kind.wire, why,
            auditContextFor(c, lastIncoming, mem));
        List<Draft> saved = new ArrayList<Draft>();
        for (String variant : r.variants) {
            String text = variant == null ? "" : variant.trim();
            if (text.isEmpty()) continue;
            if (!saved.isEmpty()) break;          // one intention ⇒ one current draft
            Draft d = new Draft();
            d.contactId = contactId;
            d.inReplyToId = null;                 // intentional: NOT a reply anchor
            d.promptSnapshotJson = snapshot;
            d.replyText = text;
            d.model = model;
            d.variantGroup = group;
            d.status = DraftStatus.GENERATED;
            d.latencyMs = latency;
            d.tokensIn = r.tokensIn;
            d.tokensOut = r.tokensOut;
            d.createdAt = now;
            d.id = drafts.insert(d);
            saved.add(d);
        }
        if (saved.isEmpty()) return Result.err("The provider returned empty text.");

        UsageEvent u = new UsageEvent();
        u.ts = now;
        u.model = model;
        u.tokensIn = r.tokensIn;
        u.tokensOut = r.tokensOut;
        u.kind = UsageKind.REPLY;
        usage.insert(u);

        log.i("DraftService", "composed " + kind.wire + " for contact " + contactId
            + " in " + latency + "ms");
        return Result.ok(new DraftOutcome(group, saved, latency, r.tokensIn, r.tokensOut));
    }

    /* ------------------------------------------------------ shared pipeline bits */

    /** P-intelligence-6 (extracted P-intel-14 shared): the automatic live-search
     *  gate. Decides locally whether the HEARING texts need live facts, consults
     *  the on-device cache, otherwise asks for the provider's native search
     *  in-call or looks the subject up on the free encyclopedia path. Mutates
     *  bundle + why; returns whether the gate fired. Never throws into a reply. */
    private boolean applyLiveSearchGate(Contact c, AiProvider provider,
            java.util.List<String> searchHearing, List<Message> thread,
            PromptBundle bundle, java.util.List<String> why) {
        java.util.List<String> historyWords = new java.util.ArrayList<String>();
        java.util.Set<String> burstSet = new java.util.HashSet<String>();
        for (String b : searchHearing) if (b != null) burstSet.add(b.trim());
        if (thread != null) {
            for (Message m : thread) {
                if (m != null && m.body != null && !m.body.trim().isEmpty()
                        && !burstSet.contains(m.body.trim())) {
                    historyWords.add(m.body.trim());   // gate history = context, not burst
                }
            }
        }
        java.util.List<String> gateNames = new java.util.ArrayList<String>();
        gateNames.add(c.displayName);
        if (profiles.loadFiltered() != null) gateNames.add(profiles.loadFiltered().displayName());
        com.replymate.core.search.SearchGate.Need gateNeed;
        try {
            gateNeed = com.replymate.core.search.SearchGate.assess(
                searchHearing, historyWords, gateNames);
        } catch (RuntimeException gateBoom) {
            gateNeed = com.replymate.core.search.SearchGate.Need.NONE;  // never hurt a reply
        }
        com.replymate.core.model.ProviderRef capRef = gateway.activeMeta();
        com.replymate.core.caps.ModelCaps caps = com.replymate.core.caps.ModelCaps.of(
            com.replymate.core.model.ProviderType.fromWire(
                capRef == null ? provider.type() : capRef.wire),
            capRef == null ? "" : capRef.modelName);
        boolean gateFired = gateNeed.kind != com.replymate.core.search.SearchGate.Kind.NONE;
        long lookupMs = 0;
        if (gateFired) {
            long lu0 = clock.now();
            java.util.List<com.replymate.core.search.WebEvidence> facts;
            if (liveKv == null) {
                facts = null;
            } else {
                facts = com.replymate.core.search.SearchCache.get(
                    liveKv, gateNeed.subject, clock.now());
            }
            if (facts != null) {
                bundle.searchLine = com.replymate.core.search.WebEvidence.promptLine(
                    facts, gateNeed.subject, true);
                why.add("live search: \"" + gateNeed.subject + "\" answered from the"
                    + " on-device cache — repeat looks inside a week are free");
            } else if (caps.search == com.replymate.core.caps.ModelCaps.SearchTransport.NATIVE) {
                // the provider's own official search tool rides the SAME generation
                // call — search happens in-call, its result grounds the text.
                bundle.requestSearch = true;
                // P-bg-10 honesty: REQUESTED is not RAN. The provider may bill
                // the feature yet execute zero searches — say exactly that below
                // from the response metadata instead of claiming grounding here.
                why.add("live search: " + gateNeed.reason
                    + " → requested the provider's native web search in-call"
                    + " (billed by the provider); whether it actually RAN is"
                    + " judged from the response metadata below — never assumed");
            } else {
                // retrieval fallback (official free encyclopedias) BEFORE generation.
                facts = retrieval == null
                    ? java.util.Collections.<com.replymate.core.search.WebEvidence>emptyList()
                    : retrieval.lookup(gateNeed.subject);
                lookupMs = clock.now() - lu0;
                if (facts == null || facts.isEmpty()) {
                    why.add("live lookup failed or found nothing ("
                        + gateNeed.reason + ") — answer leans on the"
                        + " anti-hallucination rule, not on invented facts");
                } else {
                    bundle.searchLine = com.replymate.core.search.WebEvidence.promptLine(
                        facts, gateNeed.subject, false);
                    if (liveKv != null) {
                        com.replymate.core.search.SearchCache.put(
                            liveKv, gateNeed.subject, facts, clock.now());
                    }
                    why.add("live search: " + gateNeed.reason + " → looked up just now"
                        + " (free encyclopedia, " + lookupMs + "ms): "
                        + joinAudit(
                            com.replymate.core.search.WebEvidence.auditOf(facts)));
                }
            }
        }
        return gateFired;
    }

    /** P-intelligence-6 directive 3 (extracted P-intel-14 shared): automatic
     *  reasoning depth from the deterministic signals. */
    private com.replymate.core.reason.Reasoning.Decision applyReasoning(
            com.replymate.core.plan.ReplyPlanner.Plan plan, int burstSize,
            boolean gateFired, java.util.List<String> hearing,
            PromptBundle bundle, java.util.List<String> why) {
        int qMarks = 0;
        for (String s : hearing) {
            if (s == null) continue;
            for (int qi = 0; qi < s.length(); qi++) if (s.charAt(qi) == '?') qMarks++;
        }
        com.replymate.core.reason.Reasoning.Decision think =
            com.replymate.core.reason.Reasoning.decide(plan, burstSize, gateFired, qMarks);
        bundle.reasoningLevel = think.level;
        if (think.whyLine() != null) why.add(think.whyLine());
        return think;
    }

    /** P-intelligence-6 post-call (extracted P-intel-14 shared): SEARCH metering
     *  + capability outcomes as audit metadata — executed-search credit, the
     *  requested-but-not-executed mirror, billed-reasoning confirmation and the
     *  UNCONFIRMED marker for unbillable deeper thinking. */
    private void appendCapabilityAudit(java.util.List<String> why, PromptBundle bundle,
            boolean gateFired, com.replymate.core.reason.Reasoning.Decision think,
            ChatReply r, AiProvider provider) {
        if (gateFired) {
            // live-search generations are metered as their own kind so the usage
            // dashboard prices them honestly: native searches show the provider's
            // token/call figures; free paths (cache, encyclopedia) show zero.
            UsageEvent su = new UsageEvent();
            su.ts = clock.now();
            su.model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
            su.kind = UsageKind.SEARCH;
            su.tokensIn = bundle.requestSearch ? r.tokensIn : 0;
            su.tokensOut = bundle.requestSearch ? r.tokensOut : 0;
            usage.insert(su);
        }
        if (r.searchQueries > 0) {
            why.add("live search ran inside the reply: " + r.searchQueries
                + " web search(es) executed by the provider"
                + (r.searchSources.isEmpty() ? ""
                    : " — sources: " + joinAudit(r.searchSources)));
        }
        // P-bg-10 honesty: the mirror image. Search was requested but the
        // provider reported zero executed queries — the why-trail must say the
        // answer is unverified, never imply grounding that did not happen.
        if (bundle.requestSearch && r.searchQueries == 0) {
            why.add("the provider ran NO web search for this reply despite the"
                + " request — it answered from its own knowledge, unverified live");
        }
        if (r.reasoningTokens > 0) {
            why.add("model thinking: " + r.reasoningTokens
                + " reasoning tokens billed (the reasoning itself stays at the"
                + " provider — never shown, never stored)");
        }
        // P-bg-10 honesty for thinking: a non-default reasoning level with no
        // billed reasoning tokens cannot support any "thought harder" claim.
        if (think != null
                && !com.replymate.core.reason.Reasoning.DEFAULT.equals(think.level)
                && r.reasoningTokens == 0) {
            why.add("deeper thinking ("
                + think.level.toUpperCase(java.util.Locale.US)
                + ") was requested but the provider reported no billed reasoning"
                + " tokens — treat any 'thought deeper' claim as UNCONFIRMED"
                + " for this provider/model");
        }
    }

    /* ---------------------------------------------------------------- audit ctx */

    /** P-ux-fix: deletes this contact's untouched generated drafts (not favorited,
     *  never copied/edited/sent). Returns how many were removed. Tone transforms
     *  bypass this on purpose — stacking tone variants of one draft is intentional. */
    private int purgeUnsavedDrafts(long contactId) {
        int removed = 0;
        List<Draft> existing = drafts.byContact(contactId, 50);
        for (Draft d : existing) {
            if (d.status == DraftStatus.GENERATED && !d.favorite) {
                drafts.delete(d.id);
                removed++;
            }
        }
        return removed;
    }

    /** The most recent APPROVED reply texts for one contact (M4 evidence): drafts
     *  the owner copied as-is or edited-then-copied. Newest-first, capped. */
    /** P-intelligence-5: the effective Reply-length word for the planner —
     *  contact override beats global (same precedence as the voice layer), OFF
     *  means "the owner disabled this control — the planner stays quiet about it". */
    private String planLengthLabel(long contactId) {
        if (styleService == null) return null;   // planner uses its built-in default
        java.util.Map<String, String> crows = styleService.contactRows(contactId);
        java.util.Map<String, String> grows = styleService.globalRows();
        Integer cLen = com.replymate.core.style.StyleSettings.level(crows, "length");
        Integer gLen = com.replymate.core.style.StyleSettings.level(grows, "length");
        Integer eff = cLen != null ? cLen : gLen;
        if (eff == null) return null;
        if (eff.intValue() == com.replymate.core.style.StyleControls.LEVEL_OFF) return "";
        return com.replymate.core.style.StyleControls.byKey("length")
            .levelLabel(eff.intValue());
    }

    /** One LiveContext snapshot per generation — the device clock (injected Clock
     *  + this phone's timezone). Toggle honored from Settings (livectx.enabled,
     *  default ON); never throws into generation. */
    private com.replymate.core.live.LiveContext.Snapshot liveFor(java.util.List<Message> thread) {
        boolean enabled = liveKv == null
            || "1".equals(liveKv.get(com.replymate.core.live.LiveContext.KV_ENABLED, "1"));
        java.util.List<String> incoming = new java.util.ArrayList<String>();
        if (thread != null) {
            for (Message m : thread) {
                if (m != null && m.direction == Direction.INCOMING
                        && m.body != null && !m.body.trim().isEmpty()) {
                    incoming.add(m.body.trim());
                }
            }
        }
        return com.replymate.core.live.LiveContext.build(
            clock.now(), java.util.TimeZone.getDefault(), enabled, incoming);
    }

    private java.util.List<String> approvedTextsFor(long contactId) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.util.List<Draft> recent = drafts.byContact(contactId, 60);   // newest-first
        for (Draft d : recent) {
            if (out.size() >= com.replymate.core.learning.StyleProfiler.MAX_TEXTS) break;
            if (d == null || (d.status != DraftStatus.COPIED && d.status != DraftStatus.EDITED)) {
                continue;
            }
            String t = d.replyText == null ? "" : d.replyText.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Full provenance for one reply generation (P-audit-deep): the provider identity
     *  + the answered message + its content kind + the source app's package + the
     *  resolved SOURCE IDENTITY (with confidence) + the plain-language reason.
     *  P-memory-audit: + the actual sender (groups), the captured media MIME and the
     *  long-term memory layers the request leaned on. */
    private com.replymate.core.prompt.AuditContext auditContextFor(
            Contact c, Message latest, com.replymate.core.memory.MemoryService.Recall mem) {
        com.replymate.core.model.ProviderRef ref = gateway.activeMeta();
        if (ref == null) return null;
        if (latest == null) {
            // P-intelligence-14: anchor-free intentions (a fresh OPENER) answer NO
            // message — the audit context must still render, saying exactly that.
            com.replymate.core.prompt.AuditContext ctx =
                new com.replymate.core.prompt.AuditContext(ref.wire, ref.label, ref.baseUrl,
                    ref.modelName, com.replymate.core.prompt.AuditContext.endpointFor(
                        ref.wire, ref.baseUrl, ref.modelName),
                    "", "", 0L, "", "", false, "", "",
                    "Intentional opener — no message is being answered");
            ctx.withLatestExtras(null, "");
            if (mem != null) {
                ctx.withMemory(new com.replymate.core.prompt.AuditContext.Memory(
                    mem.summaryText, mem.summaryMeta, mem.facts, mem.learnedStyle));
            }
            return ctx;
        }
        String appLabel = latest.channel == com.replymate.core.model.Channel.MANUAL
            ? "" : com.replymate.core.listener.WatchedApps.labelFor(latest.channel);
        String appPackage = com.replymate.core.listener.WatchedApps.primaryPackageFor(
            latest.channel);
        com.replymate.core.model.ContentKind kind = latest.effectiveKind();
        String identity = "";
        String confidence = "";
        if (latest.channel != null) {
            for (com.replymate.core.model.ContactChannel ch : contacts.channelsByContact(c.id)) {
                if (ch.channel == latest.channel) {
                    identity = ch.remoteKey;
                    confidence = com.replymate.core.listener.IdentityResolver
                        .confidenceOf(ch.remoteKey);
                    break;
                }
            }
        }
        // the phrase "latest <X> on <app>" must read like English: texts are "latest
        // message" (manual chats have no app to name), media keeps its article label.
        String thing = kind == null || kind == com.replymate.core.model.ContentKind.TEXT
            ? "message" : kind.label();
        String where = latest.channel == com.replymate.core.model.Channel.MANUAL
            ? " in this chat" : " on " + appLabel;
        String reason = "Manual reply request — answering " + c.displayName + "'s latest "
            + thing + where
            + (latest.sentAt > 0
                ? " (received " + com.replymate.core.util.TimeFmt.dayTime(latest.sentAt) + ")"
                : "");
        com.replymate.core.prompt.AuditContext ctx =
            new com.replymate.core.prompt.AuditContext(ref.wire, ref.label, ref.baseUrl,
                ref.modelName, com.replymate.core.prompt.AuditContext.endpointFor(
                    ref.wire, ref.baseUrl, ref.modelName),
                latest.body, appLabel, latest.sentAt,
                kind == null ? "" : kind.wire,
                appPackage == null ? "" : appPackage,
                kind != null && kind.isMedia() && !latest.mediaUri.trim().isEmpty(),
                identity, confidence, reason);
        // P-memory-audit: actual sender (group attribution) + captured media MIME +
        // the long-term memory layers this specific request leaned on.
        ctx.withLatestExtras(latest.senderName,
            kind != null && kind.isMedia() ? latest.mediaMime : "");
        if (mem != null) {
            ctx.withMemory(new com.replymate.core.prompt.AuditContext.Memory(
                mem.summaryText, mem.summaryMeta, mem.facts, mem.learnedStyle));
        }
        return ctx;
    }

    /** Kind-specific honest explanation for the no-readable-text gate. */
    private static String kindExplanation(com.replymate.core.model.ContentKind kind) {
        switch (kind) {
            case IMAGE:   return "can't see photos or images";
            case VIDEO:   return "can't watch videos";
            case AUDIO:   return "can't listen to audio files";
            case VOICE:   return "can't hear voice notes";
            case STICKER: return "can't see stickers";
            case CALL:    return "can't read a call — there is no message text in it";
            default:      return "can't read media content in this one";
        }
    }

    /** P-polish: live voice PREVIEW — runs the real reply-generation prompt with the
     *  currently saved voice (global only when contactId <= 0; else the contact's full
     *  effective voice: overrides, custom prompt, gated learned hints) over a fixed
     *  sample thread. Read-only: NO drafts are written and no messages are stored.
     *  The call is still metered in usage — previews are real AI calls. */
    public Result<ChatReply> previewVoice(long contactId) {
        Contact c;
        if (contactId <= 0) {
            c = new Contact();                    // synthetic partner for global previews
            c.id = -1;                            // no style_setting rows exist for id -1
            c.displayName = "Tobi";
        } else {
            c = contacts.get(contactId);
            if (c == null) return Result.err("Contact not found.");
        }
        if (c.privateMode) return Result.err("This contact is private — AI generation is disabled.");
        if (!c.aiEnabled) return Result.err("AI replies are disabled for this contact.");
        AiProvider provider = gateway.active();
        if (provider == null) {
            return Result.err("Set up an AI provider first (Settings → AI providers) — add your API key.");
        }

        List<Message> sample = new ArrayList<Message>();
        Message in = new Message();
        in.contactId = c.id;
        in.direction = Direction.INCOMING;
        in.body = "hey, you still coming through on Saturday?";
        in.sentAt = clock.now();
        sample.add(in);

        String styleRules = "";
        StyleProfile global = styles.get(Scope.GLOBAL, null);
        if (global != null && global.derivedRules != null) styleRules = global.derivedRules;
        com.replymate.core.style.StyleService.ComposedVoice voice =
            styleService == null ? null : styleService.compose(c);
        // P-memory-audit: previews mirror the REAL prompt, memory included (the
        // synthetic sample thread has id 0, so the summary boundary covers nothing
        // and refreshSummary writes nothing — facts + learned style still apply).
        com.replymate.core.memory.MemoryService.Recall mem = null;
        if (memory != null && c.id > 0) {
            mem = memory.withLearnedStyle(memory.recall(c, sample), c,
                approvedTextsFor(c.id));
        }
        PromptBundle previewBundle = new PromptBundle(
            profiles.loadFiltered(), c, styleRules, sample,
            voice == null ? "" : voice.voiceLine,
            voice == null ? null : voice.extraLines,
            profiles.extraFiltered(),
            mem == null ? null : mem.lines);
        // previews keep showing several sample phrasings (interactive only; the
        // background default is one — see PromptBundle.candidates)
        previewBundle.candidates =
            com.replymate.core.prompt.PromptBuilder.PREVIEW_VARIANTS;
        // previews mirror the real prompt — the live-context line rides here too.
        previewBundle.liveLine = liveFor(sample).promptLine;
        // …and so does the planning layer (identical depth semantics).
        String depthP = liveKv == null ? "normal"
            : liveKv.get(com.replymate.core.plan.PlanDepth.KV_KEY, "normal");
        if (!com.replymate.core.plan.PlanDepth.BASIC.equals(depthP)) {
            java.util.List<String> burstP = PromptBuilder.burstTailUsableIncoming(sample, 6);
            com.replymate.core.understanding.ConversationContext udP =
                com.replymate.core.understanding.ConversationContextBuilder.build(
                    c, sample, burstP,
                    styleService == null
                        ? java.util.Collections.<String, String>emptyMap()
                        : styleService.globalRows(),
                    styleService == null
                        ? java.util.Collections.<String, String>emptyMap()
                        : styleService.contactRows(c.id),
                    voice == null ? null : voice.extraLines,
                    mem == null ? null : mem.lines, 0);
            com.replymate.core.plan.ReplyPlanner.Plan planP =
                com.replymate.core.plan.ReplyPlanner.plan(
                    udP, burstP, planLengthLabel(c.id), null);
            previewBundle.planText =
                com.replymate.core.plan.PlanDepth.DEEP.equals(depthP)
                    ? planP.fullBlock() : planP.compactLine();
        }
        ChatRequest request = PromptBuilder.build(previewBundle);

        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        if (!reply.ok) return Result.err(reply.error);
        ChatReply r = reply.value;

        UsageEvent u = new UsageEvent();
        u.ts = clock.now();
        u.model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        u.tokensIn = r.tokensIn;
        u.tokensOut = r.tokensOut;
        u.kind = UsageKind.REPLY;
        usage.insert(u);
        log.i("DraftService", "voice preview for contact " + contactId
            + " in " + (clock.now() - t0) + "ms");
        return Result.ok(r);
    }

    /** P3: tone-transform ONE existing draft into new variant(s).
     *  Isolation: only the draft's own text crosses the wire — no other contact's
     *  data is ever read here. Audit: prompt snapshot stored on the new drafts. */
    /** Contact-scoped transform entry point used by the UI. */
    public Result<DraftOutcome> transformDraftForContact(long contactId, long draftId,
                                                         ToneTransform tone) {
        if (tone == null) return Result.err("Pick a tone first.");
        Contact c = contacts.get(contactId);
        if (c == null) return Result.err("Contact not found.");
        if (c.privateMode) return Result.err("This contact is private — AI generation is disabled.");
        if (!c.aiEnabled) return Result.err("AI replies are disabled for this contact.");

        Draft origin = null;
        for (Draft d : drafts.byContact(contactId, 200)) {
            if (d.id == draftId) { origin = d; break; }
        }
        if (origin == null) return Result.err("That draft is gone.");
        if (origin.replyText.trim().isEmpty()) return Result.err("Nothing to transform.");

        AiProvider provider = gateway.active();
        if (provider == null) {
            return Result.err("Set up an AI provider first (Settings → AI providers) — add your API key.");
        }

        String system = "You rewrite a single chat reply draft on request. "
            + "Keep the same language as the draft, keep every factual detail, never add "
            + "new facts, names, dates or promises. Output ONLY the rewritten reply.";
        String taskText = "Draft:\n" + origin.replyText.trim()
            + "\n\nRewrite it like this: " + tone.instruction;
        ChatRequest request = new ChatRequest(system,
            Collections.<com.replymate.core.ai.Turn>emptyList(),
            com.replymate.core.ai.Turn.user(taskText),
            com.replymate.core.ai.GenerationOpts.defaults());

        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        long latency = clock.now() - t0;
        if (!reply.ok) {
            log.w("DraftService", "tone transform failed for draft " + draftId + ": " + reply.error);
            return Result.err(reply.error);
        }
        ChatReply r = reply.value;
        if (r.variants.isEmpty()) return Result.err("The provider returned no reply text.");

        long now = clock.now();
        String group = ids.next();
        String model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        java.util.List<String> why = new java.util.ArrayList<String>();
        why.add("tone transform: " + tone.label);
        why.add("rewrite-only request — no profile, style rules or thread history was sent");
        String snapshot = PromptBuilder.snapshot(request, model, "tone:" + tone.wire, why,
            com.replymate.core.prompt.AuditContext.of(gateway.activeMeta(), "", "", 0));
        int outEach = r.tokensOut > 0 ? Math.max(1, r.tokensOut / r.variants.size()) : 0;

        List<Draft> saved = new ArrayList<Draft>();
        for (String variant : r.variants) {
            String text = variant == null ? "" : variant.trim();
            if (text.isEmpty()) continue;
            Draft d = new Draft();
            d.contactId = contactId;
            d.inReplyToId = origin.inReplyToId;
            d.promptSnapshotJson = snapshot;
            d.replyText = text;
            d.model = model;
            d.variantGroup = group;
            d.status = DraftStatus.GENERATED;
            d.latencyMs = latency;
            d.tokensIn = r.tokensIn;
            d.tokensOut = outEach;
            d.createdAt = now;
            d.id = drafts.insert(d);
            saved.add(d);
        }
        if (saved.isEmpty()) return Result.err("The provider returned empty replies.");

        UsageEvent u = new UsageEvent();
        u.ts = now;
        u.model = model;
        u.tokensIn = r.tokensIn;
        u.tokensOut = r.tokensOut;
        u.kind = UsageKind.REPLY;
        usage.insert(u);

        return Result.ok(new DraftOutcome(group, saved, latency, r.tokensIn, r.tokensOut));
    }

}
