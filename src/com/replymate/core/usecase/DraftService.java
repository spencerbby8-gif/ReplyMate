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

    /** Wire the Settings store after construction (same optional-setter pattern as
     *  ContactService.setMerger — legacy call sites keep their behavior). */
    public void setLiveKv(com.replymate.core.ports.KvStore kv) { this.liveKv = kv; }

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

        // P-intelligence-1 (message understanding): the model consumes a CLEAN
        // conversation object, not raw notification text — sender, app, message type,
        // burst state + mechanics, the owner's last reply and the cold-start flag are
        // assembled once here, shape the prompt, and are credited in Prompt Audit.
        java.util.List<String> burstTail = PromptBuilder.burstTailUsableIncoming(thread, 6);
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
        if (com.replymate.core.plan.PlanDepth.BASIC.equals(depth)) {
            why.add(com.replymate.core.plan.PlanDepth.auditLine(depth));
        } else {
            com.replymate.core.plan.ReplyPlanner.Plan plan =
                com.replymate.core.plan.ReplyPlanner.plan(
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
        bundle.planText = planText;

        // P-intelligence-4 (live context): device clock + (when the partner actually
        // used a listed term) a small DATED glossary — local only, honest stamp, off
        // switch in Settings. Credited in the audit why-lines like every other input.
        com.replymate.core.live.LiveContext.Snapshot live = liveFor(thread);
        bundle.liveLine = live.promptLine;
        why.add(live.whyLine);

        // P-intelligence-5 (live research): need-triggered, cached, metered, and
        // NEVER blocking — a failed/off lookup still generates the reply.
        researchInto(bundle, c, thread, provider, why);

        ChatRequest request = PromptBuilder.build(bundle);

        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        long latency = clock.now() - t0;
        if (!reply.ok) {
            log.w("DraftService", "generation failed for contact " + contactId + ": " + reply.error);
            return Result.err(reply.error);
        }

        ChatReply r = reply.value;
        if (r.variants.isEmpty()) return Result.err("The provider returned no reply text.");

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

        List<Draft> saved = new ArrayList<Draft>();
        for (String variant : r.variants) {
            String text = variant == null ? "" : variant.trim();
            if (text.isEmpty()) continue;
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

    /** P-intelligence-5 (live research): the only extra provider call ReplyMate
     *  can ever make — exactly ONE tiny lookup per NEW term, and only when the
     *  partner actually needs a meaning (explicit ask or one conservative
     *  unknown-slang token), the bundled glossary doesn't know it, the toggle is
     *  ON, and the 7-day cache missed. Failure degrades silently (audit-noted);
     *  the owner's reply is never blocked, metered as UsageKind.RESEARCH. */
    private void researchInto(PromptBundle bundle, Contact c, List<Message> thread,
                              AiProvider provider, List<String> why) {
        if (bundle == null || c == null || thread == null || provider == null) return;
        java.util.List<String> incoming = new java.util.ArrayList<String>();
        java.util.List<String> outgoing = new java.util.ArrayList<String>();
        for (Message m : thread) {
            if (m == null || m.body == null || m.body.trim().isEmpty()) continue;
            if (m.direction == Direction.INCOMING) incoming.add(m.body.trim());
            else if (m.direction == Direction.OUTGOING) outgoing.add(m.body.trim());
        }
        if (incoming.isEmpty()) return;
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add(c.displayName);
        names.add(profiles.loadFiltered() == null ? "" : profiles.loadFiltered().displayName());
        String term;
        try {
            term = com.replymate.core.live.TermResearch.detectTerm(incoming, outgoing, names);
        } catch (RuntimeException e) {
            return;   // trigger heuristics must never hurt a reply
        }
        if (term == null) return;

        boolean on = liveKv != null && "1".equals(
            liveKv.get(com.replymate.core.live.TermResearch.KV_ENABLED, "0"));
        if (!on) {
            why.add(com.replymate.core.live.TermResearch.whyOff(term));
            return;
        }
        String cached = com.replymate.core.live.TermResearch.cached(liveKv, term, clock.now());
        if (cached != null) {
            bundle.researchLine = com.replymate.core.live.TermResearch.promptLine(term, cached);
            why.add(com.replymate.core.live.TermResearch.whyCached(term));
            return;
        }
        String newest = incoming.get(incoming.size() - 1);
        Result<ChatReply> rr;
        try {
            rr = provider.generate(com.replymate.core.live.TermResearch.lookupRequest(term, newest));
        } catch (RuntimeException e) {
            why.add(com.replymate.core.live.TermResearch.whyFailed(term,
                e.getClass().getSimpleName()));
            return;
        }
        // metered exactly like the reply call — cost transparency for the dashboard
        UsageEvent u = new UsageEvent();
        u.ts = clock.now();
        u.model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        ChatReply rv = rr.ok ? rr.value : null;
        u.tokensIn = rv == null ? 0 : rv.tokensIn;
        u.tokensOut = rv == null ? 0 : rv.tokensOut;
        u.kind = UsageKind.RESEARCH;
        try { usage.insert(u); } catch (RuntimeException ignored) { }

        if (!rr.ok) {
            why.add(com.replymate.core.live.TermResearch.whyFailed(term, rr.error));
            return;
        }
        String meaning = rv.variants.isEmpty() ? "" : rv.variants.get(0).trim();
        meaning = meaning.replaceAll("\\s+", " ");
        if (meaning.length() > 140) meaning = meaning.substring(0, 140).trim();
        if (meaning.isEmpty() || meaning.equalsIgnoreCase("unsure")) {
            why.add(com.replymate.core.live.TermResearch.whyUnsure(term));
            return;
        }
        com.replymate.core.live.TermResearch.store(liveKv, term, meaning, clock.now());
        bundle.researchLine = com.replymate.core.live.TermResearch.promptLine(term, meaning);
        why.add(com.replymate.core.live.TermResearch.whyLookedUp(term));
    }

    /** P-intelligence-4: one LiveContext snapshot per generation — device clock
     *  (injected Clock + this phone's timezone) and, ONLY when the incoming side
     *  actually used a bundled term, the dated glossary clause. Toggle honored from
     *  Settings (livectx.enabled, default ON); never throws into generation. */
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
