package com.replymate.app.listener;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Read-only status of our system permissions (listener access + our own POST permission). */
public final class ListenerStatus {

    private ListenerStatus() { }

    /** Is ReplyMate enabled in system Notification Access? */
    public static boolean isServiceEnabled(Context ctx) {
        ComponentName cn = new ComponentName(ctx, RmNotificationListener.class);
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
            "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        return flat.contains(cn.flattenToString())
            || flat.contains(cn.flattenToShortString())
            || flat.contains(ctx.getPackageName() + "/" + RmNotificationListener.class.getName());
    }

    /** May we POST our own notifications? (33+ needs runtime grant; below it's implicit.) */
    public static boolean canPostNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            == PackageManager.PERMISSION_GRANTED;
    }

    /** Battery: is ReplyMate exempt from battery optimization (i.e. background
     *  work not throttled)? READ-ONLY system call (no permission needed); false
     *  when the platform can't answer. Diagnostics surfaces this so a killed
     *  pipeline is one glance away from its most common OEM cause. */
    public static boolean batteryWhitelisted(Context ctx) {
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** P-background-11: the SECOND, separate battery verdict — Android 9+ (API 28)
     *  lets the user (or an OEM battery manager) put an app in the RESTRICTED
     *  bucket, which kills background work even when battery optimization is
     *  ignored above. ActivityManager.isBackgroundRestricted() is the only real
     *  probe; false on older platforms or when the platform can't answer (never
     *  a false negative on those — absence of evidence is not reported as a
     *  restriction). */
    public static boolean backgroundRestricted(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 28) return false;
        try {
            android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isBackgroundRestricted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Deep-link to the system toggle screen for our listener. */
    public static void openAccessSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
