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
}
