package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.KvStore;

/** P-intelligence-3 (owner directive): privacy mode DETECTION + the copy shown for
 *  it. The mode is derived from the actual provider/account setup — NEVER from the
 *  mere existence of an API key:
 *
 *    NONE    — no provider configured at all.
 *    FREE    — the riskier default: the active setup is free/unpaid (including the
 *              solo-builder BUILT-IN key, which is free provider mode BY DESIGN and
 *              can never be presented as private).
 *    LOCAL   — the active provider runs on this device or the local network
 *              (Ollama / localhost / RFC-1918 / .local) — prompts never leave the
 *              owner's own environment.
 *    PRIVATE — the owner's OWN key, which they have explicitly flagged as a
 *              paid/private plan (ReplyMate cannot probe a provider's billing —
 *              there is no official billing-read API — so the honest detection
 *              input is the account setup the owner declares; an unflagged key
 *              stays FREE). "Do not claim private mode unless the setup is
 *              actually paid or private."
 *
 *  Pure + unit-tested: same inputs ⇒ same mode ⇒ same notice, forever. */
public final class ProviderPrivacy {

    /** SecretVault alias for the solo-builder built-in key (seeded at first launch
     *  from build-injected resources — NEVER committed to the repo). Any provider
     *  row pointing at it is free mode by design. */
    public static final String BUILT_IN_KEY_REF = "provider.builtin.key";

    public enum Mode { NONE, FREE, LOCAL, PRIVATE }

    private ProviderPrivacy() { }

    /** kv key for the owner-declared account plan of one provider row. */
    public static String planKey(long providerRowId) {
        return "provider.plan." + providerRowId;
    }

    /** The detected mode for the ACTIVE provider (kv may be null in tests). */
    public static Mode modeFor(ProviderDef active, KvStore kv) {
        if (active == null) return Mode.NONE;
        if (BUILT_IN_KEY_REF.equals(active.keyRef)) return Mode.FREE;   // never private
        if (isLocalProvider(active)) return Mode.LOCAL;
        if (kv != null && "paid".equals(kv.get(planKey(active.id), ""))) {
            return Mode.PRIVATE;
        }
        return Mode.FREE;
    }

    /** True when the endpoint is on this device or the owner's own network. */
    public static boolean isLocalProvider(ProviderDef def) {
        if (def == null) return false;
        if (def.type != null && "ollama".equals(def.type.wire)) return true;
        String host = hostOf(def.baseUrl);
        if (host.isEmpty()) return false;
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }
        if (host.endsWith(".local") || host.endsWith(".lan")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;   // 172.16.0.0/12
                } catch (NumberFormatException ignored) { }
            }
        }
        if (host.startsWith("169.254.")) return true;                  // link-local
        return false;
    }

    /** Bare host from a base URL (no scheme/port/path), lowercased. */
    static String hostOf(String baseUrl) {
        if (baseUrl == null) return "";
        String s = baseUrl.trim().toLowerCase(java.util.Locale.US);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < 0) s = s.substring(0, colon);
        return s;
    }

    /* -------------------------------------------------------------- notice copy */

    /** One-line badge for provider rows / the mode chip (never a privacy CLAIM
     *  that wasn't detected). */
    public static String badge(Mode mode) {
        switch (mode) {
            case PRIVATE: return "private (paid plan)";
            case LOCAL:   return "private (local)";
            case FREE:    return "free mode";
            default:      return "no provider";
        }
    }

    /** Short line for the Home banner. */
    public static String headline(Mode mode, boolean builtIn) {
        switch (mode) {
            case PRIVATE:
                return "Privacy: paid/private plan — prompts aren't used to train models (per provider terms)";
            case LOCAL:
                return "Privacy: local provider — prompts never leave your own device/network";
            case FREE:
                return builtIn
                    ? "Free mode (built-in ReplyMate key) — prompts may be used to improve the provider's models"
                    : "Free mode — your prompts and replies may be used to improve the provider's models";
            default:
                return "No AI provider configured — add one to generate replies";
        }
    }

    /** Full notice: what is true right now + exactly how to go private. */
    public static String noticeBody(Mode mode, String providerLabel, boolean builtIn) {
        String label = providerLabel == null || providerLabel.trim().isEmpty()
            ? "the provider" : providerLabel;
        switch (mode) {
            case PRIVATE:
                return "You're using your own " + label + " key on a paid/private plan."
                    + " As agreed in " + label + "'s API terms, your prompts and replies"
                    + " are not used to train their models. Everything else ReplyMate"
                    + " keeps (messages, memory, learning) never leaves this phone at all.";
            case LOCAL:
                return "Replies come from your own local provider (" + label
                    + ") — prompts and replies stay inside your device or local network."
                    + " Everything else ReplyMate keeps never leaves this phone either.";
            case FREE:
                return (builtIn
                        ? "This build ships with the built-in ReplyMate key (solo-builder"
                        + " mode) — that is a FREE, shared provider key."
                        : "Your current " + label + " setup is free or unpaid.")
                    + " Texts sent to generate a reply, and the replies themselves, MAY"
                    + " be used by " + label + " to improve their models. ReplyMate"
                    + " will not call this private mode — because it isn't. Don't paste"
                    + " secrets into chats on free mode. To go private: Settings →"
                    + " AI providers → add YOUR OWN key, then turn on \"Paid/private"
                    + " plan\" for it.";
            default:
                return "ReplyMate needs an AI provider to draft replies. Open Settings"
                    + " → AI providers and add your API key to start — paid/private if"
                    + " you want prompts kept out of model training.";
        }
    }

    /** True when the running setup is the solo-builder built-in key. */
    public static boolean isBuiltIn(ProviderDef active) {
        return active != null && BUILT_IN_KEY_REF.equals(active.keyRef);
    }
}
