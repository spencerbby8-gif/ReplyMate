# P-background-2 — BLOCKING AUDIT — 1.4.1 / vc22 (2026-08-07)

The Background Assistant failed on the owner's real device. No new features, no
polish — pipeline re-read end-to-end against code + official docs, the failure was
traced to an exact root cause, fixed, and the reliability/performance/diagnostics
mandates were implemented and pinned.

## ☠ ROOT CAUSE (100% repro on device, now fixed)

`AssistantRunner` (1.4.0) scheduled generation with
`new Handler(Looper.getMainLooper()).postDelayed(...)` → the fire Runnable executed
**on the UI thread**, where the provider calls a **blocking `HttpURLConnection`**
(`provider/http/HttpClient.java:30-32,67-69`) → Android throws
`NetworkOnMainThreadException` (documented since API 11 for targetSdk ≥ 11) → caught
by the runner's RuntimeException guard and written to a log line → **every single
background generation died before any network request**, silently for the user.

**Fix:** the main-looper Handler is now a pure delay timer; the fire callback hops
to `Tasks.bg` before any work. Regenerate path was already on `Tasks.bg`.

## 📦 Downloads

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.1.apk` | 211,194 | `07b7d851702f25c9565437312ad3a2d65e7e4992e828065aec96415b546977c8` |

Cert `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed` — unchanged → updates in place.

## 🔬 The 12-stage audit (each verified against code/tests/docs — not assumed)

| # | stage | verdict | evidence |
|---|---|---|---|
| 1 | Listener receives the notification | ✅ | `RmNotificationListener.onNotificationPosted` → `Tasks.bg` → extractor; read-only pattern unchanged since P2, exercised on-device daily |
| 2 | Attached to existing conversation, no duplicates | ✅ | identity resolution + ContactMerger fork-heal (1.1.0) — `ContextIsolationTest`/merger tests green; per-notification content-hash dedupe in IngestCoordinator |
| 3 | Latest conversation state reconstructed | ✅ | runner reads fire-time `lastMessages(...,30)` + latest-INCOMING scan; same logic as `DraftService` (ReplyContextHonesty tests) |
| 4 | Generation job scheduled exactly once | ✅ FIXED+PINNED | `JobCoalescer` token identity: newer schedule supersedes older automatically; stale token = no-op; in-flight job rechecked BEFORE provider call. `JobCoalescerTest` (5 tests) |
| 5 | Provider request actually executed | ❌→✅ | **was RC-broken (main thread)**; now runs on `Tasks.bg`; call path identical to manual generation used daily; aborted-early jobs record a SCHEDULE event |
| 6 | Generated reply returns | ✅ (code) | same DraftService→gateway→adapter path as manual (live-verified in 0.7.0 era); device confirmation via ledger/alert below |
| 7 | Draft saved correctly | ✅ | persisted inside `DraftService.generateForContact`; unsaved drafts purged on replace; `DraftStore.updateStatus` on send/copy |
| 8 | ReplyMate notification posted | ✅ | `AssistantNotifier.post` (channel `rm_assistant`, permission-gated, stable tag) — after the RC fix this actually runs |
| 9 | Approve sends via RemoteInput when supported | ✅ structure + 🟡 device | official contract (`RemoteInput.addResultsToIntent` + action PendingIntent, docs-audited); live re-resolve via listener `getActiveNotifications`; outcome recorded as REMOTE_SEND ledger event — owner's tap produces the device evidence |
| 10 | Regenerate replaces draft + alert | ✅ | same tag/id (update-in-place) + DraftService purge-on-regen (1.1.0 rule) |
| 11 | Open Conversation opens the right chat | ✅ | identical `EXTRA_CONTACT_ID` deep link as daily pings |
| 12 | Unsupported apps fall back honestly | ✅ | capability from extracted action evidence (never titles); Copy/Regenerate/Open + reasoned caption, `AssistantPlannerTest`-pinned; NOTIFY ledger event each time |

## ⚙ Reliability mandates (implemented + pinned)

- Duplicate jobs: impossible — coalescer tokens (test-pinned).
- Older jobs cancelled automatically: `begin()` supersedes; timer callback removed; in-flight pre-call abort (recorded).
- Only newest message used: fire-time reads.
- Never regenerate stale drafts: persisted FNV-1a content hash per contact.
- Never duplicate drafts / alerts: purge-on-regen + stable `assistant:c<id>` tag.
- Dismissal / deletion / listener restart / reboot / timeout / network / battery:
  each has an explicit path + ledger event (approve fallback, contact-gone guard,
  listener-null fallback, kv-persisted dedupe across reboots, honest generation
  failure with retry-on-next-message, zero background service to kill).
- Remaining bounded edge (declared): a provider call already MID-FLIGHT when
  superseded runs to completion but can never post (token gate) — one extra call
  per conversation burst maximum; no silent misbehavior.

## 🔋 Performance

No polling. No wake-locks. No FGS. Debounced single run per burst (+coalescer).
Aborts before provider calls when obsolete. Hash dedupe prevents repeat calls.
Reuse: unchanged-burst re-posts are skipped entirely (draft remains canonical).

## 🩺 Diagnostics expansion (owner's 8 mandated fields)

`AssistantEvent` (stage, conversation, provider, model, alert id, sbn key, reason,
action, fix) → bounded JSON ledger (24) in kv → newest-first **"Assistant ledger"**
section in Settings → Diagnostics (also shows the master-switch state). Round-trip,
wire-name, special-char, and render tests pin the shape. Every silent path records:
permission gate, generation gates, notify fallback, supersede-abort, approve
resolve failures, remote-send failures/fallbacks, copy fallback, plus sent records.

## 🔁 GitHub + workspace audits (after the fix)

- GitHub: sandbox had rolled back AGAIN; restored local from remote `main`
  (`reset --hard`), suite re-verified 450/440-era green at that point, then this
  phase pushed. Final: **remote main = local HEAD = `727d687`** (see below) —
  remote file parity already byte-proven last phase via raw fetches.
- Workspace: kept production source + latest APK + toolchain + vault + test infra;
  removed `releases/ReplyMate-1.4.0.apk` (superseded) + build intermediates.

## ✅ Verification suite

**460/460 JVM unit green** (10 new: JobCoalescer ×5, AssistantEvent ×5). APK battery:
vc22/1.4.1, minSdk 24/target 34, cert match, dex contains fix classes, zero secret
strings, zero dev-secrets entries. On-device stages flagged individually above —
the ledger now makes them self-evidencing, no trust required.

## 🧪 Owner device pass (self-verifying now)

1. Reinstall 1.4.1 → message yourself on WhatsApp → **"Reply ready for …" appears
   ~5s after the burst settles** (this previously never happened — RC).
2. Regenerate → same alert updates in place; Diagnostics → ledger shows stages.
3. Approve & send → lands in WhatsApp, alert settles "Sent ✓", ledger records REMOTE_SEND.
4. Dismiss the WhatsApp notification first → Approve → honest copy fallback + ledger reason.
5. Instagram chat → Copy/Regenerate/Open with the caption reason.
6. Private contact → no alert at all (gate line in ledger).
