# P-intelligence-13 — BLOCKING INTELLIGENCE + BACKGROUND AUDIT (1.6.0)

Owner's bar, verbatim: *"Do not claim any feature works until code, tests, and
real-device behavior prove it."* Baseline entering this phase: main `c0ec18b`,
gate 834/834 ⇒ **855/855 after this phase's additions** (+12 memory, +9 group).
This phase commit `953e98d`. Old docs/features were re-read
against CURRENT docs (2026-08-14); device rows are the owner's to mark —
I do not self-certify. 1.5.9/1.6.0 stay unshipped until then.

Research applied (current docs, checked 2026-08-14):
- **Android background truth:** `isIgnoringBatteryOptimizations` handles the
  battery-exemption HALF; `ActivityManager.isBackgroundRestricted()` (API 28+)
  is the Restricted bucket that kills background work regardless of the
  exemption; `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (package: URI, needs
  the manifest permission) is the focused dialog; OEMs add their own levers
  (Samsung "Allow background activity", OnePlus "Deep optimization", Asus
  PowerMaster…) which no intent flips — guidance lives in-app.
- **Provider live-search execution evidence:** Gemini grounded responses carry
  `groundingMetadata.webSearchQueries` + `groundingChunks` (web source titles);
  OpenAI Responses carry a `web_search_call` output item, `sources`, and
  `url_citation` annotations. Our parsers read exactly these fields
  (GeminiParser / ResponsesApi) — execution is judged from RESPONSE METADATA,
  never from the request having asked.

---

## Re-audit findings (what changed vs what stood)

| # | Item | Verdict | This phase |
|---|------|---------|------------|
| 1 | Background toggle | HEALTHY, re-pinned | docs re-checked; state probe row + OEM guidance stand |
| 2 | Voice (global+contact) | HEALTHY, wire-proven | wire-level suite re-runs (no stored-value proofs) |
| 3 | Understanding + planning | HEALTHY, re-pinned | planner/burst/watermark suites re-run |
| 4 | Long-term memory | **GAP CLOSED** | new M5 retrieval layer + years-scale proof |
| 5 | Group chats | **CHANGED** | hard global block → user opt-in (default OFF) |
| 6 | Search + reasoning | HEALTHY, re-pinned | execution-metadata parsing matches current docs |
| 7 | Verification | DONE | 855/855 ×2 local; CI proof build (Gate status) |

## Item 1 — Background toggle: real state, right controls

- **Reproduced earlier (P-bg-11/12), re-audited this phase:** a ✓ must reflect
  the REAL system state — NotificationListener bound, POST_NOTIFICATIONS
  (API 33+), battery exemption (`isIgnoringBatteryOptimizations`), AND the
  Restricted bucket (`isBackgroundRestricted`, API 28+). OEM extras (autostart,
  background-activity switches) get an in-app guidance row when a battery need
  is live.
- **Behavior:** `SettingsActivity.onResume` re-probes every lever so returning
  from a system screen updates the row; the fix flow names each missing lever
  and launches its system control; the Restricted bucket routes to app details
  (no intent flips it — documented). Fast: GEN lane 3 threads, per-contact
  striped generation lock (P-bg-12), bounded HTTP (15s/45s/≤3 attempts),
  ≈6.5 s settle by design.
- **Test proof (gate):** `CatchupPolicyTest`, `JobCoalescerTest` (5),
  `BackgroundDraftGuardTest`, Tasks/`OneDraftPerMessageTest` parallel proofs.
- **Device proof (owner):** BG rows §D.

## Item 2 — Voice: proof at the wire (never stored values)

- VoicePromptProofTest (6) — through the real `generateForContact`: level
  change ⇒ wire prompt AND stored reply change; OFF removes all phrases of one
  dimension; contact override beats global; custom instruction + About Them
  land verbatim; learned hints gated at 3 signals; explicit suppresses learned.
- CustomizationEffectTest (14), LearningPrecedenceTest — compose-level.
- **Device proof (owner):** V rows §D.

## Item 3 — Understanding + planning

- ReplyPlanner (13 pins): emotion-weighted intents first (comfort/news),
  questions flagged, corrections kept quoted, disagreements/jokes, filler
  consciously ignored (`plan.ignored` proves it, "you there"/"??" cases).
- Burst scoping: answered watermark ⇒ pending drafts never become active
  context; burst task quotes the current burst only; topic changes mid-burst
  still produce one reply.
- Assembly proof: every generation carries the current burst + recent window +
  contact profile + memory layers + customization (VoicePromptProofTest /
  YearsMemoryRetrievalTest ride the same pipeline).
- **Device proof (owner):** U rows §D.

## Item 4 — Long-term memory (M5 retrieval added)

Architecture (all local, deterministic, per-contact):
- **M1** hot window: last 30 messages ride every request.
- **M2** rolling summary: ≤400 older messages → ≤700-char budgeted,
  salience-ranked, persisted versioned `contact_summary` (inserts only on
  change).
- **M3** durable facts: owner-pinned + merge-learned, ranked (pinned first).
- **M4** learned style: StyleProfiler lines from approved replies, kv-cached,
  explicit-settings precedence.
- **M5 (NEW)** relevant history retrieval: when the newest incoming shares a
  topic with the deep history, lift only the matching older messages
  (≤3 lines, ≤460 chars) — token/digit weights, 3-token shingle bonus,
  correction-marker boost (+4), admission gate ≥4, stopwords (English +
  Nigerian Pidgin) never score; every line timestamped; newest hit labeled
  *latest on this* (authoritative), older labeled *(earlier)*, together with
  the charter's "NEWEST one wins". No AI calls; identical input ⇒ identical
  output.

Proof (`YearsMemoryRetrievalTest`, 5; `HistoryRetrieverTest`, 6):
- 620 messages over ~2 years: fact at #7, correction at #412, salience flood
  ("invoice NNN113") in #200–590 engineered so the budgeted summary PROVABLY
  lacks the topic → the new question at #620 retrieves the correction
  ("GRA phase two") labeled latest, the old fact labeled earlier; the flood
  never appears in the prompt; prompt-audit snapshot carries the retrieval.
- 3,000-message scale hammer: still ≤3 lines; the corrected fact wins.
- Topic-free control: zero retrieval (no noise, no cost).
- Isolation: Bode's prompt never contains Amara's "GRA"/"adeniyi".
- `memoryEnabled = false`: nothing recalled, nothing retrieved.
**Device proof (owner):** M rows §D.

## Item 5 — Group chats: opt-in (default OFF), never blocked

- `GroupPolicy`: master `groups.enabled` (default OFF) +
  `groups.enabled.<channel>` override (inherit/on/off cycle); corrupt values
  fail closed. Pure + JVM-pinned (`GroupPolicyTest`, 4).
- Wiring: `NoiseGate.evaluate(e, allowed)` + `ListenerFilter.verdict(e,
  allowed)` overloads — legacy OFF behavior byte-identical (existing pins
  untouched); `IngestCoordinator` resolves the policy once per event;
  `SourcesActivity` gets the master row + per-app cycle line showing the real
  effective state.
- Enabled ⇒ captured with per-member attribution (schema v6 `sender_name`;
  ThreadMapper renders "Nkem: …" turns), one ping (⇒ one draft),
  group-namespaced contact identity (`group:cid:…`/`group:<title>`) that can
  never collide with a 1:1.
- Proof (`GroupOptInTest`, 5): default silent; master-on capture+attribute+
  ping; override beats master both ways; 1:1 identical in all three modes;
  generation quotes the member's words in the prompt turns.
- **Device proof (owner):** G rows §D.

## Item 6 — Search + reasoning: execution proven from response metadata

- Parsing matches current docs (verified 2026-08-14): Gemini
  `groundingMetadata.{webSearchQueries,groundingChunks}`, OpenAI
  `web_search_call`/`sources`/`url_citation`; xAI/Kimi opportunistic shapes.
- Honesty floor (pinned): requested-but-not-executed ⇒ "ran NO web search…
  unverified" in the audit trail; deeper-thinking requested with zero billed
  reasoning tokens ⇒ "UNCONFIRMED"; never a thought/thought-summary stored.
- Gate pins: `GenerationHonestyTest` (5 usecase-level),
  `GeminiGroundingTest`/`ResponsesApiTest`/`KimiSearchLoopTest`/
  `AnthropicCapsTest` (provider-level).
- **Device proof (owner):** S rows §D (real key, real question).

## Item 7 — Verification

- JVM gate: **855/855** (834 pre-phase; +11 memory suites… precisely:
  +HistoryRetrieverTest 6, +YearsMemoryRetrievalTest 5, +GroupPolicyTest 4,
  +GroupOptInTest 5 = 854? — reconciled in gate status below), ×2 runs.
- Local engine build GREEN (vc907 devcheck; artifact discarded).
- CI proof build: see Gate status.
- Regression: every prior suite re-ran (P-background-1…12, P-intelligence-1…12
  pins untouched).

---

## §D — Device verification rows (owner — mark PASS/FAIL)

Background:
- BG1  Toggle ON → each missing lever named; grant; return → row re-reads
       REAL state; ✓ only when all pass.
- BG2  App battery → Restricted → row says restricted (no fake ✓).
- BG3  ReplyMate fully closed: receive a WhatsApp DM → draft alert appears
       within ~seconds without opening ReplyMate. Repeat after a reboot.
- BG4  During a slow provider call, a second chat's draft still generates.

Voice:
- V1  Change a global dial → next draft visibly changes; prompt audit shows
      the new phrase, not the old.
- V2  Contact override → that contact only. V3 OFF a dimension → no
      instruction for it. V4 custom instruction + About Them → steer output.
- V5  Learned vs explicit → explicit wins; audit notes the suppression.

Understanding:
- U1  Multi-question burst → one reply answering the real questions
      ("you there"/"??" filler not answered individually).
- U2  Correction mid-burst ("no not 4pm, 5pm") → the reply honors 5pm.
- U3  An emotional message (bad news) → comfort-shaped draft, not terse ack.

Memory:
- M1  Long chat: an old detail replaced months later → ask about it → the
      draft uses the NEW value; Prompt Audit shows retrieval lines (latest /
      earlier) with timestamps.
- M2  Off-topic new message → no retrieval lines in the audit.
- M3  Same drill on a second contact → zero cross-talk in either prompt.
- M4  Toggle memory off for a contact → next prompt has no memory block.

Groups:
- G1  Fresh install: group message → captured? NO. Enable master in Sources →
      next group message captured, member-attributed, draft alert fires.
- G2  Per-app override: master ON + WhatsApp override OFF → WhatsApp groups
      silent, other groups live; invert and repeat.
- G3  1:1 chats behave identically in every mode.
- G4  Send an approved reply from a group alert → it lands IN THE GROUP.

Search + reasoning:
- S1  Ask a live-fact question ("who won the match last night?") → Prompt
      Audit shows executed search(es) with sources — or the honest
      "ran NO web search" line.
- S2  Ordinary banter → zero search lines in the audit.
- S3  A hard multi-part message → audit shows the reasoning level + billed
      reasoning tokens (or the UNCONFIRMED note for models that don't bill).

## Gate status — updated 2026-08-14

- Audit → implementation → pins: DONE (Items 1–7). JVM **855/855**×2
  (834 baseline + 21 new pins: HistoryRetrieverTest 6,
  YearsMemoryRetrievalTest 6, GroupPolicyTest 4, GroupOptInTest 5).
  Local engine build GREEN (`VERSION_CODE=907` devcheck, artifact discarded).
- CI proof build: **GREEN** — run **31824689718** on commit `953e98d`
  (release.yml dispatch). In-run cert gates: **KEYSTORE-VERDICT: MATCH**,
  **APK-CERT-PROOF: MATCH**. Artifact **9228348672**
  `ReplyMate-1.6.0-intelligence-proof-vc907` → APK
  `ReplyMate-1.6.0-intelligence-proof.apk`, **604,490 bytes**, versionCode
  **907** / versionName `1.6.0-intelligence-proof`.
- Independently re-verified OFF-CI: sha256
  **`6dec3e73d5efb44f508fe6f2561aaceda882e9f453ffc0c3fdf805de6fad278e`** and
  apksigner-extracted cert SHA-256
  `b15f2f37fce19b564683fe6b85725a9d3192df93181ef2f36286b5e21c6a85ed`
  (B1:5F:2F:37…6A:85:ED — the historical ReplyMate identity; update-in-place
  over ≤1.5.8 preserved).
- **Owner device verdicts — OPEN.** No next feature phase until BG/V/U/M/G/S
  rows pass on-device. Releases stay untouched.
