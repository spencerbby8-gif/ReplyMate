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
import com.replymate.core.ports.AiProvider;
import com.replymate.core.util.Result;
import com.replymate.provider.ProviderFactory;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.RetryPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provider editor (P-polish provider abstraction): type chips → base URL (editable;
 *  defaulted per official docs, never a model name) → model via LIVE discovery or free
 *  text → encrypted key vault → live connection test → save/(de)activate → delete. */
public final class ProviderEditActivity extends Activity {

    public static final String EXTRA_PROVIDER_TYPE = "provider_type";

    private AppContainer c;
    private ProviderType type = ProviderType.GEMINI;
    private ProviderDef existing;
    private EditText label, baseUrl, model, keyInput;
    private TextView status, keyHint;
    private LinearLayout modelList;
    private final Map<ProviderType, TextView> chips =
        new HashMap<ProviderType, TextView>();
    private boolean deleteArmed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_edit);
        c = ReplyMateApp.containerOf(this);
        LinearLayout root = (LinearLayout) findViewById(R.id.provider_edit_root);

        String wire = getIntent().getStringExtra(EXTRA_PROVIDER_TYPE);
        if (wire != null) type = ProviderType.fromWire(wire);
        for (ProviderDef d : c.providers().all()) {
            if (d.type == type) { existing = d; break; }
        }

        TextView header = Ui.tv(this, this.existing == null ? "‹ Add provider" : "‹ Edit provider",
            22.0f, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 6));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);

        // --- provider type chips (single select; locked when editing an existing row)
        root.addView(Ui.label(this, "PROVIDER"));
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.VERTICAL);
        ProviderType[] all = ProviderType.values();
        for (int i = 0; i < all.length; i += 2) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < all.length; j++) {
                line.addView(chip(all[j]), new LinearLayout.LayoutParams(
                    0, -2, 1.0f));
            }
            chipsRow.addView(line);
        }
        root.addView(chipsRow);

        root.addView(Ui.label(this, "Display name"));
        label = Ui.field(this, "e.g. My OpenRouter", false);
        root.addView(label);

        root.addView(Ui.label(this, "Base URL"));
        baseUrl = Ui.field(this, "https://…", false);
        root.addView(baseUrl);
        TextView urlHint = Ui.sub(this, "Pre-filled from the provider's official docs — edit only for self-hosted or gateway setups.");
        urlHint.setPadding(0, Ui.dp(this, 2), 0, 0);
        root.addView(urlHint);

        root.addView(Ui.label(this, "Model"));
        model = Ui.field(this, "pick below or type a model id", false);
        root.addView(model);
        Button discover = Ui.btn(this, "Discover models (live)");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = Ui.dp(this, 8);
        root.addView(discover, dlp);
        modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);
        root.addView(modelList);

        root.addView(Ui.label(this, "API key"));
        keyInput = Ui.field(this, "Paste key here (never shown again after saving)", false);
        keyInput.setInputType(129);
        root.addView(keyInput);
        keyHint = Ui.sub(this, "");
        keyHint.setPadding(0, Ui.dp(this, 2), 0, 0);
        root.addView(keyHint);

        status = Ui.sub(this, "");
        status.setTextIsSelectable(true);   // audit: raw provider diagnostics must be copyable
        status.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        alp.topMargin = Ui.dp(this, 14);
        Button test = Ui.btn(this, "Test connection");
        actions.addView(test, new LinearLayout.LayoutParams(alp));
        Button save = Ui.btn(this, "Save & activate");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(alp);
        slp.leftMargin = Ui.dp(this, 8);
        actions.addView(save, slp);
        root.addView(actions);

        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        discover.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { discover(); }
        });

        if (existing != null) {
            label.setText(existing.label);
            baseUrl.setText(existing.baseUrl);
            model.setText(existing.modelName);
            selectChip(type);
            setChipsEnabled(false);       // type is the row's identity on edit
            refreshKeyHint();
            final Button delete = dangerBtn("Delete this provider");
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(-1, -2);
            delp.topMargin = Ui.dp(this, 16);
            root.addView(delete, delp);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { deleteProvider(delete); }
            });
        } else {
            selectChip(type);
            refreshKeyHint();
        }
    }

    /* ------------------------------------------------------------------ chips */

    private TextView chip(final ProviderType t) {
        TextView chip = Ui.tv(this, t.label, 12, Ui.DIM);
        chip.setBackgroundResource(R.drawable.bg_field);
        chip.setGravity(android.view.Gravity.CENTER);
        int hp = Ui.dp(this, 6), vp = Ui.dp(this, 10);
        chip.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.weight = 1;
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
        chip.setLayoutParams(lp);
        chips.put(t, chip);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                type = t;
                selectChip(t);
                if (existing == null && baseUrl.getText().toString().isEmpty()
                        && !t.defaultBaseUrl.isEmpty()) {
                    baseUrl.setText(t.defaultBaseUrl);
                }
                if (label.getText().toString().isEmpty()) label.setText(t.label);
                refreshKeyHint();
            }
        });
        return chip;
    }

    private void selectChip(ProviderType t) {
        for (Map.Entry<ProviderType, TextView> e : chips.entrySet()) {
            e.getValue().setTextColor(e.getKey() == t ? Ui.ACCENT : Ui.DIM);
        }
        if (existing == null && !type.defaultBaseUrl.isEmpty()
                && baseUrl.getText().toString().trim().isEmpty()) {
            baseUrl.setText(type.defaultBaseUrl);
        }
        if (existing == null && label.getText().toString().trim().isEmpty()) {
            label.setText(type.label);
        }
        keyInput.setHint(type.needsKey
            ? "Paste key here (never shown again after saving)" : "No key needed for this provider");
        keyInput.setEnabled(type.needsKey);
    }

    private void setChipsEnabled(boolean enabled) {
        for (TextView tv : chips.values()) tv.setEnabled(enabled);
    }

    private void refreshKeyHint() {
        if (!type.needsKey) {
            keyHint.setText("Ollama and local servers don't use keys — connection test will check reachability.");
            return;
        }
        boolean saved = existing != null && !existing.keyRef.isEmpty()
            && c.vault().hasSecret(existing.keyRef);
        keyHint.setText(saved
            ? "A key is already saved (encrypted). Leave the field empty to keep it, or paste a new one to replace."
            : "Where do I get a key? From the provider's own console — links are on their official sites.");
    }

    /* ------------------------------------------------------------ async actions */

    private AiProvider formProvider(String key) {
        ProviderDef d = new ProviderDef();
        d.type = type;
        d.baseUrl = baseUrl.getText().toString().trim();
        d.modelName = model.getText().toString().trim();
        return ProviderFactory.build(d, key, new HttpClient(), new RetryPolicy(), c.logger());
    }

    private String effectiveKey() {
        String typed = keyInput.getText().toString().trim();
        if (!typed.isEmpty()) return typed;
        if (type.needsKey && existing != null && !existing.keyRef.isEmpty()) {
            String saved = c.vault().getSecret(existing.keyRef);
            return saved == null ? "" : saved;
        }
        return "";
    }

    private void discover() {
        final String key = effectiveKey();
        if (type.needsKey && key.isEmpty()) {
            showStatus("Paste your API key first (or save one).", Ui.RED);
            return;
        }
        showStatus("Discovering models…", Ui.DIM);
        modelList.removeAllViews();
        final AiProvider provider = formProvider(key);
        Tasks.call(new Tasks.Job<Result<List<String>>>() {
            @Override public Result<List<String>> run() { return provider.listModels(); }
        }, new Tasks.Done<Result<List<String>>>() {
            @Override public void accept(Result<List<String>> r) {
                if (!r.ok) {
                    LastProviderError.save(c, r.error);
                    showStatus("Couldn't list models:\n" + r.error, Ui.RED);
                    return;
                }
                showStatus(r.value.size() + " models — tap one to use it:", Ui.GREEN);
                for (final String id : r.value) {
                    TextView m = Ui.tv(ProviderEditActivity.this, id, 13, Ui.ACCENT);
                    int hp = Ui.dp(ProviderEditActivity.this, 8);
                    m.setPadding(hp, Ui.dp(ProviderEditActivity.this, 6), hp,
                        Ui.dp(ProviderEditActivity.this, 6));
                    modelList.addView(m);
                    m.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            model.setText(id);
                            showStatus("Model set: " + id, Ui.GREEN);
                        }
                    });
                }
            }
        });
    }

    private void testConnection() {
        final String key = effectiveKey();
        if (type.needsKey && key.isEmpty()) {
            showStatus("Paste an API key to test (or save one first).", Ui.RED);
            return;
        }
        if (baseUrl.getText().toString().trim().isEmpty()) {
            showStatus("Base URL is empty.", Ui.RED);
            return;
        }
        showStatus("Testing…", Ui.DIM);
        final AiProvider provider = formProvider(key);
        Tasks.call(new Tasks.Job<Result<Boolean>>() {
            @Override public Result<Boolean> run() { return provider.validateKey(); }
        }, new Tasks.Done<Result<Boolean>>() {
            @Override public void accept(Result<Boolean> r) {
                if (!r.ok || !Boolean.TRUE.equals(r.value)) {
                    LastProviderError.save(c, r.error);
                    showStatus("Connection failed:\n" + r.error, Ui.RED);
                } else {
                    if (!key.isEmpty()) c.registerSensitive(key);
                    showStatus("Connection OK ✓ — provider is reachable"
                        + (type.needsKey ? " and the key works." : "."), Ui.GREEN);
                }
            }
        });
    }

    private void save() {
        if (baseUrl.getText().toString().trim().isEmpty()) {
            showStatus("Base URL is required.", Ui.RED);
            return;
        }
        ProviderDef d = existing != null ? existing : new ProviderDef();
        d.type = type;
        d.label = label.getText().toString().trim();
        d.baseUrl = baseUrl.getText().toString().trim();
        d.modelName = model.getText().toString().trim();
        String typed = keyInput.getText().toString().trim();
        if (!typed.isEmpty() || type.needsKey) {
            if (d.keyRef.isEmpty()) d.keyRef = "provider." + type.wire + ".key";
            if (!typed.isEmpty()) {
                c.vault().putSecret(d.keyRef, typed);
                c.registerSensitive(typed);
            }
        }
        if (type.needsKey && !c.vault().hasSecret(d.keyRef)) {
            showStatus("This provider needs an API key.", Ui.RED);
            return;
        }
        c.providers().upsertActive(d);
        c.invalidateProvider();
        Toast.makeText(this, "Saved & activated", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void deleteProvider(Button delete) {
        if (!deleteArmed) {
            deleteArmed = true;
            delete.setText("Really remove " + type.label
                + "? Its saved key is wiped too. Tap again to confirm.");
            return;
        }
        if (existing != null) {
            if (!existing.keyRef.isEmpty()) c.vault().deleteSecret(existing.keyRef);
            c.providers().delete(existing.id);
            c.invalidateProvider();
        }
        Toast.makeText(this, "Provider removed", Toast.LENGTH_SHORT).show();
        finish();
    }

    private Button dangerBtn(String text) {
        Button b = Ui.btn(this, text);
        b.setTextColor(Ui.RED);
        return b;
    }

    private void showStatus(String msg, int color) {
        status.setText(msg);
        status.setTextColor(color);
    }

    /** Error text + actionable hint (mirrors Conversation UX, offline-friendly). */
}
