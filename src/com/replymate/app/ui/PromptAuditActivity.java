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
                    payload.setText(d.promptSnapshotJson == null || d.promptSnapshotJson.isEmpty()
                        ? "(no snapshot recorded)" : pretty(d.promptSnapshotJson));
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
