package com.replymate.app.auth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.replymate.core.util.Result;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Local-first avatar store (P-auth onboarding). The phone copy
 *  (files/avatar.jpg, square-cropped ≤512px JPEG) is the source of truth and works
 *  fully offline; syncing to Supabase Storage is best-effort on top — a missing
 *  bucket or offline day never blocks onboarding, and failures are reported
 *  honestly. Pure framework APIs (no image libs). */
public final class AvatarStore {

    private static final int MAX_PX = 512;
    private static final int JPEG_Q = 88;

    private final File file;

    public AvatarStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "avatar.jpg");
    }

    public boolean exists() {
        return file.isFile();
    }

    public File file() {
        return file;
    }

    public void clear() {
        file.delete();
    }

    /** Reads the picked content, downscales + square-crops it, and atomically
     *  replaces the stored avatar. Runs on a background thread (Tasks.call). */
    public Result<File> saveFrom(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = ctx.getContentResolver().openInputStream(uri);
            if (probe == null) return Result.err("Couldn't open that image — try another one.");
            BitmapFactory.decodeStream(probe, null, bounds);
            probe.close();

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / 2.0 / sample > MAX_PX) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return Result.err("Couldn't open that image — try another one.");
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            in.close();
            if (bmp == null) return Result.err("Couldn't read that image — try another one.");

            int s = Math.min(bmp.getWidth(), bmp.getHeight());
            Bitmap square = Bitmap.createBitmap(
                bmp, (bmp.getWidth() - s) / 2, (bmp.getHeight() - s) / 2, s, s);
            File tmp = new File(file.getParentFile(), "avatar.tmp");
            OutputStream out = new FileOutputStream(tmp);
            square.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, out);
            out.flush();
            out.close();
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return Result.err("Couldn't save the avatar on this phone.");
            }
            return Result.ok(file);
        } catch (Exception e) {
            return Result.err("Couldn't process that image — try another one.");
        }
    }

    /** Best-effort upload to Supabase Storage bucket "avatars" as <userId>.jpg and
     *  writes the public URL into user_metadata.avatar_url. Any failure returns an
     *  honest error — the LOCAL avatar stays either way. */
    public Result<String> syncToCloud(String baseUrl, String anonKey,
                                      com.replymate.core.auth.SupabaseAuth auth,
                                      com.replymate.core.auth.AuthSession session) {
        if (session == null || session.accessToken.isEmpty()) {
            return Result.err("Sign in to sync your avatar (it's saved on this phone anyway).");
        }
        if (!exists()) return Result.err("No avatar to sync yet.");
        String object = "avatars/" + session.userId + ".jpg";
        HttpURLConnection conn = null;
        try {
            byte[] payload = readBytes(file);
            conn = (HttpURLConnection) new URL(
                baseUrl + "/storage/v1/object/" + object).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("apikey", anonKey);
            conn.setRequestProperty("Authorization", "Bearer " + session.accessToken);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setRequestProperty("x-upsert", "true");
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            int code = conn.getResponseCode();
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                String body = err == null ? "" : slurp(err);
                if (code == 404 || body.contains("Bucket not found")) {
                    return Result.err("Avatar kept on this phone. Cloud sync needs a public "
                        + "'avatars' bucket in Supabase Storage (one-time setup).");
                }
                return Result.err("Avatar kept on this phone; cloud sync was refused (HTTP "
                    + code + ").");
            }
            String publicUrl = baseUrl + "/storage/v1/object/public/" + object;
            com.replymate.core.util.Result<String> meta =
                auth.updateUserMetadata(session, null, publicUrl);
            if (!meta.ok) {
                // uploaded but profile-link failed — still not silently dropped
                return Result.err("Avatar uploaded; linking it to your profile failed: "
                    + meta.error);
            }
            return Result.ok(publicUrl);
        } catch (Exception e) {
            return Result.err("Avatar kept on this phone; cloud sync didn't reach the"
                + " server (" + e.getClass().getSimpleName() + ").");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static String slurp(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), "UTF-8");
    }
}
