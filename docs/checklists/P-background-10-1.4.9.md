# P-background-10 — BLOCKING PRODUCT AUDIT — 1.4.9/vc30

Build audited from source **before any change** (owner rule). One confirmed on-device
failure found and repaired; everything else re-verified intact and pinned harder.
Do not advance phases until the on-device steps below pass on the owner's phone.

---

## Per-item audit verdicts

| # | Item | Audit verdict (from code) | Action in 1.4.9 |
|---|------|---------------------------|------------------|
| 1 | Background permission flow | **BROKEN (confirmed)**: battery fix opened `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` — the generic all-apps list. Manifest also lacked `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so the focused per-app approval dialog was unreachable. | **FIXED** — focused dialog → list → app-details chain, resolve-gated; exact "what to tap" copy; optional maker (OEM) screen button shown ONLY when it truly resolves; state rows already live (`PowerManager.isIgnoringBatteryOptimizations`, runtime grant, listener flag) and re-read on every resume. |
| 2 | Notification filtering | Intact: category gates, MessagingStyle vs Title/Text tiers, group/limited rules, self-status phrases, backup/sync/digest/call/reaction drops, media-only = STORE_ONLY (never generates). Honest boundary stands: OEM bots/broadcast lists expose no official distinguishing signal — documented, not faked. | Re-pinned (regressions below unchanged, green). |
| 3 | Dismissal-safe send | Intact: live key → conversation re-match repost → cached PI (settle says "couldn't watch it land") → honest copy, draft never lost, no fake "Sent". | None — pinned by existing suites (green). |
| 4 | Speed on slow networks | Healthy by audit: coalesced single paid call per burst (obsolete jobs abort BEFORE the provider call), warm 2-thread pool for the next burst, 15s/45s connect/read timeouts, bounded 3-attempt backoff honoring Retry-After. | Timeout/retry literals now public constants pinned by `HttpDefaultsTest`. |
| 5 | Burst handling | Intact: rolling re-arm (5s window +1.5s settle), state read at fire time, one card refreshed in place, next burst re-arms on approve/copy/dismiss. | None — pinned (green). |
| 6 | Intelligence & learning | Intact: APPROVED/EDITED/REGENERATED/REJECTED signals (Edit path included), My Voice + contact overrides order, cold-start credit shown in Prompt Audit, direct-not-hostile + no-parrot rules. | None — pinned (green). |
| 7 | ReplyMate banner UX | Intact: `rm_assistant_heads_up` IMPORTANCE_HIGH, brand icon/color, Approve & send / Edit / Regenerate trio directly visible (framework cap 3), card tap = Open, Edit lives INSIDE ReplyMate (`DraftEditActivity`). | None — pinned (green). |
| 8 | Multiple contacts | Intact: per-contact coalescer/tag/kv/memory/style; Chats ranked by `ChatRanker` (real last-message activity, idles below, settings edits don't rank). | None — pinned (green). |
| 9 | Platform rule | No iOS code paths exist (verified). | Platform rule documented in `docs/SPEC.md`. |
| 10 | Verification | Suites existed for everything except item 1's launch chain and the network-patience literals. | New: `BackgroundFlowRoboTest` (5), extended `AssistantPrereqRoboTest` (→5), new `HttpDefaultsTest` (3). Harness flake found & fixed (see below). |

## What changed in code (complete list)

1. `AndroidManifest.xml` — declared `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (+ comment: it
   only opens the system dialog, never grants anything).
2. `app/assistant/AssistantPrereq.java` — `screenIntent(Context, Need)`; BATTERY now builds
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI; new `howTo(Need)` exact-tap
   copy; `batteryListIntent()` / `appDetailsIntent()` fallbacks; `oemBackgroundIntent()` over a
   curated Transsion/Xiaomi/Oppo/Vivo/Huawei/Samsung/OnePlus/Asus candidate list; `canLaunch()`
   queries the PackageManager **directly** (Intent#resolveActivity short-circuits explicit
   intents — would have fake-shown maker screens on every device; caught by the harness);
   guarded `launch()`; `openBatteryApprovalFlow()` returns what actually opened.
3. `app/ui/SettingsActivity.java` — approval dialog: exact-tap copy, battery button launches
   the flow chain (button reads "Allow in background"), neutral "Maker battery settings"
   button only when a maker screen truly resolves, honest toast echoes opened screen (or
   manual instruction when none).
4. `provider/http/HttpClient.java` — `DEFAULT_CONNECT_MS`/`DEFAULT_READ_MS` public constants
   (15s/45s) used by the default ctor. No behavior change.
5. `docs/SPEC.md` — locked Android-only platform rule.
6. Tests: `HttpDefaultsTest` (new JVM, registered in run_tests.sh);
   `AssistantPrereqRoboTest` updated (new signature + focused-dialog/have-to pins);
   `BackgroundFlowRoboTest` new (chain order, empty-answer honesty, OEM resolve gating,
   junk-intent safety, fallbacks); `AssistantHeadsUpRoboTest` send latch now polls+idles
   (deterministic under any suite ordering — was a latent race the new class exposed).

## Gates (all green in sandbox)

- JVM unit suite: **515 tests OK** (was 512; +3 HttpDefaultsTest).
- Robolectric device-semantics harness: **79 tests OK** (was 74; +5 BackgroundFlow), run
  twice consecutively green. 512-unit compile gate inside run_tests.sh green.
- Harness flakes found and fixed during this phase (honest accounting):
  ShadowPackageManager registrations leak across same-config test methods; bare ResolveInfo
  NPEs the framework (needs activityInfo); one latent latch race in the 1.4.8 money test —
  all three fixed, deterministic twice.

## Regression coverage map (item 10)

- filtering / WA backup / Discord announcements / reactions / calls / media-only →
  `ContentSignalsTest`, `IngestCoordinatorTest`, `MessagingStyleParserTest`, `TitleTextParserTest`,
  `StatusFilterTest`, `NotifExtractorRoboTest`, `ListenerPipelineRoboTest`
- dismissal-safe send → `ConversationMatchTest`, `AssistantHeadsUpRoboTest`
  (`repostedConversation…`, cached-target, honest-copy, strict identity), `AssistantTargetRefreshRoboTest`
- burst aggregation / next burst after send → `BurstTaskTest`, `JobCoalescerTest`,
  `AssistantRunnerRoboTest`
- cold start / memory / My Voice / contact profile / About Them / custom instructions /
  learned approvals+edits / Prompt Audit → `CustomizationEffectTest`, `AssistantLearningTest`,
  `StyleServiceTest`, `MemoryPersistenceRoboTest`, `P4PromptTest`
- listener restart / app restart → `ListenerPipelineRoboTest` (reconcile), `MemoryPersistenceRoboTest`
- **background permission flow → `BackgroundFlowRoboTest` + `AssistantPrereqRoboTest` (NEW)**
- speed/reliability knobs → `HttpDefaultsTest` (NEW), `RetryPolicyTest`, `JobCoalescerTest`

## ON-DEVICE VERIFICATION (owner — reply per number with OK/FAIL)

1. Settings → Background reply assistant ON (all approvals missing) — dialog lists each
   approval AND the exact tap; battery button reads **"Allow in background"** and opens the
   focused "let ReplyMate always run in the background?" box — NOT the all-apps list.
2. Grant it → back in Settings the battery chip disappears; subtitle ends "on ✓".
3. Deny everything and repeat on a Tecno/Infinix/Xiaomi-class device if available: a neutral
   "Maker battery settings" button appears ONLY when that maker screen really opens it.
4. Kill the app during a burst → reopen; banner flow still behaves (heads-up once per burst,
   update-in-place), Approve&send after swiping ReplyMate's card away re-posts or cached-sends
   and never claims Sent dishonestly.
5. Slow data (3G / 1 bar): background draft still arrives; no duplicate provider burn
   (Usage dashboard shows one call per burst).
6. Chats screen: a settings edit does NOT bump a chat; a real new message does.
