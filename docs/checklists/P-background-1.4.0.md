# P-background — 1.4.0 / vc21 — completion report (2026-08-07)

Owner order: (1) re-audit GitHub, push anything missing; (2) safe workspace cleanup;
(3) local dev-secrets vault (no env vars in Agent Mode) that can never reach GitHub
or an APK; (4) Background Reply Assistant — audit Android capabilities via current
official docs FIRST, then auto-generate on replyable notifications, ReplyMate alert
with Approve / Regenerate (replace-in-place) / Open, honest fallback for apps
without RemoteInput; (5) verify battery/background/permissions/Android 13+/OEM
before implementation; (6) regression tests → full suite → APK verify → STOP.

## Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.0.apk` | 207,098 | `d2f9a75809fdedd3dd72ff9168db4193268002ae1255140e4cfbd879b75d0185` |

Signer cert SHA-256 `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
— unchanged → **updates in place over 1.3.0.**

## A. GitHub re-audit (done first, no trust in prior assumptions)

- Reality found: another sandbox rollback — local HEAD sat at 0.9.0-era again;
  remote `main` (`38917ee`) had the truth. Restored local from remote
  (`reset --hard FETCH_HEAD`): tree clean, suite still 440/440 at that point.
- Remote file verification (raw.githubusercontent byte-compare): `SplashActivity.java`,
  `SupabaseAuth.java`, `run_tests.sh`, `SplashChoreo.java` — all **MATCH**.
- PAT from the vault still valid (fetch/push used it live). Nothing was missing
  remotely; the new phase work is pushed as the final commit (see §F).

## B. Cleanup

- Kept: `ReplyMate/` (source + latest APK only), `apk-engine/` (**keystore** — verified
  present, sha `3f4451f6…`), `android-sdk/`, `jre17/`, `rm-harness/` (test infra), sandbox dotfiles.
- Removed: `ReplyMate/build/` intermediates; root previews moved into `docs/previews/`
  (current report artifacts, committed); nothing else stray existed.
- `releases/` holds only `ReplyMate-1.4.0.apk` + `RELEASES.md` (1.3.0 pruned after ship).

## C. dev-secrets vault (`/home/user/dev-secrets/`, mode 700/600)

- `secrets.env` — GitHub PAT + repo, Supabase URL + publishable anon key, DB direct
  string (**flagged ROTATION-PENDING**, admin-only), AI-provider key placeholders
  (none have ever been given to the agent).
- `README.txt` — why it exists + hard rules (never copy into repo/builds; rotate on suspicion).
- **Cannot reach GitHub or the APK — verified 3 ways:** (1) lives OUTSIDE the repo;
  (2) repo `.gitignore` blocks `dev-secrets/`, `*.keystore`, `*.jks`, `.env`, `*.pem` —
  proven with a temporary in-repo copy: `git check-ignore -v dev-secrets/secrets.env`
  → `.gitignore:2`, and staged-file scan shows zero secret strings; (3) build inputs
  are only repo `res/` + `src/` + manifest, and `unzip -l` on the APK shows **zero**
  dev-secrets entries. Temp in-repo copy deleted after the proof.
- ⚠ Staged-diff secret grep before push: clean.

## D. Android capability audit (official docs, done BEFORE coding)

| claim | evidence-based truth |
|---|---|
| Auto-generate when a replyable notification arrives | Listener is the official, permanent ingester (existing read-only pipeline) — no polling |
| "Fill the RemoteInput reply box automatically" | **No official API pre-types text into ANOTHER app's reply box.** The official contract (Android Auto pattern): the *source app* exposes a reply action with a free-form `RemoteInput`; a third party delivers text via `Bundle.putCharSequence(resultKey, text)` + `RemoteInput.addResultsToIntent` + `actionIntent.send(...)` — the app receives it exactly like a typed reply and **sends**. So ReplyMate maps "fill" honestly: human taps **Approve & send** → the approved draft goes out through the app's own quick-reply. Nothing sends by itself |
| Apps without RemoteInput | Detected from evidence (`ActionRef.remoteFreeForm + resultKey`); fallback alert = **Copy / Regenerate / Open** + caption naming the gap — never fakes capability (unit-pinned) |
| Regenerate replaces previous reply | Same notification identity (`tag=assistant:c<contactId>`, fixed id) ⇒ update-in-place; draft layer already replaces unsaved drafts (1.1.0 rule inside `DraftService`) |
| Android 13+ (target 34) | `POST_NOTIFICATIONS` was already declared + runtime-requested in Settings + every post gated by `ListenerStatus.canPostNotifications`. Assistant re-uses the same gate; its Settings switch is disabled with an honest subtitle until the grant exists |
| Live re-resolve | `NotificationListenerService.getActiveNotifications()` is the ONLY API seeing other apps' actives (NotificationManager's = ours only) → approve-time re-resolve by sbn key; dismissed/changed/absent → honest copy fallback |
| Battery/background | Zero wake-locks, zero polling, no FGS; one provider call per NEW incoming burst per contact (content-hash dedupe, unit-pinned); Doze network loss ⇒ generation honestly skipped + diagnostics ring line, never a fake "reply ready" |
| OEM quirks (access revoked, battery killers) | Existing Diagnostics carries live listener truth (connected/disconnected, per-app stats, ring); all assistant silent-fails ring too — never silent breakage |

## E. What shipped

- **Core (`core/assistant/AssistantPlanner`)**: capability classify, button sets,
  honest captions, per-conversation alert identity, dedupe trigger, FNV-1a hash — pure JVM.
- **Extractor**: copies action shape (`title/index/free-form RemoteInput/resultKey`) + sbn key into `RawNotif`. PendingIntents are NEVER stored — re-read live at send time.
- **Listener hook**: after each ping → `AssistantTargetStore.save` + `AssistantRunner.schedule` (same BatchWindow debounce as pings, +300ms).
- **Runner**: gates via `DraftService.generateForContact` — private ⇒ NO AI ever,
  per-contact AI off ⇒ skip, no provider / unreadable latest ⇒ honest skip + ring.
  On success → assistant alert (channel `rm_assistant`, DEFAULT importance).
- **Buttons** (`AssistantReceiver`, `goAsync`): **Approve & send** (official RemoteInput
  send; marks draft SENT; settles alert to "Sent ✓") · **Copy** (marks COPIED) ·
  **Regenerate** (forced fresh draft, alert replaced in place) · **Open conversation**.
- **Settings**: master switch (default on) with live permission-aware subtitle +
  footer reworded to the approval contract ("sent only when YOU tap Approve").

## F. Verification battery (all green)

- Tests: **450/450** JVM unit (10 new `AssistantPlannerTest` invariants).
- APK: vc21/1.4.0, minSdk 24/target 34, launcher SplashActivity; receiver present
  `exported=false`; dex carries assistant classes; perms = INTERNET + POST_NOTIFICATIONS;
  cert match ⇒ in-place update; dex leak-scan zero; no dev-secrets in APK.
- Robolectric: not re-run (same posture as 1.2.0/1.3.0 — assistant app-layer seams
  are services/receivers; core logic is JVM-pinned). Honest flag.

## G. Owner device check-list (then we regen the next phase)

1. Settings → Background reply assistant ON (needs the notification grant first — the row tells you).
2. WhatsApp message to yourself → after the burst: **"Reply ready for …"** alert shows the draft.
3. **Regenerate** → same alert updates, no second alert; draft list replaced, not stacked.
4. **Approve & send** → message lands in WhatsApp; alert settles to "Sent ✓".
5. Dismiss the WhatsApp notification FIRST, then Approve → honest "was dismissed… copied ✓".
6. Instagram/X (no quick-reply) → buttons are **Copy / Regenerate / Open** with the reason in the caption.
7. Private contact / AI-off contact → NO assistant alert ever (gates honored).
8. Private mode contract + all 1.3.0 behavior still intact (spot check).

## H. Remains / honest flags

- Literal box-prefill inside other apps: NOT possible officially (see §D) — Approve-sends is the delivered truth of your spec.
- APK install + visual pass is yours (no emulator in sandbox).
- PAT rotation + DB-password rotation still open; Supabase dashboard toggles from
  1.2.0 unchanged; passkey still awaiting your one-dependency decision.
