package com.replymate.core.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** P-intelligence-19R §2: the TRUTH about background readiness, as one pure
 *  decision function. Inputs are app-probed platform facts; the verdict — and
 *  specifically whether ReplyMate may claim "Ready" — is decided here and
 *  pinned by JVM tests. Zero Android imports by design.
 *
 *  Doc anchors (re-verified 2026-08-18):
 *   - PowerManager.isIgnoringBatteryOptimizations: "Being on the power allowlist
 *     means the system will not apply MOST power saving features to the app.
 *     Guardrails for extreme cases may still be applied." → allowlist alone can
 *     never justify a bare "Ready".
 *   - ActivityManager.isBackgroundRestricted (API 28): the SEPARATE Android 9+
 *     "Restricted" app-battery setting; kills background work outright.
 *   - UsageStatsManager.getAppStandbyBucket → STANDBY_BUCKET_RESTRICTED (45,
 *     API 31): "the most restrictions, including network restrictions and
 *     additional restrictions on jobs." No user-facing intent flips it.
 *   - Low Power Standby (API 33): while enabled, non-exempt apps lose network
 *     access and their wakelocks are ignored — transient device state.
 *   - ColorOS keeps a SECOND, UNREADABLE per-app switch ("Battery usage →
 *     Allow background activity"). Android exposes no API to read it, so it is
 *     modeled as a confirmed-by-owner fact, never inferred. */
public final class BackgroundReadiness {

    public enum Level { READY, READY_WITH_WARNINGS, BLOCKED }

    /** One thing that can still block background execution, in fix order. */
    public enum Lever {
        NOTIFICATION_ACCESS,    // listener approval — no capture without it
        POST_NOTIFICATIONS,     // 33+ runtime grant — the ready-alert needs it
        BATTERY_ALLOWLIST,      // not on the power allowlist (most restrictions stay)
        BACKGROUND_RESTRICTED,  // Android 9+ "Restricted" bucket (second lever)
        STANDBY_RESTRICTED,     // app standby bucket RESTRICTED (API 31+)
        OPLUS_BACKGROUND,       // ColorOS "Allow background activity" — UNVERIFIABLE,
                                // modeled as an owner-confirmed fact
        POWER_SAVE_MODE         // power save / low power standby ACTIVE now — transient
    }

    /** Platform facts the app layer just probed (current truths, not snapshots). */
    public static final class Input {
        public boolean notificationAccess;
        public boolean postNotifications;
        public boolean batteryAllowlisted;
        public boolean backgroundRestricted;
        public boolean standbyRestricted;
        public boolean oplusDevice;
        public boolean oplusConfirmed;
        public boolean powerSaveMode;
    }

    public static final class Verdict {
        public final Level level;
        /** Hard blockers in fix order — each can still stop background work. */
        public final List<Lever> missing;
        /** Transient states worth NAMING but never gating (power save ends). */
        public final List<Lever> warnings;

        Verdict(Level level, List<Lever> missing, List<Lever> warnings) {
            this.level = level;
            this.missing = Collections.unmodifiableList(missing);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        /** May the UI claim "Ready"? Only when NOTHING can still block execution —
         *  warnings are named alongside, never hidden. */
        public boolean mayClaimReady() {
            return level != Level.BLOCKED;
        }
    }

    private BackgroundReadiness() { }

    public static Verdict evaluate(Input in) {
        List<Lever> missing = new ArrayList<Lever>();
        List<Lever> warnings = new ArrayList<Lever>();
        if (in == null) {
            missing.add(Lever.NOTIFICATION_ACCESS);
            return new Verdict(Level.BLOCKED, missing, warnings);
        }
        if (!in.notificationAccess) missing.add(Lever.NOTIFICATION_ACCESS);
        if (!in.postNotifications) missing.add(Lever.POST_NOTIFICATIONS);
        if (!in.batteryAllowlisted) missing.add(Lever.BATTERY_ALLOWLIST);
        if (in.backgroundRestricted) missing.add(Lever.BACKGROUND_RESTRICTED);
        if (in.standbyRestricted) missing.add(Lever.STANDBY_RESTRICTED);
        // The ColorOS lever is REAL (it kills background work with the Android
        // allowlist granted) and UNDETECTABLE — so until the owner has stood on
        // the Battery usage page and confirmed it once, "Ready" is a lie.
        if (in.oplusDevice && !in.oplusConfirmed) missing.add(Lever.OPLUS_BACKGROUND);
        if (in.powerSaveMode) warnings.add(Lever.POWER_SAVE_MODE);

        Level level = !missing.isEmpty() ? Level.BLOCKED
            : warnings.isEmpty() ? Level.READY : Level.READY_WITH_WARNINGS;
        return new Verdict(level, missing, warnings);
    }
}
