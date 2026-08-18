package com.replymate.app.platform;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** P-intelligence-19R §2: the Doze / process-death SAFETY NET under the
 *  in-memory generation timer.
 *
 *  The honest gap it closes (owner-mandated audit): the assistant's debounce
 *  timer is a main-looper Handler. A Handler neither survives process death
 *  (recovered today by the listener-rebind sweep) nor is guaranteed to fire
 *  while the device dozes — in Doze/Low Power Standby the CPU suspends and
 *  plain timers slip to the next maintenance window. One slow-freeze on
 *  ColorOS ("Allow background activity" left off) presents exactly like the
 *  owner's report: background drafts that arrive minutes late or never.
 *
 *  Mechanism (official, permission-free): for every scheduled generation we arm
 *  ONE AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP) fallback a little AFTER
 *  the in-memory due time. setAndAllowWhileIdle is the documented idle-mode
 *  escape hatch — even while idle, these alarms fire "approximately" at the
 *  requested time (a few minutes of slack is normal and FINE for a catch-up).
 *  It needs no SCHEDULE_EXACT_ALARM (inexact alarms stay exempt on 31+).
 *
 *  When the fallback fires it runs the SAME idempotent catch-up sweep the
 *  listener-rebind path already trusts: if the in-memory timer did its job,
 *  the hash/draft checks make the sweep a no-op; if Doze or a kill ate the
 *  timer, the sweep re-drives genuinely-unanswered conversations through the
 *  normal debounced pipeline. Nothing ever double-generates — every generation
 *  path passes through the content-hash and JobCoalescer gates. */
public final class WakeSweep {

    public static final String ACTION = "com.replymate.app.WAKE_SWEEP";

    private WakeSweep() { }

    /** Arm (or re-arm) the single rolling fallback at {@code atMs}. */
    public static void arm(Context ctx, long atMs) {
        if (ctx == null) return;
        AlarmManager am;
        try {
            am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        } catch (RuntimeException e) {
            return;
        }
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
            new Intent(ACTION).setPackage(ctx.getPackageName()),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        } catch (RuntimeException ignored) {
            // battery-policy denials (OEM quotas) must never break scheduling —
            // the in-memory timer plus the listener rebind still cover capture
        }
    }

    /** The broadcast landing point (manifest: not exported, our package only). */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            com.replymate.app.di.AppContainer c =
                com.replymate.app.ReplyMateApp.containerOf(ctx);
            if (c == null) return;
            // interval 0: the alarm itself is the throttle; the sweep is
            // idempotent (hash + draft checks) and runs on the GEN lane
            com.replymate.app.assistant.AssistantRunner.retryUnansweredThrottled(c, 0L);
        }
    }
}
