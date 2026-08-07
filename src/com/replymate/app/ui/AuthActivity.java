package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.auth.AvatarStore;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.auth.AuthDeepLink;
import com.replymate.core.auth.AuthSession;
import com.replymate.core.auth.Pkce;
import com.replymate.core.util.Result;
import java.security.SecureRandom;

/** P-auth sign-in / account screen (1.2.0). Methods (owner-locked, platform-aware):
 *  Google (Supabase OAuth + PKCE via the system browser) · Email 6-digit OTP code
 *  (never a magic link) · Guest via Supabase anonymous sign-in. No Apple anywhere;
 *  passkey is an honest explainer row until the zero-dependency rule is lifted. */
public final class AuthActivity extends Activity {

    public static final String EXTRA_MODE = "auth.mode";
    private static final String MODE_LINK = "link";
    private static final String KEY_PKCE_VERIFIER = "auth.pkce.verifier";
    private static final String KEY_OTP_SENT_AT = "auth.otp.sent_at";
    private static final long RESEND_MS = 60_000L;

    private AppContainer c;
    private AvatarStore avatars;
    private final SecureRandom random = new SecureRandom();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView error;
    private Button googleBtn, sendBtn, verifyBtn, guestBtn;
    private LinearLayout codeBlock;
    private TextView resend;
    private EditText emailField, codeField;
    private boolean busy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        c = ReplyMateApp.containerOf(this);
        avatars = new AvatarStore(this);
        render();
        // a returning browser OAuth flow may deliver the code on cold start
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /* ============================================================ render */

    private void render() {
        AuthSession s = c.sessions().get();
        boolean linkMode = MODE_LINK.equals(getIntent().getStringExtra(EXTRA_MODE));
        buildPanel(s, s != null || linkMode);
    }

    private void buildPanel(final AuthSession session, boolean accountMode) {
        LinearLayout root = (LinearLayout) findViewById(R.id.auth_root);
        root.removeAllViews();
        status = null;
        error = null;

        // header
        if (accountMode) {
            LinearLayout header = new LinearLayout(this);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView back = Ui.tv(this, "‹", 26.0f, Ui.DIM);
            back.setPadding(0, 0, Ui.dp(this, 12), 0);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
            header.addView(back);
            TextView title = Ui.tv(this, "Account", 22.0f, Ui.PRIMARY);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(title);
            header.setPadding(0, 0, 0, Ui.dp(this, 8));
            root.addView(header);
        } else {
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.ic_launcher);
            int is = Ui.dp(this, 72);
            header.addView(icon, new LinearLayout.LayoutParams(is, is));
            TextView title = Ui.tv(this, "Welcome to ReplyMate", 22.0f, Ui.PRIMARY);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            TextView why = Ui.sub(this,
                "Sign in to tie your profile and avatar to an account — or continue as a"
                    + " guest: every chat feature stays local on this phone either way.");
            why.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = Ui.dp(this, 10);
            header.addView(title, hlp);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
            wlp.topMargin = Ui.dp(this, 4);
            wlp.bottomMargin = Ui.dp(this, 10);
            header.addView(why, wlp);
            root.addView(header);
        }

        if (accountMode) accountBlock(root, session);

        // Sign-in methods: fresh + guest-upgrade. A signed-in non-guest account just
        // sees its summary + sign-out above (no method re-pick here).
        if (session == null || session.isGuest()) {
            methodsBlock(root, session != null);
        }
        ensureFeedbackViews(root);
    }

    /** Live account summary + profile edit + sign-out (non-guest) or upgrade note. */
    private void accountBlock(LinearLayout root, final AuthSession s) {
        if (s == null) {
            TextView sub = Ui.sub(this, "Not signed in yet — pick a method below.");
            sub.setPadding(0, 0, 0, Ui.dp(this, 10));
            root.addView(sub);
            return;
        }
        TextView who = Ui.tv(this,
            s.isGuest() ? "Using ReplyMate as a guest" : "Signed in", 17.0f, Ui.PRIMARY);
        root.addView(who);
        String line = s.isGuest()
            ? "Anonymous session — sign in below to upgrade it to a full account."
            : (s.email.isEmpty() ? "(no email on account)" : s.email)
                + "  ·  via " + (s.provider.isEmpty() ? "email" : s.provider);
        TextView sub = Ui.sub(this, line);
        sub.setPadding(0, 0, 0, Ui.dp(this, 4));
        root.addView(sub);
        TextView av = Ui.sub(this, "Avatar: " + (avatars.exists()
            ? "set on this phone ✓" + (s.avatarUrl.isEmpty() ? " (not synced)" : " (synced ✓)")
            : "none yet"));
        root.addView(av);

        Button profile = Ui.btn(this, "Edit profile & avatar");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(this, 10);
        root.addView(profile, plp);
        profile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(AuthActivity.this, OnboardingActivity.class));
            }
        });

        if (!s.isGuest()) {
            Button out = Ui.btn(this, "Sign out");
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
            olp.topMargin = Ui.dp(this, 8);
            root.addView(out, olp);
            out.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmSignOut(s); }
            });
        }
        root.addView(padDivider());
    }

    private View padDivider() {
        View d = Ui.divider(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.topMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 4);
        d.setLayoutParams(lp);
        return d;
    }

    /* ============================================================ sign-in methods */

    private void methodsBlock(LinearLayout root, boolean upgradeMode) {
        if (upgradeMode) {
            root.addView(Ui.label(this, "LINK A SIGN-IN METHOD (guest → full account)"));
        }

        googleBtn = Ui.btn(this, "Continue with Google");
        root.addView(googleBtn);
        googleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startGoogle(); }
        });

        passkeyRow(root);

        root.addView(Ui.label(this, "or with email (6-digit code — no links)"));
        emailField = Ui.field(this, "you@example.com", false);
        emailField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(emailField);

        sendBtn = Ui.btn(this, "Send verification code");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 8);
        root.addView(sendBtn, slp);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendCode(); }
        });

        codeBlock = new LinearLayout(this);
        codeBlock.setOrientation(LinearLayout.VERTICAL);
        codeBlock.setVisibility(View.GONE);
        root.addView(codeBlock);
        codeField = Ui.field(this, "6-digit code", false);
        codeField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new android.text.InputFilter[] {
            new android.text.InputFilter.LengthFilter(6)});
        codeField.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 10);
        codeBlock.addView(codeField, clp);
        verifyBtn = Ui.btn(this, "Verify & sign in");
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = Ui.dp(this, 8);
        codeBlock.addView(verifyBtn, vlp);
        verifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { verifyCode(); }
        });
        resend = Ui.sub(this, "");
        resend.setPadding(0, Ui.dp(this, 8), 0, 0);
        codeBlock.addView(resend);

        if (!upgradeMode) {
            guestBtn = Ui.btn(this, "Continue as guest");
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = Ui.dp(this, 14);
            root.addView(guestBtn, glp);
            guestBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startGuest(); }
            });
            TextView guestNote = Ui.sub(this,
                "Guest = a real anonymous session. Nothing leaves this phone unless you"
                    + " later sign in (Settings → Account).");
            guestNote.setGravity(android.view.Gravity.CENTER);
            root.addView(guestNote);
        }
    }

    private void ensureFeedbackViews(LinearLayout root) {
        if (status == null) {
            status = Ui.sub(this, "");
            status.setPadding(0, Ui.dp(this, 14), 0, 0);
            root.addView(status);
        }
        if (error == null) {
            error = Ui.sub(this, "");
            error.setTextIsSelectable(true);
            error.setPadding(0, Ui.dp(this, 6), 0, 0);
            root.addView(error);
        }
    }

    /** Honest non-dead passkey row (owner rule: every row does something real —
     *  this one explains exactly why it's off and what unlocks it). */
    private void passkeyRow(LinearLayout root) {
        LinearLayout row = Ui.row(this, "Sign in with a passkey",
            "coming — needs Android Credential Manager (blocked by this build's"
                + " zero-dependency rule). Tap for details.");
        root.addView(row);
        root.addView(Ui.divider(this));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(AuthActivity.this)
                    .setTitle("Passkeys, honestly")
                    .setMessage("Passkeys ARE the plan for password-free sign-in, and Supabase"
                        + " shipped them in 2026 (still experimental). But on Android the passkey"
                        + " ceremony must go through Credential Manager — a third-party library"
                        + " this build deliberately avoids so it stays dependency-free and"
                        + " offline-shippable.\n\n"
                        + "This row becomes a REAL button the moment the project allows that one"
                        + " dependency (and Supabase passkeys exit experimental). Google and email"
                        + " codes work today.")
                    .setPositiveButton("Got it", null)
                    .show();
            }
        });
    }

    /* ============================================================ flows */

    private void startGoogle() {
        String verifier = Pkce.newVerifier(random);
        c.kv().put(KEY_PKCE_VERIFIER, verifier);
        String url = c.supabaseBaseUrl()
            + c.auth().oauthAuthorizeUrl("google", AuthDeepLink.CALLBACK,
                Pkce.s256Challenge(verifier));
        banner("", false);
        showStatus("Opening your browser for Google…", Ui.ACCENT);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            banner("No browser found to continue Google sign-in.", true);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        AuthDeepLink link = AuthDeepLink.parse(intent.getData().toString());
        if (!link.isCallback()) return;
        setIntent(new Intent());   // consume — a rotation must not re-exchange the code
        if (!link.error.isEmpty()) {
            showStatus("", Ui.DIM);
            banner("Google sign-in was cancelled or failed: "
                + (link.errorDesc.isEmpty() ? link.error : link.errorDesc), true);
            return;
        }
        final String verifier = c.kv().get(KEY_PKCE_VERIFIER, "");
        c.kv().delete(KEY_PKCE_VERIFIER);
        if (verifier.isEmpty()) {
            banner("The sign-in session expired before Google returned — try again.", true);
            return;
        }
        setBusy(true, "Finishing Google sign-in…");
        Tasks.call(new Tasks.Job<Result<AuthSession>>() {
            @Override public Result<AuthSession> run() {
                return c.auth().exchangePkce(link.code, verifier);
            }
        }, new Tasks.Done<Result<AuthSession>>() {
            @Override public void accept(Result<AuthSession> r) {
                setBusy(false, "");
                if (r.ok) {
                    c.sessions().put(r.value);
                    routeAfterAuth(r.value);
                } else {
                    banner(r.error, true);
                }
            }
        });
    }

    private void sendCode() {
        final String email = emailField.getText().toString().trim();
        setBusy(true, "Sending a 6-digit code…");
        Tasks.call(new Tasks.Job<Result<String>>() {
            @Override public Result<String> run() {
                return c.auth().sendEmailOtp(email);
            }
        }, new Tasks.Done<Result<String>>() {
            @Override public void accept(Result<String> r) {
                setBusy(false, "");
                if (r.ok) {
                    c.kv().put(KEY_OTP_SENT_AT, String.valueOf(c.clock().now()));
                    codeBlock.setVisibility(View.VISIBLE);
                    showStatus("Code sent to " + email + " — check your inbox (and spam)."
                        + " It expires quickly.", Ui.GREEN);
                    emailField.setEnabled(false);
                    startResendTimer();
                } else {
                    banner(r.error, true);
                }
            }
        });
    }

    private void startResendTimer() {
        resend.setTextColor(Ui.DIM);
        resend.setOnClickListener(null);
        ui.post(new Runnable() {
            @Override public void run() {
                long sentAt = readLong(KEY_OTP_SENT_AT);
                long left = RESEND_MS - (c.clock().now() - sentAt);
                if (left <= 0) {
                    resend.setText("Didn't get it? Resend code");
                    resend.setTextColor(Ui.ACCENT);
                    resend.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            resend.setOnClickListener(null);
                            if (!busy) sendCode();
                        }
                    });
                    return;
                }
                resend.setText("Resend available in " + ((left + 999) / 1000) + "s");
                ui.postDelayed(this, 1000);
            }
        });
    }

    private void verifyCode() {
        final String email = emailField.getText().toString().trim();
        final String code = codeField.getText().toString().trim();
        setBusy(true, "Verifying…");
        Tasks.call(new Tasks.Job<Result<AuthSession>>() {
            @Override public Result<AuthSession> run() {
                return c.auth().verifyEmailOtp(email, code);
            }
        }, new Tasks.Done<Result<AuthSession>>() {
            @Override public void accept(Result<AuthSession> r) {
                setBusy(false, "");
                if (r.ok) {
                    c.kv().delete(KEY_OTP_SENT_AT);
                    c.sessions().put(r.value);
                    routeAfterAuth(r.value);
                } else {
                    banner(r.error, true);
                }
            }
        });
    }

    private void startGuest() {
        setBusy(true, "Creating your guest session…");
        Tasks.call(new Tasks.Job<Result<AuthSession>>() {
            @Override public Result<AuthSession> run() {
                return c.auth().signInAnonymously();
            }
        }, new Tasks.Done<Result<AuthSession>>() {
            @Override public void accept(Result<AuthSession> r) {
                setBusy(false, "");
                if (r.ok) {
                    c.sessions().put(r.value);
                    routeAfterAuth(r.value);
                } else {
                    banner(r.error, true);
                }
            }
        });
    }

    private void confirmSignOut(final AuthSession s) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("Your chats, contacts, style and memory stay on this phone. Only"
                + " the account session is removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    signOut(s);
                }
            })
            .show();
    }

    private void signOut(final AuthSession s) {
        setBusy(true, "Signing out…");
        Tasks.call(new Tasks.Job<Result<String>>() {
            @Override public Result<String> run() {
                return c.auth().logout(s);
            }
        }, new Tasks.Done<Result<String>>() {
            @Override public void accept(Result<String> r) {
                setBusy(false, "");
                c.sessions().clear();
                c.sessions().setOnboardingDone(false);
                render();
                if (!r.ok) {
                    banner("Signed out on this phone; the server said: " + r.error, true);
                }
            }
        });
    }

    /* ============================================================ routing/state */

    private void routeAfterAuth(AuthSession s) {
        if (s != null && s.isGuest()) {
            goHome();                    // guests keep the full local app immediately
            return;
        }
        if (!c.sessions().onboardingDone()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        goHome();
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void setBusy(boolean on, String msg) {
        busy = on;
        if (googleBtn != null) googleBtn.setEnabled(!on);
        if (sendBtn != null) sendBtn.setEnabled(!on);
        if (verifyBtn != null) verifyBtn.setEnabled(!on);
        if (guestBtn != null) guestBtn.setEnabled(!on);
        if (on) showStatus(msg, Ui.ACCENT);
        else if (msg.isEmpty()) showStatus("", Ui.DIM);
    }

    private void showStatus(String msg, int color) {
        if (status == null) return;
        status.setText(msg);
        status.setTextColor(color);
    }

    private void banner(String msg, boolean isError) {
        if (error == null) return;
        error.setText(msg);
        error.setTextColor(isError ? Ui.RED : Ui.GREEN);
    }

    private long readLong(String key) {
        try {
            return Long.parseLong(c.kv().get(key, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
