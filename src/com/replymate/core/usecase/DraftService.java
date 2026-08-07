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

    public DraftService(ContactStore contacts, MessageStore messages, StyleStore styles,
                        ProfileService profiles, DraftStore drafts, UsageStore usage,
                        ProviderGateway gateway, IdGen ids, Clock clock, Logger log,
                        com.replymate.core.style.StyleService styleService,
                        com.replymate.core.learning.LearningService learningService) {
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

        String styleRules = "";
        StyleProfile global = styles.get(Scope.GLOBAL, null);
        if (global != null && global.derivedRules != null) styleRules = global.derivedRules;

        // P4: global user voice + per-contact overrides + custom prompt + learned hints.
        com.replymate.core.style.StyleService.ComposedVoice voice =
            styleService == null ? null : styleService.compose(c);
        java.util.List<String> why = new java.util.ArrayList<String>();
        why.addAll(profiles.excludedSections());
        if (voice != null) why.addAll(voice.why);

        ChatRequest request = PromptBuilder.build(new PromptBundle(
            profiles.loadFiltered(), c, styleRules, thread,
            voice == null ? "" : voice.voiceLine,
            voice == null ? null : voice.extraLines,
            profiles.extraFiltered()));

        long t0 = clock.now();
        Result<ChatReply> reply = provider.generate(request);
        long latency = clock.now() - t0;
        if (!reply.ok) {
            log.w("DraftService", "generation failed for contact " + contactId + ": " + reply.error);
            return Result.err(reply.error);
        }

        ChatReply r = reply.value;
        if (r.variants.isEmpty()) return Result.err("The provider returned no reply text.");

        long now = clock.now();
        String group = ids.next();
        String model = gateway.activeModel() == null ? provider.type() : gateway.activeModel();
        String snapshot = PromptBuilder.snapshot(request, model, "reply", why);
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
        ChatRequest request = PromptBuilder.build(new PromptBundle(
            profiles.loadFiltered(), c, styleRules, sample,
            voice == null ? "" : voice.voiceLine,
            voice == null ? null : voice.extraLines,
            profiles.extraFiltered()));

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
        String snapshot = PromptBuilder.snapshot(request, model, "tone:" + tone.wire, why);
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
