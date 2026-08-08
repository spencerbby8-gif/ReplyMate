# P-background-4 — BLOCKING pipeline repair — 1.4.3 / vc24 (2026-08-07)

Device report: after installing 1.4.2, ReplyMate no longer sees notifications and
background generation no longer works. Mandate: audit + repair the full chain
(listener access → capture → grouping → background generation → provider call →
draft save → banner → approve & send → regenerate → diagnostics), prove reply-fill
for every reply-capable app (WhatsApp + at least one other), never fake missing
reply support, add regression tests, do not touch memory/auth/splash/providers,
do not move on until capture + background generation + reply-fill work on a real device.

## 📦 Download

| file | size (bytes) | sha256 |
|---|---|---|
| `releases/ReplyMate-1.4.3.apk` | 215,290 | `6d2ac5e21ab7f4d69df9374b9aa5233854f0549a5701139525d415fca3107301` |

Cert `b15f2f37…` unchanged → installs in place over 1.4.2.

## 🔍 Audit verdict (honest, before any change)

Diffed 1.4.1 (owner-confirmed working) → 1.4.2: **6 files, all in the assistant
layer; the live capture path is byte-identical** except a *fully guarded* wearable
action scan. Read every guard: `NotifExtractor` (per-action try/catch; wearable
block caught), `RmNotificationListener` (work moved off the binder thread to
`Tasks.bg`; extract / route / ingest each guarded; last-resort guard around all).
Verified against `android.jar`: `BadParcelableException extends
AndroidRuntimeException` (= RuntimeException) ⇒ the notorious "another app's
extras unparcel" crash **is** caught. Bottom line: **1.4.2's code cannot
systematically kill capture** — the failure pattern (capture AND generation dead
together, right after an in-place update) matches the known OEM behavior of
pulling special access / freezing the app on update (HiOS/XOS/MIUI family),
which is why the device-check list in this build puts notification access and
battery state first.

But "can't find it" is not "nothing to fix". The audit surfaced three real
resilience gaps, and 1.4.3 closes exactly those — nothing else:

1. **Restart/app-death silence**: after an OEM kill, update, or reboot there was
   NO catch-up — messages that arrived while dead stayed invisible until the next
   live one (and, if the system stripped listener access entirely, forever).
2. **Hypothetical Error-level failures** (OEM-patched frameworks) in the *optional*
   wearable enrichment or the outermost loop could still kill a single capture
   quietly (guards caught RuntimeException only).
3. **Diagnostics didn't surface the #1 OEM cause**: battery-optimization state.

## 🛠 What shipped (4 product files, no redesign, no new features)

- `RmNotificationListener` → **reconciliation on every (re)bind**: one shot over
  `getActiveNotifications()` through the SAME guarded watched pipeline. Dedupe
  (notifKey) makes it idempotent: already-captured items are skipped *before* any
  ping/draft/job → no ghost alerts, no re-generation. This is the standard
  documented NLS catch-up pattern and it directly repairs "listener restart" and
  "app restart" (owner-listed). Outermost guard widened to `Throwable` + counted
  + ring-lined.
- `NotifExtractor` → wearable scan (an *optional enrichment*) now also catches
  `Throwable`, degrading to the standard surface only. Capture can never die
  because of something wearables did.
- `ListenerStatus` → read-only `batteryWhitelisted()` (`isIgnoringBatteryOptimizations`,
  API 23+, no permission).
- `SettingsActivity` → Diagnostics gains one line: `battery optimization ignored:
  true/false (false + delivery trouble = set Battery → Unrestricted)`.

Untouched per order: memory, auth, splash, provider engine, no new features.
Buttons unchanged (owner's fallback list maps to existing behavior: Copy /
Regenerate / **Open conversation**, where the draft is editable in place — a
separate "Edit" button would duplicate "Open conversation").

## 🧪 Proof (all run on THIS tree; counts exact)

**JVM unit suite: 463/463** (`scripts/run_tests.sh`).

**Robolectric suite: 46/46** (real framework shadows; was 29, +17 new pins
covering every owner-mandated bullet):

| owner bullet | regression pin |
|---|---|
| notifications not being seen | `ListenerPipelineRoboTest.liveWhatsappNotificationIsCapturedEndToEnd` — real service, `onNotificationPosted` → message row in db + ring line |
| own apps never re-ingested | `ownPackageNotificationIsNeverIngested` | 
| listener restart / app restart | `reconcileReplaysActiveSnapshotIdempotently` (stores exactly once), `reconciliationRespectsTheWatchGate`, `listenerRestartStateIsHonest` |
| background generation not firing | `AssistantRunnerRoboTest.scheduledBackgroundGenerationActuallyFiresOffMainThread` — job fires; ledger reaches the honest provider gate; **asserts `NetworkOnMainThread` can never reappear**; `regenerateButtonFiresImmediatelyToo` |
| RemoteInput fill path | `RemoteInputFillRoboTest.fillArrivesThroughWearableSurfaceReplyAction` + `…StandardSurface…` — REAL `PendingIntent` broadcast hand-off after a REAL Parcel round-trip; receiver reads the draft via the OFFICIAL `RemoteInput.getResultsFromIntent()` under the app's OWN result key — the exact call WhatsApp makes |
| moved geometry | `movedActionGeometryResolvesNullHonestly` (never fires a wrong PendingIntent) |
| dismissed original | `approveWithDismissedOriginalCopiesHonestly` — clipboard fallback + ledger entry |
| apps with reply support | extractor fixtures: WhatsApp standard, WhatsApp wearable-only (the device shape), **Telegram-shape standard RI** (second real app — owner asked for ≥2) |
| apps without reply support | `plainActionsWithoutAnyRemoteInputStayHonestNone` (+ probe geometry), `fixedChoiceOnlyRemoteInputIsNotATextReply`, `AssistantBannerRoboTest.noneBannerShowsCopyRegenerateOpenAndExplainsWhy` — no fake send button |
| banner contents | `AssistantBannerRoboTest` ×4 — DIRECT: Approve & send / Regenerate / Open + honest caption; NONE: Copy / Regenerate / Open + reason; regenerate replaces in place under the stable tag |
| hostile input | `hostileUnparcelableWearableExtrasNeverBreakCapture` — another app's unloadable Parcelable inside wearable extras; capture proceeds, standard surface intact |

## 📱 Owner's device verification (the 4 checks you ordered + the two apps)

1. Verify the download: size **215,290 bytes**, sha256
   `6d2ac5e21ab7f4d69df9374b9aa5233854f0549a5701139525d415fca3107301`.
   (`termux: sha256sum ReplyMate-1.4.3.apk` or any checksum app.)
2. Install (updates 1.4.2 in place). Open **Settings → Diagnostics**:
   - `enabled in system:` **must be `true`** — if `false`, the update pulled
     notification access (the most likely cause of everything): tap the
     Notification-access row in Settings and re-enable ReplyMate.
   - `battery optimization ignored:` — if `false`, set ReplyMate battery to
     **Unrestricted** (system Settings → Apps → ReplyMate → Battery).
   - `connected at:` should show a recent time once access is on.
3. **Reply-capable app (WhatsApp):** have a message arrive. Expect: stored
   counter increments · ReplyMate banner with **Approve & send** → tap → the text
   lands in the chat through WhatsApp's shade-reply; ledger records
   `remote_send`. Also try **Telegram** (default-watched too) — same expectation.
4. **App without reply (Instagram):** enable it in Settings → Notification
   sources first (non-default apps start OFF by design). Banner shows
   **Copy / Regenerate / Open conversation**, caption honestly says there's no
   quick-reply box. Nothing is faked.
5. Dismissal path: dismiss the WhatsApp alert first, then Approve → banner says
   it copied instead (ledger explains why: original dismissed).
6. Restart path (the new heal): force-stop ReplyMate, get a new WhatsApp message
   while dead, re-open. On the next bind the reconciliation line
   `listener (re)connected · reconciled N active notif(s)` appears in the ring
   and the missed message is already there — no duplicates.

If step 3 still shows nothing: screenshot Diagnostics and the ledger — with this
build the phone tells us exactly which stage is silent.

## ⏳ Deferred / honest limits

- The final byte lands inside WhatsApp/Telegram's own process: only a real-device
  Approve tap can close that loop (the ledger records `remote_send` vs the copy
  fallback with reason). The robo fixtures prove our side of the contract
  byte-exactly.
- Poster-app hardware-emulator E2E (`rm-harness/e2e_p2.sh`) still needs KVM —
  unavailable in this sandbox; documented, not faked.
- Owner TODOs unchanged: PAT + DB password rotation, Supabase dashboard toggles.
