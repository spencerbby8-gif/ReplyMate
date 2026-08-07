package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.auth.AvatarStore;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.Tasks;
import com.replymate.core.auth.AuthSession;
import com.replymate.core.util.Result;
import java.io.File;

/** P-auth onboarding (1.2.0): right after sign-in the owner sets an avatar + display
 *  name. Local-first: the phone copy of the avatar is the source of truth; Supabase
 *  Storage sync is best-effort and reports honestly. Skippable — everything here is
 *  editable later (Settings → Account → Edit profile & avatar). */
public final class OnboardingActivity extends Activity {

    private static final int REQ_PICK = 41;

    private AppContainer c;
    private AvatarStore avatars;
    private ImageView preview;
    private TextView avStatus;
    private EditText name;
    private TextView status;
    private boolean busy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        c = ReplyMateApp.containerOf(this);
        avatars = new AvatarStore(this);
        AuthSession s = c.sessions().get();

        LinearLayout root = (LinearLayout) findViewById(R.id.onboarding_root);

        TextView title = Ui.tv(this, "Set up your profile", 22.0f, Ui.PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = Ui.sub(this,
            "This is how your account looks. Saved on this phone; the avatar also syncs"
                + " to your account when a connection is available.");
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        root.addView(sub);

        preview = new ImageView(this);
        preview.setBackgroundResource(R.drawable.bg_card);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int sz = Ui.dp(this, 112);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        wrap.addView(preview, new LinearLayout.LayoutParams(sz, sz));
        root.addView(wrap);

        Button pick = Ui.btn(this, avatars.exists() ? "Change photo" : "Upload photo");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = Ui.dp(this, 10);
        LinearLayout wrap2 = new LinearLayout(this);
        wrap2.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        wrap2.addView(pick, plp);
        root.addView(wrap2);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });

        avStatus = Ui.sub(this, avatars.exists() ? "photo saved ✓" : "no photo yet");
        avStatus.setGravity(android.view.Gravity.CENTER);
        avStatus.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        root.addView(avStatus);

        root.addView(Ui.label(this, "Display name"));
        name = Ui.field(this, "e.g. Chidi", false);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        root.addView(name);
        // prefill: session metadata → email prefix → existing local profile name
        String prefill = "";
        if (s != null && !s.displayName.isEmpty()) prefill = s.displayName;
        else if (s != null && s.email.contains("@")) prefill = s.email.substring(0, s.email.indexOf('@'));
        String local = c.kv().get(com.replymate.core.usecase.ProfileService.KEY_NAME, "");
        if (!local.isEmpty()) prefill = local;
        name.setText(prefill);

        status = Ui.sub(this, "");
        status.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(status);

        Button done = Ui.btn(this, "Continue");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = Ui.dp(this, 14);
        root.addView(done, dlp);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishOnboarding(); }
        });

        TextView skip = Ui.sub(this, "do this later");
        skip.setGravity(android.view.Gravity.CENTER);
        skip.setTextColor(Ui.ACCENT);
        skip.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        root.addView(skip);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                c.sessions().setOnboardingDone(true);
                goHome();
            }
        });

        if (avatars.exists()) showPreview();
    }

    /* ---------------------------------------------------------------- pick/save */

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pick your photo"), REQ_PICK);
        } catch (Exception e) {
            setStatus("No gallery app found to pick a photo.", Ui.RED);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        final Uri uri = data.getData();
        busy();
        setStatus("Saving your photo…", Ui.ACCENT);
        Tasks.call(new Tasks.Job<Result<File>>() {
            @Override public Result<File> run() {
                return avatars.saveFrom(OnboardingActivity.this, uri);
            }
        }, new Tasks.Done<Result<File>>() {
            @Override public void accept(Result<File> r) {
                idle();
                if (r.ok) {
                    showPreview();
                    avStatus.setText("photo saved ✓");
                    syncAvatar();
                } else {
                    setStatus(r.error, Ui.RED);
                }
            }
        });
    }

    private void showPreview() {
        preview.setImageBitmap(BitmapFactory.decodeFile(avatars.file().getAbsolutePath()));
    }

    /** Best-effort cloud sync for signed-in users; every outcome is announced. */
    private void syncAvatar() {
        final AuthSession s = c.sessions().get();
        if (s == null || s.isGuest()) {
            setStatus("Photo saved on this phone (guest sessions stay local only).", Ui.GREEN);
            return;
        }
        setStatus("Photo saved ✓ — syncing to your account…", Ui.ACCENT);
        Tasks.call(new Tasks.Job<Result<String>>() {
            @Override public Result<String> run() {
                return avatars.syncToCloud(c.supabaseBaseUrl(),
                    getString(R.string.supabase_publishable_key), c.auth(), s);
            }
        }, new Tasks.Done<Result<String>>() {
            @Override public void accept(Result<String> r) {
                if (r.ok) {
                    AuthSession s2 = c.sessions().get();
                    if (s2 != null) {
                        s2.avatarUrl = r.value;
                        c.sessions().put(s2);
                    }
                    avStatus.setText("photo saved ✓ (synced to your account)");
                    setStatus("Avatar synced ✓", Ui.GREEN);
                } else {
                    setStatus(r.error, Ui.DIM);
                }
            }
        });
    }

    /* ---------------------------------------------------------------- finish */

    private void finishOnboarding() {
        final String display = name.getText().toString().trim();
        c.kv().put(com.replymate.core.usecase.ProfileService.KEY_NAME, display);
        c.sessions().setOnboardingDone(true);
        final AuthSession s = c.sessions().get();
        if (s != null && !s.isGuest() && !display.isEmpty()
                && !display.equals(s.displayName)) {
            // best-effort: the display name lives in user_metadata too
            Tasks.call(new Tasks.Job<Result<String>>() {
                @Override public Result<String> run() {
                    return c.auth().updateUserMetadata(s, display, null);
                }
            }, new Tasks.Done<Result<String>>() {   // fire-and-forget, honestly logged
                @Override public void accept(Result<String> r) {
                    if (!r.ok) c.logger().w("Onboarding", "name metadata sync failed: " + r.error);
                }
            });
        }
        goHome();
    }

    private void goHome() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void busy() { busy = true; }
    private void idle() { busy = false; }

    private void setStatus(String msg, int color) {
        status.setText(msg);
        status.setTextColor(color);
    }
}
