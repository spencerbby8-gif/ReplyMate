package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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
 *  Nine controls (tone, length, emoji, formality, humor, confidence, slang,
 *  flirting, follow-ups), each with three levels. Tap a row to cycle.
 *  Storing rule: tapping back to the shipped default REMOVES the stored row, so
 *  "defaults (not customized)" stays an honest, recoverable state (audit view). */
public final class VoiceActivity extends Activity {

    private AppContainer c;
    /** Live copy of the global rows (key → "0"|"1"|"2"); mirrored to the store. */
    private final Map<String, String> rows = new HashMap<String, String>();
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
            "Your base style for every reply. A contact can override any of these from their own screen. Tap a control to change its level."));
        root.addView(Ui.divider(this));

        preview = Ui.sub(this, "");
        preview.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        root.addView(preview);
        root.addView(Ui.divider(this));

        for (StyleControls.Control control : StyleControls.all()) {
            root.addView(controlRow(control));
            root.addView(Ui.divider(this));
        }

        Button reset = Ui.btn(this, "Reset all to defaults");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        root.addView(reset, lp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetAll(); }
        });

        refreshPreview();
    }

    private View controlRow(final StyleControls.Control control) {
        final LinearLayout row = Ui.row(this, control.label, levelText(control));
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

    /** Live preview of the exact rule line the AI will receive. */
    private void refreshPreview() {
        int[] levels = StyleSettings.resolve(rows, null);
        preview.setText("The AI is told:\n" + StyleSettings.renderVoiceLine(levels));
    }

    private void resetAll() {
        for (StyleControls.Control control : StyleControls.all()) {
            if (StyleSettings.level(rows, control.key) != null) {
                rows.remove(control.key);
                c.styleSettings().remove(null, control.key);
            }
        }
        Toast.makeText(this, "Voice reset to defaults", Toast.LENGTH_SHORT).show();
        recreate();
    }
}
