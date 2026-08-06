package com.replymate.core.usecase;

import com.replymate.core.ports.KvStore;

/** Owner profile (S07). Stored as non-secret kv entries (BLUEPRINT §3.2 reserved keys). */
public final class ProfileService {

    public static final String KEY_NAME = "profile.name";
    public static final String KEY_LANGUAGES = "profile.languages";
    public static final String KEY_BIO = "profile.bio";
    public static final String KEY_TOPICS = "profile.topics";
    /** P4: private free-text "About me" extra box. */
    public static final String KEY_EXTRA = "profile.about_extra";
    /** P4: per-section use toggles ("1" default) — the user controls what the AI may use. */
    public static final String USE_NAME = "profile.use.name";
    public static final String USE_LANGUAGES = "profile.use.languages";
    public static final String USE_BIO = "profile.use.bio";
    public static final String USE_TOPICS = "profile.use.topics";
    public static final String USE_EXTRA = "profile.use.extra";

    /** Immutable profile value. */
    public static final class Profile {
        public final String name;
        public final String languages;
        public final String bio;
        public final String topics;

        public Profile(String name, String languages, String bio, String topics) {
            this.name = clean(name);
            this.languages = clean(languages);
            this.bio = clean(bio);
            this.topics = clean(topics);
        }

        public boolean isEmpty() {
            return name.isEmpty() && languages.isEmpty() && bio.isEmpty() && topics.isEmpty();
        }

        /** Name used by the prompt layer when the owner hasn't set one. */
        public String displayName() {
            return name.isEmpty() ? "the owner of this phone" : name;
        }
    }

    private final KvStore kv;

    public ProfileService(KvStore kv) {
        this.kv = kv;
    }

    public Profile load() {
        return new Profile(
            kv.get(KEY_NAME, ""),
            kv.get(KEY_LANGUAGES, ""),
            kv.get(KEY_BIO, ""),
            kv.get(KEY_TOPICS, ""));
    }

    public void save(Profile p) {
        kv.put(KEY_NAME, p.name);
        kv.put(KEY_LANGUAGES, p.languages);
        kv.put(KEY_BIO, p.bio);
        kv.put(KEY_TOPICS, p.topics);
    }

    /* --------------------------------------------------- P4: toggles + extra box */

    public String extra() {
        return kv.get(KEY_EXTRA, "").trim();
    }

    public void saveExtra(String extra) {
        kv.put(KEY_EXTRA, clean(extra));
    }

    public boolean use(String useKey) {
        return "1".equals(kv.get(useKey, "1"));
    }

    public void setUse(String useKey, boolean on) {
        kv.put(useKey, on ? "1" : "0");
    }

    /** The profile as the prompt layer may see it: toggled-off sections removed. */
    public Profile loadFiltered() {
        Profile full = load();
        return new Profile(
            use(USE_NAME) ? full.name : "",
            use(USE_LANGUAGES) ? full.languages : "",
            use(USE_BIO) ? full.bio : "",
            use(USE_TOPICS) ? full.topics : "");
    }

    /** Extra box visible to the prompt layer, honoring its own toggle. */
    public String extraFiltered() {
        return use(USE_EXTRA) ? extra() : "";
    }

    /** Audit notes for excluded sections (PromptAudit "why"). */
    public java.util.List<String> excludedSections() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (!kv.get(KEY_NAME, "").trim().isEmpty() && !use(USE_NAME)) out.add("name excluded by toggle");
        if (!kv.get(KEY_LANGUAGES, "").trim().isEmpty() && !use(USE_LANGUAGES)) out.add("languages excluded by toggle");
        if (!kv.get(KEY_BIO, "").trim().isEmpty() && !use(USE_BIO)) out.add("bio excluded by toggle");
        if (!kv.get(KEY_TOPICS, "").trim().isEmpty() && !use(USE_TOPICS)) out.add("topics excluded by toggle");
        if (!extra().isEmpty() && !use(USE_EXTRA)) out.add("about-me extra excluded by toggle");
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
