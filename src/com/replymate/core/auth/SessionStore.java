package com.replymate.core.auth;

/** Local persistence for the auth session. Guest mode means the app must never
 *  re-prompt on cold start — the session lives here until explicit sign-out. */
public interface SessionStore {
    AuthSession get();                     // null when signed out / corrupt
    void put(AuthSession session);
    void clear();
    boolean onboardingDone();
    void setOnboardingDone(boolean done);
}
