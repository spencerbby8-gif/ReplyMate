package com.replymate.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.assistant.AssistantNotifier;
import com.replymate.app.assistant.AssistantReceiver;
import com.replymate.app.di.AppContainer;
import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.learning.LearningEngine;
import com.replymate.core.model.Contact;
import com.replymate.core.model.DraftStatus;

/** P-background-9: the Edit path on the ReplyMate banner. The owner must be able
 *  to fix the generated reply IN ReplyMate and then send it — through the exact
 *  same human-approved quick-reply pipeline as the banner's Approve button (an
 *  internal broadcast to AssistantReceiver carrying the EDITED text; the receiver
 *  resolves live target → same-conversation repost → cached PI → honest fallback).
 *
 *  Learning parity with ConversationActivity: an edit that changes the text
 *  records an EDITED signal with the classroom-style delta (shorter/emoji-down/…);
 *  an unchanged send is a plain approval (the receiver records it). */
public final class DraftEditActivity extends Activity {

    private AppContainer c;
    private long contactId;
    private long draftId;
    private String name;
    private String appLabel;
    private String original;
    private EditText body;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Built programmatically (Ui-helper style) — no XML inflation, so the screen
        // is exercisable end-to-end in the Robolectric harness and stays dependency-free.
        c = ReplyMateApp.containerOf(this);
        Intent src = getIntent();
        contactId = src.getLongExtra(AssistantReceiver.EXTRA_CONTACT_ID, -1L);
        draftId = src.getLongExtra(AssistantReceiver.EXTRA_DRAFT_ID, -1L);
        name = src.getStringExtra(AssistantReceiver.EXTRA_NAME);
        appLabel = src.getStringExtra(AssistantReceiver.EXTRA_APP_LABEL);
        original = src.getStringExtra(AssistantReceiver.EXTRA_TEXT);
        if (original == null) original = "";
        if (c == null || contactId < 0) { finish(); return; }
        // P-intelligence-19 §2: NEVER an empty (or stale) composer. The STORE is
        // the source of truth, not the intent extra: preload the CURRENT text of
        // the exact draft being edited; if that draft was already replaced by a
        // regeneration (purge), retarget to the contact's newest generated draft.
        if (draftId > 0) {
            for (com.replymate.core.model.Draft d : c.drafts().byContact(contactId, 25)) {
                if (d != null && d.id == draftId && d.replyText != null
                        && !d.replyText.trim().isEmpty()) {
                    original = d.replyText;
                    break;
                }
            }
        }
        if (original.isEmpty()) {
            for (com.replymate.core.model.Draft d : c.drafts().byContact(contactId, 25)) {
                if (d != null && d.status == com.replymate.core.model.DraftStatus.GENERATED
                        && d.replyText != null && !d.replyText.trim().isEmpty()) {
                    original = d.replyText;
                    draftId = d.id;   // edit the CURRENT reply, not a replaced one
                    break;
                }
            }
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);
        scroll.setBackgroundColor(android.graphics.Color.rgb(13, 17, 23));   // @color/bg
        scroll.addView(root, new android.widget.ScrollView.LayoutParams(
            android.widget.ScrollView.LayoutParams.MATCH_PARENT,
            android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = Ui.tv(this,
            "‹ Edit reply" + (name == null || name.isEmpty() ? "" : " for " + name),
            22, Ui.PRIMARY);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, Ui.dp(this, 2));
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(title);

        TextView sub = Ui.sub(this,
            "Fix the wording — Save & send still goes through "
                + (appLabel == null || appLabel.isEmpty() ? "the app" : appLabel)
                + "'s own quick-reply, with your approval. Nothing sends by itself.");
        sub.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(sub);

        body = new EditText(this);
        body.setHint("The reply to send");
        body.setHintTextColor(Ui.DIM);
        body.setTextColor(Ui.PRIMARY);
        body.setBackground(fieldBg());
        int fp = Ui.dp(this, 12);
        body.setPadding(fp, fp, fp, fp);
        body.setText(original);
        body.setSelection(original.length());
        body.setMinLines(3);
        root.addView(body);

        Button send = new Button(this);
        send.setText("Save & send");
        send.setTextColor(Ui.PRIMARY);
        send.setBackground(primaryBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(send, lp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndSend(); }
        });

        Button copy = new Button(this);
        copy.setText("Save & copy");
        copy.setTextColor(Ui.PRIMARY);
        copy.setBackground(ghostBg());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(copy, lp2);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndCopy(); }
        });

        Button regen = new Button(this);
        regen.setText("Discard & regenerate");
        regen.setTextColor(Ui.PRIMARY);
        regen.setBackground(ghostBg());
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(regen, lp3);
        regen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(DraftEditActivity.this, AssistantReceiver.class);
                i.setAction(AssistantReceiver.ACTION_REGEN);
                i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
                i.putExtra(AssistantReceiver.EXTRA_NAME, name == null ? "" : name);
                sendBroadcast(i);
                Toast.makeText(DraftEditActivity.this,
                    "Regenerating in the background…", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    /** Persist any edit (+ EDITED learning signal), then hand the EDITED text to
     *  the SAME approve pipeline the banner uses. */
    private void saveAndSend() {
        String edited = body.getText() == null ? "" : body.getText().toString().trim();
        if (edited.isEmpty()) {
            Toast.makeText(this, "Type the reply first (or Discard & regenerate)",
                Toast.LENGTH_SHORT).show();
            return;
        }
        persistEdit(edited);
        Intent i = new Intent(this, AssistantReceiver.class);
        i.setAction(AssistantReceiver.ACTION_SEND);
        i.putExtra(AssistantReceiver.EXTRA_CONTACT_ID, contactId);
        i.putExtra(AssistantReceiver.EXTRA_NAME, name == null ? "" : name);
        i.putExtra(AssistantReceiver.EXTRA_APP_LABEL, appLabel == null ? "" : appLabel);
        i.putExtra(AssistantReceiver.EXTRA_TEXT, edited);
        i.putExtra(AssistantReceiver.EXTRA_DRAFT_ID, draftId);
        i.putExtra(AssistantReceiver.EXTRA_DIRECT,
            getIntent().getBooleanExtra(AssistantReceiver.EXTRA_DIRECT, false));
        sendBroadcast(i);
        finish();
    }

    /** Persist + copy the edited text locally (manual-screen parity: edited copy
     *  records EDITED, not an extra approval). */
    private void saveAndCopy() {
        String edited = body.getText() == null ? "" : body.getText().toString().trim();
        if (edited.isEmpty()) {
            Toast.makeText(this, "Nothing to copy yet", Toast.LENGTH_SHORT).show();
            return;
        }
        persistEdit(edited);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ReplyMate reply", edited));
        c.kv().put(AssistantPlanner.alertedKvKey(contactId), "0");
        AssistantNotifier.settled(this, contactId, "ReplyMate",
            "Copied your edited draft ✓ — paste it in "
                + (appLabel == null || appLabel.isEmpty() ? "the app" : appLabel) + ".",
            true);
        finish();
    }

    /* The three drawable looks of the app, drawn in code (identical colors/radii
     * to bg_field/btn_primary/btn_ghost) — keeps this screen inflating without
     * binary resources, which the Robolectric harness doesn't package. */
    private android.graphics.drawable.GradientDrawable fieldBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(android.graphics.Color.rgb(17, 22, 29));            // #11161D
        d.setStroke(Ui.dp(this, 1), android.graphics.Color.rgb(42, 49, 57)); // #2A3139
        d.setCornerRadius(Ui.dp(this, 10));
        return d;
    }

    private android.graphics.drawable.GradientDrawable primaryBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Ui.ACCENT);
        d.setCornerRadius(Ui.dp(this, 14));
        return d;
    }

    private android.graphics.drawable.GradientDrawable ghostBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(android.graphics.Color.TRANSPARENT);
        d.setStroke(Ui.dp(this, 1), 0x3DFFFFFF);
        d.setCornerRadius(Ui.dp(this, 14));
        return d;
    }

    /** Store the edit + record the EDITED learning signal when text changed. */
    private void persistEdit(String edited) {
        if (edited.equals(original)) return;    // untouched: let the send count plainly
        Contact contact = c.contacts().get(contactId);
        if (draftId > 0) {
            c.drafts().updateText(draftId, edited);
            c.drafts().updateStatus(draftId, DraftStatus.EDITED);
        }
        // manual-screen learning parity: edit-then-use carries the richest signal
        c.learningService().record(contact, com.replymate.core.model.StyleSignal.Kind.EDITED,
            LearningEngine.classifyEdit(original, edited),
            draftId > 0 ? Long.valueOf(draftId) : null);
    }
}
