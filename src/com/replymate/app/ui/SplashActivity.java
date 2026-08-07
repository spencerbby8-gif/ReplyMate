package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;

/** P-ux-fix-2 launch animation: the app icon is the visual center — it springs in
 *  (scale + fade, soft overshoot), the wordmark rises under it, a thin accent pulse
 *  finishes the beat, and we route onward. Total ~720ms: short, fast, no work
 *  deferred to this screen. Routing target: Home today; the auth gate (1.2.0)
 *  hooks into route() so gating never changes the animation. */
public final class SplashActivity extends Activity {

    private static final long MIN_SHOW_MS = 720;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final long start = SystemClock.uptimeMillis();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(0x0D, 0x11, 0x17));   // @color/bg — seamless

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setContentDescription("ReplyMate");
        int size = Ui.dp(this, 108);
        root.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView name = Ui.tv(this, "ReplyMate", 24.0f, Ui.PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.dp(this, 14);
        root.addView(name, nlp);

        TextView tag = Ui.sub(this, "private · on-device · your voice");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 4);
        root.addView(tag, tlp);

        // thin accent pulse line under the wordmark
        android.view.View pulse = new android.view.View(this);
        pulse.setBackgroundColor(Ui.ACCENT);
        LinearLayout.LayoutParams plp =
            new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 3));
        plp.topMargin = Ui.dp(this, 16);
        root.addView(pulse, plp);

        setContentView(root);

        icon.setScaleX(0.55f);
        icon.setScaleY(0.55f);
        icon.setAlpha(0f);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(430).setInterpolator(new OvershootInterpolator(1.35f)).start();

        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(this, 10));
        name.animate().alpha(1f).translationY(0f)
            .setStartDelay(150).setDuration(240).start();

        tag.setAlpha(0f);
        tag.setTranslationY(Ui.dp(this, 8));
        tag.animate().alpha(1f).translationY(0f)
            .setStartDelay(230).setDuration(220).start();

        pulse.setScaleX(0f);
        pulse.setAlpha(0f);
        pulse.animate().scaleX(1f).alpha(1f)
            .setStartDelay(340).setDuration(260).start();

        root.postDelayed(new Runnable() {
            @Override public void run() {
                route(start);
            }
        }, MIN_SHOW_MS);
    }

    /** Routing seam: today → Home; the auth gate plugs in here (1.2.0) without
     *  touching the animation. Ensures the minimum show-time even if callers arrive
     *  early, and is no-op once this activity is finishing. */
    private void route(long startMs) {
        if (isFinishing() || isDestroyed()) return;
        long elapsed = SystemClock.uptimeMillis() - startMs;
        if (elapsed < MIN_SHOW_MS) {
            findViewById(android.R.id.content).postDelayed(new Runnable() {
                @Override public void run() {
                    route(startMs);
                }
            }, MIN_SHOW_MS - elapsed);
            return;
        }
        Class<?> target = HomeActivity.class;
        try {
            com.replymate.app.di.AppContainer c =
                com.replymate.app.ReplyMateApp.containerOf(this);
            com.replymate.core.auth.AuthSession s = c.sessions().get();
            com.replymate.core.auth.AuthGate.Route r = com.replymate.core.auth.AuthGate
                .decide(s, c.clock().now(), c.sessions().onboardingDone());
            switch (r) {
                case AUTH:
                    target = AuthActivity.class;
                    break;
                case ONBOARDING:
                    target = OnboardingActivity.class;
                    break;
                case REFRESH_THEN_HOME:
                    refreshSessionQuietly(c, s);
                    target = HomeActivity.class;
                    break;
                default:
                    target = HomeActivity.class;
            }
        } catch (RuntimeException e) {
            target = HomeActivity.class;   // auth must never brick the local app
        }
        startActivity(new android.content.Intent(this, target));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    /** Best-effort token refresh on cold start: success persists the rotated session;
     *  failure logs honestly and the user keeps the local app (re-auth happens when
     *  a server action genuinely needs it). */
    private void refreshSessionQuietly(final com.replymate.app.di.AppContainer c,
                                       final com.replymate.core.auth.AuthSession s) {
        com.replymate.app.platform.Tasks.bg(new Runnable() {
            @Override public void run() {
                com.replymate.core.util.Result<com.replymate.core.auth.AuthSession> r =
                    c.auth().refresh(s);
                if (r.ok) {
                    c.sessions().put(r.value);
                } else {
                    c.logger().w("Splash", "session refresh failed (kept local app): " + r.error);
                }
            }
        });
    }

    /** Launch re-entry while a previous cold-start animation is still settling must
     *  never stack Home activities. */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
    }

    @Override public void onBackPressed() {
        // nothing to go back to from the launcher animation
    }
}
