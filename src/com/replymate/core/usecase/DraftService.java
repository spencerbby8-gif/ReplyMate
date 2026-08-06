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

    public DraftService(ContactStore contacts, MessageStore messages, StyleStore styles,
                        ProfileService profiles, DraftStore drafts, UsageStore usage,
                        ProviderGateway gateway, IdGen ids, Clock clock, Logger log) {
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
    }

    public Result<DraftOutcome> generateForContact(long contactId) {
        Contact c = contacts.get(contactId);
        if (c == null) return Result.err("Contact not found.");
        if (c.privateMode) return Result.err("This contact is private — AI generation is disabled.");
        if (!c.aiEnabled) return Result.err("AI replies are disabled for this contact.");

        AiProvider provider = gateway.active();
        if (provider == null) {
            return Result.err("Set up your Gemini API key first (Settings → AI provider).");
        }

        List<Message> thread = messages.lastMessages(contactId, 30);
        Message lastIncoming = null;
        for (Message m : thread) {
            if (m.direction == Direction.INCOMING && lastIncoming == null) {
                // find the LATEST incoming: iterate to end
            }
            if (m.direction == Direction.INCOMING) lastIncoming = m;
        }
        if (lastIncoming == null) {
            return Result.err("Add at least one message from " + c.displayName
                + " first, so I know what to reply to.");
        }

        String styleRules = "";
        StyleProfile global = styles.get(Scope.GLOBAL, null);
        if (global != null && global.derivedRules != null) styleRules = global.derivedRules;

        ChatRequest request = PromptBuilder.build(
            new PromptBundle(profiles.load(), c, styleRules, thread));

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
        String snapshot = PromptBuilder.snapshot(request, model);
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
}
