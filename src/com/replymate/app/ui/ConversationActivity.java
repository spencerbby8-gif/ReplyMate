package com.replymate.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.DeepLinks;
import com.replymate.app.platform.Tasks;
import com.replymate.core.learning.LearningEngine;
import com.replymate.core.listener.WatchedApps;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.model.ToneTransform;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.util.Result;
import com.replymate.core.util.TimeFmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** S04 — Conversation: thread (with in-thread search), manual capture buttons,
 *  AI draft cards (Edit / Copy / Regenerate / Delete / Favorite + tone transforms
 *  + open-in-source-app via official deep links), prompt-audit entry point.
 *  Layout contract preserved from P2 (same view ids), features are additive. */
public final class ConversationActivity extends Activity {

    public static final String EXTRA_CONTACT_ID = "contact_id";

    private Button btnGen;
    private AppContainer c;
    private Contact contact;
    private LinearLayout draftList;
    private TextView draftsHeader;
    private TextView genStatus;
    private boolean generating;
    private EditText input;
    private EditText search;
    private LinearLayout msgList;
    private ScrollView scroll;
    private String filter = "";
    /** Drafts with a tone transform currently in flight (loading/retry guard). */
    private final Set<Long> busyDrafts = new HashSet<Long>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation);
        c = ReplyMateApp.containerOf(this);
        long contactId = getIntent().getLongExtra(EXTRA_CONTACT_ID, -1L);
        contact = contactId > 0 ? c.contacts().get(contactId) : null;
        if (contact == null) { finish(); return; }

        msgList = (LinearLayout) findViewById(R.id.msg_list);
        draftList = (LinearLayout) findViewById(R.id.draft_list);
        genStatus = (TextView) findViewById(R.id.gen_status);
        draftsHeader = (TextView) findViewById(R.id.drafts_header);
        input = (EditText) findViewById(R.id.msg_input);
        search = (EditText) findViewById(R.id.conv_search);
        scroll = (ScrollView) findViewById(R.id.conv_scroll);
        btnGen = (Button) findViewById(R.id.btn_gen);

        ((TextView) findViewById(R.id.conv_name)).setText(contact.displayName);
        ((TextView) findViewById(R.id.conv_rel)).setText(
            contact.relationshipType.isEmpty() ? "manual mode" : contact.relationshipType);

        findViewById(R.id.back_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.edit_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(ConversationActivity.this, ContactEditActivity.class);
                i.putExtra("contact_id", contact.id);
                startActivity(i);
            }
        });
        findViewById(R.id.audit_btn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(ConversationActivity.this, PromptAuditActivity.class);
                i.putExtra(PromptAuditActivity.EXTRA_CONTACT_ID, contact.id);
                startActivity(i);
            }
        });
        findViewById(R.id.btn_them).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addMessage(Direction.INCOMING); }
        });
        findViewById(R.id.btn_me).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addMessage(Direction.OUTGOING); }
        });
        btnGen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { generate(); }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable s) {
                filter = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                refreshThread();
            }
        });

        refreshThread();
        refreshDrafts();
    }

    @Override protected void onResume() {
        super.onResume();
        if (contact != null && c != null) {
            Contact fresh = c.contacts().get(contact.id);
            if (fresh != null) {
                contact = fresh;
                ((TextView) findViewById(R.id.conv_name)).setText(contact.displayName);
            }
        }
    }

    /* ------------------------------------------------------------------ thread */

    private void addMessage(Direction direction) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Type or paste a message first", Toast.LENGTH_SHORT).show();
            return;
        }
        Message m = new Message();
        m.contactId = contact.id;
        m.channel = Channel.MANUAL;
        m.direction = direction;
        m.body = text;
        m.sentAt = c.clock().now();
        m.source = Source.MANUAL;
        c.messages().insert(m);
        input.setText("");
        refreshThread();
    }

    private void refreshThread() {
        msgList.removeAllViews();
        List<Message> all = c.messages().lastMessages(contact.id, filter.isEmpty() ? 100 : 400);
        List<Message> shown = new ArrayList<Message>();
        for (Message m : all) {
            if (filter.isEmpty()
                    || (m.body != null && m.body.toLowerCase(Locale.US).contains(filter))) {
                shown.add(m);
            }
        }
        if (shown.isEmpty()) {
            TextView empty = Ui.sub(this, !filter.isEmpty()
                ? "No messages match “" + filter + "”."
                : "No messages yet.\nPaste what " + contact.displayName
                    + " sent you (＋them), then tap ✨ Generate.");
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            msgList.addView(empty);
            return;
        }
        for (Message m : shown) msgList.addView(bubble(m));
        scrollToBottom();
    }

    private View bubble(Message m) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        wrap.setLayoutParams(lp);
        boolean out = m.direction == Direction.OUTGOING;

        TextView body = Ui.tv(this, m.body, 15, Ui.PRIMARY);
        body.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        int hp = Ui.dp(this, 12), vp = Ui.dp(this, 8);
        body.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = out ? android.view.Gravity.END : android.view.Gravity.START;
        int side = Ui.dp(this, 48);
        blp.setMargins(out ? side : 0, 0, out ? 0 : side, 0);
        wrap.addView(body, blp);

        String who = out ? "You" : contact.displayName;
        if (m.channel != null && m.channel != Channel.MANUAL) {
            who += " · " + WatchedApps.labelFor(m.channel);
        }
        TextView meta = Ui.sub(this, who + " · " + TimeFmt.clock(m.sentAt));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = out ? android.view.Gravity.END : android.view.Gravity.START;
        mlp.topMargin = Ui.dp(this, 2);
        wrap.addView(meta, mlp);
        return wrap;
    }

    private void scrollToBottom() {
        scroll.post(new Runnable() {
            @Override public void run() {
                if (scroll.getChildCount() > 0) {
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                }
            }
        });
    }

    /* ---------------------------------------------------------------- generate */

    private void generate() {
        if (generating) return;
        generating = true;
        btnGen.setEnabled(false);
        showStatus("Generating…", Ui.ACCENT);
        final long contactId = contact.id;
        // P4 learning: generating while drafts already exist = the user asked for
        // another take → REGENERATED (recorded on success, gated per contact).
        final boolean hadDrafts = !c.drafts().byContact(contactId, 1).isEmpty();
        Tasks.call(new Tasks.Job<Result<DraftOutcome>>() {
            @Override public Result<DraftOutcome> run() {
                return c.draftService().generateForContact(contactId);
            }
        }, new Tasks.Done<Result<DraftOutcome>>() {
            @Override public void accept(Result<DraftOutcome> r) {
                generating = false;
                btnGen.setEnabled(true);
                if (r.ok) {
                    if (hadDrafts) {
                        c.learningService().record(contact,
                            StyleSignal.Kind.REGENERATED, "re-generate", null);
                    }
                    showStatus("Ready — " + r.value.drafts.size() + " suggestions in "
                        + (r.value.latencyMs / 1000.0d) + "s", Ui.GREEN);
                    refreshDrafts();
                    scrollToBottom();
                } else {
                    showStatus("Couldn't generate: " + withHint(r.error), Ui.RED);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ drafts */

    private void refreshDrafts() {
        draftList.removeAllViews();
        List<Draft> drafts = c.drafts().byContact(contact.id, 30);
        draftsHeader.setVisibility(drafts.isEmpty() ? View.GONE : View.VISIBLE);
        for (Draft d : drafts) draftList.addView(draftCard(d));
    }

    private View draftCard(final Draft draft) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);

        // Editable draft text (the "Edit" action — persisted on Copy/status changes).
        final EditText body = new EditText(this);
        body.setText(draft.replyText);
        body.setTextColor(Ui.PRIMARY);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        body.setBackground(null);
        body.setPadding(0, 0, 0, Ui.dp(this, 6));
        card.addView(body);

        // Meta row.
        String favMark = draft.favorite ? "★ " : "";
        card.addView(Ui.sub(this, favMark + draft.model + " · "
            + TimeFmt.dayTime(draft.createdAt) + " · " + draft.status.wire));

        // Action row: Copy · ☆/★ · Regen · Delete · Open-in-app.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(actions);

        addAction(actions, "Copy", new View.OnClickListener() {
            @Override public void onClick(View v) { copyDraft(draft, body); }
        });
        addAction(actions, draft.favorite ? "★" : "☆", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFavorite(draft); }
        });
        addAction(actions, "Re-gen", new View.OnClickListener() {
            @Override public void onClick(View v) { generate(); }
        });
        addAction(actions, "Delete", new View.OnClickListener() {
            @Override public void onClick(View v) {
                c.drafts().delete(draft.id);
                // P4 learning: deleting a draft without copying = rejection.
                c.learningService().record(contact,
                    StyleSignal.Kind.REJECTED, "deleted", draft.id);
                Toast.makeText(ConversationActivity.this,
                    "Draft deleted", Toast.LENGTH_SHORT).show();
                refreshDrafts();
            }
        });

        final Channel origin = originChannel();
        if (origin != null) {
            TextView open = addAction(actions, "Open in " + WatchedApps.labelFor(origin),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openInApp(origin, body); }
                });
            open.setTextColor(Ui.ACCENT);
        }

        if (busyDrafts.contains(draft.id)) {
            TextView busy = Ui.sub(this, "Transforming…");
            busy.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(busy);
            return card;                            // no tone chips while working
        }

        // Tone transforms row.
        LinearLayout tones = new LinearLayout(this);
        tones.setOrientation(LinearLayout.HORIZONTAL);
        tones.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(tones);
        for (final ToneTransform tone : ToneTransform.values()) {
            TextView chip = Ui.tv(this, tone.label, 11, Ui.DIM);
            chip.setBackgroundResource(R.drawable.bg_field);
            int hp = Ui.dp(this, 8), vp = Ui.dp(this, 4);
            chip.setPadding(hp, vp, hp, vp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 6);
            clp.topMargin = Ui.dp(this, 4);
            tones.addView(chip, clp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { transform(draft, tone); }
            });
        }
        return card;
    }

    private TextView addAction(LinearLayout parent, String label,
                               View.OnClickListener listener) {
        TextView a = Ui.tv(this, label, 12, Ui.DIM);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        int hp = Ui.dp(this, 8), vp = Ui.dp(this, 6);
        a.setPadding(hp, vp, hp, vp);
        a.setOnClickListener(listener);
        parent.addView(a);
        return a;
    }

    private void copyDraft(Draft draft, EditText body) {
        String text = body.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!text.equals(draft.replyText)) {
            c.drafts().updateText(draft.id, text);
            c.drafts().updateStatus(draft.id, DraftStatus.EDITED);
            // P4 learning: edited-then-copied carries the richest style signal.
            c.learningService().record(contact, StyleSignal.Kind.EDITED,
                LearningEngine.classifyEdit(draft.replyText, text), draft.id);
        } else {
            c.drafts().updateStatus(draft.id, DraftStatus.COPIED);
            // P4 learning: copied as-is = approval of this exact style.
            c.learningService().record(contact,
                StyleSignal.Kind.APPROVED, "copied-as-is", draft.id);
        }
        ((ClipboardManager) getSystemService("clipboard"))
            .setPrimaryClip(ClipData.newPlainText("replymate draft", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        refreshDrafts();
    }

    private void toggleFavorite(Draft draft) {
        c.drafts().updateFavorite(draft.id, !draft.favorite);
        refreshDrafts();
    }

    private void transform(final Draft draft, final ToneTransform tone) {
        if (busyDrafts.contains(draft.id)) return;          // loading/retry guard
        busyDrafts.add(draft.id);
        showStatus("Transforming: " + tone.label + "…", Ui.ACCENT);
        final long contactId = contact.id;
        Tasks.call(new Tasks.Job<Result<DraftOutcome>>() {
            @Override public Result<DraftOutcome> run() {
                return c.draftService().transformDraftForContact(contactId, draft.id, tone);
            }
        }, new Tasks.Done<Result<DraftOutcome>>() {
            @Override public void accept(Result<DraftOutcome> r) {
                busyDrafts.remove(draft.id);
                if (r.ok) {
                    showStatus("Added a " + tone.label.toLowerCase(Locale.US)
                        + " variant ✓", Ui.GREEN);
                    refreshDrafts();
                } else {
                    showStatus("Transform failed: " + withHint(r.error), Ui.RED);
                    refreshDrafts();                        // un-freeze the card UI
                }
            }
        });
    }

    /** The channel this contact was discovered on (deep-link target), else null. */
    private Channel originChannel() {
        for (ContactChannel ch : c.contacts().channelsByContact(contact.id)) {
            if (ch.channel != null && ch.channel != Channel.MANUAL) return ch.channel;
        }
        return null;
    }

    private void openInApp(Channel channel, EditText body) {
        String text = body.getText().toString().trim();
        boolean opened = DeepLinks.openChat(this, channel, contact.displayName,
            text, WatchedApps.primaryPackageFor(channel));
        if (!opened) {
            if (!text.isEmpty()) {
                ((ClipboardManager) getSystemService("clipboard"))
                    .setPrimaryClip(ClipData.newPlainText("replymate draft", text));
            }
            Toast.makeText(this,
                "Couldn't open " + WatchedApps.labelFor(channel)
                    + " — draft copied, paste it there",
                Toast.LENGTH_LONG).show();
        }
    }

    /* ------------------------------------------------------------------ status */

    private void showStatus(String msg, int color) {
        genStatus.setVisibility(View.VISIBLE);
        genStatus.setText(msg);
        genStatus.setTextColor(color);
    }

    /** Error text + actionable hint: offline-friendly messaging for network failures. */
    private static String withHint(String error) {
        if (error == null) return "unknown error";
        String low = error.toLowerCase(Locale.US);
        if (low.contains("api key") || low.contains("401") || low.contains("403")) {
            return error + " — check your API key in Settings → AI provider";
        }
        if (low.contains("network") || low.contains("timeout") || low.contains("connect")
                || low.contains("unreachable") || low.contains("socket")) {
            return error + " — check your internet connection, then tap ✨ again to retry";
        }
        if (low.contains("429") || low.contains("quota") || low.contains("rate")) {
            return error + " — provider limit hit; wait a moment, then retry";
        }
        return error;
    }
}
