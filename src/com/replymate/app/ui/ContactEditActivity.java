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
import com.replymate.core.util.Result;


public final class ContactEditActivity extends Activity {
    public static final String EXTRA_CONTACT_ID = "contact_id";
    private AppContainer c;
    private long contactId = -1;
    private Contact existing;
    private EditText language;
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
}
