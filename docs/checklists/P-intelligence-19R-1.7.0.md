# P-intelligence-19R — DISCORD #general re-open + BACKGROUND RELIABILITY (1.7.0 / vc917)

Owner mandate (verbatim anchors):
1. "Reproduce the current #general failure. A normal Discord server/channel conversation
   with a valid Reply action must be treated as conversational, not an announcement…
   Use actual platform evidence. Do not infer 'announcement' from server/channel identity
   or sender/title alone… approve back into the SAME Discord channel/message target.
   True non-replyable announcements remain blocked. Never borrow another channel's
   action or PendingIntent."
2. "The Background Generation toggle still does not reliably take the user to the real
   Oppo/ColorOS 'Allow background activity' control… Android's power allowlist only
   removes most power-saving restrictions, not every restriction… Make ReplyMate guide
   the user to every real setting it actually needs, recheck state after returning…
   Do not claim 'Ready' when Android can still block execution. Optimize the pipeline
   so one slow provider/search/reasoning task cannot delay other conversations.
   Measure capture → scheduling → provider → draft → notification."
3. "Add regression tests for Discord #general, true announcements, cross-channel target
   safety, listener/process recovery, background restrictions, slow provider isolation,
   and real-device generation."

- Head: `66e978b` (P-intelligence-19R 1/2) → checklist commit at gate 1022/1022.

## 0. Previous build verified first (gate @ HEAD, artifact re-check)

| Check | Result |
|---|---|
| Repo state | fresh clone `.git` @ `08b2430` (P-19 2/2); scripts chmod restored; working tree clean |
| JVM gate @ HEAD | **1007/1007 OK** before any change (baseline re-run in-gate) |
| Prior CI | run 31968320488 re-queried: `completed success` @ c46c311 |
| Prior artifact (ReplyMate-1.6.9-discordfix-proof.apk) | sha256 `86197b7c59ecb656fb0dc13658dfffcec04ba721d1c37a8e43272d89c4bf2643` re-verified MATCH; release cert `B1:5F:2F:37…6A:85:ED` re-verified MATCH off-CI |

## 1. §1 Discord — reproduction FIRST, then root cause

**Reproduction (in-gate, before any fix):** new `IngestReopenTest` ran at HEAD `08b2430`
→ **3 failures / 1012** (exact on-device chain, two dedupe branches + audit trail):
1. `discordGeneralReopensWhenTheUpdatePostBringsTheReplyAction_sameKey` —
   `expected:<1> but was:<0>`: the SAME-key update post carrying the real Reply action
   was swallowed by exact-key dedupe; #general stayed blocked (no ping, no generation).
2. `discordGeneralReopensWhenTheUpdatePostGainsTheConversationId_nearDup` —
   `expected:<1> but was:<0>`: the update post that ALSO publishes the native
   conversation id died in the P-bg-10 near-dup collapse; same permanent block.
3. `reopenedThreadLeavesAnOwnerVisibleRingLine` — no audit trail existed.

Controls passed pre-fix as designed: a true announcement never re-opens; a
conversational row is never demoted by a later less-complete capture.

**Root cause (audited code path, not guessed):** Discord single-shot server-channel
notifications arrive in the self-announce geometry (`senderName == conversationTitle`,
group, e.g. `#general`). Messengers post the alert first and attach the Reply action a
beat later — this codebase has documented that exact timing for WhatsApp since
P-background-6 ("the capture-time raw can predate the visible actions"), and the
send-path already re-probes the live shade at notify time for the same reason. The
listener fires once per post/update. At the FIRST post, the P-17/19 classifier is
honest about its evidence level → `announcement`, stored `item_class=announcement`,
demoted STORE_ONLY (no ping, no generation, no captured target). The UPDATE post —
the one the shade actually ends up showing with its Reply action — then dies in a
dedupe branch (`getByNotifKey` exact hit, or `findRecentSame` on remote-key drift)
WITHOUT re-evaluating the stamp. From then on: DraftService hard-stops on the stale
stamp even for in-app Generate ("This message can't be replied to from ReplyMate"),
so the conversation is blocked forever while the shade visibly offers Reply. 1.6.9's
classifier fix was correct but could only fire when capability arrived WITH the first
capture — on-device it arrives one post later.

**Fix (one-way re-open, evidence-only):** `IngestCoordinator.maybeReopenBlockedClass`
at both dedupe branches — when the stored row is stamped a non-replyable semantic
class AND the SAME notification's fresh evidence carries a real free-form Reply action
(`hasFreeFormReply`, requirement is the capability itself, never an inferred class),
the row is corrected IN PLACE (`updateClassification`: stamp + newly-published
conv_id/conv_title adopted) and the message finally pings (PingAggregation reused →
coalescer + content-hash + batch window govern the job exactly like any message).
Direction is one-way by construction and pinned both ways: conversational rows are
never re-stamped blocked by a later less-complete post; a post that STILL lacks
capability never re-opens (true announcements stay blocked forever).

**Cross-channel target safety:** unchanged mechanisms (P-17R decisive tiers →
unique-match rebind → conversation-bound cached token → honest fail), now pinned
with an explicit Discord scenario: `discordCrossChannelBorrowIsStructurallyImpossible`
(#general vs #random — with native ids, with titles only, with colliding plain titles)
plus the positive own-channel re-post matches.

## 2. §2 Background reliability — research, then implementation

Research (re-verified 2026-08-18):
- **PowerManager.isIgnoringBatteryOptimizations**: "Being on the power allowlist means
  that the system will not apply most power saving features to the app. Guardrails for
  extreme cases may still be applied." (developer.android.com/reference/android/os/PowerManager
  — the owner's exact quote.) ⇒ allowlist-only probes can never justify a bare "Ready".
- **ActivityManager.isBackgroundRestricted (API 28)**: the SECOND lever, Android 9+
  "Restricted" app-battery setting — separate from the allowlist; blocks are outward.
- **UsageStatsManager.getAppStandbyBucket / STANDBY_BUCKET_RESTRICTED (45, API 31)**:
  "the most restrictions, including network restrictions and additional restrictions on
  jobs." Buckets > FREQUENT may lose background network. NO intent flips a bucket.
- **Low Power Standby (API 33)**: non-exempt apps lose network + wakelocks while enabled
  (transient; surfaced in diagnostics, never claimed around).
- **ColorOS**: keeps its own unreadable per-app switch — Settings → Battery → App battery
  management → app → "Allow background activity" (dontkillmyapp.com/oppo; community-verified
  ColorOS 12–15 paths: App info → Battery usage → Allow background activity; plus
  Power-saving mode and Sleep standby optimization as device-level killers). Android
  exposes NO API to read that switch ⇒ modeled as an owner-confirmed fact, never inferred.
- **AOSP Settings manifest (main branch, android.googlesource.com)**: `Settings$AppBatteryUsageActivity`
  is `android:exported="true"` and answers `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
  (DEFAULT+BROWSABLE) ⇒ an implicit intent with `package:` data is a legitimate second
  route when OEMs rename the explicit stub; PackageManager resolution stays the truth.
- **AlarmManager.setAndAllowWhileIdle (RTC_WAKEUP)**: documented idle-mode escape hatch
  for low-frequency catch-up work; no SCHEDULE_EXACT_ALARM needed for inexact alarms.

Implementation:
- `BackgroundReadiness` (pure core, 9 pinned tests): the ONLY place "may we claim
  Ready" is decided. Hard blockers in fix order: notification access →
  POST_NOTIFICATIONS → battery allowlist → Android 9+ background-restricted →
  standby-RESTRICTED → ColorOS-switch-unconfirmed. Power-save mode is a named
  WARNING, never a gate (it ends).
- Settings toggle: ON reads a FRESH verdict — blocked ON persists OFF + a pending
  flag, reverts the switch, and walks the approval flow; **onResume re-evaluates
  live state** and finishes the pending enable, toasting "Ready ✓" only from the
  fresh verdict (warnings appended, e.g. power-save ON). The approval dialog names
  every missing lever in plain words with the exact tap; ColorOS gets the explicit
  two-step path (system dialog → Allow, then Battery usage page → "Allow background
  activity" ON) plus an **"I've enabled it ✓" confirmation** (auto-cleared if the
  allowlist is later lost). `oemBackgroundIntent` gained the implicit alias route.
- Diagnostics surface every probe: Android 9+ restricted, standby bucket name +
  RESTRICTED flag, power-save, low-power-standby exemption, ColorOS confirm state,
  and the **last background pipeline timing** ("queue Xs · generate Ys · message→alert Zs").
- Doze / process-death safety net: every scheduled generation arms ONE
  `setAndAllowWhileIdle(RTC_WAKEUP)` fallback 30 s after the in-memory due time
  (non-exported receiver). Firing runs the idempotent catch-up sweep — a no-op when
  the Handler path did its job (hash/draft gates), a real recovery when Doze or a
  kill ate the timer. Process death itself was already covered by the
  listener-rebind reconcile+sweep (unchanged).
- Isolation: GEN lane 3 → elastic 6 lanes (60 s idle retire). Per-contact
  serialization (DraftService) and pre-call coalescer aborts are untouched, so
  extra lanes cannot double-run a conversation or double-bill a burst — one
  crawling provider/research task can hold at most 1/6 lanes instead of 1/3.
- Latency measurement (no guessing): each scheduled cycle stamps
  enqueue→due→fire times; on completion the kv trace records queue/generate/
  message→alert seconds (Settings → Diagnostics), and genuinely slow cycles
  (queue >20 s or total >90 s) leave an honest diagnostics line with their numbers.

## 3. Proof

| Item | Value |
|---|---|
| Version | `1.7.0-bgfix-proof` / vc 917 |
| Commits | `66e978b` (1/2, this phase's code+tests) + checklist commit (2/2) |
| JVM gate | pre-fix repro **3/1012 FAILED** (named above) → **1022/1022 OK** |
| Engine devcheck | vc917 / 1.7.0-devcheck1 builds + verifies (debug cert `446310cc…04e9d2`), cleaned after |
| CI release | run **32123702613** (completed success) |
| Artifact | **9319531059** `ReplyMate-1.7.0-bgfix-proof-vc917` |
| CI keystore verdict | KEYSTORE-VERDICT: MATCH — historical ReplyMate identity confirmed; APK-CERT-PROOF: MATCH — update-in-place over ≤1.5.8 is preserved (run logs, 09:51 UTC) |
| Off-CI verify | **678,218 B**; sha256 `85e256cd20ef4220a64df3ee2c3d8cd00d979f9e293f343c982c2ace7107ba57`; cert `B1:5F:2F:37…6A:85:ED` **MATCH** (B15F2F37FCE19B564683FE6B85725A9D3192DF93181EF2F36286B5E21C6A85ED); badging com.replymate.app · vc917 · `1.7.0-bgfix-proof` · minSdk 24 · targetSdk 34 · compileSdk 35 (target identical to every prior proof; prior checklists' "target 35" meant compile) |

## 4. Device rows (owner-marked — never self-certified)

Full trace required per row: classification → provenance → direction → reason →
source message ID → reply target → provider call y/n → delivery/send target.

| Row | Scenario | Expected (trace) | Result |
|---|---|---|---|
| P19R-1 | Discord #general normal message (watch+groups ON): shade shows Reply | [direct_reply (announcement→re-opened OK)] → LISTENER → INCOMING → "the app's own Reply action appeared on the same notification" → real msg id → #general target → provider YES → draft alert; Approve→ lands in #general | OPEN |
| P19R-2 | ReplyMate conversation view after P19R-1 | ring shows "re-opened: announcement → direct_reply"; Generate works (no "can't be replied to") | OPEN |
| P19R-3 | Discord #announcements (or any no-Reply-action channel) | [announcement] → LISTENER → INCOMING → "speaks AS the channel itself" → msg id → no target stored → provider NO → blocked; Generate attempt explains "can't be replied to from ReplyMate" | OPEN |
| P19R-4 | #general + #random both notify; approve the #general draft | target stays #general's own action (never #random); SendPath order live→rebind(unique)→cached→honest-fail | OPEN |
| P19R-5 | Background toggle ON (Oppo): flow to the REAL page | dialog lists every missing lever in plain words; button opens Battery usage page; "Allow background activity" ON → "I've enabled it ✓" → Ready ✓ appears ONLY then; leaving/returning re-checks live state | OPEN |
| P19R-6 | Subtitle honesty | "Ready ✓…" only when every lever green + ColorOS confirmed; otherwise "limited — blocked by: <chips> (tap to fix)"; power-save ON shows as a warning, never hidden | OPEN |
| P19R-7 | Screen off 15 min → new watched message | draft alert arrives without opening ReplyMate (timer or the alarm fallback / rebind sweep); diagnostics ring shows which path answered | OPEN |
| P19R-8 | Process death mid-cycle: kill ReplyMate from recents right after a message, then wait | on rebind/boot the sweep re-drives the same message (no duplicate draft; catch-up line in ring) | OPEN |
| P19R-9 | Two conversations while one is slow (bad network) | second conversation's draft is NOT parked: Settings → Diagnostics "last background pipeline: queue Xs · generate Ys · message→alert Zs" shows queue ~0s for the fast one | OPEN |
| P19R-10 | Diagnostics honesty | standby bucket named (e.g. working_set), restricted=false; power-save line correct; ColorOS confirm state shown as owner-confirmed (not probed) | OPEN |

## 5. Regressions checked

- All 1022 JVM tests green (1007 pre-existing + 5 re-open + 1 cross-channel + 9 readiness).
- P-17/17R guarantees intact: DeliveryGuard/TargetRules/SendPath/ConversationMatch
  suites untouched and green; re-open moves nothing in the send path — it only stops
  a stale stamp from vetoing what the current notification proves.
- P-18 manual-context flows untouched (ManualIncomingTest green).
- P-19 regenerates/refreshAlert behavior untouched (OneDraftPerMessageTest green).
- Dedupe counters unchanged for non-reopen traffic (both branches still count one
  duplicate, store nothing, and — NEW ONLY when re-opening — emit at most one ping
  through the same aggregation every other message uses).
- No new permissions; one non-exported internal receiver added; alarm is
  setAndAllowWhileIdle (idle-tolerant, no exact-alarm permission).
- Fire-when-enabled OFF: pending-enable only completes from a fresh verdict;
  nothing schedules while the master switch is off (unchanged gate).
