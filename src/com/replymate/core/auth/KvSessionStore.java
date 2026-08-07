package com.replymate.core.auth;

import com.replymate.core.json.Json;
import com.replymate.core.ports.KvStore;

/** SessionStore on the existing local kv table — no new schema, honours local-first. */
public final class KvSessionStore implements SessionStore {

    private static final String KEY_SESSION = "auth.session.v1";
    private static final String KEY_ONBOARDED = "auth.onboarding.done";

    private final KvStore kv;

    public KvSessionStore(KvStore kv) {
        this.kv = kv;
    }

    @Override public AuthSession get() {
        return AuthSession.fromJson(kv.get(KEY_SESSION, ""));
    }

    @Override public void put(AuthSession session) {
        if (session == null) {
            clear();
            return;
        }
        kv.put(KEY_SESSION, Json.write(session.toJson()));
    }

    @Override public void clear() {
        kv.delete(KEY_SESSION);
    }

    @Override public boolean onboardingDone() {
        return "1".equals(kv.get(KEY_ONBOARDED, ""));
    }

    @Override public void setOnboardingDone(boolean done) {
        if (done) kv.put(KEY_ONBOARDED, "1");
        else kv.delete(KEY_ONBOARDED);
    }
}
