package com.replymate.core.auth;

/** Cold-start routing decision — one pure function over the stored session, so the
 *  splash stays dumb and the policy stays unit-tested. Guest sessions are valid
 *  sessions (the owner chose Gate-with-Guest: sign-in is offered, not forced). */
public final class AuthGate {

    public enum Route {
        AUTH,          // no usable session → show the sign-in / guest screen
        REFRESH_THEN_HOME,   // session close to expiry → refresh, then continue
        HOME,          // valid session (real or guest)
        ONBOARDING     // authenticated but the owner never completed profile setup
    }

    private AuthGate() { }

    /**
     * @param session stored session (null = never signed in / signed out)
     * @param nowMs current time
     * @param onboardingDone whether the profile/avatar step has been completed
     */
    public static Route decide(AuthSession session, long nowMs, boolean onboardingDone) {
        if (session == null || session.accessToken.isEmpty()) return Route.AUTH;
        if (!onboardingDone) {
            // guests may skip onboarding freely; a REAL account finishes its profile
            if (!session.isGuest()) {
                return session.needsRefresh(nowMs) ? Route.REFRESH_THEN_HOME : Route.ONBOARDING;
            }
        }
        return session.needsRefresh(nowMs) ? Route.REFRESH_THEN_HOME : Route.HOME;
    }
}
