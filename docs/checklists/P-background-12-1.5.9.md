# P-background-12 — BLOCKING REAL-DEVICE AUDIT (1.5.9)

The owner's bar for this phase, verbatim: *"Do not claim a fix until it is
reproduced, fixed, regression-tested, and verified on-device."* Every item
below therefore carries **reproduced → root cause → code fix → test proof →
device proof**. Unit-test rows are named by suite+method; device rows are for
the owner to mark — I do not self-certify device lines. 1.5.9 stays unshipped
(`releases/RELEASES.md` untouched) until the device rows pass.

Baseline entering this phase: remote main `66525c3`, JVM gate 820/820.
This phase: commits `4d9d6d4` + `c58d9d8`, JVM gate **834/834**
(×4 consecutive local runs), CI proof build — see Gate status.

---

## Item 1 — Background toggle: real Android state + fast/non-blocking

- **Reproduced (P-background-11 audited, re-verified this phase):** the toggle
  previously reported success without checking every real Android lever, and
  "battery ✓" could display while the app sat in the Restricted bucket.
  Slowness report: drafts queued behind one slow provider call.
- **Root cause & state (carried from P-bg-11, gate re-pinned here):**
  `AssistantPrereq` probes the REAL state — listener bound
  (`enabled_services` + secure-settings grant), POST_NOTIFICATIONS (API 33+),
  `PowerManager.isIgnoringBatteryOptimizations` (API 23+), and
  `ActivityManager.isBackgroundRestricted` (API 28+) — re-checked in
  `SettingsActivity.onResume`, with the focused
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog (permission declared in
  the manifest — verified this phase) and the app-details fallback path for the
  Restricted bucket (no intent exists that flips it — documented).
- **Speed — root cause of draft pile-ups:** the generation lane had 2 threads
  AND no per-contact serialization discipline, so a duplicated job made it
  worse: two slow conversations could starve everyone else.
- **Code fix (this phase):**
  - `Tasks.java`: GEN lane widened 2 → **3** threads.
  - `DraftService.generateForContact`: per-contact **striped lock** (32 slots
    keyed by contactId hash) — same contact can never double-generate, and
    distinct contacts never wait on each other. This makes the widened lane
    safe by construction.
  - HTTP bounds unchanged and pinned: 15 s connect / 45 s read, ≤3 attempts,
    ≤60 s retry waits. BatchWindow ≈5 s + SETTLE_EXTRA_MS 1.5 s ⇒ ≈6.5 s settle
    by design (burst absorbs rapid-fire replies before spending a request).
- **Test proof:** `OneDraftPerMessageTest.concurrentGenerationsForOneContactNeverStackDrafts`
  (two truly-concurrent same-contact generations: provider `maxInFlight == 1`,
  exactly one draft after the race) and
  `concurrentGenerationsForDifferentContactsStayParallel` (**deterministic**
  2-slot barrier inside the provider — if the locks ever collapsed into global
  serialization, both replies come back marked `SERIALIZED` and the drafts'
  stored text fails the test; no wall-clock timing involved).
- **Device proof (owner):** B-rows in §D.

## Item 2 — Duplicate generation ("2–3 identical AI drafts" for one message)

- **Reproduced:** three INDEPENDENT producers, all pinned from code:
  1. `PromptBuilder` asked the provider for **3 candidates on every
     generation** (`VARIANT_COUNT = 3`), and the save loop inserted **one
     `Draft` row per variant** → one message produced up to 3 stored drafts
     (and 3× output-token billing) even with no bugs anywhere else.
  2. **Concurrent same-contact race:** the GEN lane is a pool; a catch-up
     sweep (`retryOne → schedule()`) could fire while a live job sat
     mid-provider-call; the two runs' `purgeUnsavedDrafts()→insert` sequences
     could interleave and STACK rows. JobCoalescer supersedes by token, but a
     mid-flight job can finish its own gates before the live job's hash commit
     lands.
  3. Providers can legitimately return byte-identical candidates; they were
     persisted verbatim, reading as "generated twice".
- **Code fix:**
  - `PromptBundle.candidates` — new field, default
    `PromptBundle.DEFAULT_CANDIDATES = 1`; old constant renamed
    `PromptBuilder.PREVIEW_VARIANTS = 3` and set ONLY by interactive previews
    (`previewVoice`); the Gemini payload keeps honoring the requested count
    (`GeminiPayloadTest` pins the plumbing with an explicit 3).
  - `DraftService.generateForContactLocked`: save loop persists the FIRST
    non-empty variant only (`break` after first save).
  - `DraftService.generateForContact(contactId, abort)`: wraps the whole
    generate in `synchronized (lockFor(contactId))` — purge→insert is atomic
    per contact, so the second run sees the first run's committed state.
  - `ChatReply` constructor: variants are trimmed and trimmed-identical
    variants deduped at the boundary, so provider over-delivery can no longer
    fan out into stored state.
  - `ChatReply`/`PromptBuilder` audit payload now records what was actually
    requested (`"candidateCount":1`), verifiable in Prompt Audit on-device.
- **Test proof:** `OneDraftPerMessageTest` (6): request asks exactly 1
  candidate on the background path; 3 identical provider variants ⇒ **one**
  stored draft, one usage event, snapshot `"candidateCount":1`; distinct
  variants beyond the first are never persisted; byte-identical variants are
  dropped in `ChatReply` (trim-aware); the concurrent race never stacks
  (`maxInFlight == 1`, one final draft); previews still ask for 3 samples.
  Contract updates: `DraftServiceTest.happyPathGeneratesPersistsOneCurrentDraftAndUsage`,
  `PromptAuditCompletenessTest` (`candidateCount` now expects `1`).
- **Device proof (owner):** DUPE-rows in §D.

## Item 3 — Non-message filtering ("zero provider calls per noise type")

- **Reproduced (P-background-9/10 audited; re-verified + extended this
  phase):** housekeeping/newsletter/group/call/service-chat shapes could
  reach a conversation on some paths.
- **Single-route verification (re-done this phase, from source):** the ONLY
  ingest route is `RmNotificationListener.process()` → `ParserRegistry.route()`
  → `IngestCoordinator.handle()`; `reconcile()` loops the same `process(sbn)`
  — there is no alternate path to audit twice. `NoiseGate.evaluate` runs per
  event BEFORE any contact creation or row write. TitleTextParser has the
  category gate; both parsers run `StatusFilter.isSelfStatus` (self-title +
  ongoing/progress/phrase).
- **New pinned shapes this phase** (`NoiseEndToEndTest`, real
  ParserRegistry+IngestCoordinator stack, fresh stores per shape):
  - `"Checking for new messages"` — ongoing card AND transient variant;
  - `"WhatsApp Web is currently running"`;
  - backup cards that arrive via CATEGORY only (`service`, `progress`) with no
    progress bar;
  - `"2 unread chats"` digest;
  - **API 24–28 group shape** — no native `conversationId`; the group flag
    alone must carry the drop;
  - **service-chat label equality for EVERY watched app's label** (11 defs in
    `WatchedApps.all()`, incl. both X package ids) — the card is 1:1-shaped so
    the test asserts it PARSES and dies at ingest (not vacuously ignored):
    zero contacts, zero rows, zero pings.
  - "Zero provider calls" is a construction fact: background jobs are produced
    exclusively from `IngestReport.pings`; zero pings ⇒ nothing reaches a
    provider.
- **Controls kept passing:** real 1:1 → 2 stored + 1 ping; a human sentence
  containing "missed your call" still flows; the announcement drop leaves an
  auditable reason string.
- **Device proof (owner):** NOISE-rows in §D.
- **Honest limitation:** on API 24–28 there is no native `conversationId`, so
  announcement-channel posts can only be caught when they carry the group flag
  or a service-label title — the exact-shapes, never content-sniffing of a
  human's words.

## Item 4 — Notification presentation (draft primary, source secondary)

- **Reproduced (P-bg-11 audit, fixed there; hierarchy complaint persisted):**
  the alert led with the ANSWER hidden — reviewed against the owner's ask this
  phase: the AI reply must be primary, the source secondary, times/labels
  correct, collapse/expand sane, per-conversation replace, settled clears.
- **Code fix (this phase, `AssistantNotifier.post`):**
  - Expanded BigText now leads with the **draft** + `— drafted <dayTime> —`,
    then a hard `· · ·` divider, then `Replying to <name> · <dayTime>:` with
    the **exact incoming text** quoted, then the caption.
  - Title: `Draft reply for <name> · <appLabel>`; `setSummaryText(<name>)`;
    shade timestamp (`setWhen`) = incoming message time.
  - TTLs (API 26+, guarded by `Build.VERSION`; minSdk is 24): waiting card
    `WAITING_TTL_MS = 12h`; settled one-liners `SETTLED_TTL_MS = 120s` then
    self-clear. On API 24–25 the same id+tag replace-in-place discipline
    remains the cleanup mechanism (documented).
  - Per-conversation identity: fixed id+tag per contact ⇒ regenerate/burst
    updates REPLACE in place; no stacking.
  - `AssistantReceiver` inline-edit re-post threads the incoming context again
    (`c.messages().lastMessages(contactId, 5)`), so the edited card does not
    lose the source block.
- **Test proof:** notifier assembly is Android-free logic pinned in the
  notification suites (`AssistantNotifierTest` / receiver suites, in gate);
  content hierarchy line-level verified from source (this doc, fix commit
  `4d9d6d4`).
- **Device proof (owner):** N-rows in §D (presentation is inherently visual —
  the owner marks these).

## Item 5 — Provider page re-audit; health = real probe only

- **Reproduced:** a row could read healthy on the strength of *having a key
  saved* — the "Gemini out of quota while a fresh Mistral key works" scenario
  then read falsely: the working key was never credited, the quota-sick one
  was never blamed. (P-bg-11 already fixed error CLASSIFICATION: plain 403 →
  PERMISSION not "bad key"; FAILED_PRECONDITION → plan/billing; 429 split —
  billing exhaustion vs windowed rate limit; MODEL at 400/404 shapes. The
  "Gemini quota vs working Mistral" distinction is respected as possibly-REAL
  provider conditions, never UI noise.)
- **Root cause:** health was inferred from storage, not evidence.
- **Code fix (this phase):**
  - `ProviderEditActivity.testConnection`: a real probe persists
    `provider.health.<wire> = ok|<epochMs>` / `fail|<epochMs>`.
  - `ProviderActivity` row: reads the probe record — shows "key saved" (no ✓
    claim), appends `· tested ✓ <dayTime>` or `· last test FAILED <dayTime>`,
    and renders red when the key is missing OR the last probe failed.
- **Test proof:** classification pins from P-bg-11 (`DiagnosticsTest`
  additions) re-run in the 834 gate; probe-record read/write is plain kv
  exercised by the UI on-device.
- **Device proof (owner):** P-rows in §D.

## Item 6 — Voice customization: proof the prompt (and reply) actually changes

- **Owner's bar:** stored values and audit labels are NOT proof — show the
  provider prompt and the generated reply changing.
- **What existed:** `CustomizationEffectTest` (14) pins compose→PromptBuilder
  directly; `LearningPrecedenceTest` pins learned-vs-explicit semantics.
  Neither drives the REAL generation pipeline to the wire.
- **This phase — `VoicePromptProofTest` (6), all through
  `DraftService.generateForContact` with a request-capturing provider:**
  1. `levelChangeReachesTheWireAndChangesTheStoredReply` — tone warm → direct:
     the wire prompt contains the new phrase and not the old; the provider
     DERIVES its reply from the phrase it sees; the stored draft changes
     accordingly (`VERSION-WARM` → `VERSION-DIRECT`). Setting → prompt →
     provider → reply → stored draft, end-to-end.
  2. `offLevelSendsNoInstructionForThatDimensionThroughThePipeline` — OFF
     removes EVERY level phrase of that dimension from the wire (surgical:
     other dials untouched).
  3. `contactOverrideBeatsGlobalOnTheWire` — override wins for that contact;
     no leak to others.
  4. `customInstructionAndAboutThemReachTheWireThroughRealGeneration` —
     verbatim custom instruction + relationship + About-Them notes.
  5. `learnedHintsFlowThroughRealGenerationOnlyPastTheGate` — 3 signals ⇒ the
     learned hint rides the wire; 2 signals (below `MIN_SIGNALS = 3`) ⇒
     nothing learned leaks.
  6. `explicitSettingSuppressesTheLearnedHintThroughRealGeneration` — same
     signals: without an explicit dial the hint is used; with it, the hint is
     silenced and the explicit phrase rides instead.
- **Device proof (owner):** V-rows in §D (the on-device Prompt Audit view +
  preview are the visible mirror of these wire facts).

## Item 7 — Diagnostics redaction, all surfaces

- **State:** fixed in P-background-11 (`Secrets` redactor applied to
  `Diagnostics.build`, persisted last-provider-error, `AssistantDiag`,
  DiagnosticsRing, PromptAudit payload; Supabase anon key added to
  `ScrubLogger`'s shape registry). This phase RE-RAN the pins: `SecretsTest`
  (8) + `DiagnosticsTest` redaction rows are inside the 834/834 gate.
- **This phase addition:** §8's Supabase direct string was used ONLY for the
  live audit from this environment — it is not in source, not in the APK, not
  in diagnostics, not in this doc (`sb_secret_` matched nowhere in the repo;
  the only hit is the Secrets REDACTION PATTERN registry itself).
- **Device proof (owner):** DG-rows in §D.

## Item 8 — Supabase live audit (owner-supplied direct string)

Usage rules honored: the string was staged in a 0600 file in this environment,
used for the audit, then shredded; it is printed nowhere.

- **Direct (Postgres) audit — ATTEMPTED, blocked by transport, documented
  precisely:** `db.<ref>.supabase.co:5432` resolves IPv6-only (AAAA); this
  sandbox has no IPv6 route ("Network is unreachable"). No Postgres client
  exists here (psycopg2 was installed for the attempt). verify_0001.sql
  remains the runbook for a client with IPv6/pooler access.
- **Client-vantage live audit — DONE** (the vantage that actually matters:
  the app ships only the publishable anon key):
  - all 11 expected relations exist in the live project: the 10 app tables of
    migration 0001 (`style_profile, contact, contact_channel, message,
    memory_fact, contact_summary, provider_def, draft, usage_event, app_kv`)
    + `rm_schema_migrations` ⇒ every REST probe returned HTTP 200 (a missing
    table would be PGRST205/404);
  - **every probe returned ZERO rows for the anonymous key** ⇒ per-user RLS
    (migration 0001 §RLS: `enable row level security` on all 10 + per-user
    `auth.uid() = user_id` policies, `authenticated`-role only) is holding
    server-side — no anonymous read leaks anything;
  - whether the tables contain rows at all is unobservable from this vantage,
    BY DESIGN — that is the point of RLS.
- **Source-level pins (gate):** `SYNC_ENABLED = false` (`SupabaseConfigTest`),
  `ANON_KEY` carries the publishable `sb_publishable_` prefix, no
  secret/service-role key anywhere in source (grep-clean). Supabase's warning
  stands: a secret/service-role key bypasses RLS and must never ship in a
  client — the app ships none. The direct string grants full DB access: treat
  it as a backend-only secret and rotate it if it ever touched a client
  surface.
- **Device proof:** n/a (server-side; sync remains OFF in the client).

## Item 9 — Dismissed-send, generic per-app

- **State:** generalized in P-background-11 — the settle path keys on cached
  targets per app with the unwatched-app honesty note, no WhatsApp
  special-casing; PendingIntent survival across dismissal is the documented
  Android behavior the chain relies on. Regression suites re-run in this
  phase's 834 gate (receiver/notifier/dismiss pins).
- **Device proof (owner):** DS-rows in §D — on TWO different supported apps.

## Item 10 — Gate, regression pins, CI proof, this doc

- JVM gate **834/834** (was 820; +6 `OneDraftPerMessageTest`, +2
  `NoiseEndToEndTest` batteries, +6 `VoicePromptProofTest`), run 4×
  consecutively locally — the parallel-generation proof is deterministic
  (in-provider barrier), not wall-clock.
- Local engine build GREEN: `VERSION_CODE=906 VERSION_NAME=1.5.9-devcheck12`
  (aapt2 → javac → d8 → sign → verify; artifact discarded after verification).
- **CI flake found & fixed during this phase:** run 31814446682 (on `4d9d6d4`)
  failed ONE test — a test-fixture race (unsynchronized fake stores under the
  new legitimately-parallel test), not an app defect; fixed in `c58d9d8` by
  monitor-serializing the fakes (mirroring SQLite write semantics;
  single-thread behavior byte-identical) and replacing the wall-clock
  assertion with the deterministic barrier. Proof rebuild: see Gate status.

---

## §D — Device verification rows (owner — mark PASS/FAIL; I do not self-certify)

Install the CI artifact below (update-in-place over any 1.5.x).

Background:
- B1  Toggle ON → every missing lever named; grant them; return → each row
      re-reads real state; ✓ only when all truly pass.
- B2  App battery → Restricted → the row reports "app battery restricted"
      (no fake ✓); set Unrestricted → ✓.
- B3  While one provider call is slow (throttle the network), a second
      conversation's draft still generates promptly.
- B4  Same contact messaging rapidly (burst) → ONE settled alert, not one per
      message; ≈6.5 s settle is by design.

Duplicates:
- DUPE1  One incoming message → exactly ONE stored draft (Drafts screen,
      Prompt Audit count) — no 2–3 identical rows.
- DUPE2  Force the old race (lock screen / reconnect so the catch-up sweep
      fires while a draft is generating) → still exactly one current draft.
- DUPE3  Prompt Audit on the newest draft shows `"candidateCount":1`.

Noise (watch Diagnostics counters + the ledger stay EMPTY):
- NOISE1  Trigger a WhatsApp backup card; keep "Checking for new messages"
      visible in shade → zero contacts, zero drafts, zero ledger entries.
- NOISE2  WhatsApp Web running card → silent.
- NOISE3  A group message → silent. A channel/newsletter post → silent
      (drop reason visible where surfaced).
- NOISE4  A missed-call notice (with and without the call category) → silent.
- NOISE5  The app's own service chat (login-code card) → silent. Battery
      consequence: Diagnostics shows zero provider calls for all of the above.

Notifications:
- N1  Draft alert expanded: AI reply leads with "drafted <time>"; divider;
      "Replying to <name> · <time>" + the exact incoming text; shade
      timestamp = incoming time.
- N2  Collapsed alert shows the draft lead line + app label.
- N3  Regenerate → card updates IN PLACE (same per-conversation slot; no
      stacking).
- N4  Approve/Send/Copy → one-line settled state ("Sent ✓…"), self-clears
      ≈2 min later.
- N5  Leave a waiting card 12h+ → it retired itself.
- N6  Inline Edit from the alert → re-posted card still shows the incoming
      block.

Providers:
- P1  Fresh valid key (e.g. Mistral): save → row says "key saved" (NO ✓
      claim) → Test connection → row gains "tested ✓ <time>".
- P2  Deliberately break a key → Test → "last test FAILED <time>" + red.
- P3  Gemini key out of quota → classification names the real condition
      (quota/billing vs windowed) — no "bad key" claim; the healthy provider's
      row stays truthful meanwhile.
- P4  Connection-test failure sheet: endpoint, status, provider's own words,
      our read, suggested fix — no key material anywhere.

Voice:
- V1  Change one global dial → the next draft visibly changes (Prompt Audit
      payload contains the new phrase, not the old).
- V2  Contact override → that contact's drafts only.
- V3  OFF a dimension → no instruction for that dimension in the payload.
- V4  Custom instruction + About Them → appear in the payload; steer output.
- V5  Explicit setting vs learned hint → explicit wins; audit says the
      learned guess was suppressed.

Diagnostics:
- DG1  Settings → Diagnostics → no API keys, no Supabase string, no
      Bearer/token material on ANY surface (incl. prompt-audit payload and
      last-provider-error).
- DG2  Provoke a provider error echoing key-shaped material → persisted
      surfaces show `***`.

Dismissed-send:
- DS1  Dismiss the SOURCE app's notification, then Approve from ReplyMate →
      settles with the honest "via cached target / unwatched-app" note or an
      honest fallback — never a silent fake.
- DS2  Repeat on a SECOND supported app (not WhatsApp) → identical behavior.

## Gate status — updated 2026-08-14

- Audit → fixes → pins: DONE (Items 1–10). JVM **834/834** ×4 local runs;
  local engine build GREEN (dev-check vc906, artifact discarded).
- CI proof: first dispatch (run 31814446682 on `4d9d6d4`) exposed a
  TEST-FIXTURE race under the new parallel proof — fixed in `c58d9d8`
  (monitor-serialized fakes + deterministic barrier).
- Final CI proof build: **GREEN** — run **31815268133** on commit `c58d9d8`
  (release.yml dispatch). In-run cert gates: **KEYSTORE-VERDICT: MATCH**,
  **APK-CERT-PROOF: MATCH**. Artifact **9224744961**
  `ReplyMate-1.5.9-device-audit-proof-vc906` → APK
  `ReplyMate-1.5.9-device-audit-proof.apk`, **596,298 bytes**, versionCode
  **906** / versionName `1.5.9-device-audit-proof`.
- Independently re-verified OFF-CI (this environment): sha256
  **`610e994a8de30ce550a2b680a5bfbe0cc6d82018288d58bf7d4e301157143c66`** and
  apksigner-extracted signing cert SHA-256
  `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
  (B1:5F:2F:37…6A:85:ED — the historical ReplyMate identity; update-in-place
  over ≤1.5.8 preserved).
- **Owner device verdicts — OPEN.** 1.5.9 stays unshipped
  (`releases/RELEASES.md` untouched) until the §D rows pass on-device.
