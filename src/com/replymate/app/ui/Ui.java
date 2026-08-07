package com.replymate.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;


public final class Ui {
    public static final int PRIMARY = -1;
    public static final int DIM = Color.rgb(170, 178, 189);
    public static final int ACCENT = Color.rgb(10, 132, 255);
    public static final int RED = Color.rgb(255, 107, 107);
    public static final int GREEN = Color.rgb(63, 185, 80);

    private Ui() {
    }

    public static int dp(Context context, int i) {
        return (int) TypedValue.applyDimension(1, i, context.getResources().getDisplayMetrics());
    }

    public static TextView tv(Context context, String str, float f, int i) {
        TextView textView = new TextView(context);
        textView.setText(str);
        textView.setTextSize(2, f);
        textView.setTextColor(i);
        return textView;
    }

    public static TextView title(Context context, String str) {
        TextView tv = tv(context, str, 22.0f, -1);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    public static TextView sub(Context context, String str) {
        return tv(context, str, 13.0f, DIM);
    }

    public static LinearLayout row(Context context, String str, String str2) {
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(0, dp(context, 14), 0, dp(context, 14));
        LinearLayout linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(1);
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout2.addView(tv(context, str, 16.0f, -1));
        if (str2 != null && !str2.isEmpty()) {
            TextView sub = sub(context, str2);
            sub.setPadding(0, dp(context, 2), 0, 0);
            linearLayout2.addView(sub);
            linearLayout.setTag(R.id.row_subtitle, sub);
        }
        linearLayout.addView(tv(context, "›", 22.0f, DIM));
        return linearLayout;
    }

    public static void setRowSub(View view, String str) {
        Object tag = view.getTag(R.id.row_subtitle);
        if (tag instanceof TextView) {
            ((TextView) tag).setText(str);
        }
    }

    public static Button btn(Context context, String str) {
        Button button = new Button(context);
        button.setText(str);
        return button;
    }

    public static EditText field(Context context, String str, boolean z) {
        EditText editText = new EditText(context);
        editText.setHint(str);
        editText.setHintTextColor(DIM);
        editText.setTextColor(-1);
        editText.setTextSize(2, 15.0f);
        editText.setBackgroundResource(R.drawable.bg_field);
        editText.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        if (z) {
            editText.setMinLines(2);
            editText.setMaxLines(6);
            editText.setGravity(48);
        }
        return editText;
    }

    public static TextView label(Context context, String str) {
        TextView tv = tv(context, str, 12.0f, DIM);
        tv.setPadding(0, dp(context, 12), 0, dp(context, 4));
        return tv;
    }

    public static View divider(Context context) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        view.setBackgroundColor(Color.rgb(42, 49, 57));
        return view;
    }

    /** P-ux-fix-2: a row that IS a toggle and LOOKS like one — title/subtitle left,
     *  a real Switch right (no misleading "›" chevron). Tapping the row flips the
     *  switch; behavior is wired via the returned switch's checked-change listener. */
    public static final class ToggleRow {
        public final LinearLayout row;
        public final android.widget.Switch sw;
        ToggleRow(LinearLayout row, android.widget.Switch sw) {
            this.row = row;
            this.sw = sw;
        }
    }

    public static ToggleRow toggleRow(Context ctx, String title, String subText) {
        final ToggleRow tr;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(ctx, 4), dp(ctx, 10), 0, dp(ctx, 10));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(1);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        col.addView(tv(ctx, title, 16.0f, -1));
        TextView subV = sub(ctx, subText);
        subV.setPadding(0, dp(ctx, 2), 0, 0);
        col.addView(subV);
        row.setTag(R.id.row_subtitle, subV);
        android.widget.Switch sw = new android.widget.Switch(ctx);
        sw.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(sw);
        tr = new ToggleRow(row, sw);
        // Default whole-row behavior: flip the switch, but never force it while
        // disabled (CompoundButton.toggle() ignores the enabled state otherwise).
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tr.sw.isEnabled()) tr.sw.toggle();
            }
        });
        return tr;
    }

    /** Callback for showLevelPicker: level 0..2, or -1 for the "inherit" row. */
    public interface LevelPick {
        void onPick(int level);
    }

    /** P-ux-fix: a REAL selection screen for a style control. Every option is its own
     *  tappable row showing the level name (✓ marks the current choice), a plain-English
     *  line of what it does, and a concrete example reply — no vague labels, no dead
     *  rows. {@code inheritLabel} (when non-null) adds a top row that clears a
     *  per-contact override so the global setting applies again (picked as -1). */
    public static void showLevelPicker(Context ctx, String titleText,
                                       com.replymate.core.style.StyleControls.Control ctl,
                                       int currentLevel, String inheritLabel,
                                       final LevelPick cb) {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx).create();
        dlg.setTitle(titleText);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(1);
        int pad = dp(ctx, 16);
        col.setPadding(pad, dp(ctx, 2), pad, dp(ctx, 10));
        if (inheritLabel != null) {
            col.addView(pickRow(ctx, dlg, "↩ " + inheritLabel,
                "clear this contact's choice — the global setting applies",
                null, currentLevel == -1, -1, cb));
            col.addView(divider(ctx));
        }
        for (int i = 0; i < 3; i++) {
            col.addView(pickRow(ctx, dlg,
                capitalize(ctl.levelLabel(i)),
                ctl.levelDesc(i),
                "e.g. \"" + ctl.levelExample(i) + "\"",
                currentLevel == i, i, cb));
            if (i < 2) col.addView(divider(ctx));
        }
        dlg.setView(col);
        dlg.show();
    }

    private static View pickRow(Context ctx, final android.app.AlertDialog dlg,
                                String name, String desc, String example,
                                boolean current, final int level, final LevelPick cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(1);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        TextView head = tv(ctx, (current ? "✓ " : "") + name, 16.0f, current ? ACCENT : -1);
        if (current) head.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(head);
        TextView d = sub(ctx, desc);
        d.setPadding(0, dp(ctx, 2), 0, 0);
        row.addView(d);
        if (example != null) {
            TextView ex = sub(ctx, example);
            ex.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            ex.setPadding(0, dp(ctx, 2), 0, 0);
            row.addView(ex);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onPick(level);
                dlg.dismiss();
            }
        });
        return row;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
