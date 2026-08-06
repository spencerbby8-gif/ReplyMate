# ReplyMate — Technical Specification & Phased Roadmap
**Status:** APPROVED v1.1 — decisions locked 2026-07-17. Build governed by `docs/BLUEPRINT.md`.
**Date:** 2026-07-17 · **Type:** Personal, Android-first, local-first AI reply assistant

---

## 1. Product Overview

**One-liner:** ReplyMate is a private, on-device AI copilot that reads your incoming Telegram/WhatsApp
message notifications, and drafts replies that sound like *you* — per contact, with isolated memory.

**Core concept (unchanged from idea):**
- Each contact = its own isolated AI "session" (like separate ChatGPT conversations). Memory of contact A
  can never leak into replies for contact B — enforced at the data layer, not by prompt discipline.
- AI learns three things: (a) who *you* are (profile + writing style), (b) who *they* are (facts, preferences,
  relationship), (c) what has happened (conversation history, summarized).
- **Draft-first:** app generates, you review/edit, you send using the normal messaging app. Sending
  automation is a late, optional, carefully-gated phase.

**North-star qualities:** sounds like me · never leaks across contacts · private by default · works offline
except the AI call itself · fast draft (< 5s).

**Primary AI:** Google Gemini API (user's own AI Studio key — "BYOK"). Provider abstraction allows
OpenAI/Claude/local models later.

---

## 2. User Journey

**J1 — First-run onboarding (5 min)**
1. Welcome → privacy promise screen ("your messages stay on this phone; only the text you approve goes to Gemini").
2. Create **My Profile**: name/nickname, languages, city/timezone, short bio, things I often talk about.
3. **Style setup** (skippable): paste 10–20 of your own past messages → AI derives style rules; or pick presets
   (emoji usage, length, formality, sign-offs, "never say" list).
4. Add **Gemini API key** → app tests it with a 1-token call.
5. Grant **Notification Access** (system settings deep-link) → pick monitored apps (Telegram, WhatsApp).
6. Done → inbox-style home ("No messages yet — ReplyMate listens in the background").

**J2 — Daily flow (the 30-second loop)**
1. Message arrives on WhatsApp/Telegram → ReplyMate's listener captures it silently (batched if messages arrive rapidly).
2. ReplyMate posts its own quiet notification: *"New from Amara — tap to draft"*.
3. Tap → conversation screen for that contact: incoming message(s), AI draft already generating (or tap "Generate").
4. Review draft → optionally "Shorter / Softer / Funnier / Regenerate" → edit inline.
5. **Copy reply** (button) → "Open chat" deep-link button jumps into the original app → paste → send.
   *(Phase 6 option: send directly through the notification's Reply action — reviewed below.)*
6. In the background: memory extractor saves durable facts; rolling summary updated if due.

**J3 — Contact management**
- Contacts are auto-discovered from notifications; user merges/renames, sets relationship ("close friend",
  "client", "sister"), per-contact tone override, per-contact AI on/off (private mode).

**J4 — Steer the AI**
- Per contact: "How to talk to this person" notes + pinned facts. Global edit of My Profile/style anytime.
- Every generated reply stores a prompt snapshot → "Why did it say this?" debugging.

**J5 — Manual mode (no listener)**
- Paste/type an incoming message for any contact → generate draft. Works even where notification access
  can't be granted; also the testing scaffold for every later phase.

---

## 3. Complete Feature List (MoSCoW)

**MUST (personal-use v1)**
- F1 My Profile editor (bio, languages, style rules, sample messages)
- F2 Gemini provider (BYOK): key entry, validation, model selector, per-month usage log
- F3 Contacts: auto-create from notifications, manual create, merge, relationship metadata
- F4 Per-contact isolated conversation log (channel, direction, timestamp, deduped)
- F5 Notification listener (read-only) for Telegram + WhatsApp, text messages
- F6 Draft generation screen: reply variants (1–3), tone transforms (shorter/softer/etc.), edit, copy
- F7 "Open original chat" deep-links (WhatsApp/Telegram) + copy-to-clipboard
- F8 Memory layer I: raw log + rolling per-contact summary (token-budgeted)
- F9 Memory layer II: durable extracted facts per contact (pin/disable/delete)
- F10 Full draft history with latency/token stats
- F11 Security: API key in Android Keystore-backed encrypted store; app lock (biometric/PIN); no backups to cloud
- F12 Privacy controls: per-contact private mode (never sent to AI, no memory), global pause, incognito schedule
- F13 Error resilience: offline queue, 429/5xx exponential backoff, clear in-app error states
- F14 Manual mode (paste message → draft) — also the app fallback when listener is unavailable

**SHOULD (v1.x)**
- F15 Group-chat awareness (names participants; default: suggest-only, extra confirmation)
- F16 Search across messages/facts (FTS5)
- F17 Export per-contact data (JSON), full local backup (encrypted file)
- F18 Media placeholders ("📷 photo — reply not generated"), voice-note notice
- F19 Cost dashboard: token usage per contact/model, daily free-tier budget guard
- F20 Multi-language style per contact ("pidgin with Sam, formal English with bank")

**COULD (v2)**
- F21 Direct send via notification RemoteInput (gated, opt-in per contact) — see §8/§11
- F22 Additional providers (OpenAI, Anthropic, on-device Gemini Nano/Gemma)
- F23 Embedding-based semantic memory recall (using Gemini embeddings)
- F24 Calendar/context awareness (time-of-day tone), quick-reply from ReplyMate's own notification
- F25 Chat-history import (Telegram JSON export) to bootstrap memory

**WON'T (explicit non-goals for v1)**
- Auto-send without human approval · media generation · reading messages outside notifications
  (no Accessibility screen-scraping) · any cloud sync of messages · accounts/sign-in · ads/analytics.

---

## 4. Technical Architecture

**Design rules:** local-first · provider-agnostic AI behind an interface · per-contact isolation at the
*query layer* · core logic platform-free so it is unit-testable on the JVM · smallest possible dependency
footprint (matters because of our build environment — see below).

```
┌───────────────────────── UI LAYER (app) ─────────────────────────┐
│ Home/Inbox · Conversation/Draft · Contacts · Profile/Style ·     │
│ Memory browser · Settings/Providers · Onboarding · Debug log     │
│ (Android Views, dark theme; MVVM-lite with observable state)     │
├──────────────────────── DOMAIN / CORE (pure JVM) ────────────────┤
│ PromptBuilder · MemoryEngine (extract, merge, dedupe, budget) ·  │
│ StyleEngine · DraftService · TokenBudgeter · Redaction/PII guard │
│ (no android.* imports → JUnit-testable in sandbox)               │
├──────────────────────── PROVIDERS (pure JVM) ────────────────────┤
│ interface AiProvider { generate(req) ; embed?(req) }             │
│ GeminiProvider(HTTP, JSON, retry/backoff, usage meter)           │
├──────────────────────── DATA LAYER ──────────────────────────────┤
│ SQLite + SQLCipher · DAOs · Repository · migrations · export     │
├──────────────────────── PLATFORM (android) ──────────────────────┤
│ NotificationListenerService · Keystore crypto · Biometric lock · │
│ Notifier (our own heads-up) · Clipboard · deep-link intents      │
└──────────────────────────────────────────────────────────────────┘
```

**Build tracks (important, honest constraint):**
- **Track A — this sandbox (what we can compile here):** pure Java (or Kotlin via standalone kotlinc),
  classic Views, zero third-party AARs, raw SQLite. Our `apk-engine` pipeline (aapt2 → javac → d8 →
  sign) produces the APK in seconds. v1 targets Track A.
- **Track B — public-release track (Android Studio):** Kotlin + Compose + Room + WorkManager +
  DataStore, Play-ready. The Clean Architecture above is chosen so Track A code ports 1:1 (core module
  unchanged, swap UI/data implementations).
- **Decision D1 below confirms Track A for v1.** No feature in §3 "MUST" requires Track B.

**Language decision for Track A:** Java 8 language level (matches pipeline; zero setup).
**Threading:** single background executor + main-thread callbacks (no Rx).
**HTTP:** `HttpsURLConnection` wrapped in a tiny client (no OkHttp dependency).
**JSON:** hand-rolled minimal JSON writer/parser in core (messages are structured; ~200 lines, unit-tested).

---

## 5. Database Design

SQLite in app-private storage for v1 (approved decision #2; SQLCipher upgrade reserved for P6+). No cross-contact foreign keys are *used in prompt assembly* — isolation
is structural: every memory query is scoped by `contact_id`, and `PromptBuilder` accepts exactly one
`contactId` and physically cannot receive another contact's rows.

```
provider_config   id PK · type(gemini|…) · label · base_url · model_name
                  · api_key_enc (Keystore-wrapped) · is_active · created_at
contact           id PK · display_name · relationship_type · relationship_notes
                  · tone_override · language_pref · style_profile_id FK NULL
                  · ai_enabled (bool) · memory_enabled (bool) · merged_into_id NULL
                  · created_at · updated_at
contact_channel   id PK · contact_id FK · channel(whatsapp|telegram|manual)
                  · remote_key (normalized sender/conversation id) UNIQUE(channel, remote_key)
message           id PK · contact_id FK · channel · direction(in|out) · body TEXT
                  · sent_at · notif_key UNIQUE(channel,notif_key) NULLABLE (dedupe)
                  · source(listener|manual|import) · INDEX(contact_id, sent_at)
memory_fact       id PK · contact_id FK · category(person|preference|event|relation|comm_style|boundary)
                  · text · importance 1..5 · confidence 0..1 · pinned bool · disabled bool
                  · source_message_id FK NULL · created_at · updated_at
                  · UNIQUE(contact_id, text_norm)  ← merge key for dedupe
contact_summary   id PK · contact_id FK · summary_text · covers_until_ts · version · created_at
                  · UNIQUE(contact_id, version)
style_profile     id PK · scope(global|contact) · contact_id FK NULL · sample_messages TEXT
                  · derived_rules TEXT · updated_at
draft             id PK · contact_id FK · in_reply_to_message_id FK NULL · prompt_snapshot JSON
                  · reply_text · model · variant_group · status(generated|edited|copied|sent)
                  · latency_ms · tokens_in · tokens_out · created_at · INDEX(contact_id, created_at)
usage_event       id PK · ts · model · tokens_in · tokens_out · kind(reply|summary|extract|style)
app_kv            key PK · value   (non-secret prefs; secrets never here)
```

**Migration strategy:** `PRAGMA user_version`-driven, sequential `migrate(n→n+1)` functions, each with
up/down, covered by JVM tests (create v_n schema → migrate → assert). Backups (F17) export JSON per
contact + manifest of schema version; import validates version then replays migrations.

---

## 6. AI Pipeline Design

**Endpoint (2026-current pattern):**
`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
Header `x-goog-api-key: <BYOK key>`, JSON body `{systemInstruction, contents[], generationConfig}`.
Model name is user-configurable; default a free-tier Flash-class model. Research snapshot (Jul 2026):
AI Studio keys are free with no card; free tier ≈ rate-limited (10 RPM / ~500 RPD class on 2.5 Flash,
daily quotas vary by model — app treats 429 as normal backpressure, never as failure) — see Sources.

**Request assembly (per draft):**
1. **System instruction block** (composed by StyleEngine): identity ("You ARE <me>, writing my personal
   messages"), My Profile digest, global style rules, contact tone override, relationship context,
   hard boundaries (never disclose API/app internals; never invent facts about contact; reply length
   target; language).
2. **Memory context block:** latest rolling summary + pinned/high-importance facts (cap ~30, budgeted).
3. **Thread:** last N messages (default 30, oldest-first) mapped incoming→`user`, my outgoing→`model`,
   names prefixed so 1:1 vs group stays unambiguous.
4. **Task:** current incoming message(s) + explicit task ("Write my reply. Output only the message text,
   no quotes or commentary. 1–3 sentences unless context demands more."). Variants via `candidateCount`
   or N calls for tone transforms.

**Budgets & performance:** token estimate chars/4; hard context cap (e.g., 8k tokens input); drop oldest
thread turns first, then trim facts, never drop system style rules. All of this in `TokenBudgeter` (pure,
tested). Streamed responses: v1 no (simpler), v1.x optional SSE.

**Background intelligence jobs** (debounced, behind memory_enabled flag):
- *Fact extraction:* every K new messages or T minutes per contact → JSON-schema extraction prompt
  → normalize (lowercase, strip punctuation) → upsert on `text_norm` → supersede conflicts (newer wins,
  older kept disabled unless pinned).
- *Rolling summary:* regenerate when uncovered span > threshold (~2k tokens); store versioned.
- *Style derivation (J3/F1):* from pasted samples → style rules text, refreshed on demand.

**Resilience:** 429/5xx → exponential backoff (0.5s×2ⁿ, ≤3 retries) with jitter; offline → queue + banner;
provider errors classified (quota/auth/other) with specific user guidance. Every call logged to
`usage_event` (F19 dashboard, free-tier guard: warn at 80% of a user-set daily cap).

---

## 7. Memory Architecture

Five per-contact layers, read in this order for every prompt:

| # | Layer | Written by | Lifetime | Purpose |
|---|-------|-----------|----------|---------|
| M1 | Raw message log | listener/manual | until user purges | ground truth, thread tail |
| M2 | Rolling summary | AI job | versioned, latest used | long-history compression |
| M3 | Durable facts | AI extraction + user pins | until deleted/disabled | "Amara hates voice notes", "Client pays Fridays" |
| M4 | Relationship notes | user | until edited | who they are, tone contract, boundaries |
| M5 | Style (global/contact) | AI-derived + user rules | until refreshed | how *I* write / how I write *to them* |

**Isolation guarantees (the core idea, made structural):**
1. All reads for generation take a single `contactId`; cross-contact queries don't exist in the codebase.
2. Unique-merge keys (`text_norm`) are per contact. 3. Summary/fact tables FK to contact with cascade
   delete. 4. Deleting/merging a contact wipes or reassigns *only* their rows. 5. A "private mode" contact
   writes M1 only locally, never appears in any API payload (enforced in PromptBuilder + Redaction guard).

**Communication-pattern capture:** facts of category `comm_style` ("they write in short bursts",
" hates late-night calls") + summary prose; per-contact language preference feeds style layer.
**Recall quality (v1):** recency + pinned + importance ranking — deterministic and debuggable;
semantic embeddings postponed to F23 so v1 stays cheap and predictable.

---

## 8. Android Implementation Approach (messaging integration)

**Chosen approach — safest by construction: NotificationListenerService (read-only).**
- System-supported special access; works on stock Android; **zero interaction with WhatsApp/Telegram
  servers, zero ToS exposure** — all data collection happens on the user's own device, explicitly granted.
- Telegram and WhatsApp both render message notifications in `Notification.MessagingStyle`, exposing
  sender display name, conversation title, per-message text/timestamps, and group detection — the cleanest
  possible data source without touching their internals. (See Sources.)
- Extraction plan: package filter (`com.whatsapp`, `org.telegram.messenger` → user-selectable list) →
  parse `MessagingStyle` messages → build `remote_key` (conversation id key from `sbn.getKey()` stable id)
  → upsert contact_channel → batch rapid bursts (5s debounce window) → insert messages with `notif_key`
  dedupe → post ReplyMate action-notification.
- **Groups:** M3 notes mark "is_group"; draft flow adds explicit confirmation (avoid replying-to-wrong-audience
  accidents). Default group policy: generate draft on tap only (no proactive notification).
- **Dual apps / work profile:** notifications arrive per-profile; keys are namespaced per Android user
  handle to prevent duplicate contacts.
- **Silent cases:** media-only ("📷"), voice notes, vanished notification texts → stored as placeholders,
  no generation attempt. Edited/unsent messages can't be detected from notifications — accepted limit.

**Draft → send paths (in order of build):**
1. **Copy + open chat (v1):** clipboard + deep-link intent. WhatsApp: `Intent(VIEW, "https://wa.me/…")`
   scoped `setPackage("com.whatsapp")` when number known, else just clipboard + app launch; Telegram:
   `tg://` resolution where username known, else app launch. Always lands user one paste away from sent.
2. **RemoteInput direct-send (v2, F21):** both apps ship notifications whose "Reply" action carries a
   `RemoteInput`; invoking its PendingIntent with filled bundle sends a reply *through the app itself*
   without opening it. Proven technique (used by Pushbullet-class apps), but: action list can be null when
   multiple chats bundle into one notification, and behavior varies by app version → built behind per-contact
   opt-in + runtime validation + graceful fallback to path 1. **Never auto-sends: one tap = one confirmation.**

**Explicitly rejected approaches (recorded):** AccessibilityService screen-scraping/typing (fragile,
Play-policy restricted, over-privileged) · unofficial WhatsApp APIs/web-protocol clients (account-ban risk)
· Telegram userbots/TDLib on personal number (violates scope; bots can't read your DMs anyway) · anything
requiring a server we operate (breaks local-first).

**Reliability:** listener auto-rebinds via `onListenerConnected`/`requestRebind`; OEM battery killers
(Xiaomi/Tecno/Infinix common locally) → in-app "keep me alive" checklist (ignore-battery-optimizations +
autostart deep links) + health indicator on home ("Listener: active ✅"). No foreground service needed —
delivery is event-driven; fallback is Manual mode (F14).

---

## 9. Security Model

| Asset | Protection |
|---|---|
| Gemini API key | Android Keystore AES-GCM-wrapped; never in plain SharedPreferences/logs; masked UI |
| Message DB | v1: app-private storage + app lock + FLAG_SECURE + no backups (approved decision #2); SQLCipher reserved as P6+ upgrade with planned migration |
| App access | BiometricPrompt + device-credential fallback; auto-lock after N min; screen-shot block (`FLAG_SECURE`) on conversation/memory screens |
| Backups | `android:allowBackup="false"`, `fullBackupContent=false` → nothing leaves via adb/cloud; export only via explicit encrypted-file action |
| Network | HTTPS only (`usesCleartextTraffic=false`); app only ever calls the configured provider host(s); no third-party SDKs at all = **zero third-party data egress** |
| Least privilege | Permissions: Notification Access (special), POST_NOTIFICATIONS, Biometric. Nothing else. No contacts/location/SMS |
| Privacy controls | per-contact private mode · global pause · redaction toggle (strip phone numbers/emails before API call) · prompt-audit log w/ auto-purge · "delete everything" nuke |
| Public-release track | privacy policy, Play Data-safety form, disclosure of notification use, provider key stays user-owned (BYOK) |

**Threat notes:** root/physical attackers are out of scope; biggest real risks are shoulder-surfing
(biometric + FLAG_SECURE), key leakage via logs (no-log rule + tests), and accidental API over-sharing
(private-mode + redaction + prompt audit viewer).

---

## 10. Development Phases (foundation → final)

Each phase ships an installable APK from this sandbox + a test gate. Effort in focused sessions (S ≈ one session).

- **P0 — Foundations (1–2 S):** Track-A scaffold (`com.replymate.app`), SQLite open/migrate harness,
  settings, dark shell, nav stub. *Gate:* migrations tested; APK installs.
- **P1 — Manual mode MVP (2–3 S):** My Profile, contacts, message log, Gemini provider + key vault,
  PromptBuilder v1 (system + thread), draft screen with copy, usage log. *Gate:* paste message → draft in
  <10s over mobile data; key survives reinstall-lock test; core JUnit suite green.
- **P2 — Listener (2–3 S):** NotificationListenerService onboarding, MessagingStyle parser, dedupe/batching,
  contact auto-discovery, ReplyMate notifications, listener health check. *Gate:* 3-day soak on owner device,
  real WhatsApp + Telegram, 0 missed-text failures, 0 dupes.
- **P3 — Draft UX polish (1–2 S):** variants, tone transforms, open-chat deep-links, draft history, faster
  perceived latency (prefetch on notification). *Gate:* daily driver by owner for a week.
- **P4 — Memory layers (3–4 S):** facts extraction+merge UI, pinned facts, rolling summaries, budgeter v2,
  memory browser/edit/delete, isolation audit tests. *Gate:* cross-contact leak test suite (seeded A/B
  contacts — impossible-by-construction assertions); reply quality review by owner.
- **P5 — Memory layer: style (2 S):** style profiling from samples, per-contact overrides, language per
  contact. *Gate:* owner blind-rates 20 drafts "sounds like me" ≥ 8/10 average.
- **P6 — Hardening (2 S):** SQLCipher decision implementation, biometric lock, FLAG_SECURE, export/import,
  incognito schedule, cost guard, polish/perf. *Gate:* security checklist 100%; 200-message stress test.
- **P7 — Optional advanced (per decision):** RemoteInput send pilot (validated on-device per app version),
  provider #2, embeddings recall, history import. *Gate:* explicit per-item approval.
- **P8 — Public-readiness (only when asked):** Track-B rewrite/Compose, Play compliance, privacy policy,
  beta channel. Out of current scope; contracts kept clean to preserve the option.

---

## 11. Risks & Solutions

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Free-tier quota exhaustion during heavy use | Med | Med | budget guard F19, backoff, model switcher, paid toggle |
| OEM battery manager kills listener → missed notifications | High (local devices) | High | health indicator + guided whitelist; manual mode fallback; rebind on boot/unlock |
| MessagingStyle format differs by app version | Med | Med | parser tolerant (extras fallback chain), P2 soak test, per-app parser layer with unit fixtures |
| Draft "doesn't sound like me" | Med | High | style samples, tone transforms, blind-rating gate P5, per-contact override |
| Cross-contact memory leak | Low (structural) | **Critical** | isolation-by-construction + automated leak tests + audit viewer; treated as release-blocker |
| RemoteInput send breaks/bundled notifications hide actions | Med | Low | optional F21 only, per-contact opt-in, fallback to copy flow |
| Accidental reply in group/work chat | Med | Med | groups draft-on-tap only + confirmation; work-profile namespacing |
| API key compromised | Low | High | Keystore wrap, no logs, masked display, easy rotate + revoke guidance |
| Privacy regression via backups/sync | Low | High | allowBackup=false, export-only design, privacy tests in P6 |
| Sandbox build limits (no Room/Compose) | Known | Low | Track-A/B split preserves future quality; core code portable |
| Notifications miss edits/un-sends & media | Certain | Low | placeholders; documented UX copy |
| Cost creep at scale (many contacts) | Low-Med | Med | summary-first architecture, token caps, cheap model default |

---

## Decisions — LOCKED (owner-approved 2026-07-17)

1. Track A APK-first architecture confirmed.
2. No SQLCipher in v1 — Keystore-protected secrets + secure local storage; DB encryption deferred until core works (P6+ evaluation).
3. minSdk 24 (blueprint handles BiometricPrompt/API-28 consequence with PIN/device-credential fallback).
4. Gemini stays behind a provider interface; more providers addable later without touching app/core.
5. Strict per-contact isolation: separate history, memory, style, AI context per contact — structural + release-gated leak tests.
6. Manual mode is the MVP; works with zero notification permissions.
7. Notification listener, Telegram/WhatsApp integration, advanced memory jobs, sending features → later gated phases.
8. No product decisions without approval → enforced via BLUEPRINT §11 change control.

(This sections supersedes earlier D1–D7 open questions; defaults D3/D4/D5 were superseded by decisions 3–7 above.)

## Test strategy summary
Core (prompt builder, memory merge/dedupe actors, budgeter, JSON, migrations, redaction): pure-JVM JUnit,
runnable in this sandbox → must pass before every APK. Platform (listener parsing): fixture-based unit tests
from captured notification dumps + on-device checklist per phase gate. Release gates per phase above.

## Sources (researched 2026-07-17)
- Gemini API key/free tier & endpoint pattern: apideck.com/blog/how-to-get-your-gemini-api-key;
  costgoat.com/pricing/gemini-api; metacto.com (free-tier quotas, Flash vs Pro, Apr 2026 changes).
- NotificationListenerService + MessagingStyle + RemoteInput technique (WhatsApp/Telegram workable, bundled-
  notification caveat): StackOverflow threads 40369508, 71532612, 46788600; r/androiddev 6hxf5z.
