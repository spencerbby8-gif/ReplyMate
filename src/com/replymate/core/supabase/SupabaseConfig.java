package com.replymate.core.supabase;

/** Supabase foundation config (foundation phase only).
 *
 *  The project URL and the PUBLISHABLE (anon) key are NON-SECRET by design — they
 *  are shipped in every Supabase client; the database is protected by the RLS
 *  policies applied in migration 0001, never by hiding this key. The Gemini API
 *  key stays exclusively in the Keystore vault and is unrelated to this file.
 *
 *  SYNC IS INTENTIONALLY OFF: this phase ships ONLY the endpoint configuration.
 *  Local storage remains the source of truth. No auth flow, no REST calls, no
 *  background sync exist anywhere in the app; flipping SYNC_ENABLED requires a
 *  separately approved phase. SupabaseConfigTest pins this. */
public final class SupabaseConfig {

    public static final String PROJECT_URL = "https://xdcsxxwhvpiissetbrdr.supabase.co";
    public static final String ANON_KEY = "sb_publishable_bmOsVq4BeVTpQLLJl0J7yA_e4zf6_Lm";

    public static final String REST_URL = PROJECT_URL + "/rest/v1";
    public static final String AUTH_URL = PROJECT_URL + "/auth/v1";

    /** Hard gate: must stay false until a future owner-approved sync phase. */
    public static final boolean SYNC_ENABLED = false;

    /** Cloud tables provisioned by migration 0001 (order matches rm_schema_migrations). */
    public static final String[] TABLES = {
        "style_profile", "contact", "contact_channel", "message", "memory_fact",
        "contact_summary", "provider_def", "draft", "usage_event", "app_kv"
    };

    private SupabaseConfig() { }
}
