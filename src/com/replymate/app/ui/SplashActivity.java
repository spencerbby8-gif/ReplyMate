package com.replymate.app.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.core.ui.SplashChoreo;

/** P-splash-auth-polish (1.3.0) launch animation, rebuilt around the app icon as the
 *  hero. Choreography (pure data in core SplashChoreo, unit-pinned):
 *   1. a soft brand glow blooms behind the icon (0ms, 950ms)
 *   2. a thin accent ring settles into place (130ms, 560ms)
 *   3. the hero icon springs in — scale + fade, rounded-clipped (150ms, 640ms)
 *   4. one light sweep crosses the icon face (820ms, 520ms)
 *   5. the wordmark rises while its letter-spacing pulls in (640ms, 340ms)
 *   6. tagline fades in (800ms, 280ms) and a thin accent underline grows (960ms, 300ms)
 *  Every beat lands by ~1340ms; the frame then rests until MIN_SHOW_MS (1900ms) so
 *  the user actually sees it, and routing (auth gate, 1.2.0) never changes the beat. */
public final class SplashActivity extends Activity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final long start = SystemClock.uptimeMillis();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0x0D, 0x11, 0x17));   // @color/bg — seamless

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        root.addView(col, centerParams(-1, -1));

        /* ---- hero stage: glow + ring + rounded icon frame, all centered ---- */
        FrameLayout stage = new FrameLayout(this);
        int stageSize = Ui.dp(this, 300);   // roomy — the 280dp glow must never clip
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(stageSize, stageSize);
        col.addView(stage, slp);

        View glow = new View(this);
        glow.setBackgroundResource(R.drawable.splash_glow);
        int glowSize = Ui.dp(this, 280);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(glowSize, glowSize);
        glp.gravity = Gravity.CENTER;
        stage.addView(glow, glp);

        View ring = new View(this);
        ring.setBackgroundResource(R.drawable.splash_ring);
        int ringSize = Ui.dp(this, 150);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ringSize, ringSize);
        rlp.gravity = Gravity.CENTER;
        stage.addView(ring, rlp);

        final int iconSize = Ui.dp(this, 118);
        final int corner = Ui.dp(this, 27);
        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        iconFrame.setClipToOutline(true);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconSize, iconSize);
        ilp.gravity = Gravity.CENTER;
        stage.addView(iconFrame, ilp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        icon.setContentDescription("ReplyMate");
        iconFrame.addView(icon, new FrameLayout.LayoutParams(-1, -1));

        // light sweep band (starts parked off the icon's left edge, ends off the right)
        final View shimmer = new View(this);
        shimmer.setBackgroundResource(R.drawable.splash_shimmer);
        int band = Ui.dp(this, 64);
        FrameLayout.LayoutParams shlp = new FrameLayout.LayoutParams(band, -1);
        iconFrame.addView(shimmer, shlp);
        shimmer.setVisibility(View.INVISIBLE);

        /* ---- wordmark / tagline / underline ---- */
        TextView name = Ui.tv(this, "ReplyMate", 25.0f, Ui.PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.dp(this, 26);
        col.addView(name, nlp);

        TextView tag = Ui.sub(this, "private · on-device · your voice");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 6);
        col.addView(tag, tlp);

        View line = new View(this);
        line.setBackgroundColor(Ui.ACCENT);
        LinearLayout.LayoutParams llp =
            new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 3));
        llp.topMargin = Ui.dp(this, 18);
        col.addView(line, llp);

        setContentView(root);

        /* ---- the beat (values pinned by SplashChoreoTest) ---- */
        PathInterpolator decel = new PathInterpolator(0.16f, 1f, 0.3f, 1f);  // emphasize-out

        glow.setAlpha(0f);
        glow.setScaleX(0.35f);
        glow.setScaleY(0.35f);
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(SplashChoreo.GLOW_DELAY).setDuration(SplashChoreo.GLOW_DUR)
            .setInterpolator(new DecelerateInterpolator(1.6f)).start();

        ring.setAlpha(0f);
        ring.setScaleX(1.28f);
        ring.setScaleY(1.28f);
        ring.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(SplashChoreo.RING_DELAY).setDuration(SplashChoreo.RING_DUR)
            .setInterpolator(decel).start();

        iconFrame.setAlpha(0f);
        iconFrame.setScaleX(0.72f);
        iconFrame.setScaleY(0.72f);
        iconFrame.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(SplashChoreo.ICON_DELAY).setDuration(SplashChoreo.ICON_DUR)
            .setInterpolator(new OvershootInterpolator(0.9f)).start();

        shimmer.post(new Runnable() {
            @Override public void run() {
                shimmer.setTranslationX(-(iconSize + band));
                shimmer.setVisibility(View.VISIBLE);
                shimmer.animate().translationX(iconSize + band)
                    .setStartDelay(SplashChoreo.SHIMMER_DELAY)
                    .setDuration(SplashChoreo.SHIMMER_DUR)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            shimmer.setVisibility(View.GONE);
                        }
                    }).start();
            }
        });

        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(this, 12));
        name.setLetterSpacing(0.15f);
        name.animate().alpha(1f).translationY(0f)
            .setStartDelay(SplashChoreo.WORD_DELAY).setDuration(SplashChoreo.WORD_DUR)
            .setInterpolator(decel).start();
        ValueAnimator tracking = ValueAnimator.ofFloat(0.15f, 0.02f);
        tracking.setStartDelay(SplashChoreo.WORD_DELAY);
        tracking.setDuration(SplashChoreo.WORD_DUR);
        tracking.setInterpolator(decel);
        final TextView nameRef = name;
        tracking.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                nameRef.setLetterSpacing((Float) a.getAnimatedValue());
            }
        });
        tracking.start();

        tag.setAlpha(0f);
        tag.setTranslationY(Ui.dp(this, 8));
        tag.animate().alpha(1f).translationY(0f)
            .setStartDelay(SplashChoreo.TAG_DELAY).setDuration(SplashChoreo.TAG_DUR)
            .setInterpolator(decel).start();

        line.setScaleX(0f);
        line.setAlpha(0f);
        line.animate().scaleX(1f).alpha(1f)
            .setStartDelay(SplashChoreo.LINE_DELAY).setDuration(SplashChoreo.LINE_DUR)
            .setInterpolator(decel).start();

        root.postDelayed(new Runnable() {
            @Override public void run() {
                route(start);
            }
        }, SplashChoreo.MIN_SHOW_MS);
    }

    private FrameLayout.LayoutParams centerParams(int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    /** Routing seam: unchanged since 1.2.0 — the auth gate plugs in here without
     *  touching the animation. Ensures the minimum show-time even if callers arrive
     *  early, and is no-op once this activity is finishing. */
    private void route(long startMs) {
        if (isFinishing() || isDestroyed()) return;
        long elapsed = SystemClock.uptimeMillis() - startMs;
        if (elapsed < SplashChoreo.MIN_SHOW_MS) {
            findViewById(android.R.id.content).postDelayed(new Runnable() {
                @Override public void run() {
                    route(startMs);
                }
            }, SplashChoreo.MIN_SHOW_MS - elapsed);
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
