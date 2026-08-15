package com.replymate.core.prompt;

import com.replymate.core.ai.ChatRequest;
import com.replymate.core.ai.GenerationOpts;
import com.replymate.core.ai.Turn;
import com.replymate.core.budget.TokenBudgeter;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.List;

/** Assembles the full provider request (BLUEPRINT §5.3, P1 scope: L0 + L3 + L4). */
public final class PromptBuilder {

    /** Kept for the interactive preview UIs (voice/global previews show several
     *  sample phrasings). NEVER the background default — see
     *  PromptBundle.DEFAULT_CANDIDATES. */
    public static final int PREVIEW_VARIANTS = 3;

    private PromptBuilder() { }

    public static ChatRequest build(PromptBundle bundle) {
        // P-intelligence-1: when understanding is present, the system prompt carries
        // the explicit cold-start situation line and burst tasks get grounded
        // mechanical annotations; when it is absent every byte is the legacy prompt.
        com.replymate.core.understanding.ConversationContext ud = bundle.understanding;
        java.util.List<String> situationLines = new java.util.ArrayList<String>();
        if (ud != null) {
            String coldLine =
                com.replymate.core.understanding.ConversationContextBuilder
                    .coldStartPromptLine(ud);
            if (!coldLine.isEmpty()) situationLines.add(coldLine);
        }
        // P-intelligence-4: the live-context (device clock) line rides the same
        // situation-line channel — auditable, toggleable, offline-honest.
        if (bundle.liveLine != null && !bundle.liveLine.trim().isEmpty()) {
            situationLines.add(bundle.liveLine.trim());
        }
        // P-intelligence-6: automatic live-search evidence (bounded, attributed).
        if (bundle.searchLine != null && !bundle.searchLine.trim().isEmpty()) {
            situationLines.add(bundle.searchLine.trim());
        }
        // P-intelligence-15: the GROUP context line — capture-time fact only
        // (isGroupConversation persisted on the contact); 1:1 prompts unchanged.
        String groupLine = GroupContext.header(bundle.thread,
            bundle.contact.displayName, bundle.contact.isGroup,
            bundle.profile == null ? "" : bundle.profile.displayName());
        if (!groupLine.isEmpty()) situationLines.add(groupLine);
        // P-intelligence-16b: ConversationState lines (topic / same-name members /
        // targeted reply) — groups only, every line fact-backed by the state.
        if (bundle.groupExtraLines != null) {
            for (String gl : bundle.groupExtraLines) {
                if (gl != null && !gl.trim().isEmpty()) situationLines.add(gl.trim());
            }
        }
        String system = SystemComposer.compose(bundle.profile, bundle.contact, bundle.styleRules,
            bundle.voiceLine, bundle.voiceExtra, bundle.aboutExtra, bundle.memoryLines,
            situationLines);
        List<Turn> turns = ThreadMapper.map(bundle.thread, bundle.contact.displayName);
        // P-context-honesty: the task turn quotes the exact message being answered and
        // names the app, so the model can never reply from the contact name alone.
        // P-memory-audit: the answered message is attributed to its ACTUAL sender
        // (group chats — schema v6 sender_name).
        com.replymate.core.model.Message latest = latestUsableIncoming(bundle.thread);
        String appLabel = latest == null || latest.channel == null
                || latest.channel == com.replymate.core.model.Channel.MANUAL
            ? null : com.replymate.core.listener.WatchedApps.labelFor(latest.channel);
        com.replymate.core.model.ContentKind kind = latest == null
            ? null : latest.effectiveKind();
        // P-background-6: a rapid-fire unread tail is answered ONCE as a burst —
        // summarizing it into its single point — not message by message.
        // P-intelligence-6: scoped by the answered watermark — a pending draft's
        // messages are never re-answered as part of a newer burst.
        java.util.List<String> burst = bundle.composeKind != null
                && bundle.composeKind != ComposeKind.REPLY
            ? java.util.Collections.<String>emptyList()   // intentional kinds: no burst task
            : burstTailUsableIncoming(bundle.thread, 6, bundle.answeredWatermark);
        Turn task;
        if (bundle.composeKind != null && bundle.composeKind != ComposeKind.REPLY) {
            // P-intelligence-14: intentional generation carries its OWN task text;
            // burst/planner machinery is reply-scoped. Anchor by kind:
            // FOLLOW_UP → owner's last unanswered outgoing; CLARIFY → their
            // latest incoming; CONTINUE → the pausing message (either side);
            // OPENER → none.
            String anchor;
            String anchorSender = null;
            switch (bundle.composeKind) {
                case FOLLOW_UP: {
                    // the auto-follow-up override wins: it is the exact approved
                    // text that just went out through the source app (never stored)
                    if (bundle.followUpAnchorOverride != null
                            && usableText(bundle.followUpAnchorOverride)) {
                        anchor = bundle.followUpAnchorOverride;
                        break;
                    }
                    com.replymate.core.model.Message lastOut = null;
                    for (int i = bundle.thread.size() - 1; i >= 0; i--) {
                        com.replymate.core.model.Message m = bundle.thread.get(i);
                        if (m != null && m.direction
                                == com.replymate.core.model.Direction.OUTGOING
                                && usableText(m.body)) { lastOut = m; break; }
                    }
                    anchor = lastOut == null ? null : lastOut.body;
                    break;
                }
                case CLARIFY:
                    anchor = latest == null ? null : latest.body;
                    anchorSender = latest == null ? null : latest.senderName;
                    break;
                case CONTINUE:
                    anchor = latestUsableText(bundle.thread);
                    break;
                default:
                    anchor = null;
            }
            task = TaskComposer.intentionalTask(bundle.composeKind,
                bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
                bundle.contact.displayName, anchor, anchorSender, appLabel);
        } else {
        // P-intelligence-16b: when the engagement evaluation pinned the EXACT
        // message being answered (group mention / open room question), quote and
        // attribute THAT one — never an accidental older "latest". 1:1 carries no
        // target ⇒ legacy bytes (latest line answers, as always).
        String taskText = latest == null ? null : latest.body;
        String taskSender = latest == null ? null : latest.senderName;
        if (bundle.replyTarget != null && bundle.replyTarget.hasIdentity()) {
            taskText = bundle.replyTarget.snippet;
            taskSender = bundle.replyTarget.senderLabel;
        }
        java.util.List<String> annotations = ud == null
            ? null
            : com.replymate.core.understanding.ConversationContextBuilder
                .burstAnnotations(ud);
        if (bundle.replyTarget != null && bundle.replyTarget.hasIdentity()
                && burst.size() >= 2) {
            if (annotations == null) annotations = new java.util.ArrayList<String>();
            int idx = burst.indexOf(bundle.replyTarget.snippet);
            annotations.add(idx >= 0
                ? "Line #" + (idx + 1) + " — from " + bundle.replyTarget.senderLabel
                    + " — is the message addressed to you; your answer leads with it."
                : "The message addressed to you is " + bundle.replyTarget.senderLabel
                    + "'s: \"" + bundle.replyTarget.snippet + "\" — lead with that answer.");
        }
        task = burst.size() >= 2
            ? TaskComposer.burstTask(
                bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
                bundle.contact.displayName, burst, appLabel,
                annotations)
            : TaskComposer.defaultTask(
                bundle.profile == null ? "the owner of this phone" : bundle.profile.displayName(),
                bundle.contact.displayName,
                taskText, appLabel, kind,
                taskSender);
        }
        // P-intelligence-5: the deterministic plan grounds the read; it rides the
        // END of the task turn, after the quoted message, before nothing — the
        // final instruction remains "Output only the reply text." (BASIC depth
        // leaves planText null and the task is byte-identical legacy.)
        if (bundle.planText != null && !bundle.planText.trim().isEmpty()) {
            String base = task.text;
            int tail = base.lastIndexOf("\nOutput only the reply text.");
            if (tail >= 0) {
                base = base.substring(0, tail) + "\n" + bundle.planText.trim()
                    + base.substring(tail);
            } else {
                base = base + "\n" + bundle.planText.trim();
            }
            task = Turn.user(base);
        }
        ChatRequest req = new ChatRequest(system, turns, task,
            // P-background-12: candidate count comes from the bundle — 1 for
            // background drafts (one message ⇒ one current draft), set higher
            // only by interactive preview flows.
            GenerationOpts.of(bundle.candidates < 1 ? PromptBundle.DEFAULT_CANDIDATES
                    : bundle.candidates, 0.8, 220)
                .withSearch(bundle.requestSearch)
                .withReasoning(bundle.reasoningLevel));
        return TokenBudgeter.fit(req, TokenBudgeter.DEFAULT_MAX_INPUT);
    }

    /** The unread burst tail: consecutive INCOMING usable-text messages counting
     *  back from the thread end, stopping at the owner's last OUTGOING (that ends
     *  the burst logically) or at {@code max} items. Oldest-first in the result. */
    public static java.util.List<String> burstTailUsableIncoming(
            java.util.List<com.replymate.core.model.Message> thread, int max) {
        return burstTailUsableIncoming(thread, max, 0L);
    }

    /** P-intelligence-6 (context-expiry fix): same walk, but messages at or below
     *  {@code answeredWatermark} are ALREADY ANSWERED — a draft (pending or used)
     *  was generated against them — so they must not be re-answered as part of a
     *  new burst. A pending draft is therefore never permanent active context: the
     *  next message starts a fresh burst/topic, while everything older stays
     *  available as history + long-term memory. Watermark 0 = legacy behavior
     *  (previews, transforms and synthetic id-0 threads are untouched). */
    public static java.util.List<String> burstTailUsableIncoming(
            java.util.List<com.replymate.core.model.Message> thread, int max,
            long answeredWatermark) {
        java.util.LinkedList<String> out = new java.util.LinkedList<String>();
        if (thread == null) return out;
        for (int i = thread.size() - 1; i >= 0 && out.size() < max; i--) {
            com.replymate.core.model.Message m = thread.get(i);
            if (m == null) continue;
            if (m.direction == com.replymate.core.model.Direction.OUTGOING) break;
            // Context-expiry fix: already-answered messages are never re-answered.
            if (answeredWatermark > 0 && m.id > 0 && m.id <= answeredWatermark) break;
            if (m.direction == com.replymate.core.model.Direction.INCOMING
                    && usableText(m.body)) {
                out.addFirst(m.body.trim());
            }
        }
        return out;
    }

    /** The message a reply would answer: the LATEST incoming message whose body carries
     *  usable text (non-empty, not the media placeholder). Single source of truth shared
     *  by the generation gate in DraftService and the task composer here. */
    /** Newest readable message text of the thread — EITHER direction (P-intel-14
     *  CONTINUE anchor). Null when nothing readable exists. */
    public static String latestUsableText(
            java.util.List<com.replymate.core.model.Message> thread) {
        if (thread == null) return null;
        for (int i = thread.size() - 1; i >= 0; i--) {
            com.replymate.core.model.Message m = thread.get(i);
            if (m != null && m.body != null && usableText(m.body)) return m.body;
        }
        return null;
    }

    public static com.replymate.core.model.Message latestUsableIncoming(
            java.util.List<com.replymate.core.model.Message> thread) {
        if (thread == null) return null;
        com.replymate.core.model.Message found = null;
        for (com.replymate.core.model.Message m : thread) {   // oldest-first → ends at latest
            if (m != null && m.direction == com.replymate.core.model.Direction.INCOMING
                    && usableText(m.body)) {
                found = m;
            }
        }
        return found;
    }

    /** Real readable text test (the generation gate): non-empty and NOT a stored
     *  placeholder of ANY content kind (legacy or per-kind). A captioned media
     *  message passes — its caption IS real text (the task discloses the media). */
    public static boolean usableText(String body) {
        if (body == null) return false;
        String t = body.trim();
        return !t.isEmpty()
            && !com.replymate.core.listener.ListenerFilter.isPlaceholder(t);
    }

    /** Audit snapshot of exactly what is sent (stored on each draft row). */
    public static String snapshot(ChatRequest req, String model) {
        return snapshot(req, model, "reply");
    }

    /** As above, with an explicit kind tag ("reply", "tone:friendlier", …) so the
     *  audit viewer can tell full generations from tone transforms. */
    public static String snapshot(ChatRequest req, String model, String kind) {
        return snapshot(req, model, kind, null);
    }

    /** Full audit snapshot incl. the "why" notes: human-readable lines explaining
     *  exactly which style inputs shaped this request (P4 prompt audit). */
    public static String snapshot(ChatRequest req, String model, String kind,
                                  List<String> why) {
        return snapshot(req, model, kind, why, null);
    }

    /** Full audit snapshot with provider provenance + the message being answered
     *  (P-context-honesty). ctx may be null (e.g. tone transforms of old drafts). */
    public static String snapshot(ChatRequest req, String model, String kind,
                                  List<String> why, AuditContext ctx) {
        JsonArr turns = JsonArr.create();
        for (Turn t : req.turns) {
            turns.add(JsonObj.create()
                .put("role", t.role == Turn.Role.USER ? "user" : "model")
                .put("text", t.text));
        }
        JsonArr whyArr = JsonArr.create();
        if (why != null) {
            for (String w : why) {
                if (w != null && !w.trim().isEmpty()) whyArr.add(w.trim());
            }
        }
        JsonObj out = JsonObj.create()
            .put("kind", kind)
            .put("model", model)
            .put("why", whyArr)
            .put("system", req.system)
            .put("turns", turns)
            .put("contextTurns", req.turns.size())
            .put("task", req.task == null ? "" : req.task.text)
            .put("temperature", req.opts.temperature)
            .put("candidateCount", req.opts.candidates)
            .put("maxOutputTokens", req.opts.maxOutputTokens)
            .put("maxInputTokens", TokenBudgeter.DEFAULT_MAX_INPUT);
        if (ctx != null) {
            out.put("provider", JsonObj.create()
                .put("wire", ctx.providerWire)
                .put("label", ctx.providerLabel)
                .put("baseUrl", ctx.baseUrl)
                .put("model", ctx.providerModel)
                .put("endpoint", ctx.endpoint));
            if (!ctx.latestText.isEmpty()) {
                JsonObj li = JsonObj.create()
                    .put("text", ctx.latestText)
                    .put("channel", ctx.latestChannel)
                    .put("app", ctx.latestPackage)
                    .put("at", ctx.latestAt)
                    .put("contentType", ctx.latestKind)
                    .put("mediaRef", ctx.mediaRefAvailable
                        ? "captured locally — never opened, never uploaded" : "none");
                if (!ctx.latestSender.isEmpty()) li.put("sender", ctx.latestSender);
                if (!ctx.latestMediaMime.isEmpty()) li.put("mediaMime", ctx.latestMediaMime);
                out.put("latestIncoming", li);
            }
            if (!ctx.sourceIdentity.isEmpty()) {
                out.put("source", JsonObj.create()
                    .put("identity", ctx.sourceIdentity)
                    .put("confidence", ctx.sourceConfidence));
            }
            if (!ctx.reason.isEmpty()) {
                out.put("reason", ctx.reason);
            }
            // P-memory-audit: the long-term memory layers this request leaned on.
            if (ctx.memory != null && !ctx.memory.isEmpty()) {
                JsonObj mem = JsonObj.create();
                if (!ctx.memory.summaryText.isEmpty()) {
                    mem.put("summary", ctx.memory.summaryText)
                       .put("summaryMeta", ctx.memory.summaryMeta);
                }
                if (!ctx.memory.facts.isEmpty()) {
                    JsonArr fa = JsonArr.create();
                    for (String f : ctx.memory.facts) fa.add(f);
                    mem.put("facts", fa);
                }
                if (!ctx.memory.learnedStyle.isEmpty()) {
                    JsonArr ls = JsonArr.create();
                    for (String l : ctx.memory.learnedStyle) ls.add(l);
                    mem.put("learnedStyle", ls);
                }
                out.put("memory", mem);
            }
        }
        return out.toJson();
    }
}
