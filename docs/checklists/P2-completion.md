# P2 Completion Report — Notification Listener (read-only)
**Shipped:** `releases/ReplyMate-0.2.0.apk` · versionCode 4 / 0.2.0 · 78KB ·
sha256 `a457af11…1881548` · **Status:** built + unit-verified; device soak = owner step.

## Scope delivered (BLUEPRINT §8/P2 + owner's constraints — all honored)

**Core pipeline (`core.listener`, pure JVM, 10 new suites):**
- `NotifExtractor` input model `NotifEvent` • `ListenerFilter` (SKIP/STORE_ONLY/STORE_AND_PING policy:
  media → placeholder, groups → store-only, package whitelist = WhatsApp+Telegram only)
- `IdentityResolver` — stable case-insensitive remote keys; groups namespaced `group:` (no 1:1 collisions)
- `TextIds` — **content-hash dedupe (SHA-1)** deliberately NOT based on `sbn.getKey()`: messaging apps
  re-post the same notification with an extended MessagingStyle; the same message dedupes across re-posts
- `MessageClassifier` — incoming vs outgoing via sender/owner name comparison (conservative default: incoming)
- `BatchWindow` — 5s debounce math (messages stored **immediately**; only alerts debounced)
- `DiagnosticsRing` — capped ring (12) of recent events in kv, no schema change
- `IngestCoordinator` — the whole ingestion pipeline: watch toggles → filter → identity → classify →
  dedupe → **scoped insert (isolation preserved)** → per-contact ping aggregation → counters/ring

**Platform (`app.listener`, kept razor-thin):**
- `RmNotificationListener` — official `NotificationListenerService`; work moved off the callback thread
- `NotifExtractor` — **framework-only MessagingStyle parsing** via the documented `android.messages`
  parcelable extras (see "build finding" below); API-28+ features read through Bundle keys with fallbacks
- `NotifierPings` — our quiet alerts, debounced per contact, gated on POST_NOTIFICATIONS (33+),
  deep-link into the conversation screen; notification channel created at app start
- `ListenerStatus` — system enable-state + own-permission checks + deep-link to system settings

**UI:** Home health chip now shows listener state ("Listener on ✓" / amber "Listening is off — tap to
enable" → system screen, storage error still takes priority). Settings adds: Message listening row
(opens system toggle), **Watch WhatsApp / Watch Telegram toggles** (`watch.*` kv), ReplyMate notifications
row (33+ runtime request), and an extended **Diagnostics** panel: DB self-test + listener state,
connected/disconnected times, watch flags, total stored, last event, and the recent-events ring.

## Constraints checklist (owner message, verbatim)
Official NotificationListenerService ✓ · supported-API parsing only ✓ · **read-only** ✓ · no replies ✓ ·
no Accessibility ✓ · no unofficial APIs ✓ · no scraping ✓ · no cloud sync ✓ · no Supabase ✓ ·
**no automatic replies** ✓ (ping only opens draft screen) · no background AI generation ✓ (generation
remains user-triggered in S05 only) · existing architecture extended, nothing redesigned ✓ ·
contact isolation maintained (every write contact-scoped; isolation suite green) ✓ · local-first ✓

## Build finding (recorded honestly)
First P2 compile failed: `Notification.MessagingStyle.extractMessagingStyleFromNotification()` is
**AndroidX-only** (`NotificationCompat`), absent from the framework's API-34 `android.jar` (verified via
`javap`). As a zero-dependency Track-A app we parse the framework's own `android.messages` parcelable
extras directly — the same data, official and version-tolerant (28+ sender Person/owner handled via
bundle keys, legacy `sender` fallback, `isGroupConversation` read as primitive extra). This is the
documented, supported representation; soak-test on real WhatsApp/Telegram payloads is its validation gate.

## Verification evidence
- **101/101 unit tests green** (+35 vs P1): IdentityResolver(6), TextIds(4), MessageClassifier(4),
  ListenerFilter(5), BatchWindow(2), ContactService(4), IngestCoordinator(10 — incl. **re-posted
  notification dedupe**, own-message/group/media-no-ping rules, watch gating, **cross-contact isolation**,
  ring capping), plus all prior suites unchanged. Harness fixed two of its own issues mid-phase
  (DiagnosticsRing private-API usage; fakes upgraded for channels) — caught pre-release, not by you.
- **APK:** signature valid (persistent key → updates in place over 0.1.0); **exactly ONE permission**
  (`POST_NOTIFICATIONS` — approved P2 delta); service verified in binary manifest: label, permission
  `BIND_NOTIFICATION_LISTENER_SERVICE` (only the system can bind despite exported=true), correct
  intent-filter action; minSdk 24/target 34 unchanged; no other manifest changes.

## Owner validation plan (soak per blueprint gate)
1. Install over 0.1.0. Home shows amber banner → tap → enable "ReplyMate listener" in system settings.
2. Settings → ReplyMate notifications → allow (Android 13+).
3. Have someone WhatsApp/Telegram you (or second phone): expect ~5s later a quiet
   "New from {name}" alert; tapping opens that contact with messages already logged.
4. Rapid-fire a 5-message burst → ONE alert, all 5 messages logged once (dedupe).
   Reply from the chat app → your own lines appear as "You".
5. Media-only message → placeholder stored, no alert. Group chat → logs silently, no alert.
6. Settings → Diagnostics: confirm counters/ring; screenshots are excellent phase-gate evidence.
7. Optional fixtures: any event in the ring is a real captured payload pattern we can pin as a test later.

## Deferred/known limits (unchanged scope)
Same-named contacts on the same channel share one remote key (merge tooling = P3) · same person on
WhatsApp and Telegram = two contacts (merge = P3, pinned by test) · dual-app/work-profile namespacing
not yet wired · listener dies → Home chip turns amber (recovery toggle) — manual mode unaffected.

## Gate (BLUEPRINT §8/P2)
"3-day soak: 0 duplicate texts · 0 unlogged text msgs · survives reboot" → pending owner soak.
**STOPPED for approval before P3.**
