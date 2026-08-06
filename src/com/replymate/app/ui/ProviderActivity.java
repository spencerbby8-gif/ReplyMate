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
import com.replymate.app.platform.Tasks;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.core.util.Result;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.RetryPolicy;


public final class ProviderActivity extends Activity {
    private static final String KEY_REF = "gemini.main";
    private EditText baseUrl;
    private AppContainer c;
    private EditText keyInput;
    private EditText label;
    private EditText model;
    private TextView status;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_provider);
        this.c = ReplyMateApp.containerOf(this);
        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.provider_root);
        TextView tv = Ui.tv(this, "‹ AI provider (Gemini)", 22.0f, -1);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, Ui.dp(this, 6));
        tv.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ProviderActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ProviderActivity.this.finish();
            }
        });
        linearLayout.addView(tv);
        linearLayout.addView(Ui.sub(this, "Bring your own key (Google AI Studio → Get API key). The key is encrypted with your device's hardware keystore and never leaves this phone except in calls to the Gemini endpoint below."));
        ProviderDef active = this.c.providers().active();
        linearLayout.addView(Ui.label(this, "Label"));
        EditText field = Ui.field(this, "Gemini", false);
        this.label = field;
        field.setText(active != null ? active.label : "Gemini");
        linearLayout.addView(this.label);
        linearLayout.addView(Ui.label(this, "Base URL"));
        EditText field2 = Ui.field(this, "https://generativelanguage.googleapis.com", false);
        this.baseUrl = field2;
        field2.setText(active != null ? active.baseUrl : "https://generativelanguage.googleapis.com");
        linearLayout.addView(this.baseUrl);
        linearLayout.addView(Ui.label(this, "Model"));
        String str = "gemini-2.5-flash";
        EditText field3 = Ui.field(this, "gemini-2.5-flash", false);
        this.model = field3;
        if (active != null && !active.modelName.isEmpty()) {
            str = active.modelName;
        }
        field3.setText(str);
        linearLayout.addView(this.model);
        linearLayout.addView(Ui.label(this, "API key"));
        EditText field4 = Ui.field(this, "Paste key here (never shown again after saving)", false);
        this.keyInput = field4;
        field4.setInputType(129);
        linearLayout.addView(this.keyInput);
        TextView sub = Ui.sub(this, "");
        this.status = sub;
        sub.setPadding(0, Ui.dp(this, 10), 0, 0);
        linearLayout.addView(this.status);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        Button btn = Ui.btn(this, "Validate key");
        Button btn2 = Ui.btn(this, "Save & activate");
        layoutParams.topMargin = Ui.dp(this, 14);
        linearLayout2.addView(btn, layoutParams);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams2.topMargin = Ui.dp(this, 14);
        layoutParams2.leftMargin = Ui.dp(this, 8);
        linearLayout2.addView(btn2, layoutParams2);
        linearLayout.addView(linearLayout2);
        refreshStatus();
        btn.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ProviderActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                final String typedKeyOrSaved = ProviderActivity.this.typedKeyOrSaved();
                if (typedKeyOrSaved == null) {
                    ProviderActivity.this.showStatus("Paste an API key to validate (or save one first).", Ui.RED);
                    return;
                }
                ProviderActivity.this.showStatus("Validating…", Ui.DIM);
                final String trim = ProviderActivity.this.baseUrl.getText().toString().trim();
                final String trim2 = ProviderActivity.this.model.getText().toString().trim();
                Tasks.call(new Tasks.Job<Result<Boolean>>() { // from class: com.replymate.app.ui.ProviderActivity.2.1
                    /* JADX WARN: Can't rename method to resolve collision */
                    @Override // com.replymate.app.platform.Tasks.Job
                    public Result<Boolean> run() {
                        return new GeminiProvider(trim, trim2, typedKeyOrSaved, new HttpClient(), new RetryPolicy(), ProviderActivity.this.c.logger()).validateKey();
                    }
                }, new Tasks.Done<Result<Boolean>>() { // from class: com.replymate.app.ui.ProviderActivity.2.2
                    @Override // com.replymate.app.platform.Tasks.Done
                    public void accept(Result<Boolean> result) {
                        if (!result.ok || !Boolean.TRUE.equals(result.value)) {
                            ProviderActivity.this.showStatus("Invalid: " + String.valueOf(result.error), Ui.RED);
                        } else {
                            ProviderActivity.this.c.registerSensitive(typedKeyOrSaved);
                            ProviderActivity.this.showStatus("Key valid ✓ — model is reachable.", Ui.GREEN);
                        }
                    }
                });
            }
        });
        btn2.setOnClickListener(new View.OnClickListener() { // from class: com.replymate.app.ui.ProviderActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                String trim = ProviderActivity.this.keyInput.getText().toString().trim();
                if (!trim.isEmpty()) {
                    ProviderActivity.this.c.vault().putSecret(ProviderActivity.KEY_REF, trim);
                    ProviderActivity.this.c.registerSensitive(trim);
                }
                if (!ProviderActivity.this.c.vault().hasSecret(ProviderActivity.KEY_REF)) {
                    ProviderActivity.this.showStatus("Save an API key before activating.", Ui.RED);
                    return;
                }
                ProviderDef providerDef = new ProviderDef();
                providerDef.type = ProviderType.GEMINI;
                providerDef.label = ProviderActivity.emptyTo(ProviderActivity.this.label.getText().toString(), "Gemini");
                providerDef.baseUrl = ProviderActivity.emptyTo(ProviderActivity.this.baseUrl.getText().toString(), "https://generativelanguage.googleapis.com");
                providerDef.modelName = ProviderActivity.emptyTo(ProviderActivity.this.model.getText().toString(), "gemini-2.5-flash");
                providerDef.keyRef = ProviderActivity.KEY_REF;
                providerDef.createdAt = ProviderActivity.this.c.clock().now();
                ProviderActivity.this.c.providers().upsertActive(providerDef);
                ProviderActivity.this.c.invalidateProvider();
                ProviderActivity.this.keyInput.setText("");
                ProviderActivity.this.refreshStatus();
                Toast.makeText(ProviderActivity.this, "Provider activated", 0).show();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String typedKeyOrSaved() {
        String trim = this.keyInput.getText().toString().trim();
        return !trim.isEmpty() ? trim : this.c.vault().getSecret(KEY_REF);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshStatus() {
        boolean hasSecret = this.c.vault().hasSecret(KEY_REF);
        if (this.c.providerOrNull() != null) {
            showStatus("Active ✓ · key stored encrypted (" + String.valueOf(this.c.activeModelOrNull()) + ")", Ui.GREEN);
        } else if (hasSecret) {
            showStatus("Key stored encrypted — tap Save & activate.", Ui.DIM);
        } else {
            showStatus("Not configured.", Ui.DIM);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showStatus(String str, int i) {
        this.status.setText(str);
        this.status.setTextColor(i);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String emptyTo(String str, String str2) {
        return (str == null || str.trim().isEmpty()) ? str2 : str.trim();
    }
}
