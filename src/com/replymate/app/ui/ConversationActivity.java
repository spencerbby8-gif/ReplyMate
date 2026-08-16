package com.replymate.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
import com.replymate.core.prompt.ComposeKind;
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
    private Button btnIntent;
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
        genStatus.setTextIsSelectable(true);
        draftsHeader = (TextView) findViewById(R.id.drafts_header);
        input = (EditText) findViewById(R.id.msg_input);
        search = (EditText) findViewById(R.id.conv_search);
        scroll = (ScrollView) findViewById(R.id.conv_scroll);
        btnGen = (Button) findViewById(R.id.btn_gen);
        btnIntent = (Button) findViewById(R.id.btn_intent);

        ((TextView) findViewById(R.id.conv_name)).setText(contact.displayName);
        ((TextView) findViewById(R.id.conv_rel)).setText(
            contact.relationshipType.isEmpty() ? "manual mode" : contact.relationshipType);

        // P-intelligence-3: ONE chat timeline — the separate "AI drafts" section is
        // retired; drafts render as their own bubbles inline (see refreshThread()).
        draftsHeader.setVisibility(View.GONE);
        draftList.setVisibility(View.GONE);

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
        // P-ux-fix: ONE manual send action (outgoing) + AI Generate. P-18 §3 adds
        // back "+ Them" — narrowly scoped to MISSED incoming messages (chat app was
        // open, no notification ever posted): stored as INCOMING with visible
        // "added by you (not captured)" provenance, never as platform data.
        findViewById(R.id.btn_manual).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { manualSend(); }
        });
        findViewById(R.id.btn_them).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAddFromThem(); }
        });
        btnGen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { generate(); }
        });
        btnIntent.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickIntention(); }
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
            if (fresh == null) { finish(); return; }   // contact deleted meanwhile
            contact = fresh;
            ((TextView) findViewById(R.id.conv_name)).setText(contact.displayName);
            ((TextView) findViewById(R.id.conv_rel)).setText(
                contact.relationshipType.isEmpty() ? "manual mode" : contact.relationshipType);
            // P-ux-fix: edits, newly-arrived notifications and fresh drafts all show
            // immediately on return to this screen — no stale views.
            refreshThread();
            refreshDrafts();
        }
    }

    /* ------------------------------------------------------------------ thread */

    /** The manual composer (P-intelligence-3): behaves like a normal chat input.
     *  Your words are ALWAYS saved to the thread like a sent text, then — when this
     *  contact is connected to an app whose quick-reply target is still valid — the
     *  text goes straight out through that app's own reply action (the exact same
     *  dismissal-safe chain the Approve button uses). When no live/cached target
     *  survives there is NO fake "Sent": the words stay in the thread, the text is
     *  copied, and the status line honestly offers Copy + Open. */
    private void manualSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Type your reply first", Toast.LENGTH_SHORT).show();
            return;
        }
        // The WHAT-TO-DO lives in ManualComposer (tested view-free); here only the
        // honest rendering of the outcome.
        final com.replymate.app.assistant.ManualComposer.Result r =
            com.replymate.app.assistant.ManualComposer.send(c, contact.id, text);
        input.setText("");
        refreshThread();
        final Channel origin = r.origin;
        switch (r.outcome) {
            case SAVED_ONLY:
                break;                                // manual-only contact: saved
            case SENT_LIVE:
            case SENT_CONVERSATION:
                showStatus("Sent ✓ through " + r.appLabel + "'s quick-reply:\n" + text,
                    Ui.GREEN);
                genStatus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        genStatus.setOnClickListener(null);
                    }
                });
                break;
            case SENT_CACHED:
                showStatus("Sent ✓ via " + r.appLabel + "'s cached quick-reply —"
                        + " couldn't watch it land; check " + r.appLabel
                        + " to be sure:\n" + text, Ui.GREEN);
                genStatus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        genStatus.setOnClickListener(null);
                    }
                });
                break;
            default:
                showStatus("Couldn't send straight to " + r.appLabel + " (" + r.reason
                        + "). Your reply is saved above and copied ✓ — tap here to open "
                        + r.appLabel + " and paste it.", Ui.ACCENT);
                genStatus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openInApp(origin, "");
                        genStatus.setOnClickListener(null);
                    }
                });
                break;
        }
    }

    /** P-intelligence-3: the chat screen reads like a real messaging app. ONE
     *  timeline holds incoming bubbles (left), the owner's manual/approved messages
     *  (right) AND every AI draft as its own bubble tile at the moment it was made
     *  — pending drafts carry an obvious "waiting for you" chip and full actions;
     *  finalized drafts collapse to compact state bubbles (✓ copied / ✓ edited /
     *  ✓ sent) so nothing ever looks sent when it hasn't been. */
    private void refreshThread() {
        msgList.removeAllViews();
        List<Message> all = c.messages().lastMessages(contact.id, filter.isEmpty() ? 100 : 400);
        List<Draft> drafts = c.drafts().byContact(contact.id, 30);   // newest-first

        // Merge messages + drafts into one ascending timeline (drafts slot in at
        // createdAt, right after the message they answered).
        List<Object> items = new ArrayList<Object>();
        List<Long> times = new ArrayList<Long>();
        for (Message m : all) {
            if (filter.isEmpty()
                    || (m.body != null && m.body.toLowerCase(Locale.US).contains(filter))) {
                items.add(m);
                times.add(Long.valueOf(m.sentAt));
            }
        }
        for (Draft d : drafts) {
            String body = d.replyText == null ? "" : d.replyText.toLowerCase(Locale.US);
            if (filter.isEmpty() || body.contains(filter)) {
                items.add(d);
                times.add(Long.valueOf(d.createdAt));
            }
        }
        // simple stable insertion sort by time (messages win ties — they arrived first)
        for (int i = 1; i < items.size(); i++) {
            Object cur = items.get(i);
            long ct = times.get(i).longValue();
            int j = i - 1;
            while (j >= 0 && times.get(j).longValue() > ct) {
                items.set(j + 1, items.get(j));
                times.set(j + 1, times.get(j));
                j--;
            }
            items.set(j + 1, cur);
            times.set(j + 1, Long.valueOf(ct));
        }

        if (items.isEmpty()) {
            TextView empty = Ui.sub(this, !filter.isEmpty()
                ? "No messages or drafts match “" + filter + "”."
                : "No messages yet.\nNew WhatsApp/Discord messages from "
                    + contact.displayName
                    + " appear here on their own. Then tap ✨ Generate, or write"
                    + " your own reply below and tap ➤ Send.");
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            msgList.addView(empty);
            return;
        }
        for (Object item : items) {
            if (item instanceof Message) {
                msgList.addView(bubble((Message) item));
            } else {
                msgList.addView(draftTile((Draft) item));
            }
        }
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

        String who = out ? "You" : (m.senderName != null && !m.senderName.trim().isEmpty()
            ? m.senderName.trim() : contact.displayName);
        // P-intelligence-18 §3: a MANUALLY_ADDED row never borrows the app's name —
        // its meta says plainly YOU recorded it; nothing claims notification capture.
        if (m.source == com.replymate.core.model.Source.MANUALLY_ADDED) {
            who += " · added by you (not captured)";
        } else if (m.channel != null && m.channel != Channel.MANUAL) {
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

    /* ------------------------------------------------------- + Them (P-18 §3) */

    /** "+ Them": record an incoming message the platform never showed us (the
     *  chat app was open, no notification existed). The WHAT-TO-DO lives in
     *  ManualEntryService (pure, pinned); here only the honest rendering:
     *  group conversations REQUIRE the member's name — attribution is the point —
     *  and the success line says plainly the row was added by hand. */
    private void showAddFromThem() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        final EditText who = new EditText(this);
        if (contact.isGroup) {
            who.setHint("Who in " + contact.displayName + " said it? (required)");
            box.addView(who);
        }
        final EditText body = new EditText(this);
        body.setHint(contact.isGroup
            ? "Their exact message…" : "What " + contact.displayName + " said…");
        body.setMinLines(1);
        body.setMaxLines(5);
        box.addView(body);

        TextView hint = Ui.sub(this,
            "Use this only for a message notifications missed. ReplyMate never"
                + " claims the app delivered it — the thread marks it"
                + " “added by you (not captured)”.");
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(hint);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Add a message from "
                + (contact.isGroup ? "a member" : contact.displayName))
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("+ Them", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    com.replymate.core.usecase.ManualEntryService.Result r =
                        com.replymate.core.usecase.ManualEntryService.addIncomingFromThem(
                            c.contacts(), c.messages(), c.clock(), contact.id,
                            body.getText().toString(),
                            contact.isGroup ? who.getText().toString() : "");
                    if (r.outcome == com.replymate.core.usecase.ManualEntryService
                            .Outcome.STORED) {
                        refreshThread();
                        showStatus("Added as their message (by you, not captured).",
                            Ui.ACCENT);
                    } else {
                        Toast.makeText(ConversationActivity.this, r.reason,
                            Toast.LENGTH_LONG).show();
                    }
                }
            })
            .show();
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
                    // P-intelligence-19 §2: an in-app (re)generate must update the
                    // EXISTING shade alert in place — same draft identity, same
                    // tag, never a stale card, never a stacked duplicate.
                    if (!r.value.drafts.isEmpty()) {
                        Message lastInc = null;
                        for (Message m : c.messages().lastMessages(contactId, 30)) {
                            if (m.direction == Direction.INCOMING) lastInc = m;
                        }
                        if (lastInc != null) {
                            com.replymate.app.assistant.AssistantRunner.refreshAlert(
                                c, contactId, contact.displayName, lastInc,
                                r.value.drafts.get(0));
                        }
                    }
                    scrollToBottom();
                } else {
                    LastProviderError.save(c, r.error);
                    showStatus("Couldn't generate:\n" + r.error, Ui.RED);
                }
            }
        });
    }

    /* -------------------------------------------------- intentional generation */

    /** P-intelligence-14 (topic 3): INTENTIONAL generation. The owner picks the
     *  intention; the SAME reply pipeline (voice, memory, contact settings,
     *  learning, Search, reasoning) composes it; the result lands in this
     *  timeline as a DRAFT — explicit approval is still required before anything
     *  is sent. Nothing ever auto-sends. */
    private void pickIntention() {
        if (generating) return;
        final String[] labels = {
            "Follow-up — bump my unanswered message",
            "Clarify — ask about their latest message",
            "Continue — move the current topic forward",
            "Opener — start a fresh conversation",
        };
        final ComposeKind[] kinds = {
            ComposeKind.FOLLOW_UP,
            ComposeKind.CLARIFY,
            ComposeKind.CONTINUE,
            ComposeKind.OPENER,
        };
        new AlertDialog.Builder(this)
            .setTitle("Intentional draft (you approve before sending)")
            .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface dlg, int which) {
                    composeIntentional(kinds[which]);
                }
            })
            .show();
    }

    private void composeIntentional(final ComposeKind kind) {
        if (generating) return;
        generating = true;
        btnGen.setEnabled(false);
        btnIntent.setEnabled(false);
        showStatus("Composing…", Ui.ACCENT);
        final long contactId = contact.id;
        Tasks.call(new Tasks.Job<Result<DraftOutcome>>() {
            @Override public Result<DraftOutcome> run() {
                return c.draftService().composeForContact(contactId, kind);
            }
        }, new Tasks.Done<Result<DraftOutcome>>() {
            @Override public void accept(Result<DraftOutcome> r) {
                generating = false;
                btnGen.setEnabled(true);
                btnIntent.setEnabled(true);
                if (r.ok) {
                    // deliberately NO learning signal: an intentional compose is
                    // NOT a "re-generate" judgment on a previous draft.
                    showStatus("Composed — approve the draft below before sending",
                        Ui.GREEN);
                    refreshDrafts();
                    scrollToBottom();
                } else {
                    LastProviderError.save(c, r.error);
                    showStatus("Couldn't compose:\n" + r.error, Ui.RED);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ drafts */

    private void refreshDrafts() {
        // P-intelligence-3: drafts live inside the one timeline now — one refresh paints all.
        refreshThread();
    }

    /** Honest, glanceable state chip for a draft bubble (P-intelligence-3):
     *  pending drafts say they WAIT for a human; finalized drafts name exactly what
     *  happened (copied / edited / sent) — a draft must never LOOK sent when it is not. */
    private static String stateChip(Draft d) {
        switch (d.status) {
            case COPIED: return "✓ approved & copied";
            case EDITED: return "✓ edited, then copied";
            case SENT:   return "✓ sent through quick-reply";
            default:     return "● AI draft — waiting for your approval";
        }
    }

    private static boolean isPending(Draft d) {
        return d.status == DraftStatus.GENERATED;
    }

    /** A draft as a chat bubble tile: right-aligned like the owner's outgoing
     *  messages, state chip on top, the text, audit "why" access, and the action
     *  row. Pending tiles keep the inline edit field + tone transforms; finalized
     *  tiles are read-only receipts of what was approved/copied/sent. */
    private View draftTile(final Draft draft) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        lp.leftMargin = Ui.dp(this, 48);            // outgoing side — like "You" bubbles
        card.setLayoutParams(lp);

        boolean pending = isPending(draft);
        TextView chip = Ui.tv(this, (draft.favorite ? "★ " : "") + stateChip(draft), 11,
            pending ? Ui.ACCENT : Ui.GREEN);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(0, 0, 0, Ui.dp(this, 4));
        card.addView(chip);

        if (pending) {
            // Editable draft text (the "Edit" action — persisted on Copy/status changes).
            final EditText body = new EditText(this);
            body.setText(draft.replyText);
            body.setTextColor(Ui.PRIMARY);
            body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            body.setBackground(null);
            body.setPadding(0, 0, 0, Ui.dp(this, 6));
            card.addView(body);
            fillDraftActions(card, draft, body, true);
            return card;
        }

        TextView body = Ui.tv(this, draft.replyText, 15, Ui.PRIMARY);
        body.setPadding(0, 0, 0, Ui.dp(this, 6));
        body.setTextIsSelectable(true);
        card.addView(body);
        fillDraftActions(card, draft, null, false);
        return card;
    }

    /** Meta row + why panel + actions for one draft tile — shared by pending and
     *  finalized flavors. {@code body} is the inline edit field (pending) or null. */
    private void fillDraftActions(LinearLayout card, final Draft draft,
                                  final EditText body, final boolean pending) {

        // Meta row (P-ux-fix: provider/model stays in the "Why this reply?" panel +
        // Prompt Audit only — the chip above carries the readable state).
        card.addView(Ui.sub(this, TimeFmt.dayTime(draft.createdAt)
            + " · " + draft.status.wire));

        // Expandable "Why this reply?" panel (owner ask): which voice settings, contact
        // overrides, custom instructions and learning signals shaped THIS reply — read
        // from the same immutable snapshot the audit screen uses.
        final TextView whyBody = Ui.sub(this, "");
        whyBody.setVisibility(View.GONE);
        whyBody.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        card.addView(whyBody);
        final TextView whyToggle = Ui.tv(this, "▸ Why this reply?", 12, Ui.ACCENT);
        whyToggle.setPadding(0, Ui.dp(this, 2), 0, 0);
        card.addView(whyToggle);
        whyToggle.setOnClickListener(new View.OnClickListener() {
            boolean open;
            @Override public void onClick(View v) {
                open = !open;
                if (open) {
                    java.util.List<String> lines = WhyLines.from(draft.promptSnapshotJson);
                    if (lines.isEmpty()) {
                        whyBody.setText("(generated before prompt auditing — no notes recorded)");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (String w : lines) sb.append("• ").append(w).append('\n');
                        whyBody.setText(sb.toString().trim());
                    }
                    whyBody.setVisibility(View.VISIBLE);
                    whyToggle.setText("▾ Why this reply?");
                } else {
                    whyBody.setVisibility(View.GONE);
                    whyToggle.setText("▸ Why this reply?");
                }
            }
        });

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
        // Re-gen asks for a fresh take — only offered while the draft is pending:
        // on finalized bubbles it would read as if the sent/copied reply could be
        // redone, which it can't (the audit trail stays).
        if (pending) {
            addAction(actions, "Re-gen", new View.OnClickListener() {
                @Override public void onClick(View v) { generate(); }
            });
        }
        addActionColored(actions, "Delete", Ui.RED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                c.drafts().delete(draft.id);
                // P4 learning: deleting a PENDING draft without copying = rejection.
                // Deleting a finalized receipt is just history cleanup (no signal,
                // same as before — the approval/copy signal was already recorded).
                if (pending) {
                    c.learningService().record(contact,
                        StyleSignal.Kind.REJECTED, "deleted", draft.id);
                }
                Toast.makeText(ConversationActivity.this,
                    "Draft deleted", Toast.LENGTH_SHORT).show();
                refreshDrafts();
            }
        });

        final Channel origin = originChannel();
        if (origin != null) {
            TextView open = addAction(actions, "Open in " + WatchedApps.labelFor(origin),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openInApp(origin, body == null
                            ? draft.replyText : body.getText().toString().trim());
                    }
                });
            open.setTextColor(Ui.ACCENT);
        }

        if (!pending) return;                        // finalized receipt: no transforms

        if (busyDrafts.contains(draft.id)) {
            TextView busy = Ui.sub(this, "Transforming…");
            busy.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(busy);
            return;                                 // no tone chips while working
        }

        // Tone transforms row.
        LinearLayout tones = new LinearLayout(this);
        tones.setOrientation(LinearLayout.HORIZONTAL);
        tones.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(tones);
        for (final ToneTransform tone : ToneTransform.values()) {
            TextView toneChip = Ui.tv(this, tone.label, 11, Ui.DIM);
            toneChip.setBackgroundResource(R.drawable.bg_field);
            int hp = Ui.dp(this, 8), vp = Ui.dp(this, 4);
            toneChip.setPadding(hp, vp, hp, vp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 6);
            clp.topMargin = Ui.dp(this, 4);
            tones.addView(toneChip, clp);
            toneChip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { transform(draft, tone); }
            });
        }
    }

    private TextView addAction(LinearLayout parent, String label,
                               View.OnClickListener listener) {
        return addActionColored(parent, label, Ui.DIM, listener);
    }

    /** Destructive actions get the danger color (owner ask: visually distinct). */
    private TextView addActionColored(LinearLayout parent, String label, int color,
                                      View.OnClickListener listener) {
        TextView a = Ui.tv(this, label, 12, color);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        int hp = Ui.dp(this, 8), vp = Ui.dp(this, 6);
        a.setPadding(hp, vp, hp, vp);
        a.setOnClickListener(listener);
        parent.addView(a);
        return a;
    }

    /** {@code body} is the pending tile's inline edit field; finalized tiles pass
     *  null and copy the receipt text as-is. */
    private void copyDraft(Draft draft, EditText body) {
        String text = body == null
            ? (draft.replyText == null ? "" : draft.replyText.trim())
            : body.getText().toString().trim();
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
        // P-intelligence-14: this approved reply may arm ONE auto follow-up draft
        // (per-contact switch + policy inside; the follow-up itself is approve-first).
        com.replymate.app.assistant.AssistantRunner.maybeFollowUp(c, contact.id, draft.id);
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
                    LastProviderError.save(c, r.error);
                    showStatus("Transform failed:\n" + r.error, Ui.RED);
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
        openInApp(channel, body.getText().toString().trim());
    }

    private void openInApp(Channel channel, String text) {
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
        genStatus.setOnClickListener(null);   // cancel any pending "tap to open" offer
        genStatus.setVisibility(View.VISIBLE);
        genStatus.setText(msg);
        genStatus.setTextColor(color);
    }

    /** Error text + actionable hint: offline-friendly messaging for network failures. */
}
