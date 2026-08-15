package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.core.model.Contact;
import com.replymate.core.style.StyleControls;
import com.replymate.core.style.StyleSettings;
import com.replymate.core.util.Result;


public final class ContactEditActivity extends Activity {
    public static final String EXTRA_CONTACT_ID = "contact_id";
    private static final String[] REL_CHIPS =
        {"friend", "close friend", "family", "partner", "work", "client"};
    private AppContainer c;
    private long contactId = -1;
    private Contact existing;
    private java.util.Map<String, String> contactRows;
    private EditText customPrompt;
    private boolean deleteArmed;
    private java.util.Map<String, String> globalRows;
    private EditText language;
    private LinearLayout learningContent;
    private android.widget.TextView learningGateNote;
    private android.widget.TextView learningStats;
    private EditText name;
    private EditText notes;
    private EditText pinnedFacts;
    private boolean previewBusy;
    private android.widget.Button previewBtn;
    private android.widget.TextView previewOut;
    private LinearLayout relChips;
    private final java.util.List<android.widget.TextView> relChipViews =
        new java.util.ArrayList<android.widget.TextView>();
    private EditText relType;
    private String selectedRel = "";
    private EditText tone;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_contact_edit);
        this.c = ReplyMateApp.containerOf(this);
        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.contact_edit_root);
        long longExtra = getIntent().getLongExtra("contact_id", -1L);
        this.contactId = longExtra;
        if (longExtra > 0) {
            this.existing = this.c.contacts().get(this.contactId);
        }
        TextView tv = Ui.tv(this, this.existing == null ? "‹ New contact" : "‹ Edit contact", 22.0f, -1);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, Ui.dp(this, 6));
        tv.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ContactEditActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ContactEditActivity.this.finish();
            }
        });
        linearLayout.addView(tv);
        linearLayout.addView(Ui.sub(this, "Who is this person to you? This context shapes how the AI replies to them."));
        // SECTION 1 — about them
        linearLayout.addView(Ui.label(this, "ABOUT THEM"));
        linearLayout.addView(Ui.label(this, "Name *"));
        EditText field = Ui.field(this, "e.g. Amara", false);
        this.name = field;
        linearLayout.addView(field);
        linearLayout.addView(Ui.label(this, "Relationship — tap one"));
        this.relChips = new LinearLayout(this);
        this.relChips.setOrientation(LinearLayout.HORIZONTAL);
        linearLayout.addView(this.relChips);
        String[] rels = {"friend", "close friend", "family", "partner", "work", "client"};
        for (final String rel : rels) {
            TextView chip = relChip(rel);
            this.relChips.addView(chip, relChipLp());
        }
        LinearLayout otherWrap = new LinearLayout(this);
        otherWrap.setOrientation(LinearLayout.HORIZONTAL);
        TextView otherChip = relChip("Other…");
        otherWrap.addView(otherChip, relChipLp());
        linearLayout.addView(otherWrap);
        this.relType = Ui.field(this, "type the relationship (e.g. sister)", false);
        this.relType.setVisibility(View.GONE);
        linearLayout.addView(this.relType);
        linearLayout.addView(Ui.label(this, "Notes about them (context for the AI)"));
        EditText field3 = Ui.field(this, "e.g. runs a logistics business in PH; we met at uni…", true);
        this.notes = field3;
        linearLayout.addView(field3);
        linearLayout.addView(Ui.label(this, "Tone with them"));
        EditText field4 = Ui.field(this, "e.g. playful, very casual, respectful-professional", false);
        this.tone = field4;
        linearLayout.addView(field4);
        linearLayout.addView(Ui.label(this, "Language with them (optional)"));
        EditText field5 = Ui.field(this, "e.g. Pidgin, English", false);
        this.language = field5;
        linearLayout.addView(field5);
        Contact contact = this.existing;
        if (contact != null) {
            this.name.setText(contact.displayName);
            selectRelationship(this.existing.relationshipType);
            this.notes.setText(this.existing.relationshipNotes);
            this.tone.setText(this.existing.toneOverride);
            this.language.setText(this.existing.languagePref);
        } else {
            selectRelationship("");
        }
        // P4: per-contact customization + learning — needs a saved contact id.
        if (this.existing != null) {
            this.contactRows = new java.util.HashMap<String, String>(
                this.c.styleSettings().all(this.contactId));
            this.globalRows = this.c.styleSettings().all(null);
            buildCustomization(linearLayout);
        } else {
            linearLayout.addView(Ui.divider(this));
            linearLayout.addView(Ui.sub(this,
                "Save this contact first — then you can set voice overrides, a custom prompt, and learning controls for them here."));
        }
        Button btn = Ui.btn(this, this.existing == null ? "Create contact" : "Save changes");
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = Ui.dp(this, 16);
        linearLayout.addView(btn, layoutParams);
        btn.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ContactEditActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (ContactEditActivity.this.existing != null) {
                    ContactEditActivity.this.existing.displayName = ContactEditActivity.this.name.getText().toString().trim();
                    ContactEditActivity.this.existing.relationshipType = ContactEditActivity.this.relationshipValue();
                    ContactEditActivity.this.existing.relationshipNotes = ContactEditActivity.this.notes.getText().toString().trim();
                    ContactEditActivity.this.existing.toneOverride = ContactEditActivity.this.tone.getText().toString().trim();
                    ContactEditActivity.this.existing.languagePref = ContactEditActivity.this.language.getText().toString().trim();
                    ContactEditActivity.this.c.contactService().update(ContactEditActivity.this.existing);
                    ContactEditActivity.this.persistCustomPrompt();
                    // P-memory-audit: replace this contact's pinned facts exactly.
                    ContactEditActivity.this.c.memoryService().replacePinnedFacts(
                        ContactEditActivity.this.contactId,
                        ContactEditActivity.this.pinnedFacts == null
                            ? "" : ContactEditActivity.this.pinnedFacts.getText().toString());
                } else {
                    Result<Contact> createManualContact = ContactEditActivity.this.c.contactService().createManualContact(ContactEditActivity.this.name.getText().toString(), ContactEditActivity.this.relationshipValue(), ContactEditActivity.this.notes.getText().toString(), ContactEditActivity.this.tone.getText().toString(), ContactEditActivity.this.language.getText().toString());
                    if (!createManualContact.ok) {
                        Toast.makeText(ContactEditActivity.this, createManualContact.error, 1).show();
                        return;
                    }
                }
                ContactEditActivity.this.finish();
            }
        });

        // P4-stabilization: contact deletion completes the P1 CRUD scope (the store
        // method + FK cascade existed but nothing reachable called them). Two-tap
        // inline confirm — no dialog framework needed.
        if (this.existing != null) {
            final Button delete = Ui.btn(this, "Delete contact");
            delete.setTextColor(Ui.RED);   // destructive — visually distinct
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = Ui.dp(this, 8);
            linearLayout.addView(delete, dlp);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!ContactEditActivity.this.deleteArmed) {
                        ContactEditActivity.this.deleteArmed = true;
                        delete.setText("Really delete " + ContactEditActivity.this.existing.displayName
                            + "? Messages, drafts, voice settings and learning for them are wiped. Tap again to confirm.");
                        return;
                    }
                    ContactEditActivity.this.c.contacts().delete(ContactEditActivity.this.contactId);
                    Toast.makeText(ContactEditActivity.this, "Contact deleted", 0).show();
                    ContactEditActivity.this.finish();
                }
            });
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        persistCustomPrompt();      // custom prompt survives the ‹ back path too
    }

    /* -------------------------------------------------- relationship chips */

    private TextView relChip(final String rel) {
        final TextView chip = Ui.tv(this, rel, 12, Ui.DIM);
        chip.setBackgroundResource(R.drawable.bg_field);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        chip.setTag(rel);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectRelationship(rel); }
        });
        this.relChipViews.add(chip);
        return chip;
    }

    private LinearLayout.LayoutParams relChipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), 0);
        return lp;
    }

    /** Select a relationship chip; an unknown value selects "Other…" with free text. */
    private void selectRelationship(String value) {
        String v = value == null ? "" : value.trim();
        boolean preset = false;
        for (String r : REL_CHIPS) if (r.equals(v)) preset = true;
        this.selectedRel = preset ? v : (v.isEmpty() ? "" : "Other…");
        for (TextView chip : this.relChipViews) {
            boolean sel = chip.getTag().equals(this.selectedRel)
                || (!preset && "Other…".equals(chip.getTag()) && !v.isEmpty());
            chip.setTextColor(sel ? Ui.ACCENT : Ui.DIM);
        }
        boolean other = !preset && !v.isEmpty();
        if (other) this.selectedRel = "Other…";
        this.relType.setVisibility("Other…".equals(this.selectedRel) ? View.VISIBLE : View.GONE);
        if (other) this.relType.setText(v);
    }

    /** Effective relationship value for saving (chip value or Other's free text). */
    private String relationshipValue() {
        if ("Other…".equals(this.selectedRel)) {
            return this.relType.getText().toString().trim();
        }
        return this.selectedRel == null ? "" : this.selectedRel;
    }

    /* ------------------------------------------------------ P4: customization */

    private void buildCustomization(LinearLayout root) {
        root.addView(Ui.divider(this));
        buildPrivacyAi(root);

        root.addView(Ui.label(this, "VOICE FOR THIS CONTACT"));
        TextView why = Ui.sub(this,
            "Override your global voice for " + this.existing.displayName
                + " only — your tap here always wins over learned guesses. Tap a control"
                + " to see every option with examples; the top row follows your global"
                + " voice again, and Off switches that dial off for this person.");
        root.addView(why);
        // P-intelligence-3: same grouping as the global voice page so both screens
        // read the same way (storage order untouched).
        addOverrideGroup(root, "FEEL & TONE",
            new StyleControls.Control[] {StyleControls.TONE, StyleControls.HUMOR,
                StyleControls.CONFIDENCE, StyleControls.FLIRTING});
        addOverrideGroup(root, "REPLY SHAPE",
            new StyleControls.Control[] {StyleControls.LENGTH, StyleControls.FORMALITY,
                StyleControls.FOLLOW_UP});
        addOverrideGroup(root, "EXPRESSION",
            new StyleControls.Control[] {StyleControls.EMOJI, StyleControls.SLANG});

        // Live preview (P-polish): real generation over a sample chat using THIS
        // contact's effective voice (overrides + custom prompt + learned hints).
        this.previewOut = Ui.sub(this, "");
        this.previewOut.setTextIsSelectable(true);
        this.previewOut.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(this.previewOut);
        this.previewBtn = Ui.btn(this, "Preview replies with this voice");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(this, 6);
        root.addView(this.previewBtn, plp);
        this.previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runVoicePreview(); }
        });

        root.addView(Ui.label(this, "This contact's own rules (custom instruction — overrides the global voice when they clash)"));
        this.customPrompt = Ui.field(this,
            "e.g. never bring up work; keep replies playful; she's planning a surprise party…", true);
        this.customPrompt.setText(StyleSettings.customPrompt(this.contactRows));
        root.addView(this.customPrompt);
        root.addView(Ui.sub(this, "Saved with the contact. Max 400 characters."));

        // P-intelligence-14: per-contact AUTO FOLLOW-UP (distinct from the
        // "Follow-ups" voice dial, which shapes reply wording). OFF by default;
        // the prepared follow-up is an approve-first draft like every other.
        Ui.ToggleRow autoFollow = Ui.toggleRow(this, "Auto follow-up draft",
            "after you approve a reply and they go quiet, ReplyMate prepares ONE"
                + " natural follow-up for this contact — through the same memory,"
                + " voice, learning and Search as any draft. It knows when not to"
                + " bump (their fresh message, a draft already waiting, one per"
                + " reply) and it NEVER sends by itself.");
        autoFollow.sw.setChecked(StyleSettings.autoFollowOn(this.contactRows));
        autoFollow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    if (on) {
                        setOverride(StyleSettings.AUTO_FOLLOW_KEY, 1);
                        android.widget.Toast.makeText(ContactEditActivity.this,
                            "Auto follow-up on — every draft still waits for your approval",
                            android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        ContactEditActivity.this.contactRows.remove(StyleSettings.AUTO_FOLLOW_KEY);
                        ContactEditActivity.this.c.styleSettings().remove(
                            ContactEditActivity.this.contactId, StyleSettings.AUTO_FOLLOW_KEY);
                        android.widget.Toast.makeText(ContactEditActivity.this,
                            "Auto follow-up off", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        root.addView(autoFollow.row);
        root.addView(Ui.divider(this));

        buildPinnedFacts(root);
        buildLearning(root);
    }

    /* ------------------------------------------- P-memory-audit: pinned facts */

    /** Owner-authored pinned facts — the M3 stable-details memory layer. One per
     *  line; the AI may READ them for this contact only and can never edit them.
     *  Persisted in memory_fact (pinned=1) so they survive restarts; replacing is
     *  exact (the box is the whole pinned set for this contact). */
    private void buildPinnedFacts(LinearLayout root) {
        root.addView(Ui.label(this, "Pinned facts about them (your own notes — one per line)"));
        this.pinnedFacts = Ui.field(this,
            "e.g. her mum's shop is in Wuse 2\nshe's allergic to peanuts\nprefers voice notes after 9pm",
            true);
        this.pinnedFacts.setText(this.c.memoryService().pinnedFactsText(this.contactId));
        root.addView(this.pinnedFacts);
        boolean memOn = this.existing == null || this.existing.memoryEnabled;
        root.addView(Ui.sub(this, memOn
            ? "Stable facts stay saved on this phone, never leave it, and shape replies for this contact only."
            : "Memory is OFF for this contact — pinned facts are saved but never used until memory is back on."));
    }

    /** One labeled group of per-contact voice overrides (P-intelligence-3). */
    private void addOverrideGroup(LinearLayout root, String title,
                                  StyleControls.Control[] controls) {
        root.addView(Ui.label(this, title));
        for (StyleControls.Control control : controls) {
            root.addView(overrideRow(control));
            root.addView(Ui.divider(this));
        }
    }

    private View overrideRow(final StyleControls.Control control) {
        final LinearLayout row = Ui.row(this, control.label, "");
        Ui.setRowSub(row, overrideText(control));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // P-ux-fix: real picker — inherit row on top, then every level with
                // what it does + an example, ✓ on the current choice.
                Integer cur = StyleSettings.level(ContactEditActivity.this.contactRows,
                    control.key);
                String who = ContactEditActivity.this.existing == null
                    ? "this contact" : ContactEditActivity.this.existing.displayName;
                Ui.showLevelPicker(ContactEditActivity.this,
                    control.label + " for " + who, control,
                    cur == null ? -1 : cur.intValue(),
                    "Inherit global voice",
                    new Ui.LevelPick() {
                        @Override public void onPick(int level) {
                            if (level < 0) {
                                ContactEditActivity.this.contactRows.remove(control.key);
                                ContactEditActivity.this.c.styleSettings().remove(
                                    ContactEditActivity.this.contactId, control.key);
                            } else {
                                setOverride(control.key, level);
                            }
                            Ui.setRowSub(row, overrideText(control));
                        }
                    });
            }
        });
        return row;
    }

    private void setOverride(String key, int level) {
        this.contactRows.put(key, String.valueOf(level));
        this.c.styleSettings().put(this.contactId, key, String.valueOf(level));
    }

    private String overrideText(StyleControls.Control control) {
        Integer ov = StyleSettings.level(this.contactRows, control.key);
        if (ov != null) {
            return "current: " + control.levelLabel(ov.intValue()) + " (overrides global)";
        }
        Integer g = StyleSettings.level(this.globalRows, control.key);
        int base = g != null ? g.intValue() : StyleControls.defaultLevel(control.key);
        return "inheriting global: " + control.levelLabel(base);
    }

    private void runVoicePreview() {
        if (this.previewBusy) return;
        persistCustomPrompt();             // preview what the user just typed too
        this.previewBusy = true;
        this.previewBtn.setEnabled(false);
        this.previewOut.setText("Generating preview…");
        this.previewOut.setTextColor(Ui.ACCENT);
        com.replymate.app.platform.Tasks.call(
            new com.replymate.app.platform.Tasks.Job<com.replymate.core.util.Result<com.replymate.core.ai.ChatReply>>() {
                @Override public com.replymate.core.util.Result<com.replymate.core.ai.ChatReply> run() {
                    return ContactEditActivity.this.c.draftService()
                        .previewVoice(ContactEditActivity.this.contactId);
                }
            },
            new com.replymate.app.platform.Tasks.Done<com.replymate.core.util.Result<com.replymate.core.ai.ChatReply>>() {
                @Override public void accept(com.replymate.core.util.Result<com.replymate.core.ai.ChatReply> r) {
                    ContactEditActivity.this.previewBusy = false;
                    ContactEditActivity.this.previewBtn.setEnabled(true);
                    if (!r.ok) {
                        LastProviderError.save(c, r.error);
                        ContactEditActivity.this.previewOut.setText("Preview failed:\n" + r.error);
                        ContactEditActivity.this.previewOut.setTextColor(Ui.RED);
                        return;
                    }
                    StringBuilder sb = new StringBuilder(
                        "Sample chat — “you still coming on Saturday?”\nThis voice says:\n");
                    int n = 1;
                    for (String v : r.value.variants) {
                        sb.append(n++).append(") ").append(v).append('\n');
                    }
                    ContactEditActivity.this.previewOut.setText(sb.toString().trim());
                    ContactEditActivity.this.previewOut.setTextColor(Ui.GREEN);
                }
            });
    }

    /** Custom prompt box → style_setting (empty removes the row). */
    private void persistCustomPrompt() {
        if (this.existing == null || this.customPrompt == null) return;
        String text = this.customPrompt.getText().toString();
        String capped = StyleSettings.customPrompt(
            java.util.Collections.singletonMap(StyleSettings.CUSTOM_PROMPT_KEY, text));
        String stored = StyleSettings.customPrompt(this.contactRows);
        if (capped.equals(stored)) return;
        if (capped.isEmpty()) {
            this.contactRows.remove(StyleSettings.CUSTOM_PROMPT_KEY);
            this.c.styleSettings().remove(this.contactId, StyleSettings.CUSTOM_PROMPT_KEY);
        } else {
            this.contactRows.put(StyleSettings.CUSTOM_PROMPT_KEY, capped);
            this.c.styleSettings().put(this.contactId, StyleSettings.CUSTOM_PROMPT_KEY, capped);
        }
    }

    /* ---------------------------------------------------------- P4: learning */

    /** AI & privacy toggles (P4-stabilization): these flags existed in the model since P1
     *  and gate generation + learning, but no screen set them — they were unreachable.
     *  Every row writes through immediately and the learning section below reacts live. */
    private void buildPrivacyAi(LinearLayout root) {
        root.addView(Ui.label(this, "AI & PRIVACY FOR THIS CONTACT"));
        root.addView(Ui.sub(this,
            "These are real switches — they save instantly and show the live state."));

        final Ui.ToggleRow priv = Ui.toggleRow(this, "Private mode", privacyText());
        root.addView(priv.row);
        root.addView(Ui.divider(this));

        final Ui.ToggleRow mem = Ui.toggleRow(this, "Memory for this contact", memoryText());
        root.addView(mem.row);
        root.addView(Ui.divider(this));

        final Ui.ToggleRow ai = Ui.toggleRow(this, "AI replies for this contact", aiText());
        root.addView(ai.row);
        root.addView(Ui.divider(this));

        // initial states (listeners attach AFTER, so no echo writes)
        priv.sw.setChecked(this.existing.privateMode);
        mem.sw.setChecked(this.existing.memoryEnabled);
        ai.sw.setChecked(this.existing.aiEnabled);
        ai.sw.setEnabled(!this.existing.privateMode);

        priv.sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on == ContactEditActivity.this.existing.privateMode) return;   // no-op render
                ContactEditActivity.this.existing.privateMode = on;
                // model contract (core Contact): privateMode == true ⇒ aiEnabled == false
                ContactEditActivity.this.existing.aiEnabled = !on;
                ContactEditActivity.this.c.contactService().update(ContactEditActivity.this.existing);
                Ui.setRowSub(priv.row, privacyText());
                ai.sw.setEnabled(!on);
                ai.sw.setChecked(ContactEditActivity.this.existing.aiEnabled);
                Ui.setRowSub(ai.row, aiText());
                refreshLearningState();
            }
        });
        mem.sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on == ContactEditActivity.this.existing.memoryEnabled) return;
                ContactEditActivity.this.existing.memoryEnabled = on;
                ContactEditActivity.this.c.contactService().update(ContactEditActivity.this.existing);
                Ui.setRowSub(mem.row, memoryText());
                refreshLearningState();
            }
        });
        ai.sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (ContactEditActivity.this.existing.privateMode) {
                    // private mode owns this switch — keep it off and say why
                    Toast.makeText(ContactEditActivity.this,
                        "Private mode is on — turn it off to allow AI replies", 0).show();
                    return;
                }
                if (on == ContactEditActivity.this.existing.aiEnabled) return;
                ContactEditActivity.this.existing.aiEnabled = on;
                ContactEditActivity.this.c.contactService().update(ContactEditActivity.this.existing);
                Ui.setRowSub(ai.row, aiText());
            }
        });
        // a disabled AI switch must still EXPLAIN itself when the row is tapped
        ai.row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ContactEditActivity.this.existing.privateMode) {
                    Toast.makeText(ContactEditActivity.this,
                        "Private mode is on — turn it off above to allow AI replies", 0).show();
                } else {
                    ai.sw.toggle();
                }
            }
        });
    }

    private String privacyText() {
        return this.existing.privateMode
            ? "on — this chat is never sent to any AI (generation + learning off)"
            : "off — AI replies allowed (the settings below apply)";
    }

    private String memoryText() {
        return this.existing.memoryEnabled
            ? "on — ReplyMate may keep context + learn for this chat"
            : "off — no memory and no learning for this chat";
    }

    private String aiText() {
        if (this.existing.privateMode) return "off — private mode is on";
        return this.existing.aiEnabled
            ? "on — ✨ Generate works in this chat" : "off — Generate refuses this chat";
    }

    private void buildLearning(LinearLayout root) {
        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "LEARNING FROM YOUR CHOICES"));
        final com.replymate.core.learning.LearningService learning = this.c.learningService();

        this.learningGateNote = Ui.sub(this, "");
        root.addView(this.learningGateNote);
        this.learningContent = new LinearLayout(this);
        this.learningContent.setOrientation(1);
        root.addView(this.learningContent);

        final LinearLayout toggle = Ui.row(this, "Learning for this contact", "");
        Ui.setRowSub(toggle, learningToggleText(learning));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                learning.setOff(ContactEditActivity.this.contactId,
                    !learning.isOff(ContactEditActivity.this.contactId));
                Ui.setRowSub(toggle, learningToggleText(learning));
                refreshLearningStats();
            }
        });
        this.learningContent.addView(toggle);
        this.learningContent.addView(Ui.divider(this));

        final LinearLayout pause = Ui.row(this, "Pause learning", "");
        Ui.setRowSub(pause, pauseText(learning));
        pause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                learning.setPaused(ContactEditActivity.this.contactId,
                    !learning.isPaused(ContactEditActivity.this.contactId));
                Ui.setRowSub(pause, pauseText(learning));
                refreshLearningStats();
            }
        });
        this.learningContent.addView(pause);
        this.learningContent.addView(Ui.divider(this));

        this.learningStats = Ui.sub(this, "");
        this.learningStats.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        this.learningContent.addView(this.learningStats);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(this, 8);
        blp.rightMargin = Ui.dp(this, 8);
        Button reset = Ui.btn(this, "Reset learning");
        reset.setTextColor(Ui.RED);        // destructive — visually distinct
        buttons.addView(reset, blp);
        Button export = Ui.btn(this, "Export (copy)");
        buttons.addView(export, new LinearLayout.LayoutParams(blp));
        this.learningContent.addView(buttons);

        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                learning.reset(ContactEditActivity.this.contactId);
                Toast.makeText(ContactEditActivity.this,
                    "Learning history wiped for this contact", 0).show();
                refreshLearningStats();
            }
        });
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String json = learning.exportJson(ContactEditActivity.this.existing,
                    ContactEditActivity.this.c.styleSettings().all(null),
                    ContactEditActivity.this.c.styleSettings().all(ContactEditActivity.this.contactId));
                ((android.content.ClipboardManager) getSystemService("clipboard"))
                    .setPrimaryClip(android.content.ClipData.newPlainText(
                        "replymate learning export", json));
                Toast.makeText(ContactEditActivity.this,
                    "Learning data copied to clipboard (stays on this phone)", 1).show();
            }
        });
        refreshLearningState();
    }

    /** Gate-driven learning panel state: contact-level privacy flags (private mode,
     *  memory off) close the gate no matter what the switches below say — say so
     *  honestly and hide the controls; otherwise show controls + live stats. */
    private void refreshLearningState() {
        if (this.learningGateNote == null || this.learningContent == null) return;
        if (this.existing.privateMode) {
            this.learningGateNote.setText(
                "learning off — private mode is on (learning can never run for private contacts)");
            this.learningGateNote.setVisibility(View.VISIBLE);
            this.learningContent.setVisibility(View.GONE);
            return;
        }
        if (!this.existing.memoryEnabled) {
            this.learningGateNote.setText("learning off — memory is disabled for this contact");
            this.learningGateNote.setVisibility(View.VISIBLE);
            this.learningContent.setVisibility(View.GONE);
            return;
        }
        this.learningGateNote.setVisibility(View.GONE);
        this.learningContent.setVisibility(View.VISIBLE);
        refreshLearningStats();
    }

    private String learningToggleText(com.replymate.core.learning.LearningService learning) {
        return learning.isOff(this.contactId)
            ? "off — ReplyMate won't learn from this contact" : "on — learns from your copy / edit / delete choices";
    }

    private String pauseText(com.replymate.core.learning.LearningService learning) {
        return learning.isPaused(this.contactId)
            ? "paused — history kept, nothing applied" : "active";
    }

    private void refreshLearningStats() {
        if (this.learningStats == null) return;
        com.replymate.core.learning.LearningService learning = this.c.learningService();
        com.replymate.core.learning.LearningEngine.Counters k =
            learning.counters(this.contactId);
        StringBuilder sb = new StringBuilder("Signals: approved ").append(k.approved);
        if (k.approved > 0) {
            sb.append(" (").append(k.copiedAsIs).append(" copied as-is · ")
              .append(k.quickSent).append(" quick-sends · ")
              .append(k.manualMatched).append(" manual matches)");
        }
        sb.append(" · edited ").append(k.edited)
            .append(" · regenerated ").append(k.regenerated)
            .append(" · rejected ").append(k.rejected);
        if (k.manualTotal() > 0) {
            sb.append("\\nManual sends (typed in the app yourself): ")
              .append(k.manualMatched).append(" matched a draft word-for-word · ")
              .append(k.manualCorrected).append(" corrected one");
        }
        if (learning.openFor(this.existing)) {
            java.util.List<com.replymate.core.learning.LearningEngine.Hint> hints =
                learning.hintsFor(this.existing);
            for (com.replymate.core.learning.LearningEngine.Hint h : hints) {
                sb.append("\n• ").append(h.line).append(" (").append(h.why).append(')');
            }
            if (hints.isEmpty()) sb.append("\nNo learned adjustments yet — keep using drafts.");
        } else if (learning.isOff(this.contactId)) {
            sb.append("\nLearning is off — these signals are ignored until re-enabled.");
        } else if (learning.isPaused(this.contactId)) {
            sb.append("\nPaused — these signals are kept but not applied.");
        }
        this.learningStats.setText(sb.toString());
    }
}
