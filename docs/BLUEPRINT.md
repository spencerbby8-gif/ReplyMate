# ReplyMate — Implementation Blueprint (BUILD-AUTHORITY)
**Version:** 1.0 · **Status:** AWAITING OWNER APPROVAL — no code yet.
Product context lives in `docs/SPEC.md` (v1.1). If this blueprint and the spec conflict,
this file wins on *how to build*; the spec wins on *what the product is*.

---

## 0. Locked decisions (owner-approved 2026-07-17)

| # | Decision |
|---|----------|
| 1 | **Track A only** — APK-first, sandbox-buildable (pure Java 8, classic Views, zero 3rd-party libs), built by `/home/user/apk-engine/`. Track B (Kotlin/Compose/Play) deferred. |
| 2 | **No SQLCipher in v1.** Secrets protected by Android Keystore; DB in app-private storage; defense-in-depth via app lock + FLAG_SECURE + no backups. DB encryption is a later (P6+) upgrade with a planned migration path. |
| 3 | **minSdk 24**, targetSdk 34. (Consequence handled in §6: BiometricPrompt is API 28+ → PIN/device-credential fallback for API 24–27.) |
| 4 | **Gemini behind `AiProvider` interface;** model/base-URL configurable; new providers addable without touching app/core layers. |
| 5 | **Strict per-contact isolation** — separate history, memory, style, AI context per contact, enforced structurally (§3 isolation rules + dedicated leak test suite §9). |
| 6 | **Manual mode is the MVP** — fully functional with zero notification permissions. Onboarding never blocks on the notification grant. |
| 7 | Notification listener, Telegram/WhatsApp integration, advanced memory jobs, and any sending capability stay in later gated phases. |
| 8 | **No product decisions without approval.** Deviations from this blueprint require owner sign-off via §11 change control. |

---

## 1. Complete architecture

### 1.1 Module & layer map

```
com.replymate.app        (ANDROID — Activities, platform services. Depends on core+data+provider)
   ├─ ui                 (one Activity per screen; no fragments; MV-less thin views → use-case calls)
   ├─ platform           (KeystoreCrypto, LockManager, Clip, DeepLinks, Notifier, ListenerService[P2])
   └─ di                 (AppContainer — manual dependency wiring, created in ReplyMateApp.onCreate)

com.replymate.core       (PURE JVM — zero android.* imports → unit-testable in sandbox)
   ├─ model              (POJOs + enums — §4)
   ├─ ports              (interfaces the outside world implements:
   │                      ContactStore, MessageStore, MemoryStore, StyleStore, DraftStore,
   │                      UsageStore, KvStore, AiProvider, SecretVault, Clock, IdGen, Logger)
   ├─ usecase            (DraftService, ContactService, ProfileService, MemoryAdmin — orchestration only)
   ├─ prompt             (PromptBuilder, SystemComposer, ThreadMapper, TaskComposer)
   ├─ memory             (MemoryEngine, FactExtractor[parse-side], FactNormalizer, SummaryPlanner)
   ├─ style              (StyleEngine — rule assembly & precedence)
   ├─ budget             (TokenBudgeter, ContextTruncator)
   ├─ json               (JsonWriter, JsonReader — ~200 lines, no org.json: must run on JVM too)
   └─ util               (TextNorm, TimeFmt, Result<T>, Rounding)

com.replymate.provider   (PURE JVM — HTTPS via javax.net.ssl.HttpsURLConnection; runs on JVM & Android)
   ├─ gemini             (GeminiProvider implements AiProvider: payload builder, response parser)
   ├─ http               (HttpClient, RetryPolicy(backoff+jitter), ApiError classifier, RateLimitInfo)
   └─ dto                (ChatRequest, ChatReply, TokenCount)

com.replymate.data       (ANDROID-db — SQLite via android.database.sqlite; maps Cursor↔core POJOs)
   ├─ db                 (DbHelper, Migrations v1..n with up()/down(), Pragmas)
   ├─ dao                (ContactDao, MessageDao, FactDao, SummaryDao, StyleDao, DraftDao, UsageDao, KvDao)
   └─ store              (SqliteContactStore…— DAO adapters implementing core.ports)
```

**Dependency rule (compile-enforced by build order):** `app → {core, data, provider}`, `data → core`,
`provider → core`, `core → nothing`. Core never imports android, java.awt, or org.json.
**Isolation rule:** stores expose only `…ByContact(contactId, …)` queries; there is no API that returns
mixed-contact memory or messages (§3.4).

### 1.2 Runtime flow (P1 manual mode — happy path)

```
User pastes msg → ConversationScreen
      → DraftService.generate(contactId, incomingText?)
          → stores: contact + profile(style) + last 30 msgs        [single contactId everywhere]
          → PromptBuilder → ChatRequest(systemText, turns[], task)
          → TokenBudgeter.fit(request)                             [truncate order §5.4]
          → AiProvider(Gemini).generate → HttpClient+RetryPolicy
          → parse → save Draft(variant_group) + UsageEvent
      → UI shows 1–3 variants → Copy (clipboard) / Edit / Transform("shorter…")
```

### 1.3 Process & threading
- One app process. Background work runs on SEPARATE daemon lanes (P-background-9):
  a single-threaded INGEST lane (listener capture only — ordered, never blocked by
  network), a 2-thread GEN lane (research/reasoning/paid provider calls), and the
  original small BG pool (UI/misc). Results post to UI via main-thread `Handler`.
  No loaders, no Rx, no coroutines (Track A = Java 8).
- No components exported. No broadcast receivers in P0/P1. Listener service arrives in P2 (manifest delta §8).
- Lifecycle: `ReplyMateApp` holds `AppContainer`; `LockManager` gates UI on cold start & idle timeout.

---

## 2. Folder / package structure

### 2.1 Workspace tree (this is also the apk-engine project layout: manifest+res+src at root)

```
/home/user/ReplyMate/
├── docs/                     SPEC.md · BLUEPRINT.md · decisions log (this folder)
├── releases/                 versioned APKs + RELEASES.md + sha256 (P0 creates)
├── tools/                    junit-4.13.2.jar · hamcrest-core-1.3.jar (test runner, ~350KB, persists)
├── app.json                  optional build meta (name, colors) — informational
├── AndroidManifest.xml       (P0; deltas per phase §8)
├── res/
│   ├── values/               colors.xml · strings.xml · themes.xml (dark Material-free theme)
│   ├── drawable/             vector icons (launcher, states) — XML vectors only
│   └── layout/               one XML per screen root (§7); shared row_*.xml partials
├── src/
│   └── com/replymate/{app,core,provider,data}/…   (packages per §1.1)
├── tests/
│   └── src/com/replymate/…   JUnit4 suites for core+provider (§9)
└── scripts/
    ├── run_tests.sh          compiles core+provider+tests on JVM → runs JUnitCore (§9.1)
    └── release.sh            wraps apk-engine build.sh → tags versions → archives APK + sha256 (§10)
```

### 2.2 Key classes (name → one-line responsibility) — representative, per package

- **core.prompt:** `PromptBuilder` (assemble ChatRequest), `SystemComposer` (persona+style+contact blocks),
  `ThreadMapper` (messages→user/model turns, name prefixes), `TaskComposer` (goal text incl. transforms).
- **core.memory:** `MemoryEngine` (recall ranking: pinned→importance→recency), `FactNormalizer`
  (text_norm merge key), `SummaryPlanner` (when/what span to re-summarize), `FactExtractionParser`
  (AI JSON→candidates; merge/supersede logic; *network side stays in usecase*).
- **core.usecase:** `DraftService`, `ContactService` (create/merge/configure/contact-scoping),
  `ProfileService`, `MemoryAdmin` (pin/disable/delete/export models).
- **core.json:** `Json` (parse/write), `JsonObj/JsonArr` wrappers.
- **provider.gemini:** `GeminiProvider`, `GeminiPayloads` (request JSON), `GeminiParser` (reply+usage+errors).
- **provider.http:** `HttpClient` (timeouts: connect 15s / read 45s), `RetryPolicy`
  (≤3 tries, backoff 0.5·2ⁿ s +20% jitter, honor `Retry-After`), `ApiError`{AUTH, QUOTA, NETWORK,
  SERVER, PARSE, UNKNOWN}+`classify(status, body)`.
- **data.db:** `DbHelper` (open, pragmas, migration runner), `Migrations` (`V1` §3.2).
- **app.ui:** per §7 screens. **app.platform:** `KeystoreCrypto`, `SecretPrefs`, `LockManager`,
  `BiometricGate`, `Pin` (PBKDF2), `Clipboard`, `DeepLinks`, `AppNotifier`.
- **app.di:** `AppContainer` (constructs DbHelper→DAOs→stores→services→providers; exposes getters).

**Coding conventions (binding):** Java 8 only (no `var`, no `List.of`, no streams-on-JDK9+ APIs);
UTF-8 everywhere; no external jars; `Result<T>` instead of throwing across layers; all timestamps UTC
epoch ms; all logging via core `Logger` port (scrub filter §6.4); min line length targets, no dead code.

---

## 3. Database schema (authoritative for v1)

### 3.1 Rules
- File `replymate.db`, app-private dir. `PRAGMA foreign_keys=ON; journal_mode=WAL; synchronous=NORMAL;`
- `user_version` drives migrations; `Migrations.Vn.up()/down()` idempotent, unit-tested (§9).
- Owner-approved decision #2: v1 stores plaintext in app-private file; schema already contains no secret
  columns — `provider_def` keeps only a *reference* to the Keystore-held key (§6.1).

### 3.2 DDL — schema v1 (final)

```sql
CREATE TABLE contact(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  display_name TEXT NOT NULL,
  relationship_type TEXT DEFAULT '',
  relationship_notes TEXT DEFAULT '',
  tone_override TEXT DEFAULT '',
  language_pref TEXT DEFAULT '',
  style_profile_id INTEGER NULL REFERENCES style_profile(id) ON DELETE SET NULL,
  ai_enabled INTEGER NOT NULL DEFAULT 1,
  memory_enabled INTEGER NOT NULL DEFAULT 1,
  private_mode INTEGER NOT NULL DEFAULT 0,          -- never included in API payloads
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);

CREATE TABLE contact_channel(                        -- 1 contact ↔ N app identities
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  channel TEXT NOT NULL CHECK(channel IN('whatsapp','telegram','manual')),
  remote_key TEXT NOT NULL,                          -- normalized sender/thread id (P2); 'manual' for P1
  last_seen_at INTEGER NOT NULL DEFAULT 0,
  UNIQUE(channel, remote_key));

CREATE TABLE message(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  channel TEXT NOT NULL,
  direction TEXT NOT NULL CHECK(direction IN('in','out')),
  body TEXT NOT NULL,
  sent_at INTEGER NOT NULL,
  notif_key TEXT NULL,                               -- dedupe (NULL for manual rows; SQLite allows multi-NULL)
  source TEXT NOT NULL CHECK(source IN('listener','manual','import')),
  UNIQUE(channel, notif_key));
CREATE INDEX idx_message_contact_ts ON message(contact_id, sent_at);

CREATE TABLE memory_fact(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  category TEXT NOT NULL CHECK(category IN('person','preference','event','relation','comm_style','boundary')),
  text TEXT NOT NULL,
  text_norm TEXT NOT NULL,                           -- FactNormalizer output (merge key)
  importance INTEGER NOT NULL DEFAULT 3,             -- 1..5
  confidence REAL NOT NULL DEFAULT 0.7,
  pinned INTEGER NOT NULL DEFAULT 0,
  disabled INTEGER NOT NULL DEFAULT 0,
  source_message_id INTEGER NULL REFERENCES message(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  UNIQUE(contact_id, text_norm));
CREATE INDEX idx_fact_contact_active ON memory_fact(contact_id, disabled);

CREATE TABLE contact_summary(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  summary_text TEXT NOT NULL,
  covers_until_ts INTEGER NOT NULL,
  version INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(contact_id, version));

CREATE TABLE style_profile(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope TEXT NOT NULL CHECK(scope IN('global','contact')),
  contact_id INTEGER NULL REFERENCES contact(id) ON DELETE CASCADE,
  sample_messages TEXT NOT NULL DEFAULT '',
  derived_rules TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL);

CREATE TABLE provider_def(                           -- NON-SECRET config only
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL CHECK(type IN('gemini')),       -- more values allowed by app layer later
  label TEXT NOT NULL DEFAULT 'Gemini',
  base_url TEXT NOT NULL DEFAULT 'https://generativelanguage.googleapis.com',
  model_name TEXT NOT NULL DEFAULT 'gemini-2.5-flash',
  key_ref TEXT NOT NULL,                             -- alias into SecretVault; secret never in DB
  is_active INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL);

CREATE TABLE draft(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  in_reply_to_id INTEGER NULL REFERENCES message(id) ON DELETE SET NULL,
  prompt_snapshot TEXT NOT NULL,                     -- JSON: system+context+task actually sent
  reply_text TEXT NOT NULL,
  model TEXT NOT NULL,
  variant_group TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN('generated','edited','copied','sent')) DEFAULT 'generated',
  latency_ms INTEGER NOT NULL DEFAULT 0,
  tokens_in INTEGER NOT NULL DEFAULT 0,
  tokens_out INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL);
CREATE INDEX idx_draft_contact_ts ON draft(contact_id, created_at);

CREATE TABLE usage_event(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL, model TEXT NOT NULL,
  tokens_in INTEGER NOT NULL, tokens_out INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN('reply','summary','extract','style')));
CREATE INDEX idx_usage_ts ON usage_event(ts);

CREATE TABLE app_kv(key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- reserved keys: profile.name · profile.languages · profile.bio · profile.topics
-- lock.mode · lock.pin_ref · lock.timeout_min · debug.audit_enabled · budget.daily_tokens
```

### 3.3 Migration policy
Append-only `V1, V2…`; each has `up(SQLiteDatabase)` + `down()`; runner wraps in transactions; tests
create v_n → upgrade to latest → assert shape (§9). Export/backup files carry `schema_version` and are
imported by replaying migrations forward (P6).

### 3.4 Isolation enforcement (decision #5 — structural)
1. Stores expose contact-scoped methods only (`messagesByContact`, `factsFor`, `latestSummaryFor`,
   `styleFor(scope, contactId)`); **no unscoped memory query exists.**
2. `DraftService` accepts one `contactId`; its bundle type physically holds one contact's rows.
3. `private_mode=1` rows are filtered in the *store adapter* (single choke point) and additionally
   re-checked in `PromptBuilder` (belt & braces, both unit-tested).
4. Cascade deletes guarantee full per-contact wipe; merge reassigns only the merged contact's rows.
5. `IsolationSuite` (§9.3) seeds two contacts and asserts zero cross-contamination — a release gate.

---

## 4. Data models (core.model — fields, types, invariants)

Enums: `Channel{WHATSAPP,TELEGRAM,MANUAL}` · `Direction{INCOMING,OUTGOING}` · `Source{LISTENER,MANUAL,IMPORT}`
· `FactCategory{PERSON,PREFERENCE,EVENT,RELATION,COMM_STYLE,BOUNDARY}` · `DraftStatus{GENERATED,EDITED,COPIED,SENT}`
· `UsageKind{REPLY,SUMMARY,EXTRACT,STYLE}` · `Scope{GLOBAL,CONTACT}` · `ProviderType{GEMINI}` (extensible).

- `Contact{long id; String displayName, relationshipType, relationshipNotes, toneOverride, languagePref;
  Long styleProfileId; boolean aiEnabled, memoryEnabled, privateMode; long createdAt, updatedAt}`
  *Invariant: privateMode ⇒ aiEnabled==false enforced in ContactService.*
- `ContactChannel{long id; long contactId; Channel channel; String remoteKey; long lastSeenAt}`
- `Message{long id; long contactId; Channel channel; Direction direction; String body; long sentAt;
  String notifKey; Source source}` *body trimmed, 1..8000 chars.*
- `MemoryFact{long id; long contactId; FactCategory category; String text, textNorm; int importance 1..5;
  double confidence 0..1; boolean pinned, disabled; Long sourceMessageId; long createdAt, updatedAt}`
- `ContactSummary{long id; long contactId; String summaryText; long coversUntilTs; int version; long createdAt}`
- `StyleProfile{long id; Scope scope; Long contactId; String sampleMessages; String derivedRules; long updatedAt}`
- `ProviderDef{long id; ProviderType type; String label, baseUrl, modelName, keyRef; boolean active; long createdAt}`
- `Draft{long id; long contactId; Long inReplyToId; String promptSnapshotJson, replyText, model, variantGroup;
  DraftStatus status; long latencyMs; int tokensIn, tokensOut; long createdAt}`
- `UsageEvent{long id; long ts; String model; int tokensIn, tokensOut; UsageKind kind}`
- **Ports (core.ports) signatures:** `List<Message> lastMessages(long contactId, int limit)` ·
  `List<MemoryFact> activeFacts(long contactId)` · `ContactSummary latestSummary(long contactId)` ·
  `StyleProfile styleFor(Scope, Long contactId)` · `ChatReply generate(ChatRequest)` (AiProvider) ·
  `void putSecret(String alias, String value) / String getSecret(String alias)` (SecretVault) · etc.
  (full list written alongside P0 code; any change goes through §11).

---

## 5. AI pipeline flow

### 5.1 Provider contract (decision #4)
`AiProvider.generate(ChatRequest) → Result<ChatReply>`; `ChatRequest{String system; List<Turn> turns;
Turn task; GenerationOpts{n,candidates,temperature,maxOut}}`; `ChatReply{List<String> variants;
int tokensIn, tokensOut; RateLimitInfo limits}`. Provider also exposes `validateKey()` (1-token probe).

### 5.2 Gemini wire format
`POST {baseUrl}/v1beta/models/{model}:generateContent`, header `x-goog-api-key`, JSON with
`system_instruction{parts[]}`, `contents[{role:user|model,parts[]}…]`, `generationConfig{temperature:0.8,
candidateCount:n, maxOutputTokens:220}`. No streaming in v1. Payloads built by `GeminiPayloads`
(unit-tested against golden JSON) — base_url & model overridable in Settings (free Flash default, per research).

### 5.3 Prompt assembly (P1 scope: L0+L1+L3+L4; P4 added voice/custom/learned lines + toggled profile; L1/L2 arrive in P4b)

```
L0 SYSTEM  = Identity("You are <profile.name> writing your own chat messages; output only the reply text")
           + MyProfile digest (name·languages·bio·topics — sections removed when toggled off [P4])
           + More-about-me extra when set + toggled on [P4]
           + Global style rules (GLOBAL StyleProfile.derived_rules  [P5; preset fallback P1])
           + Voice line: the 9 style controls rendered as rules [P4; global base → contact overrides]
           + Contact block (relationship_type, relationship_notes, tone_override, language_pref)
           + Contact extras: custom prompt line + learned-hints line (gated) [P4]
           + Boundaries (reply length target, never invent facts, match their language)
L1 SUMMARY = latest contact_summary           [P4b]
L2 FACTS   = top active memory_facts (pinned→importance5→recency, ≤30)   [P4b]
L3 THREAD  = last 30 messages, incoming→user "Name: text", mine→model    [ThreadMapper]
L4 TASK    = new incoming message(s) + instruction (default: natural reply, 1–3 sentences)
             transformed for tone actions: shorter|softer|funnier|more-formal|regenerate(seed++)
```

### 5.4 Token budget (budget.TokenBudgeter — pure, tested)
Estimate `chars/4`. Budget: input ≤ 6000 est. tokens. Truncation order: drop oldest thread turns →
drop lowest-(importance,recency) facts → shorten summary (re-summarize smaller) → **never** truncate L0.
Output cap 220 tokens default (variants share one call via candidateCount).

### 5.5 Error & retry policy (provider.http)
- Retry only QUOTA(429)/SERVER(5xx, incl. 503 overload)/NETWORK timeouts → ≤3 attempts,
  `sleep = 0.5s·2ⁿ + jitter(±20%)`, obey `Retry-After` header when present.
- `ApiError` mapping → UI copy: AUTH "key rejected — re-check Settings" · QUOTA "busy/daily limit —
  retrying (attempt n/3)" · NETWORK "offline — will retry when you tap again" · PARSE logged w/ snapshot.
- Every attempt outcome → `usage_event` + draft row saved on partial failure with empty reply (status
  generated only on success). Daily budget guard warns at 80% of user-set `budget.daily_tokens`.

### 5.6 Background memory jobs — P4b/P5 only (decision #7)
`FactExtractionJob`: every K=12 new msgs or 30 min per contact (whichever first) → AI JSON extraction
`{facts:[{category,text,importance,confidence}]}` → `FactNormalizer` → upsert by `(contact_id,text_norm)`,
supersede=disable-old unless pinned. `SummaryJob`: when uncovered span > ~2k tokens → new versioned summary.
`StyleJob` (P5): user-triggered from samples → derive rules. All jobs honor `memory_enabled` &
`private_mode`, and are fully testable parse/merge-side on JVM (network mocked behind AiProvider).

---

## 6. Security model (v1, per decision #2)

### 6.1 Secrets & crypto
- `KeystoreCrypto`: Android Keystore, alias `rm_master`, `AES/GCM/NoPadding` (KeyGenParameterSpec,
  256-bit, random 12-byte IV per encryption). Keystore AES is supported on API 23+ → OK for minSdk 24.
- `SecretPrefs`: ciphertext = Base64(IV‖CT) in private SharedPreferences `secrets.xml` (MODE_PRIVATE).
  Holds: `apikey.<key_ref>` (Gemini key), `lock.pin_hash` (below). Secrets never enter `replymate.db`.
- Key lifecycle: "Add key" → validate via `AiProvider.validateKey()` → encrypt → store → DB row gets
  `key_ref`. Rotate = replace under same ref. Key material never leaves TEE/KeyStore.

### 6.2 App lock matrix (minSdk 24 consequence, decision #3)
| API | Mechanism |
|---|---|
| 28+ | `BiometricPrompt` (biometric, lock screen not required) + "Use PIN instead" fallback |
| 24–27 | Device-credential confirm (`createConfirmDeviceCredentialIntent`) or app PIN |
| All | App PIN option always available: PBKDF2-HmacSHA256(20k iters, 16B salt), hash Keystore-wrapped |
Rules: lock on cold start + after `lock.timeout_min` (default 2) background idle; lock before any
sensitive screen; biometric change → optional key-invalidation handling = re-wrap on next unlock.

### 6.3 Data & platform hardening
- Manifest: `allowBackup=false`, `fullBackupContent=false`, `usesCleartextTraffic=false`, no exported
  components. Permissions (P1): none beyond default; `POST_NOTIFICATIONS` runtime in P2; `USE_BIOMETRIC`.
- `Window.FLAG_SECURE` on: Conversation, Memory browser, Profile/Style editor, Provider settings, Debug log.
- Storage: everything in `getFilesDir()` (sandboxed); export file (P6) is an explicit, user-driven copy.

### 6.4 Leakage guards
- `Logger` port with scrub list (API key, PIN, message bodies) — debug builds only; release builds log only
  event names/ids. Prompt snapshots stored in `draft.prompt_snapshot` (audit screen, toggle, 7-day auto-purge).
- Redaction toggle (P6): regex strip of phone/email patterns from outgoing payloads (core, tested).
- Threat line: root/physical attackers out of scope; shoulders + casual access + cloud backups covered.

---

## 7. UI screen map (Track A: Activities, dark theme, no fragments)

| ID | Screen | Purpose / key elements | Entry |
|----|--------|------------------------|-------|
| S00 | Lock | biometric/PIN gate | cold start, idle |
| S01 | Onboarding (4 steps) | welcome+privacy promise → My Profile → API key+test → done *(listener step NOT here — P2 adds optional step)* | first run |
| S02 | Home (Inbox) | recent contacts w/ last msg + draft badge; ➕ new contact; search P3; listener health chip hidden until P2 | launcher |
| S03 | Contact list/manage | all contacts, sort/filter, merge (P3) | S02 |
| S04 | Contact detail | header (name·relationship·chips: ai/memory/private) · tabs: Messages / Facts[P4] / Notes(style+tones) | S02/S03 |
| S05 | Conversation + Draft | thread view; input to paste incoming msg OR write my own; **Generate** → variant cards (copy/edit/transform/regenerate); scrollable draft history | S02/S04 |
| S06 | Draft detail/audit | full prompt snapshot, model, latency, tokens | S05 |
| S07 | My Profile editor | name, languages, bio, topics → kv | S02 menu |
| S08 | Style editor | presets + samples paste → derived rules (P5); per-contact override edit lives in S04-notes tab | S02 menu |
| S09 | Providers settings | provider list; add/edit key (masked), model picker (free text + current default shown), base URL, validate button, set active | settings |
| S10 | Privacy & security | app lock config, timeout, FLAG_SECURE is implicit, audit toggle+purge, daily token budget, redaction (P6), "delete all data" nuke | settings |
| S11 | Usage & cost | today/month totals by kind+model, budget bar | settings |
| S12 | Memory browser | facts list per contact (pin/disable/delete) | S04 tab (P4) |
| S13 | Settings hub | rows: S07,S08,S09,S10,S11, About | S02 menu |
| S14 | About/debug | version, build info, debug log viewer, listener status (P2) | S13 |

Navigation: `S02` is home/task root; up-navigation to logical parent per table. All generate/actions give
immediate inline feedback (progress row → variants). Every screen renders an empty state (no crashes on
fresh DB). Deep-link entry points (P2): ReplyMate notification tap → S05(contact). No external intents in P1.

---

## 8. Phase-by-phase build plan (each ends with an installable APK — §10)

Global **definition of done:** core+provider suites green · `scripts/run_tests.sh` passes in sandbox · APK
builds+signs+verifies via apk-engine · owner smoke pass · version logged in `releases/RELEASES.md`.

| Phase | Version | Scope (concrete) | Exit gate |
|---|---|---|---|
| **P0 Foundations** (≈1 S) | 0.0.1 / vc1 | repo tree §2.1; manifest v0 (`package=com.replymate.app`, min24/target34, backups off); theme+colors; `ReplyMateApp`,`AppContainer`,`DbHelper`,`Migrations.V1`,`KvDao`, stub S02; test harness (`tools/` jars + `run_tests.sh`) with MigrationTest+JsonTest | installs; suites green |
| **P1 Manual MVP** (≈3–4 S) | **0.1.0 / vc2** | P1a S07+S13+S09 Keystore key vault + validate; P1b contacts CRUD + channels(manual) + messages + S03/S04(msg tab)/S02; P1c prompt pipeline L0+L3+L4 + GeminiProvider http/retry + S05 draft flow + copy/edit + draft history + usage log (S11 basic); P1d transforms + variants + audit screen S06 + error UX | paste→draft ≤10 s on mobile data; key survives lock/rotate; suites (incl. golden payloads) green; owner uses it a day |
| **P2 Listener** (2–3 S) | 0.2.0 / vc3 | manifest+`NotificationListenerService`; permission onboarding screen (optional); MessagingStyle parser+fixtures; dedupe/batch (5 s window); contact auto-discovery+remote_key; app notifications w/ action → S05; health chip | 3-day soak, real WA+TG: 0 duplicate texts, 0 unlogged text msgs, listener survives reboot |
| **P3 Draft UX** (1–2 S) | 0.3.0 / vc4 | prefetch-on-notification; S02 search; WA/TG open-chat deep-links; copy polish; contact merge UI; empty/edge-state pass | owner daily-drives 1 week |
| **P4 Personalization & learning** (3–4 S) | **0.5.0 / vc10** | **scope REDEFINED by owner (was "Memory layers"):** global user voice = 9 controls (tone·length·emoji·formality·humor·confidence·slang·flirting·follow-ups, 3 levels each, S14) as base style → per-contact overrides + custom prompt box (S04 edit) → private "About me" extra + per-section use-toggles (S07) → learning from approved/edited/regenerated/rejected drafts (`style_signal`, schema v3) with per-contact reset/pause/export/disable + privacy gates (private-mode & memory-off contacts can neither feed nor consume learning) → prompt-audit "why it sounded this way" (S06) from per-draft snapshot `why`; all local-first + contact-isolated | customization suites green (style/learning/migration-v3/isolation); owner: voice audible in drafts, audit explains each reply, toggles verifiably remove sections |
| **P4b Memory layers** (planned, awaits owner approval) | 0.6.0 / vc11+ | original P4 scope, groundwork committed (`core.memory` + `memory_fact` store): facts job+merge UI (S12, S04 tabs), pinned facts, summaries+budgeter v2, prompt L1+L2, `IsolationSuite` release gate, memory audit views | leak suite green; owner rates drafts for 3 contacts "context-aware" ≥8/10 |
| **P5 Style engine** (2 S) | 0.5.0 / vc6 | sample→rules job (S08 real), per-contact style overrides, per-contact language | blind-rating "sounds like me" ≥8/10 |
| **P6 Hardening** (2 S) | 0.6.0→**1.0.0** / vc7–8 | biometric gate/PIN polish, redaction, export/import (JSON, schema-versioned), budget dashboards, perf pass (200 msgs/contact ×10), secure-delete, SQLCipher evaluation (decision #2 reopening possible w/ approval) | security checklist 100%; owner acceptance → tag 1.0.0 |
| **P7 Optional** (per item, only on request) | 0.7.x+ | RemoteInput direct-send pilot (per-device validation, opt-in per contact, never auto-send) · provider #2 · embeddings recall · history import | per-item owner approval |

Manifest deltas are listed per phase so permissions appear exactly when features do (P2 adds the listener
service + `POST_NOTIFICATIONS`; nothing earlier).

---

## 9. Testing strategy

### 9.1 Harness (sandbox-runnable)
`tools/junit-4.13.2.jar` + `hamcrest-core-1.3.jar` (Maven Central, ~350 KB, persisted in workspace).
`scripts/run_tests.sh`: javac(8) → core+provider → tests → `java -cp … org.junit.runner.JUnitCore <Suites>`.
Core/provider are pure JVM by §1 rule — they test on the sandbox JVM *identically* to on-device behavior.

**Addendum (P-release-1, 2026-08-12):** the "device-stack / robo" counts cited in release notes up to
1.5.8 (e.g. "110 robo green") came from an external harness that was never committed and is LOST.
Those numbers are **retired as release gates** — the official reproducible gate is the JVM suite
(`scripts/run_tests.sh`; 757 @ 1.5.8). Any future device-stack harness must live in this repo (e.g.
`tests/device/`) before its counts may be cited as gates again.

### 9.2 Suites (name — representative cases)
- `JsonTest` — round-trip, nested escaping, unicode, malformed input errors.
- `TokenBudgeterTest` — estimate accuracy ±15%; truncation order honored; L0 never truncated; edge: 0 msgs.
- `PromptBuilderTest` — golden assembly order L0..L4; name-prefix mapping; private_mode contact → build fails closed; transform tasks change only task text.
- `FactNormalizeTest` — case/punct collapse; unicode names; merge-key stability.
- `MemoryMergeTest` — dedupe by text_norm; supersede disables old unless pinned; importance/clocks tiebreaks.
- `SummaryPlannerTest` — span thresholds; version increments; covers_until_ts monotonic.
- `GeminiPayloadTest` — golden request JSON; response parse incl. multi-candidate; usage extraction; 429/403/500 → correct `ApiError`; `Retry-After` parsed.
- `RetryPolicyTest` — backoff schedule, jitter bound, no-retry on AUTH/PARSE, max-3 cap.
- `MigrationTest` — fresh-latest equals upgrade-from-every-prior; idempotent re-run; FK/unique/index presence.
- `IsolationSuite` — two seeded contacts (facts/summary/msgs/styles both): every store read for A contains only A rows; PromptBuilder bundle for A provably has no B strings; delete-B leaves A intact; private contact never serialized into a request. **Release-blocking.**

### 9.3 Platform & on-device (per phase)
P1: install/lock/rotate smoke checklist. P2: fixture tests from captured `StatusBarNotification` dumps
(serialized extras) + real-device checklist (Telegram DM/group, WhatsApp DM/group/batch, dual-profile,
kill/reboot listener). P4-6: regression + stress (perf budget: S05 open <300 ms w/ 5k msgs; generation
UI never janked by parsing). Every phase exports its checklist into `docs/checklists/`.

---

## 10. APK release strategy

- **Versioning:** semver `versionName`, monotonic `versionCode` (table in §8). Bump per shipped phase;
  hotfix → patch++ with its own APK.
- **Build pipeline (in-repo engine — P-release-1):**
  `VERSION_CODE=40 VERSION_NAME=1.5.9 bash engine/build.sh "$(pwd)" releases/ReplyMate-1.5.9.apk`
  `scripts/release.sh` wraps this: runs tests → builds → verifies (`apksigner verify`, `aapt2 dump badging`,
  `zipalign -c`, empty-`rm_builtin` scan) → computes sha256 → archives to `releases/` + appends
  `RELEASES.md` row (date, vc, name, sha, notes). The engine self-bootstraps the pinned toolchain from
  `engine/TOOLS.txt` into gitignored `.engine-sdk/` — a fresh clone builds with no workspace assumptions.
  (Pre-2026-08-12 versions used an external `/home/user/apk-engine` that was never committed; it was
  lost with the old sandbox.)
- **Signing (P-release-1 reality):** the original `/home/user/apk-engine/keystore/arena.keystore` lived
  outside git and was **lost** in the 2026-08-12 workspace prune (its cert sha-256 is recorded in the
  1.5.8 checklist: `b15f2f37…c6a85ed`). The engine now keeps a local key in gitignored `secrets/`
  (generated once, chmod 600, never committed). **Consequence: builds signed with the new key do NOT
  update-in-place over ≤1.5.8** — devices need a clean install (app data resets) unless the original
  key is recovered into `secrets/`. The "never regenerate" rule stands for the new key going forward.
- **Delivery:** APK → workspace download → phone ("install from this source" once) → over-install for updates.
  Optional fallback: keep last 3 APKs in `releases/` for quick rollback.
- **Pre-flight per release:** suites green · vc bumped · private-mode + isolation suite green · manifest
  diff reviewed (no surprise permissions) → then owner smoke list for that phase gate.
- **Public release later (Track B only):** new package id decision, Play upload key (separate from personal
  key), privacy policy + Data-safety form. Out of scope until requested (decision #8).

---

## 11. Change control (decision #8)
Any deviation with product, privacy, security, schema-destructive, or permission impact requires explicit
owner approval before implementation. Process: edit this blueprint → bump its version → add line to the
change log below → proceed. Mechanical/bug-fix changes need no approval.

**Change log:** v1.0 (2026-07-17) — initial blueprint, decisions #1–8 incorporated.

---
**END OF BLUEPRINT — awaiting approval to begin Phase P0.**
