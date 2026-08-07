# P-auth — 1.2.0 / vc19 — completion report (2026-08-07)

Phase ordered by the owner: add onboarding/auth (Google + email 6-digit OTP + passkey explainer,
guest mode via Supabase anonymous sign-in), after the 1.1.1 UX fixes. Constraint kept: **official
Supabase Auth REST only, zero third-party libraries, no SDK.** Auth is optional for this build —
gate-with-guest, local-first preserved.

## Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.2.0.apk` | 194,292 | `6bd9f7dd17a03589aa3f6c4b82802f30e29452f4aaaa464c04c7464e1045f090` |

Signer cert SHA-256 `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
— unchanged → **updates in place over any previous install.**

## A. Owner's auth audit checklist — all 7 items answered

| # | audit item | where it lives | how verified |
|---|---|---|---|
| 1 | **Login** | `AuthActivity` + `SupabaseAuth` (REST): Google → system browser with PKCE (`/auth/v1/authorize` + `/auth/v1/token?grant_type=pkce`, verifier held in kv `auth.pkce.verifier`, callback `replymate://auth/callback`); Email → 6-digit code (`/auth/v1/otp` + `/auth/v1/verify type=email`), no magic links; Guest → anonymous (`/auth/v1/signup {}`) | 13 `AuthFlowTest` tests (token-response parsing, PKCE exchange, OTP verify, anonymous) + RFC 7636 pinned vector (verifier `dBjftJeZ…` → challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`) + live probes below |
| 2 | **Logout** | Settings→Account / Account screen → confirm dialog → `POST /auth/v1/logout?scope=global` (401 treated as already-out), then session + PKCE verifier + OTP timer wiped from kv; local app data untouched | `logoutClearsSessionAndKeepsLocalData` test; route-after-logout gate test |
| 3 | **Account restore** | Session JSON in kv `auth.session.v1` survives process death; cold start → `AuthGate` (AUTH / ONBOARDING / HOME / REFRESH_THEN_HOME); if token within 5-min expiry window → background `refresh_token` grant, app never blocked | `AuthGate` route tests incl. expired/valid/anonymous matrix; `KvSessionStore` round-trip test |
| 4 | **Avatar save** | `OnboardingActivity`: pick (ACTION_GET_CONTENT) → `AvatarStore` square-crop ≤512px JPEG q88 atomic write to `filesDir/avatar.jpg` → instant preview → best-effort cloud sync (`POST /storage/v1/object/avatars/<uid>.jpg` x-upsert, public URL, `updateUserMetadata avatar_url`) | local save/crop/atomic-replace unit tests; sync failure paths return honest errors (bucket-missing/offline) and never block onboarding |
| 5 | **Session persistence** | `SessionStore`/`KvSessionStore` (`auth.session.v1`, `auth.onboarding.done`); expiry carried as `expires_at`; `needsRefresh` 5-min window; `is_anonymous` tracked for link-later | round-trip + expiry-window + anonymous-flag tests |
| 6 | **Error handling** | `AuthErrors` maps REAL live-probed shapes: `anonymous_provider_disabled` → "isn't enabled on the ReplyMate server (Authentication → Providers → Anonymous)…"; `email_address_invalid` → "can't receive codes — use a real inbox…"; `otp_expired` (403); `validation_failed` provider-not-enabled (400); `flow_state_not_found` (404); network failures → offline copy | `liveProbedErrorShapesMapToHonestCopy` pins each mapping against the captured bodies in `docs/provider-probes/auth-live-probe-1.2.0.txt` |
| 7 | **Loading states** | Every auth action sets a busy state: buttons disable + inline progress while calling; resend locked 60 s (`auth.otp.sent_at`); OAuth launch, token exchange, guest sign-in, avatar save all show progress; gate never shows a dead screen | state-machine tests + layout contains progress affordances; on-device check listed in §G |

## B. Test evidence — 434/434 unit green (re-run after build, this session)

- Harness: `PATH=/usr/lib/jvm/jdk-11/bin:$PATH bash scripts/run_tests.sh` → `OK (434 tests)`.
- Added `AuthFlowTest` (13 tests): session JSON round-trip, needsRefresh window, fromTokenResponse
  (`expires_at`/`expires_in`), anonymous flag, PKCE RFC 7636 vector, authorize URL builder,
  deep-link query+fragment error parsing, gate routing matrix, live-probed error-shape mapping.
- **Honest note:** the 25 Robolectric suites were last run green at 1.1.1 (vc18). They were NOT
  re-run for 1.2.0 — they don't cover auth (UI-level), and core changes behind auth are covered by
  the JVM suite. Flagging rather than assuming.

## C. Docs re-verified by web search (2026-08-07, before auth code was written)

- PKCE flow endpoints (`authorize` → `token?grant_type=pkce`) — Supabase auth server guide.
- Email OTP under PKCE: code verify returns the session in the response body ("no behavior change").
- Anonymous sign-in: `POST /auth/v1/signup {}` requires the dashboard toggle (off by default).
- `GET`/`PUT /auth/v1/user`, `logout?scope=global`, refresh grant.
- Google installed-app OAuth: custom-scheme redirects are dead for Google's own OAuth clients →
  Supabase web-callback broker + app deep link is the clean zero-lib path.
- Passkeys: Supabase docs list passkeys as **experimental**, opt-in, official support in JS
  v2.105.0+/Flutter/Swift; Android ceremony requires Credential Manager (third-party lib) →
  violates the zero-lib rule → deferred (§F).

## D. Live probes against the real project (transcript: `docs/provider-probes/auth-live-probe-1.2.0.txt`)

| probe | result | meaning |
|---|---|---|
| `GET /auth/v1/settings` | `email:true`, `google:false`, `anonymous_users:false`, `disable_signup:false`, `mailer_autoconfirm:false`, `passkeys_enabled:false` | email OTP live today; Google & guest need dashboard toggles |
| `POST /auth/v1/signup {}` | **422 `anonymous_provider_disabled`** | honest actionable error shipped |
| `POST /auth/v1/otp` (probe address) | **400 `email_address_invalid`** (Supabase blocks example.com) | owner must test with a real inbox |
| `POST /auth/v1/verify` bogus `000000` | **403 `otp_expired`** | mapped |
| `GET /auth/v1/authorize?provider=google` | **400 `validation_failed` "provider is not enabled"** | blocked until Google provider ON |
| `POST /auth/v1/token?grant_type=pkce` bogus | **404 `flow_state_not_found`** | mapped |

## E. APK battery (1.2.0 / vc19) — all green

- `badging`: name `com.replymate.app`, versionCode 19, versionName 1.2.0, minSdk 24, targetSdk 34; INTERNET present.
- Signer cert SHA-256 matches all previous releases → in-place update.
- Manifest (verified via `aapt2 dump xmltree --file AndroidManifest.xml` — positional form silently
  fails on this aapt2 build with "missing required flag --file"; the earlier empty probe was a
  tooling artifact, not a missing scheme): `.ui.SplashActivity` = MAIN/LAUNCHER, singleTask;
  `.ui.AuthActivity` exported singleTask with VIEW+DEFAULT+BROWSABLE `scheme="replymate" host="auth"`;
  `allowBackup=false`, `usesCleartextTraffic=false`.
- Leak scan of dex+res: **zero** hits for `service_role`, the DB password, `postgresql://`, `sb_secret`.
- Public-by-design strings present as intended: `supabase.co` URL, `sb_publishable_…` anon key,
  `auth/v1/authorize` path, auth button labels.
- Note: the publishable anon key is SUPPOSED to be embedded in clients — security lives in
  Supabase Auth + Row Level Security, not in hiding that key. Service-role/secret keys never ship.

## F. Deferred — with exact re-entry conditions

1. **Passkey sign-in.** Why: (a) Supabase marks passkeys experimental + opt-in and your project has
   `passkeys_enabled:false`; (b) the Android WebAuthn ceremony requires Credential Manager, a
   third-party dependency — violating the locked zero-lib rule. Shipped instead: an explainer row
   ("Passkeys — coming when…") so the UI promise matches reality. **Re-entry:** owner approves ONE
   dependency (`androidx.credentials`) **and** enables passkeys in the Supabase dashboard **and**
   Supabase passes Android/cross-platform stability beyond experimental. Then the ceremony +
   `POST /auth/v1/verify` passkey path get implemented and re-probed live.
2. **Real-mailbox OTP round trip.** Supabase rejects `example.com` (`email_address_invalid`), so a
   full send→receive→verify cycle can't be automated from the sandbox. Mapped error paths are
   probe-verified; the happy path must run on your device with a real inbox (§G).
3. **Guest + Google happy paths.** Server-side disabled today (live-verified). App shows honest,
   actionable errors until dashboard toggles flip (§H).
4. **Robolectric re-run** deferred to next phase boundary (auth UI not robo-covered anyway; JVM
   suite carries the auth logic).

## G. Owner on-device verification checklist

1. Install/update → splash animation (icon spring + wordmark) → gate routes.
2. **Guest**: Continue as guest → code shows honest "not enabled" error until §H step 1 → after toggle: lands in chats, all local features intact (anonymous sessions skip onboarding by design).
3. **Email OTP**: real inbox → 6-digit code → verify → onboarding.
4. **Onboarding**: pick avatar → preview updates → name prefilled from email prefix → Done (skip allowed).
5. **avatar sync** (optional §H step 4): check Supabase Storage `avatars/<uid>.jpg` + user metadata.
6. **Session persistence**: force-stop → cold restart → straight to Home, no re-login; background refresh only.
7. **Sign out** (Settings→Account) → confirm → session gone → gate back to auth screen; local chats untouched.
8. **Airplane mode**: every auth action shows the offline error, never a hang or dead screen.
9. **Google** (after §H step 2): Google button → browser → consent → returns to app signed in.

## H. Owner dashboard setup (project `xdcsxxwhvpiissetbrdr`) — numbered

1. **Guest mode**: Dashboard → Authentication → Sign In / Providers → **Anonymous** → enable → Save.
2. **Google**: Authentication → Sign In / Providers → **Google** → enable with a Google Cloud
   **Web application** OAuth client; Authorized redirect URI =
   `https://xdcsxxwhvpiissetbrdr.supabase.co/auth/v1/callback`.
3. **Return URL allowlist**: Authentication → URL Configuration → Redirect URLs → add
   `replymate://auth/callback`.
4. **Email template** (required — no magic links): Authentication → Email Templates → confirm the
   OTP template renders **`{{ .Token }}`** (a plain 6-digit code, not only a confirmation link).
5. **Avatar sync** (optional): Storage → create **public** bucket named `avatars`. Without it the
   avatar stays local (which is fully supported).
6. **⚠ SECURITY — rotate the leaked password NOW**: the Postgres direct-connection password was
   pasted in chat. Dashboard → Project Settings → Database → **Reset database password**. It was
   never bundled anywhere in the app (scanned, zero hits) — but treat it as compromised and rotate.

## I. Honest incident note — git history

After a sandbox snapshot restore, the commit objects for 1.0.0 (`5c74794`), 1.1.0 (`f6ff59c`) and
1.1.1 (`512d055`) vanished from the repo (HEAD sat at 0.9.0) while the working tree, checklists and
release APKs survived intact. This phase's commit consolidates 1.0.0→1.2.0 in one commit that names
the lost hashes. Per-version truth remains in `releases/RELEASES.md` + the shipped APKs (shas
above). No source or artifact was lost; only the intermediate commit boundaries.
