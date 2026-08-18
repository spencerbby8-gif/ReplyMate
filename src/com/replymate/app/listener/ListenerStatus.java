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

    /** P-intelligence-19R: app STANDBY BUCKET (UsageStatsManager, API 28+). −1 when
     *  the platform can't answer (never a false negative). Bucket 45
     *  (STANDBY_BUCKET_RESTRICTED, API 31) carries "the most restrictions,
     *  including network restrictions and additional restrictions on jobs" —
     *  background generation cannot be trusted while the app sits there. */
    public static int standbyBucket(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) return -1;
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            return usm == null ? -1 : usm.getAppStandbyBucket();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** True only on a POSITIVE restricted-bucket answer (API 31+); unknown/preattentive
     *  platforms never report a restriction they cannot prove. */
    public static boolean standbyRestricted(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        return standbyBucket(ctx)
            == android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
    }

    /** Human bucket name for diagnostics (never null). */
    public static String standbyBucketName(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) return "n/a (pre-28)";
        switch (standbyBucket(ctx)) {
            case 10: return "active";
            case 20: return "working_set";
            case 30: return "frequent";
            case 40: return "rare";
            case 45: return "RESTRICTED";
            default: return "unknown";
        }
    }

    /** Power save mode (ColorOS "Power saving mode", AOSP Battery Saver) active
     *  RIGHT NOW — transient: apps should reduce functionality; background work
     *  may be deferred until it ends. Named, never hidden. */
    public static boolean powerSaveOn(Context ctx) {
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Low Power Standby exemption (API 33): while LPS is enabled, non-exempt
     *  apps lose network access and their wakelocks are ignored outside idle
     *  maintenance windows. Query-only tri-state; true also when LPS is off. */
    public static boolean lowPowerStandbyExempt(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isExemptFromLowPowerStandby();
        } catch (RuntimeException e) {
            return true;   // tri-state absence of evidence: don't report a restriction
        }
    }

    /** Deep-link to the system toggle screen for our listener. */
    public static void openAccessSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
