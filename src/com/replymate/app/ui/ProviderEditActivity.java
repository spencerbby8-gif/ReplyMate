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
    private TextView status, keyHint, urlHint, testBtn;
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
        urlHint = Ui.sub(this, "");
        urlHint.setPadding(0, Ui.dp(this, 2), 0, 0);
        root.addView(urlHint);

        root.addView(Ui.label(this, "Model"));
        model = Ui.field(this, "pick below or type a model id", false);
        root.addView(model);
        Button discover = Ui.btn(this, "Discover & test models (live)");
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

        // P-intelligence-3: privacy mode setup for THIS provider's account — the
        // built-in key is pinned FREE; an owner's own key offers the paid/private
        // plan switch (the honest detection input, since providers expose no
        // billing-read API). Sits directly under the key it describes.
        setupPrivacyRows(root);

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
        applyBaseUrlRule();
        renderCachedStatuses();   // cached model badges (with "tested <when>") survive restarts
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
                applyBaseUrlRule();
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
        if (existing == null && label.getText().toString().trim().isEmpty()) {
            label.setText(type.label);
        }
        applyBaseUrlRule();
        keyInput.setHint(type.needsKey
            ? "Paste key here (never shown again after saving)" : "No key needed for this provider");
        keyInput.setEnabled(type.needsKey);
    }

    /** P-editor-url: known providers keep their official endpoint (auto-filled, field
     *  locked) — never the previous provider's URL. Only "Other (OpenAI-compatible)"
     *  edits the base URL by hand. Enforced on chip select, screen load and save. */
    private void applyBaseUrlRule() {
        if (existing == null || !type.baseUrlEditable()) {
            baseUrl.setText(ProviderType.resolveBaseUrlForUi(type,
                baseUrl.getText().toString()));
        }
        baseUrl.setEnabled(type.baseUrlEditable());
        baseUrl.setFocusable(type.baseUrlEditable());
        urlHint.setText(type.baseUrlEditable()
            ? "Any OpenAI-compatible endpoint: local servers, gateways, or providers not in the list."
            : "Official endpoint from the provider's docs — fixed automatically.");
    }

    private void setChipsEnabled(boolean enabled) {
        for (TextView tv : chips.values()) tv.setEnabled(enabled);
    }

    /* ------------------------------------------------- P-intelligence-3 privacy */

    private Ui.ToggleRow planRow;

    /** Mode rows under the API key: built-in ⇒ pinned FREE (solo-builder by
     *  design); owner's key ⇒ paid/private plan switch (the honest manual input —
     *  providers ship no official billing-read API, and the owner mandated mode
     *  detection from the account setup, never from "a key exists"). */
    private void setupPrivacyRows(LinearLayout root) {
        boolean builtIn = existing != null
            && com.replymate.core.privacy.ProviderPrivacy.BUILT_IN_KEY_REF.equals(
                existing.keyRef);
        if (builtIn) {
            TextView note = Ui.sub(this,
                "This row runs on the built-in ReplyMate key (solo-builder mode) — a"
                + " FREE, shared provider key: prompts and replies may be used to"
                + " improve the provider's models. Paste YOUR OWN key below (it will"
                + " replace the built-in one on save) to turn this into your own"
                + " setup — then the paid/private switch appears.");
            note.setTextColor(Ui.RED);
            note.setPadding(0, Ui.dp(this, 4), 0, 0);
            root.addView(note);
            keyInput.setHint("built-in ReplyMate key active — paste your own key to take over");
            return;
        }
        if (existing != null && existing.type.needsKey) {
            boolean paid = "paid".equals(c.kv().get(
                com.replymate.core.privacy.ProviderPrivacy.planKey(existing.id), ""));
            planRow = Ui.toggleRow(this, "Paid/private plan", planSub(paid));
            planRow.sw.setChecked(paid);
            planRow.sw.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(
                            android.widget.CompoundButton b, boolean on) {
                        c.kv().put(com.replymate.core.privacy.ProviderPrivacy
                            .planKey(existing.id), on ? "paid" : "free");
                        c.invalidateProvider();
                        Ui.setRowSub(planRow.row, planSub(on));
                    }
                });
            root.addView(planRow.row);
            return;
        }
        if (existing == null && type.needsKey) {
            TextView note = Ui.sub(this,
                "Privacy: a key starts in FREE mode (prompts/replies may help improve"
                + " the provider's models). If your account is paid/private, save first"
                + " — the \"Paid/private plan\" switch appears on this row, and the"
                + " Home banner turns private.");
            note.setPadding(0, Ui.dp(this, 4), 0, 0);
            root.addView(note);
        }
    }

    private String planSub(boolean paid) {
        return paid
            ? "on — paid/private by your account; prompts aren't used to train models"
                + " (per provider terms)"
            : "off — free/unpaid: prompts and replies may be used to improve the"
                + " provider's models";
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
        d.baseUrl = type.baseUrlEditable()
            ? baseUrl.getText().toString().trim()
            : type.defaultBaseUrl;
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
                classifyAndRender(r.value);
            }
        });
    }

    /* ------------------------------------------------ model classification (live) */

    private String cacheKey() {
        return "modelstatus." + type.wire + "."
            + Integer.toHexString(baseUrl.getText().toString().trim().hashCode());
    }

    /** Discover → actively test EVERY model with a tiny live request, badge each from
     *  the real response, cache results locally (kv), and never list a known-fail
     *  model above a working one (classifier sorts). */
    private void classifyAndRender(final java.util.List<String> models) {
        if (models.isEmpty()) {
            showStatus("Provider returned no models.", Ui.RED);
            return;
        }
        showStatus("Discovered " + models.size() + " models — testing each one live…", Ui.DIM);
        final String key = effectiveKey();
        final String base = baseUrl.getText().toString().trim();
        Tasks.call(new Tasks.Job<java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus>>() {
            @Override public java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus> run() {
                return com.replymate.provider.discovery.ModelClassifier.classify(
                    type.wire, base, models, key, new HttpClient());
            }
        }, new Tasks.Done<java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus>>() {
            @Override public void accept(java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus> statuses) {
                saveStatusCache(statuses);
                renderStatuses(statuses, "tested just now");
            }
        });
    }

    private void saveStatusCache(java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus> statuses) {
        com.replymate.core.json.JsonArr items = com.replymate.core.json.JsonArr.create();
        for (com.replymate.provider.discovery.ModelClassifier.ModelStatus st : statuses) {
            items.add(com.replymate.core.json.JsonObj.create()
                .put("m", st.model).put("b", st.badge.name()).put("n", st.note));
        }
        c.kv().put(cacheKey(), com.replymate.core.json.JsonObj.create()
            .put("checked", System.currentTimeMillis()).put("items", items).toJson());
    }

    /** Cached badges survive restarts; they always say when they were last tested. */
    private void renderCachedStatuses() {
        String raw = c.kv().get(cacheKey(), "");
        if (raw.isEmpty()) return;
        try {
            com.replymate.core.json.JsonObj root = com.replymate.core.json.Json.parseObj(raw);
            Object items = root.raw("items");
            if (!(items instanceof java.util.List)) return;
            java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus> out =
                new java.util.ArrayList<com.replymate.provider.discovery.ModelClassifier.ModelStatus>();
            for (Object o : (java.util.List<?>) items) {
                if (!(o instanceof java.util.Map)) continue;
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                out.add(new com.replymate.provider.discovery.ModelClassifier.ModelStatus(
                    String.valueOf(m.get("m")),
                    com.replymate.provider.discovery.ModelClassifier.Badge.valueOf(String.valueOf(m.get("b"))),
                    m.get("n") == null ? "" : String.valueOf(m.get("n"))));
            }
            if (!out.isEmpty()) {
                renderStatuses(out, "tested " + com.replymate.core.util.TimeFmt.dayTime(
                    (long) root.lng("checked", 0)));
            }
        } catch (RuntimeException ignored) { }
    }

    private void renderStatuses(java.util.List<com.replymate.provider.discovery.ModelClassifier.ModelStatus> statuses,
                                String when) {
        modelList.removeAllViews();
        int working = 0;
        for (com.replymate.provider.discovery.ModelClassifier.ModelStatus st : statuses) {
            if (com.replymate.provider.discovery.ModelClassifier.rank(st.badge) == 0) working++;
        }
        showStatus(statuses.size() + " models — " + working + " working on this key ("
            + when + "). Tap one to use it:", working > 0 ? Ui.GREEN : Ui.RED);
        final int hp = Ui.dp(this, 8), vp = Ui.dp(this, 6);
        for (final com.replymate.provider.discovery.ModelClassifier.ModelStatus st : statuses) {
            final int badgeColor = badgeColor(st.badge);
            // row 1: model id + badge; row 2 (note, only when it adds info)
            LinearLayout rowv = new LinearLayout(this);
            rowv.setOrientation(LinearLayout.VERTICAL);
            rowv.setPadding(hp, vp, hp, vp);
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView id = Ui.tv(this, st.model, 13,
                com.replymate.provider.discovery.ModelClassifier.rank(st.badge) <= 2 ? Ui.ACCENT : Ui.DIM);
            top.addView(id, new LinearLayout.LayoutParams(0, -2, 1f));
            final TextView badge = Ui.tv(this,
                com.replymate.provider.discovery.ModelClassifier.labelFor(st.badge), 11, badgeColor);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(badge);
            rowv.addView(top);
            if (!st.note.isEmpty() && com.replymate.provider.discovery.ModelClassifier.rank(st.badge) > 0) {
                TextView note = Ui.tv(this, st.note, 10, Ui.DIM);
                note.setPadding(0, Ui.dp(this, 2), 0, 0);
                rowv.addView(note);
            }
            modelList.addView(rowv);
            rowv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    model.setText(st.model);
                    showStatus("Model set: " + st.model + " ("
                        + com.replymate.provider.discovery.ModelClassifier.labelFor(st.badge) + ")",
                        badgeColor);
                }
            });
        }
    }

    private static int badgeColor(com.replymate.provider.discovery.ModelClassifier.Badge b) {
        switch (b) {
            case WORKING: case FREE_TIER: return Ui.GREEN;
            case PAID: return Ui.ACCENT;
            case UNKNOWN: return Ui.DIM;
            default: return Ui.RED;
        }
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
        d.baseUrl = type.baseUrlEditable()
            ? baseUrl.getText().toString().trim()
            : type.defaultBaseUrl;
        d.modelName = model.getText().toString().trim();
        String typed = keyInput.getText().toString().trim();
        if (!typed.isEmpty() || type.needsKey) {
            boolean wasBuiltIn = com.replymate.core.privacy.ProviderPrivacy
                .BUILT_IN_KEY_REF.equals(d.keyRef);
            if (d.keyRef.isEmpty()) d.keyRef = "provider." + type.wire + ".key";
            if (!typed.isEmpty()) {
                if (wasBuiltIn) {
                    // P-intelligence-3: the owner pasted THEIR OWN key over a
                    // built-in row — this is no longer the built-in setup. Move the
                    // secret to the normal per-type alias so the row detects as
                    // an owner's key (free until the plan switch says paid).
                    d.keyRef = "provider." + type.wire + ".key";
                    c.vault().deleteSecret(
                        com.replymate.core.privacy.ProviderPrivacy.BUILT_IN_KEY_REF);
                }
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
