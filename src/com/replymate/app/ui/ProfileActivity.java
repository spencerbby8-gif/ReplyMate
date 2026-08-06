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
import com.replymate.core.usecase.ProfileService;


public final class ProfileActivity extends Activity {
    private EditText bio;
    private AppContainer c;
    private EditText languages;
    private EditText name;
    private EditText topics;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_profile);
        this.c = ReplyMateApp.containerOf(this);
        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.profile_root);
        TextView tv = Ui.tv(this, "‹ My profile", 22.0f, -1);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, Ui.dp(this, 6));
        tv.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ProfileActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ProfileActivity.this.finish();
            }
        });
        linearLayout.addView(tv);
        linearLayout.addView(Ui.sub(this, "The AI uses this to sound like you. It stays on this phone and is only included in replies you explicitly generate."));
        ProfileService.Profile load = this.c.profiles().load();
        linearLayout.addView(Ui.label(this, "Your name / nickname"));
        EditText field = Ui.field(this, "e.g. Kelechi", false);
        this.name = field;
        field.setText(load.name);
        linearLayout.addView(this.name);
        linearLayout.addView(Ui.label(this, "Languages you write in"));
        EditText field2 = Ui.field(this, "e.g. English, Pidgin, Igbo", false);
        this.languages = field2;
        field2.setText(load.languages);
        linearLayout.addView(this.languages);
        linearLayout.addView(Ui.label(this, "Short bio"));
        EditText field3 = Ui.field(this, "Who you are, what you do, how you text…", true);
        this.bio = field3;
        field3.setText(load.bio);
        linearLayout.addView(this.bio);
        linearLayout.addView(Ui.label(this, "Things you often talk about"));
        EditText field4 = Ui.field(this, "e.g. weekdays logistics, football, business", false);
        this.topics = field4;
        field4.setText(load.topics);
        linearLayout.addView(this.topics);
        Button btn = Ui.btn(this, "Save");
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = Ui.dp(this, 16);
        linearLayout.addView(btn, layoutParams);
        btn.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ProfileActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ProfileActivity.this.c.profiles().save(new ProfileService.Profile(ProfileActivity.this.name.getText().toString(), ProfileActivity.this.languages.getText().toString(), ProfileActivity.this.bio.getText().toString(), ProfileActivity.this.topics.getText().toString()));
                Toast.makeText(ProfileActivity.this, "Saved", 0).show();
                ProfileActivity.this.finish();
            }
        });
    }
}
