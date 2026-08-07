package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;

/** Settings → AI providers (P-polish): the LIST of configured providers. Tap a row to
 *  edit it; the ✓ badge marks the active one (tap activates). "Add provider" opens the
 *  editor with a type picker. API keys never render here — only a saved/missing hint. */
public final class ProviderActivity extends Activity {

    private AppContainer c;
    private LinearLayout root;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider);
        c = ReplyMateApp.containerOf(this);
        root = (LinearLayout) findViewById(R.id.provider_root);

        TextView header = Ui.tv(this, "‹ AI providers", 22.0f, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 6));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);
        root.addView(Ui.sub(this,
            "Bring a key from any provider — keys are encrypted in this device's hardware keystore and only ever go to that provider's endpoint. Tap a provider to make it active."));
        root.addView(Ui.divider(this));
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        // keep header (index 0..2: title, sub, divider), rebuild the rest
        while (root.getChildCount() > 3) root.removeViewAt(3);

        java.util.List<ProviderDef> all = c.providers().all();
        if (all.isEmpty()) {
            TextView none = Ui.sub(this, "No providers yet — add one to start generating replies.");
            none.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            root.addView(none);
        } else {
            for (final ProviderDef def : all) {
                root.addView(providerRow(def));
                root.addView(Ui.divider(this));
            }
        }

        LinearLayout add = Ui.row(this, "＋ Add provider",
            "Gemini · OpenAI · OpenRouter · Anthropic · DeepSeek · Grok · Kimi · Mistral · Ollama · any OpenAI-compatible API");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ProviderActivity.this, ProviderEditActivity.class));
            }
        });
        root.addView(add);
    }

    private View providerRow(final ProviderDef def) {
        boolean hasKey = !def.type.needsKey
            || (!def.keyRef.isEmpty() && c.vault().hasSecret(def.keyRef));
        StringBuilder sub = new StringBuilder();
        sub.append(def.modelName.isEmpty() ? "no model selected" : def.modelName);
        sub.append(def.type.needsKey ? (hasKey ? " · key saved ✓" : " · key missing!")
            : " · no key needed");
        if (def.isActive) sub.append("  ● ACTIVE");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(16);
        row.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
        String title = (def.label.isEmpty() ? def.type.label : def.label)
            + "  ·  " + def.type.label;
        texts.addView(Ui.tv(this, title, 16.0f, Ui.PRIMARY));
        TextView subTv = Ui.sub(this, sub.toString());
        if (!hasKey) subTv.setTextColor(Ui.RED);
        else if (def.isActive) subTv.setTextColor(Ui.GREEN);
        subTv.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(subTv);
        row.addView(Ui.tv(this, "›", 22.0f, Ui.DIM));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!def.isActive) {
                    c.providers().setActive(def.id);
                    c.invalidateProvider();
                }
                Intent i = new Intent(ProviderActivity.this, ProviderEditActivity.class);
                i.putExtra(ProviderEditActivity.EXTRA_PROVIDER_TYPE, def.type.wire);
                startActivity(i);
            }
        });
        return row;
    }
}
