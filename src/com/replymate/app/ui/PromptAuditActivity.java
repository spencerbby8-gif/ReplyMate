package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Draft;
import com.replymate.core.util.TimeFmt;
import java.util.List;

/** S-P3c — Prompt audit viewer: for every draft, shows EXACTLY the prompt snapshot
 *  that was sent to the AI provider (stored locally at generation time). Purely
 *  read-only; nothing leaves this screen. Scoped to ONE contact (isolation). */
public final class PromptAuditActivity extends Activity {

    public static final String EXTRA_CONTACT_ID = "contact_id";

    private AppContainer c;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt_audit);
        c = ReplyMateApp.containerOf(this);

        long contactId = getIntent().getLongExtra(EXTRA_CONTACT_ID, -1L);
        Contact contact = contactId > 0 ? c.contacts().get(contactId) : null;
        if (contact == null) { finish(); return; }

        LinearLayout root = (LinearLayout) findViewById(R.id.audit_root);

        TextView header = Ui.tv(this, "‹ Prompt audit", 22, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 4));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);
        root.addView(Ui.sub(this, contact.displayName
            + " — exactly what was sent to your AI provider for each draft. Read-only."));
        root.addView(Ui.divider(this));

        List<Draft> drafts = c.drafts().byContact(contact.id, 100);
        if (drafts.isEmpty()) {
            TextView none = Ui.sub(this,
                "\nNo drafts yet for this contact — nothing has been sent anywhere.");
            none.setGravity(android.view.Gravity.CENTER);
            root.addView(none);
            return;
        }

        for (Draft d : drafts) {
            root.addView(auditCard(contact, d));
        }
    }

    private View auditCard(Contact contact, final Draft d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);

        TextView meta = Ui.tv(this,
            String.valueOf(d.model) + " · " + TimeFmt.dayTime(d.createdAt)
                + " · " + d.status.wire,
            13, Ui.PRIMARY);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(meta);

        TextView meta2 = Ui.sub(this,
            "latency " + d.latencyMs + "ms · tokens " + d.tokensIn + "/" + d.tokensOut
                + (d.favorite ? " · ★" : ""));
        meta2.setTextSize(11);
        meta2.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        card.addView(meta2);

        // P-context-honesty: the request provenance + the exact message that was
        // answered — read from the SAME snapshot the provider call was made with.
        String prov = requestSummary(d.promptSnapshotJson);
        if (prov != null) {
            TextView reqHeader = Ui.tv(this, "What was sent", 12, Ui.ACCENT);
            reqHeader.setTypeface(Typeface.DEFAULT_BOLD);
            reqHeader.setPadding(0, 0, 0, Ui.dp(this, 2));
            card.addView(reqHeader);
            TextView reqBody = Ui.tv(this, prov, 12, Ui.DIM);
            reqBody.setTextIsSelectable(true);
            reqBody.setPadding(0, 0, 0, Ui.dp(this, 6));
            card.addView(reqBody);
        }

        // "Why a reply sounded the way it did" (recorded with the snapshot at
        // generation time — voice decisions, toggles, learning state).
        java.util.List<String> why = WhyLines.from(d.promptSnapshotJson);
        if (!why.isEmpty()) {
            TextView whyHeader = Ui.tv(this, "Why it sounded this way", 12, Ui.ACCENT);
            whyHeader.setTypeface(Typeface.DEFAULT_BOLD);
            whyHeader.setPadding(0, 0, 0, Ui.dp(this, 2));
            card.addView(whyHeader);
            StringBuilder sb = new StringBuilder();
            for (String w : why) sb.append("• ").append(w).append('\n');
            TextView whyBody = Ui.tv(this, sb.toString().trim(), 12, Ui.DIM);
            whyBody.setPadding(0, 0, 0, Ui.dp(this, 6));
            card.addView(whyBody);
        }

        TextView body = Ui.tv(this, d.replyText, 14, Ui.PRIMARY);
        body.setPadding(0, 0, 0, Ui.dp(this, 6));
        card.addView(body);

        final TextView payload = new TextView(this);
        payload.setTextSize(11);
        payload.setTextColor(Ui.DIM);
        payload.setTypeface(Typeface.MONOSPACE);
        payload.setTextIsSelectable(true);
        payload.setVisibility(View.GONE);
        card.addView(payload);

        final TextView toggle = Ui.tv(this, "▸ show prompt payload", 12, Ui.ACCENT);
        toggle.setPadding(0, Ui.dp(this, 2), 0, 0);
        card.addView(toggle);
        toggle.setOnClickListener(new View.OnClickListener() {
            boolean open;
            @Override public void onClick(View v) {
                open = !open;
                if (open) {
                    // P-background-11: even the audit payload renders redacted — a
                    // custom base URL with userinfo or a provider that echoed key
                    // material into a request field must never be readable here.
                    payload.setText(d.promptSnapshotJson == null || d.promptSnapshotJson.isEmpty()
                        ? "(no snapshot recorded)"
                        : com.replymate.core.privacy.Secrets.redact(pretty(d.promptSnapshotJson)));
                    payload.setVisibility(View.VISIBLE);
                    toggle.setText("▾ hide prompt payload");
                } else {
                    payload.setVisibility(View.GONE);
                    toggle.setText("▸ show prompt payload");
                }
            }
        });
        return card;
    }

    /** Human summary of a draft's stored request snapshot: provider · endpoint · model ·
     *  request settings · the exact latest incoming message that was answered · context
     *  size. Returns null for pre-0.8.0 snapshots (fields simply absent). */
    private static String requestSummary(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            com.replymate.core.json.JsonObj root = com.replymate.core.json.Json.parseObj(json);
            com.replymate.core.json.JsonObj provider = root.obj("provider");
            com.replymate.core.json.JsonObj latest = root.obj("latestIncoming");
            if (provider == null && latest == null) return null;
            StringBuilder sb = new StringBuilder();
            if (provider != null) {
                sb.append("Provider: ").append(str(provider, "label"))
                  .append("  ·  model: ").append(str(provider, "model")).append('\n');
                sb.append("Endpoint: ").append(str(provider, "endpoint")).append('\n');
            }
            sb.append("Request settings: temperature ").append(root.raw("temperature"))
              .append(" · candidates ").append(root.raw("candidateCount"))
              .append(" · max output tokens ").append(root.raw("maxOutputTokens"))
              .append(" · max input tokens ").append(root.raw("maxInputTokens"))
              .append(" · kind ").append(str(root, "kind")).append('\n');
            if (latest != null) {
                String text = str(latest, "text");
                if (text.length() > 300) text = text.substring(0, 300) + "…";
                sb.append("Latest incoming answered (").append(str(latest, "channel"));
                String app = str(latest, "app");
                if (!app.isEmpty()) sb.append(" · ").append(app);
                long at = latest.lng("at", 0);
                if (at > 0) sb.append(" · ").append(TimeFmt.dayTime(at));
                sb.append("): \"").append(text).append("\"\n");
                String kind = str(latest, "contentType");
                if (!kind.isEmpty()) {
                    sb.append("Content type detected: ").append(kind).append('\n');
                }
                String sender = str(latest, "sender");
                if (!sender.isEmpty()) {
                    sb.append("Actual sender: ").append(sender).append('\n');
                }
                String mime = str(latest, "mediaMime");
                if (!mime.isEmpty()) {
                    sb.append("Captured media type: ").append(mime)
                      .append(" (reference only — never opened, never uploaded)\n");
                }
                String mediaRef = str(latest, "mediaRef");
                if (!mediaRef.isEmpty() && !"none".equals(mediaRef)) {
                    sb.append("Media reference: ").append(mediaRef).append('\n');
                }
            }
            com.replymate.core.json.JsonObj source = root.obj("source");
            if (source != null) {
                sb.append("Source identity: ").append(str(source, "identity"))
                  .append(" (confidence: ").append(str(source, "confidence"))
                  .append(")\n");
            }
            String reason = str(root, "reason");
            if (!reason.isEmpty()) {
                sb.append("Reason: ").append(reason).append('\n');
            }
            // P-memory-audit: recent thread history used (newest 6 turns shown;
            // the FULL history stays viewable in the payload below).
            com.replymate.core.json.JsonArr turns = root.arr("turns");
            Long turnCount = root.lng("contextTurns", -1);
            if (turns != null && turnCount != null && turnCount > 0) {
                sb.append("Recent thread history used (").append(turnCount)
                  .append(" turn(s), newest last):\n");
                int start = Math.max(0, turns.size() - 6);
                for (int i = start; i < turns.size(); i++) {
                    com.replymate.core.json.JsonObj t = turns.obj(i);
                    if (t == null) continue;
                    String who = "user".equals(str(t, "role")) ? "them" : "you";
                    String text = str(t, "text");
                    if (text.length() > 120) text = text.substring(0, 120) + "…";
                    sb.append("  · ").append(who).append(": ").append(text).append('\n');
                }
            }
            // P-memory-audit (P-intelligence-4 hardened): the long-term memory layers
            // this reply leaned on — COUNTS + meta inline, BODIES gated. Someone
            // glancing at this screen (or a screenshot of it) must never learn the
            // contact's private details; the owner reads the bodies through the
            // deliberate per-card "show prompt payload" expander below.
            com.replymate.core.json.JsonObj mem = root.obj("memory");
            if (mem != null) {
                String summary = str(mem, "summary");
                com.replymate.core.json.JsonArr facts = mem.arr("facts");
                com.replymate.core.json.JsonArr style = mem.arr("learnedStyle");
                if (!summary.isEmpty() || (facts != null && facts.size() > 0)
                        || (style != null && style.size() > 0)) {
                    sb.append("Long-term memory used:\n");
                    if (!summary.isEmpty()) {
                        sb.append("  · Rolling summary (")
                          .append(str(mem, "summaryMeta"))
                          .append(") — text behind the payload toggle below\n");
                    }
                    if (facts != null && facts.size() > 0) {
                        sb.append("  · ").append(facts.size())
                          .append(" pinned/stored fact(s) — details behind the payload")
                          .append(" toggle below\n");
                    }
                    if (style != null) {
                        for (int i = 0; i < style.size(); i++) {
                            sb.append("  · Learned style: ").append(style.str(i)).append('\n');
                        }
                    }
                }
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(com.replymate.core.json.JsonObj o, String k) {
        String v = o.str(k);
        return v == null ? "" : v;
    }

    /** Display-only light formatting of the stored JSON snapshot; content unchanged. */
    private static String pretty(String json) {
        StringBuilder out = new StringBuilder(json.length() + 64);
        int indent = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                out.append(ch);
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            switch (ch) {
                case '"': inStr = true; out.append(ch); break;
                case '{': case '[':
                    out.append(ch).append('\n');
                    indent++;
                    for (int k = 0; k < indent; k++) out.append("  ");
                    break;
                case '}': case ']':
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    for (int k = 0; k < indent; k++) out.append("  ");
                    out.append(ch);
                    break;
                case ',':
                    out.append(ch).append('\n');
                    for (int k = 0; k < indent; k++) out.append("  ");
                    break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }
}
