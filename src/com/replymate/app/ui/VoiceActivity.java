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
import com.replymate.core.style.StyleControls;
import com.replymate.core.style.StyleSettings;
import java.util.HashMap;
import java.util.Map;

/** P4 — "My voice": the GLOBAL user voice, base style for every chat.
 *  Two ways to shape it, both local and instant:
 *    1) PRESET CONTROLS — the 9 controls (tone, length, emoji, formality, humor,
 *       confidence, slang, flirting, follow-ups), three levels each, tap to cycle.
 *    2) CUSTOM INSTRUCTIONS — the owner's own free-text style instructions, added
 *       to every generated reply (capped at 400 chars).
 *  Storing rule: tapping a control back to the shipped default REMOVES the stored
 *  row, so "defaults (not customized)" stays an honest, recoverable state (audit). */
public final class VoiceActivity extends Activity {

    private AppContainer c;
    /** Live copy of the global rows (key → "0"|"1"|"2" or custom text); mirrored to the store. */
    private final Map<String, String> rows = new HashMap<String, String>();
    private EditText custom;
    private TextView preview;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        c = ReplyMateApp.containerOf(this);
        rows.putAll(c.styleSettings().all(null));

        LinearLayout root = (LinearLayout) findViewById(R.id.voice_root);
        TextView header = Ui.tv(this, "‹ My voice", 22.0f, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 6));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);
        root.addView(Ui.sub(this,
            "Your base style for every reply. A contact can override any preset from their own screen. Everything here is stored only on this phone."));
        root.addView(Ui.divider(this));

        preview = Ui.sub(this, "");
        preview.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        root.addView(preview);
        root.addView(Ui.divider(this));

        root.addView(Ui.label(this, "PRESET CONTROLS — TAP TO CHANGE"));
        for (StyleControls.Control control : StyleControls.all()) {
            root.addView(controlRow(control));
            root.addView(Ui.divider(this));
        }

        root.addView(Ui.label(this, "YOUR OWN STYLE INSTRUCTIONS (OPTIONAL)"));
        root.addView(Ui.sub(this,
            "Write anything the presets don't cover — e.g. \"I never use full stops\", \"always answer with one question back\". Saved automatically, added to every reply (max 400 characters)."));
        custom = Ui.field(this, "e.g. I never use full stops; keep energy high but short…", true);
        custom.setText(StyleSettings.customPrompt(rows));
        root.addView(custom);

        Button reset = Ui.btn(this, "Reset all to defaults");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        root.addView(reset, lp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetAll(); }
        });

        refreshPreview();
    }

    @Override protected void onPause() {
        super.onPause();
        persistCustom();        // custom instructions survive the ‹ back path too
    }

    private View controlRow(final StyleControls.Control control) {
        final LinearLayout row = Ui.row(this, control.label, "");
        Ui.setRowSub(row, levelText(control));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int current = effective(control);
                int next = (current + 1) % 3;
                if (next == StyleControls.defaultLevel(control.key)) {
                    rows.remove(control.key);
                    c.styleSettings().remove(null, control.key);
                } else {
                    rows.put(control.key, String.valueOf(next));
                    c.styleSettings().put(null, control.key, String.valueOf(next));
                }
                Ui.setRowSub(row, levelText(control));
                refreshPreview();
            }
        });
        return row;
    }

    private int effective(StyleControls.Control control) {
        Integer level = StyleSettings.level(rows, control.key);
        return level != null ? level.intValue() : StyleControls.defaultLevel(control.key);
    }

    private String levelText(StyleControls.Control control) {
        boolean isDefault = StyleSettings.level(rows, control.key) == null;
        return control.levelLabel(effective(control)) + (isDefault ? "  (default)" : "");
    }

    /** Custom instructions → global style_setting row (empty removes the row). */
    private void persistCustom() {
        if (custom == null) return;
        String text = StyleSettings.customPrompt(
            java.util.Collections.singletonMap(StyleSettings.CUSTOM_PROMPT_KEY,
                custom.getText().toString()));
        String stored = StyleSettings.customPrompt(rows);
        if (text.equals(stored)) return;
        if (text.isEmpty()) {
            rows.remove(StyleSettings.CUSTOM_PROMPT_KEY);
            c.styleSettings().remove(null, StyleSettings.CUSTOM_PROMPT_KEY);
        } else {
            rows.put(StyleSettings.CUSTOM_PROMPT_KEY, text);
            c.styleSettings().put(null, StyleSettings.CUSTOM_PROMPT_KEY, text);
        }
        refreshPreview();
    }

    /** Live preview of the exact rules the AI will receive. */
    private void refreshPreview() {
        int[] levels = StyleSettings.resolve(rows, null);
        StringBuilder sb = new StringBuilder("The AI is told:\n")
            .append(StyleSettings.renderVoiceLine(levels));
        String own = StyleSettings.customPrompt(rows);
        if (!own.isEmpty()) {
            sb.append("\n+ your instructions: \"").append(own).append('"');
        }
        preview.setText(sb.toString());
    }

    private void resetAll() {
        boolean cleared = false;
        for (StyleControls.Control control : StyleControls.all()) {
            if (StyleSettings.level(rows, control.key) != null) {
                rows.remove(control.key);
                c.styleSettings().remove(null, control.key);
                cleared = true;
            }
        }
        if (!StyleSettings.customPrompt(rows).isEmpty()) {
            rows.remove(StyleSettings.CUSTOM_PROMPT_KEY);
            c.styleSettings().remove(null, StyleSettings.CUSTOM_PROMPT_KEY);
            cleared = true;
        }
        Toast.makeText(this, cleared ? "Voice reset to defaults" : "Already at defaults",
            Toast.LENGTH_SHORT).show();
        if (cleared) recreate();
    }
}
