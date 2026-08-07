# P-splash-auth-polish — 1.3.0 / vc20 — completion report (2026-08-07)

Owner order: (1) re-audit the current build before changing anything, (2) clean the
workspace + push all source to GitHub, (3) rebuild the opening animation around the
icon — modern/premium/normal-paced, (4) auth UI polish — real Google logo, serious
sign-up screen, then full verification before stopping.

## Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.3.0.apk` | 203,002 | `09df75e01e162601c0e0b27b2fec1192a4d159a1132e63d9ab1ad45a6b1ca52d` |

Signer cert SHA-256 `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
— unchanged → **updates in place over 1.2.0.**

## A. Re-audit of the shipped build (before any change)

| check | result |
|---|---|
| Latest shipped | 1.2.0/vc19, sha `6bd9f7dd…f090` — matched RELEASES.md byte-for-byte |
| Test suite | 434/434 JVM unit green at audit time |
| Manifest/gate | SplashActivity = launcher; `replymate://auth` deep link present (verified via `aapt2 dump xmltree --file …` — the positional-arg form of this aapt2 build silently errors, earlier probe artifact identified) |
| Incidents found | Sandbox snapshot restores had rolled git back again: the 1.0.0–1.1.1 commits, the first 1.2.0 consolidation commit, and the auth probe transcript file were lost from `.git`/disk — working tree otherwise intact. All re-landed (`a26cc73`) and pushed to GitHub, which is now the durable record. |

## B. GitHub push

- Remote: `github.com/spencerbby8-gif/ReplyMate` — repo **existed and is PUBLIC**, containing a **different, older Kotlin/Gradle prototype** on three `arena/…` branches (commits like "feat: generate reviewable drafts from platform events"). Those branches were left untouched.
- **Pre-push secret scan (tree + full history):** every key-shaped string is an explicit audit dummy (`AIzaSyAUDIT-INVALID…`, `sk-ant-audit-invalid…`, "(keys are dummy)"); no GitHub tokens, no DB password, no service-role/secret keys. Supabase publishable anon key is public-by-design. Safe for a public repo.
- Pushed production source as branch **`main`**; set it as the repo **default branch**. Verified: remote `main` = local HEAD `c400210…` (then final commit, see §F).
- ⚠ Owner follow-ups: the repo stays public unless you flip Settings → Change visibility; old `arena/…` branches + PR #1 are yours to close/delete; **revoke the PAT you pasted in chat** after this phase (GitHub → Settings → Developer settings → PATs) and mint a fresh one when needed.

## C. Workspace cleanup

| removed | why |
|---|---|
| 18 pre-1.2.0 APK binaries from `releases/` (tree) | "keep only the latest verified release"; shas stay in RELEASES.md, binaries recoverable from git history |
| `releases/ReplyMate-1.2.0.apk` after 1.3.0 shipped | superseded by latest verified |
| `/home/user/ArenaDemo.apk`, `apk-demo/`, `demo/` | sandbox demo scratch, not ReplyMate |
| `auth-dbg.apk`, `uxfix-dbg.apk` (home root) | stale unsigned debug artifacts |

Kept on purpose (all ReplyMate ops): `ReplyMate/` (source + latest APK), `apk-engine/`
(**irreplaceable signing keystore** + build scripts), `android-sdk/`, `jre17/`,
`rm-harness/` (Robolectric infra). Sandbox dotfiles (`.android`, etc.) untouched.

## D. Splash rebuild (owner brief: icon-centric, premium, normal pace, clean load)

Choreography (pure data in `core/ui/SplashChoreo`, 6 JVM invariants pin it):
1. **0ms** radial brand glow blooms behind the icon (950ms)
2. **130ms** thin accent ring settles in (560ms)
3. **150ms** hero icon — rounded-clipped — springs in, gentle overshoot (640ms)
4. **820ms** one light sweep crosses the icon face (520ms)
5. **640ms** wordmark rises while letter-spacing pulls 0.15em→0.02em (340ms)
6. **800ms** tagline fades (280ms) · **960ms** accent underline grows (300ms)

Motion ends ≈ 1.34s; frame rests to **MIN_SHOW 1.9s** so users actually see it; exit
is the same fade transition. Cold flash guard unchanged (theme bg #0D1117). The
`route()`/auth-gate/background-refresh logic is **byte-identical** to 1.2.0.

## E. Auth UI polish

- **Google button**: white pill + official **four-color G** (`ic_google_g.xml`, brand-standard geometry) + "Continue with Google"; dims to 55% alpha while busy.
- **Button system**: `btn_primary` (accent pill) for Send code / Verify; `btn_ghost` (hairline) for guest / edit profile; Sign-out = ghost with red label (destructive-lite).
- **Structure**: divider-labeled sections ("— OR CONTINUE WITH EMAIL · 6-DIGIT CODE, NO LINKS —"), letter-spaced caps, 52dp actions, OTP code field centered 22sp with 0.3 letter-spacing and `· · · · · ·` hint; welcome header uses the rounded hero mark (68dp) + 24sp title.
- **Zero logic changes**: ids, flow, PKCE exchange, OTP timer, guest, sign-out, deep-link handling — untouched.

## F. Verification battery (all green)

- Tests: **440/440** JVM unit (6 new SplashChoreoTest invariants).
- Badging: `com.replymate.app` vc20 / 1.3.0, minSdk 24, targetSdk 34, launchable `.ui.SplashActivity`.
- Cert match → in-place update (see above).
- All 7 new drawables confirmed inside the APK (resource dump); dex contains `SplashChoreo` + new button builders.
- Leak scan (dex strings): zero hits for `service_role`, `sb_secret`, DB password, `postgresql://`.
- Deep-link scheme `replymate`//`auth` still present in manifest (xmltree grep = 2 hits).
- Robolectric: not re-run (unchanged areas; infra note — suite last green at 1.1.1). Honest flag, same as 1.2.0.
- Not verifiable from sandbox (owner device): visual splash motion, Google button rendering, reveals-on-install. Expected behavior documented in §D/§E — anything off, say so.

## G. Remains / next (unchanged from 1.2.0)

- Dashboard toggles still pending on your Supabase project (Anonymous for guest, Google provider, `replymate://auth/callback` allowlist, `{{ .Token }}` email template, optional `avatars` bucket).
- ⚠ DB superuser password rotation (pasted in chat earlier) — still open unless done.
- Passkey row stays an explainer until the one-dependency exception is approved.
- Real-inbox OTP happy path is on-device only (Supabase blocks example.com in probes).
