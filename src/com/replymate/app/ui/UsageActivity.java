package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.model.UsageKind;
import com.replymate.core.util.TimeFmt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** S-P3b — Usage dashboard: local metering of every AI call (count + tokens),
 *  over 24h / 7d / 30d / all time. No cost guesswork — tokens are the honest unit.
 *  Data never leaves the device. */
public final class UsageActivity extends Activity {

    private static final long HOUR = 3600_000L;
    private static final long DAY = 24 * HOUR;

    private AppContainer c;
    private LinearLayout root;
    /** Selected range in ms; 0 = all time. */
    private long rangeMs = DAY;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usage);
        c = ReplyMateApp.containerOf(this);
        root = (LinearLayout) findViewById(R.id.usage_root);
        render();
    }

    private void render() {
        root.removeAllViews();

        TextView header = Ui.tv(this, "‹ Usage dashboard", 22, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 8));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);
        root.addView(Ui.sub(this, "Every AI call ReplyMate makes, metered locally. Tokens in + out."));
        root.addView(Ui.divider(this));

        addRangeChips();

        long now = c.clock().now();
        long since = rangeMs <= 0 ? 0 : now - rangeMs;
        List<UsageEvent> events = c.usage().since(since);

        long tin = 0, tout = 0;
        Map<String, long[]> byModel = new LinkedHashMap<String, long[]>();
        Map<UsageKind, int[]> byKind = new LinkedHashMap<UsageKind, int[]>();
        for (UsageEvent e : events) {
            tin += e.tokensIn;
            tout += e.tokensOut;
            long[] m = byModel.get(e.model);
            if (m == null) { m = new long[3]; byModel.put(e.model == null ? "?" : e.model, m); }
            m[0]++; m[1] += e.tokensIn; m[2] += e.tokensOut;
            int[] k = byKind.get(e.kind);
            if (k == null) { k = new int[3]; byKind.put(e.kind, k); }
            k[0]++; k[1] += e.tokensIn; k[2] += e.tokensOut;
        }

        root.addView(Ui.label(this, "Summary (" + rangeLabel() + ")"));
        if (events.isEmpty()) {
            root.addView(Ui.sub(this, "No AI calls in this range yet."));
        } else {
            TextView total = Ui.tv(this,
                events.size() + " calls · "
                    + fmt(tin) + " in · " + fmt(tout) + " out · "
                    + fmt(tin + tout) + " total tokens",
                16, Ui.PRIMARY);
            total.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
            root.addView(total);
        }

        if (!byModel.isEmpty()) {
            root.addView(Ui.label(this, "By model"));
            for (Map.Entry<String, long[]> e : byModel.entrySet()) {
                long[] m = e.getValue();
                root.addView(statLine(e.getKey(), m[0], m[1], m[2]));
            }
        }

        if (!byKind.isEmpty()) {
            root.addView(Ui.label(this, "By feature"));
            for (Map.Entry<UsageKind, int[]> e : byKind.entrySet()) {
                int[] k = e.getValue();
                root.addView(statLine(kindLabel(e.getKey()), k[0], k[1], k[2]));
            }
        }

        if (!events.isEmpty()) {
            root.addView(Ui.label(this, "Recent calls"));
            int shown = 0;
            for (UsageEvent e : events) {
                if (shown++ >= 20) {
                    root.addView(Ui.sub(this, "… and " + (events.size() - 20) + " more"));
                    break;
                }
                root.addView(Ui.sub(this, TimeFmt.dayTime(e.ts) + " — "
                    + kindLabel(e.kind) + " · " + String.valueOf(e.model)
                    + " · " + e.tokensIn + "/" + e.tokensOut + " tok"));
            }
        }
    }

    private void addRangeChips() {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(chips);
        addChip(chips, "24h", DAY);
        addChip(chips, "7d", 7 * DAY);
        addChip(chips, "30d", 30 * DAY);
        addChip(chips, "All", 0);
    }

    private void addChip(LinearLayout parent, String label, final long ms) {
        TextView chip = Ui.tv(this, label, 13,
            ms == rangeMs ? Ui.PRIMARY : Ui.DIM);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackgroundResource(R.drawable.bg_field);
        int hp = Ui.dp(this, 12), vp = Ui.dp(this, 6);
        chip.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        parent.addView(chip, lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                rangeMs = ms;
                render();
            }
        });
    }

    private View statLine(String name, long calls, long tin, long tout) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        TextView nameTv = Ui.tv(this, name, 14, Ui.PRIMARY);
        row.addView(nameTv, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.sub(this, calls + " calls · " + fmt(tin + tout) + " tok"));
        return row;
    }

    private String rangeLabel() {
        if (rangeMs == DAY) return "last 24 hours";
        if (rangeMs == 7 * DAY) return "last 7 days";
        if (rangeMs == 30 * DAY) return "last 30 days";
        return "all time";
    }

    private static String kindLabel(UsageKind k) {
        if (k == null) return "other";
        switch (k) {
            case REPLY: return "reply drafts";
            case SUMMARY: return "summaries";
            case EXTRACT: return "fact extraction";
            case STYLE: return "style";
            default: return k.wire;
        }
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }
}
