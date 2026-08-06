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
    private AppContainer c;
    private long contactId = -1;
    private Contact existing;
    private java.util.Map<String, String> contactRows;
    private EditText customPrompt;
    private java.util.Map<String, String> globalRows;
    private EditText language;
    private android.widget.TextView learningStats;
    private EditText name;
    private EditText notes;
    private EditText relType;
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
        linearLayout.addView(Ui.sub(this, "Who is this person to you? Relationship and tone shape how the AI replies to them."));
        linearLayout.addView(Ui.label(this, "Name *"));
        EditText field = Ui.field(this, "e.g. Amara", false);
        this.name = field;
        linearLayout.addView(field);
        linearLayout.addView(Ui.label(this, "Relationship"));
        EditText field2 = Ui.field(this, "e.g. close friend, sister, client", false);
        this.relType = field2;
        linearLayout.addView(field2);
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
            this.relType.setText(this.existing.relationshipType);
            this.notes.setText(this.existing.relationshipNotes);
            this.tone.setText(this.existing.toneOverride);
            this.language.setText(this.existing.languagePref);
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
                    ContactEditActivity.this.existing.relationshipType = ContactEditActivity.this.relType.getText().toString().trim();
                    ContactEditActivity.this.existing.relationshipNotes = ContactEditActivity.this.notes.getText().toString().trim();
                    ContactEditActivity.this.existing.toneOverride = ContactEditActivity.this.tone.getText().toString().trim();
                    ContactEditActivity.this.existing.languagePref = ContactEditActivity.this.language.getText().toString().trim();
                    ContactEditActivity.this.c.contactService().update(ContactEditActivity.this.existing);
                    ContactEditActivity.this.persistCustomPrompt();
                } else {
                    Result<Contact> createManualContact = ContactEditActivity.this.c.contactService().createManualContact(ContactEditActivity.this.name.getText().toString(), ContactEditActivity.this.relType.getText().toString(), ContactEditActivity.this.notes.getText().toString(), ContactEditActivity.this.tone.getText().toString(), ContactEditActivity.this.language.getText().toString());
                    if (!createManualContact.ok) {
                        Toast.makeText(ContactEditActivity.this, createManualContact.error, 1).show();
                        return;
                    }
                }
                ContactEditActivity.this.finish();
            }
        });
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        persistCustomPrompt();      // custom prompt survives the ‹ back path too
    }

    /* ------------------------------------------------------ P4: customization */

    private void buildCustomization(LinearLayout root) {
        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "VOICE FOR THIS CONTACT"));
        TextView why = Ui.sub(this,
            "Override your global voice for " + this.existing.displayName
                + " only. Tap a control to cycle through its levels — \"Inherit\" follows your global voice.");
        root.addView(why);
        for (StyleControls.Control control : StyleControls.all()) {
            root.addView(overrideRow(control));
            root.addView(Ui.divider(this));
        }

        root.addView(Ui.label(this, "Special instruction for this chat (custom prompt)"));
        this.customPrompt = Ui.field(this,
            "e.g. never bring up work; keep replies playful; she's planning a surprise party…", true);
        this.customPrompt.setText(StyleSettings.customPrompt(this.contactRows));
        root.addView(this.customPrompt);
        root.addView(Ui.sub(this, "Saved with the contact. Max 400 characters."));

        buildLearning(root);
    }

    private View overrideRow(final StyleControls.Control control) {
        final LinearLayout row = Ui.row(this, control.label, "");
        Ui.setRowSub(row, overrideText(control));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Integer current = StyleSettings.level(ContactEditActivity.this.contactRows,
                    control.key);
                if (current == null) {
                    setOverride(control.key, 0);
                } else if (current.intValue() < 2) {
                    setOverride(control.key, current.intValue() + 1);
                } else {
                    ContactEditActivity.this.contactRows.remove(control.key);
                    ContactEditActivity.this.c.styleSettings().remove(
                        ContactEditActivity.this.contactId, control.key);
                }
                Ui.setRowSub(row, overrideText(control));
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
            return "Override: " + control.levelLabel(ov.intValue());
        }
        Integer g = StyleSettings.level(this.globalRows, control.key);
        int base = g != null ? g.intValue() : StyleControls.defaultLevel(control.key);
        return "Inherit: " + control.levelLabel(base);
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

    private void buildLearning(LinearLayout root) {
        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "LEARNING FROM YOUR CHOICES"));
        final com.replymate.core.learning.LearningService learning = this.c.learningService();

        if (this.existing.privateMode) {
            root.addView(Ui.sub(this,
                "learning off — private contact (learning can never run for private contacts)"));
            return;
        }
        if (!this.existing.memoryEnabled) {
            root.addView(Ui.sub(this, "learning off — memory is disabled for this contact"));
            return;
        }

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
        root.addView(toggle);
        root.addView(Ui.divider(this));

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
        root.addView(pause);
        root.addView(Ui.divider(this));

        this.learningStats = Ui.sub(this, "");
        this.learningStats.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(this.learningStats);
        refreshLearningStats();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(this, 8);
        blp.rightMargin = Ui.dp(this, 8);
        Button reset = Ui.btn(this, "Reset learning");
        buttons.addView(reset, blp);
        Button export = Ui.btn(this, "Export (copy)");
        buttons.addView(export, new LinearLayout.LayoutParams(blp));
        root.addView(buttons);

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
        StringBuilder sb = new StringBuilder("Signals: approved ").append(k.approved)
            .append(" · edited ").append(k.edited)
            .append(" · regenerated ").append(k.regenerated)
            .append(" · rejected ").append(k.rejected);
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
