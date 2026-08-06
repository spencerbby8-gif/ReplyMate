package com.replymate.core.supabase;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.*;

/** Pins the Supabase foundation contract (foundation phase):
 *  - endpoint values are present and well-formed;
 *  - ONLY a publishable (public-by-design) key is embedded — never a secret key;
 *  - SYNC IS OFF and must stay off until a separately approved phase;
 *  - the table list mirrors migration 0001's provisioned set. */
public final class SupabaseConfigTest {

    @Test public void endpointIsWellFormed() {
        assertTrue(SupabaseConfig.PROJECT_URL.startsWith("https://"));
        assertTrue(SupabaseConfig.PROJECT_URL.endsWith(".supabase.co"));
        assertEquals(SupabaseConfig.PROJECT_URL + "/rest/v1", SupabaseConfig.REST_URL);
        assertEquals(SupabaseConfig.PROJECT_URL + "/auth/v1", SupabaseConfig.AUTH_URL);
    }

    @Test public void onlyPublishableKeyIsEmbedded() {
        assertTrue(SupabaseConfig.ANON_KEY.startsWith("sb_publishable_"));
        // guard rails: a service/secret key or a JWT-looking key must never land here
        assertFalse(SupabaseConfig.ANON_KEY.startsWith("sb_secret_"));
        assertFalse(SupabaseConfig.ANON_KEY.contains("\n"));
    }

    @Test public void syncIsHardOff() {
        assertFalse("sync must stay disabled in the foundation phase",
            SupabaseConfig.SYNC_ENABLED);
    }

    @Test public void tableSetMatchesMigration0001() {
        Set<String> expected = new HashSet<String>(Arrays.asList(
            "style_profile", "contact", "contact_channel", "message", "memory_fact",
            "contact_summary", "provider_def", "draft", "usage_event", "app_kv"));
        Set<String> actual = new HashSet<String>(Arrays.asList(SupabaseConfig.TABLES));
        assertEquals(expected, actual);
        assertEquals(10, SupabaseConfig.TABLES.length);
    }
}
