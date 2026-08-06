package com.replymate.core.usecase;

import com.replymate.core.ports.KvStore;

/** Owner profile (S07). Stored as non-secret kv entries (BLUEPRINT §3.2 reserved keys). */
public final class ProfileService {

    public static final String KEY_NAME = "profile.name";
    public static final String KEY_LANGUAGES = "profile.languages";
    public static final String KEY_BIO = "profile.bio";
    public static final String KEY_TOPICS = "profile.topics";

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

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
