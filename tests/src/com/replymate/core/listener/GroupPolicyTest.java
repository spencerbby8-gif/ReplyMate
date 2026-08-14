package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-13: the group opt-in policy — fail-closed default, master
 *  switch, per-app override precedence, corrupt-value safety. */
public final class GroupPolicyTest {

    @Test public void freshInstallReadsOffEverywhere() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertFalse(GroupPolicy.globalEnabled(kv));
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            assertFalse(def.label + " must default OFF", GroupPolicy.allowed(kv, def.channel));
        }
        assertFalse("null channel never slips through", GroupPolicy.allowed(kv, null));
        assertFalse("null kv never slips through", GroupPolicy.allowed(null, Channel.WHATSAPP));
    }

    @Test public void masterOnEnablesEveryAppThatHasNoOverride() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        assertTrue(GroupPolicy.allowed(kv, Channel.WHATSAPP));
        assertTrue(GroupPolicy.allowed(kv, Channel.TELEGRAM));
        assertTrue(GroupPolicy.allowed(kv, Channel.SLACK));
    }

    @Test public void perAppOverrideBeatsTheMasterBothWays() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        // master OFF, whatsapp explicitly ON
        kv.put(GroupPolicy.keyFor(Channel.WHATSAPP), "1");
        assertTrue(GroupPolicy.allowed(kv, Channel.WHATSAPP));
        assertFalse("telegram inherits the OFF master", GroupPolicy.allowed(kv, Channel.TELEGRAM));
        // master ON, telegram explicitly OFF
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        kv.put(GroupPolicy.keyFor(Channel.TELEGRAM), "0");
        assertTrue(GroupPolicy.allowed(kv, Channel.WHATSAPP));
        assertFalse(GroupPolicy.allowed(kv, Channel.TELEGRAM));
    }

    @Test public void corruptValuesAreFailClosedAndTheCycleIsStable() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        kv.put(GroupPolicy.KV_GLOBAL, "banana");
        kv.put(GroupPolicy.keyFor(Channel.WHATSAPP), "yes please");
        assertFalse(GroupPolicy.globalEnabled(kv));
        assertFalse(GroupPolicy.allowed(kv, Channel.WHATSAPP));
        assertEquals("", GroupPolicy.overrideFor(kv, Channel.WHATSAPP));
        // the settings row cycle: inherit → on → off → inherit
        assertEquals("1", GroupPolicy.nextOverride(""));
        assertEquals("0", GroupPolicy.nextOverride("1"));
        assertEquals("", GroupPolicy.nextOverride("0"));
        assertEquals("1", GroupPolicy.nextOverride("garbage"));
    }
}
