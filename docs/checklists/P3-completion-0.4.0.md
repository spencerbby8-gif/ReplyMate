# P3 Completion — ReplyMate 0.4.0 (versionCode 8)

**Date:** 2026-08-06 · **Scope:** owner-approved P3 only (notification sources, provider
parser architecture, draft cards + tones, deep links, audit, usage, search, diagnostics).
Local-first remains the source of truth: **Supabase sync OFF, no Auth, no Paystack,
no cloud sync, contact isolation preserved.** No Accessibility, no scraping, no
unofficial APIs, no auto-replies — still NotificationListenerService only.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.4.0.apk` | `918599dbb82502a8cbffbedd8d2e603c5cddc9741b3c396e02c4c08f5447bf2b` | 102K |

Signer cert SHA-256 `b15f2f37…6a85ed` — **identical to 0.2.2/0.3.0 → update-in-place.**

## Per-app support matrix (owner asked for this explicitly)

| App | Package(s) | Tier | What that means |
|---|---|---|---|
| WhatsApp | `com.whatsapp` | **FULL** | MessagingStyle: per-sender history, groups, media markers, timestamps, owner detection |
| Telegram | `org.telegram.messenger` | **FULL** | same framework shape |
| Signal | `org.thoughtcrime.securesms` | **FULL** | same framework shape |
| Google Messages | `com.google.android.apps.messaging` | **FULL** | same framework shape |
| Messenger | `com.facebook.orca` | **FULL*** | same framework shape when Meta posts MessagingStyle (*on versions where Meta uses plain text it degrades gracefully to title/text) |
| Slack | `com.Slack` | **PARTIAL** | title+text parser; `#channel` → group heuristic; sender from title |
| Discord | `com.discord` | **PARTIAL** | title+text; `Sender: body` split; MessagingStyle history used when present |
| Instagram | `com.instagram.android` | **LIMITED** | category-gated (`msg`/`social` only); sender inference is title-based; non-message noise ignored |
| X | `com.x.android` **+ legacy** `com.twitter.android` | **LIMITED** | same gating; both packages supported |
| TikTok | `com.zhiliaoapp.musically` | **LIMITED** | same gating; DM vs promo accuracy depends on what TikTok publishes |

Everything else on the phone: **silently not watched** (NOT_WATCHED, unmetered).
Unsupported/malformed notifications: **IGNORED or FAILED with a short content-free
reason**, counted per app, never crash the listener.

**WhatsApp Business / Telegram forks are NOT read** (deliberate; adding them needs
owner approval).

## Deep-link reality matrix (honest)

| Channel | "Open in app" behavior |
|---|---|
| WhatsApp | official `https://wa.me/?text=…` share flow (draft pre-filled, user picks the chat) |
| Telegram | official `https://t.me/share/url?…&text=…` share flow |
| All other 8 | official package launcher (notifications carry no thread IDs we could honestly deep-link); if the app isn't installed → draft is copied to clipboard with a toast to paste it |

## What was built

- **Provider parser architecture**: `RawNotif` (dumb Bundle copy, app layer) →
  `ParserRegistry.route` (watch-gate BEFORE parse → per-app parser → stats) →
  one `IngestCoordinator` (store/dedupe/ping). Parsers never throw: EVENTS / IGNORE / FAIL.
- **Sources screen** (Settings → Notification sources): auto-discovers installed apps,
  per-app ON/OFF (immediate effect; disabled = dropped before processing), per-app
  counters + last ignore/fail reason, honest support tier, store link for missing apps.
  WhatsApp + Telegram default ON (preserves shipped behavior); **all new apps default OFF**.
- **Draft cards**: Edit (inline), Copy (persists edits), Regenerate, Delete, Favorite (★),
  6 tone transforms (Shorter/Longer/Friendlier/Professional/Confident/Casual) —
  contact-scoped, private/AI-disabled contacts fail closed, snapshot audited as
  `kind":"tone:<wire>"`, per-card loading guard.
- **Prompt audit viewer** (conversation → Audit): exact prompt payload per draft,
  pretty-printed, selectable, read-only.
- **Usage dashboard** (Settings): 24h/7d/30d/all — calls, tokens in/out/total,
  by-model and by-feature breakdowns, recent 20 calls.
- **Search**: Home (contact name OR message body → "match:" preview), in-conversation filter.
- **Error handling**: API-key / offline / rate-limit hints appended to failures;
  per-card transform guards; global statuses preserved.
- **Diagnostics**: per-app received/parsed/ignored/failed + last reason; legacy counters kept.
- **Schema v2**: `draft.favorite`, `contact_channel` CHECK widened to 11 wires
  (table rebuilt, rows preserved — JDBC-proven incl. UNIQUE survival + garbage rejection).

## Bugs found this phase (all fixed, honestly counted)

1. **P2-inherited false positive** (found by the new Robolectric test): a *call/progress*
   notification ("WhatsApp · Ongoing call") parsed as a MESSAGE from a contact named
   "WhatsApp". Fixed with a category gate in `MessagingStyleParser` (only `msg`/`social`/
   unset pass) + 3 regression tests. **Existed since 0.2.2** — worth a soak check.
2. **My own Batch-3 audit-tag bug**: `DraftService` tried to retag snapshots via a string
   `.replace("\"kind\":\"reply\""…)` on JSON that never contained that key — silent no-op.
   Found while writing the tone test. Fixed properly: `PromptBuilder.snapshot(req, model, kind)`.
3. Two compile misses caught by the engine build before any test ran (`UsageDao` row
   mapper, `SqlDraftStore` overrides). No runtime impact.
4. Test-expectation mismatch on URL encoding (`+` vs `%20`): standardized on `%20`.

## Verification evidence

- **Unit (JVM)**: `scripts/run_tests.sh` → **OK (159 tests)** across 27 suites
  (new: MessagingStyleParser, TitleTextParser, ParserRegistry, ChatLink, ToneTransform,
  DraftServiceTone incl. cross-contact draft isolation, MigrationV2 JDBC).
- **Robolectric (real AOSP API-34 Bundle/MessagingStyle code)**: **10/10 pass**
  (`/home/user/rm-harness/robo`) — modern Person payloads, group/own/media, category
  gate, NOT_WATCHED unmetered, DISABLED-before-parse, re-post dedupe + ping policy.
- **APK**: vc=8 / 0.4.0 · minSdk 24 / targetSdk 34 · permissions exactly
  `INTERNET` + `POST_NOTIFICATIONS` · 9 activities + listener service
  (`BIND_NOTIFICATION_LISTENER_SERVICE`) in binary manifest · dex gate OK
  (Application + launcher) · 18/18 sentinel classes in dex (Sources/Usage/PromptAudit/
  DeepLinks/ParserRegistry/parsers/RawNotif/ChatLink/ToneTransform/SchemaV2/
  transformDraftForContact…) · zip integrity OK · **no .idsig** · signature valid.
- **Cloud parity**: migration `0002_widen_channel_checks.sql` applied to the LIVE
  Supabase project via pooler — constraint now accepts all 11 wires (verified:
  'slack' insert succeeds, 'myspace' rejected, ledger at version 2, probe rolled back).
  **App-side sync remains OFF (pinned by SupabaseConfigTest).**

## On-device soak asks (for the owner)

- Same as before: exact original crash symptom, device model + Android version,
  regular vs Business/forked WhatsApp/Telegram, 24h Diagnostics screenshot —
  now including the new per-app stats table if you enable extra apps.

Process: **stopping here per P3 gate — awaiting owner approval before any P4 work.**
