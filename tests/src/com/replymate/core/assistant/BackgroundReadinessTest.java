package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-intel-19R §2: "Do not claim Ready when Android can still block execution." */
public final class BackgroundReadinessTest {

    private static BackgroundReadiness.Input allGreen() {
        BackgroundReadiness.Input in = new BackgroundReadiness.Input();
        in.notificationAccess = true;
        in.postNotifications = true;
        in.batteryAllowlisted = true;
        in.backgroundRestricted = false;
        in.standbyRestricted = false;
        in.oplusDevice = false;
        in.oplusConfirmed = false;
        in.powerSaveMode = false;
        return in;
    }

    @Test public void allGreenIsReadyAndClaimable() {
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(allGreen());
        assertEquals(BackgroundReadiness.Level.READY, v.level);
        assertTrue(v.mayClaimReady());
        assertTrue(v.missing.isEmpty());
        assertTrue(v.warnings.isEmpty());
    }

    @Test public void theAndroidAllowlistAloneNeverMeansReady() {
        // the P-19R mandate, verbatim: allowlist removes MOST restrictions only
        BackgroundReadiness.Input in = allGreen();
        in.backgroundRestricted = true;                       // Android 9+ second lever
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(in);
        assertEquals(BackgroundReadiness.Level.BLOCKED, v.level);
        assertFalse(v.mayClaimReady());
        assertEquals(BackgroundReadiness.Lever.BACKGROUND_RESTRICTED, v.missing.get(0));
    }

    @Test public void restrictedStandbyBucketBlocksReady() {
        BackgroundReadiness.Input in = allGreen();
        in.standbyRestricted = true;
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(in);
        assertFalse(v.mayClaimReady());
        assertTrue(v.missing.contains(BackgroundReadiness.Lever.STANDBY_RESTRICTED));
    }

    @Test public void colorosBackgroundSwitchBlocksUntilTheOwnerConfirms() {
        BackgroundReadiness.Input in = allGreen();
        in.oplusDevice = true;
        assertFalse(BackgroundReadiness.evaluate(in).mayClaimReady());
        assertTrue(BackgroundReadiness.evaluate(in).missing
            .contains(BackgroundReadiness.Lever.OPLUS_BACKGROUND));
        in.oplusConfirmed = true;
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(in);
        assertEquals(BackgroundReadiness.Level.READY, v.level);
        assertTrue(v.mayClaimReady());
    }

    @Test public void nonOplusDevicesNeverSeeTheColorosLever() {
        BackgroundReadiness.Input in = allGreen();
        in.oplusDevice = false;                               // confirm irrelevant
        assertFalse(BackgroundReadiness.evaluate(in).missing
            .contains(BackgroundReadiness.Lever.OPLUS_BACKGROUND));
    }

    @Test public void powerSaveModeWarnsButNeverBlocks() {
        // transient device state: name it, don't gate on it
        BackgroundReadiness.Input in = allGreen();
        in.powerSaveMode = true;
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(in);
        assertEquals(BackgroundReadiness.Level.READY_WITH_WARNINGS, v.level);
        assertTrue(v.mayClaimReady());
        assertEquals(BackgroundReadiness.Lever.POWER_SAVE_MODE, v.warnings.get(0));
        assertTrue(v.missing.isEmpty());
    }

    @Test public void fixOrderIsCaptureThenAlertsThenPowerLevers() {
        BackgroundReadiness.Input in = new BackgroundReadiness.Input();  // all probes red…
        in.backgroundRestricted = true;                                  // …and both
        in.standbyRestricted = true;                                     // restriction
        in.oplusDevice = true;                                           // flags set
        BackgroundReadiness.Verdict v = BackgroundReadiness.evaluate(in);
        assertEquals(BackgroundReadiness.Lever.NOTIFICATION_ACCESS, v.missing.get(0));
        assertEquals(BackgroundReadiness.Lever.POST_NOTIFICATIONS, v.missing.get(1));
        assertEquals(BackgroundReadiness.Lever.BATTERY_ALLOWLIST, v.missing.get(2));
        assertEquals(BackgroundReadiness.Lever.BACKGROUND_RESTRICTED, v.missing.get(3));
        assertEquals(BackgroundReadiness.Lever.STANDBY_RESTRICTED, v.missing.get(4));
        assertEquals(BackgroundReadiness.Lever.OPLUS_BACKGROUND, v.missing.get(5));
    }

    @Test public void missingListenerAccessAloneBlocks() {
        BackgroundReadiness.Input in = allGreen();
        in.notificationAccess = false;
        assertFalse(BackgroundReadiness.evaluate(in).mayClaimReady());
    }

    @Test public void nullInputFailsClosed() {
        assertFalse(BackgroundReadiness.evaluate(null).mayClaimReady());
    }
}
