# P-background-11 — BLOCKING PROVIDER + NOTIFICATION + BACKGROUND AUDIT (1.5.9)

Scope: fresh audit of the CURRENT 1.5.9 build against today's official Android +
provider docs (not previous claims): provider/API-key screen for every provider,
voice customization end-to-end, diagnostics security, notification design,
dismiss-safe sending, real background state. Previous fixes from P-background-1…10
are preserved; the JVM gate and CI proof are the regression harness.

Owner-supplied Supabase direct string: NONE was supplied in this phase (the
placeholder `<PASTE_SECURELY_HERE>` was never replaced). Nothing to store, log,
or exclude from the APK.

Research sources applied this phase (official docs, checked 2026-08-14):
- **Provider error models:** Google Vertex/Gemini "API errors" table (400
  INVALID_ARGUMENT/FAILED_PRECONDITION, 401 UNAUTHENTICATED, 403
  PERMISSION_DENIED, 404 NOT_FOUND, 429 RESOURCE_EXHAUSTED, 500/503/504);
  OpenAI error-codes page (401 invalid key/org/IP, 403 unsupported region, 429
  insufficient_quota vs rate_limit_exceeded vs *_spend_limit_exceeded, 500/503);
  Anthropic error envelope (authentication_error 401, permission_error 403,
  not_found_error 404, rate_limit_error 429, overloaded_error 529); xAI/Grok,
  Mistral, OpenRouter, Kimi shapes as already captured live in
  docs/provider-probes/.
- **Android background truth:** `PowerManager.isIgnoringBatteryOptimizations`
  (API 23+) is only HALF the battery story — API 28+ adds the app-battery
  "Restricted" bucket probed by `ActivityManager.isBackgroundRestricted()`;
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (package: URI) is the focused
  dialog, `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` + app-details are the
  fallback chain; no intent exists to flip the Restricted bucket — app-details
  is the documented path.
- **Android notification lifecycle:** PendingIntents survive notification
  dismissal (the dismissed-send chain's existence proof); replacing an answered
  direct-reply card with the same id/tag + `setTimeoutAfter` (API 26+) is the
  documented way to clear/expire cards cleanly.

---

## §0 Fresh-audit findings (what was WRONG on the audited build)

1. **Error classification lied by status shortcut.** ANY HTTP 403 was surfaced
   as "the provider rejected the API key" — but 403 is officially
   PERMISSION_DENIED / permission_error / unsupported-region: the key is
   ACCEPTED and re-pasting it can never help. Only bodies that PROVE a bad key
   are auth failures now.
2. **Every 429 was described as a retryable rate window.** Officially 429 also
   means billing exhaustion (insufficient_quota, credit_balance_exhausted,
   *_spend_limit_exceeded) — retrying is pointless and "wait a minute" is
   wrong advice.
3. **FAILED_PRECONDITION (400) fell into the generic OTHER bucket** — Google's
   "free tier is not available in your country / billing not enabled" is a plan
   problem with a precise fix.
4. **Model-unavailable shapes at 404** ("is not found for API version",
   "model_not_found", "not supported for generateContent") landed in NOT_FOUND
   generic copy instead of the "Discover models" fix path.
5. **Persisted diagnostics were redaction-free.** The log stream had ScrubLogger,
   but durable surfaces (provider-error kv, assistant ledger, listener ring,
   raw error bodies, prompt-audit payload) stored whatever the provider echoed —
   including key-shaped material.
6. **Draft alerts answered an invisible question.** The notification showed only
   the draft — never the exact incoming message or the two times (message time,
   draft time). Waiting cards had no shelf-life; settled "Sent ✓" lines could
   sit forever.
7. **The battery verdict was half the battery state.** isBackgroundRestricted()
   (the Restricted bucket) kills background work even on whitelisted apps; it
   was never probed, so "battery ✓" could be shown while Android froze the app.

## §1 Fixes (all wired before this doc)

### Provider page (every supported provider — Gemini, OpenAI, OpenRouter,
### Anthropic, DeepSeek, Grok/xAI, Kimi, Mistral, Ollama, OpenAI-compatible)
- `Diagnostics.build` re-classified against the official models, evidence-first:
  - 401 (or bad-key PROOF in the body at 400/403 — Gemini API_KEY_INVALID, xAI
    "Incorrect API key", Mistral "Invalid API Key", Google "unregistered
    callers") → AUTH "the provider rejected the API key" (non-retryable).
  - 403 WITHOUT bad-key evidence → NEW Cause PERMISSION: "key ACCEPTED, this
    API/model/region not permitted — not an invalid-key error", non-retryable,
    fix = enable the API / project / region coverage.
  - 400 FAILED_PRECONDITION / "free tier is not available" / billing-not-enabled
    → NEW Cause PLAN: plan precondition, non-retryable, fix = billing/plan.
  - 400/404 model-evidence ("model not found", "model_not_found", "is not found
    for API version", "not supported for generateContent", …) → MODEL with the
    "Discover models (live)" fix path.
  - 429 + zero-limit evidence → QUOTA_ZERO (unchanged, never retried).
  - 429 + billing evidence (insufficient_quota / credit_balance_exhausted /
    spend_limit_exceeded / "exceeded your current quota" / insufficient balance)
    → NO_BALANCE: "credits or spend limit exhausted (billing) — NOT a rate
    window", non-retryable, fix = top-up / raise limits.
  - 429 otherwise (rate_limit_exceeded, RATE_LIMIT_EXCEEDED, retryDelay …) →
    QUOTA windowed, retryable, Retry-After honored.
  - 402 / 5xx / transport unchanged (NO_BALANCE / SERVER / NETWORK).
- Verified healthy and left as-is after re-audit: key validation via the FREE
  models-list probe (no generation burn); model selection via live discovery +
  typed override; per-model capability classification (ModelClassifier) with
  cached "tested <when>" badges; base URL editable always, suggested-not-forced
  per provider change (hand-typed endpoints never overwritten), persisted
  verbatim on save; connection test shows the provider's real words; health
  indicators derive ONLY from actual probes (never config presence).
- No code claims "Healthy" anywhere; rows state config facts ("key saved ✓")
  and test outcomes only.

### Diagnostics security
- NEW `core/privacy/Secrets`: pattern redactor for query credentials (key=/api_key=/
  access_token=), header material (Authorization: Bearer, x-api-key,
  x-goog-api-key), provider key shapes (sk-…, sk-proj-, sk-ant-, sk-svcacct-,
  AIza…, xai-…, sb_publishable_…, sb_secret_…, JWT eyJ… blobs), URL userinfo
  (user:pass@), plus owner-registered fragments. Verified shape-by-shape in
  SecretsTest (secret shapes die; ordinary English, "monkey=business" prose and
  already-masked fixtures survive).
- Applied at EVERY durable surface: Diagnostics.build (endpoint/body/message),
  LastProviderError.save, AssistantDiag.record (reason/action/fix),
  DiagnosticsRing.append (single choke point), PromptAuditActivity payload render.
- Supabase publishable key registered into ScrubLogger at container construction
  (log stream) in addition to the pattern coverage.
- ScrubLogger itself unchanged (live log fragments ≥6 chars → ***).
- What REMAINS visible by design: provider/model names, endpoint paths (query
  stripped), HTTP status, the provider's own error text (redacted), local
  token meters, the owner's own email on their own device.

### Notification design
- `AssistantNotifier.post` full-context flavor: expanded body = "Replying to
  <contact> · <message time>:\n<exact incoming message>\n\n<draft>\n\n— drafted
  <generation time> —\n\n<capability caption>". The shade when-stamp is the
  INCOMING MESSAGE time (not re-post time). Title now names the source app.
- One card per conversation (stable per-contact tag, replaced in place — never
  stacked); the inline-Edit re-post keeps the full context block too.
- Waiting cards self-expire after WAITING_TTL_MS = 12h (API 26+; 24–25 keep the
  replace-in-place discipline); settled lines show their time and retire after
  120s. Approve/send replaces the card with the settled line; swipe-away routes
  through ACTION_DISMISS (re-arms the next cycle).
- Wrong-message/duplicate protection is structural and unchanged: generation
  reads state at fire time; the alert threads THAT exact stored incoming row.

### Dismiss-safe sending (generalized — re-audited)
- The chain is app-agnostic by construction (verified in source: zero app-name
  branches in the send path): live sbn key-match on the EXACT RemoteInput
  result key → re-posted same-conversation notification → cached reply
  PendingIntent with the rebuilt official results wire → honest copy fallback.
  Works identically for any app exposing a quick-reply action; per-platform
  behavior research above (PendingIntent survives dismissal) is what makes the
  cached tier possible AT ALL.
- Honesty contract unchanged: live/re-posted deliveries settle "Sent ✓ —
  delivered through <app>'s quick-reply"; the cached tier settles "Sent ✓ via
  cached target … couldn't watch it land" (unwatched is stated); failure paths
  never claim Sent — the draft is copied and preserved.

### Background generation — the REAL Android state
- AssistantPrereq.missing() now probes FOUR levers: notification access,
  POST_NOTIFICATIONS, battery-optimization whitelist, and NEW
  BACKGROUND_RESTRICTED (ActivityManager.isBackgroundRestricted(), API 28+).
- Each missing lever is named in plain words with the exact tap; battery opens
  the focused per-app dialog (list + app-details fallbacks; OEM screen only when
  it really resolves); BACKGROUND_RESTRICTED opens app-details (the only
  documented path) with "Battery → Unrestricted" guidance.
- Re-check happens in onResume — state is re-read after returning from every
  system screen; the switch subtitle only reads "on ✓ … battery ✓" when every
  probe passes, else it names the missing lever ("blocked by: …").
- Speed/non-blocking architecture preserved: INGEST (1 thread, capture-only) /
  GEN (2 threads: research+provider) / BG (2 threads: catch-up) lanes; per-call
  HTTP 15s/45s timeouts, ≤3 attempts, ≤60s retry waits; a superseded job aborts
  before AND after the paid call. One slow provider holds at most one GEN lane.

### Voice customization (end-to-end re-audit — verified healthy)
- UI → storage → precedence → prompt → provider: VoiceActivity (global) and
  ContactEditActivity (per-contact + About Them + relationship + custom
  instruction) write the documented style_setting keys; StyleSettings.resolve
  applies contact > global > default with corrupt-row inheritance; all 9
  controls (tone, length, emoji, formality, humor, confidence, slang, flirting,
  follow-ups) render phrases at 3 active levels; OFF removes the dimension
  entirely; custom prompt box capped at 400 chars; learned hints enter only through
  the open learning gate and are suppressed per-dimension by explicit contact
  settings; prompt-audit snapshots record exactly what engaged.
- Pins kept green (pre-existing): everyOneOfTheNineControlsReachesThePrompt,
  contactVoiceOverrideChangesOutputForThatContactOnly, aboutThemFieldsReachThePrompt,
  relationshipTypeDifferentiatesPromptsAcrossContacts, global custom instruction,
  learned-style placement + audit, OFF-state removal, hostility boundary.
- The live voice preview persists the edited custom instruction BEFORE calling the
  real pipeline (what you see is what was sent).

### P-background-1…10 regression suite
- Kept intact: near-identity duplicate collapse (120s window), announcement /
  service-chat / call-card noise rejected pre-contact, first-message capture,
  burst handling, aura→Arsenal topic reset, Search/reasoning executed-or-said-so
  honesty, memory, three-tier dismissed-send, inline Edit, manual Send, catch-up
  sweeps, mid-call supersede discard. JVM gate covers all of it.

---

## §2 Evidence (this phase)

- **JVM gate: 820/820** — +16 new pins vs P-background-10: SecretsTest (8:
  every secret shape dies / prose and masks survive / fragment registry floor),
  DiagnosticsTest additions (8: plain-403 → PERMISSION not key-claim;
  FAILED_PRECONDITION → PLAN; 429 insufficient_quota → billing, non-retryable;
  windowed 429 → retryable; spend-limit 429 → billing; Anthropic permission_error;
  model errors at 404 → MODEL + Discover fix; every persisted field secret-free).
- **Local engine build GREEN** (aapt2 → javac → d8 → sign → verify; dev-check
  APK discarded).
- **CI proof build + cert continuity + off-CI re-verification: see Gate status.**

## §3 Device verification rows (owner — mark PASS/FAIL per row; I do not self-certify)

Provider page:
- P1  Gemini key: valid key → Test connection OK; deliberately broken key →
      "rejected the API key" (not a permission story), no retry loop.
- P2  A key valid but WITHOUT the needed permission/plan → PERMISSION copy
      ("accepted the key but…"), suggestion names API/project/region.
- P3  A 429 billing response (exhausted credits) → "credits or spend limit
      exhausted" with top-up guidance, NOT "wait a minute".
- P4  A wrong/hand-typed model → MODEL outcome + "Discover models (live)".
- P5  Per provider: change provider chip → its official base URL is suggested;
      type a custom URL → switching chips keeps it; save → reopen → custom URL
      persisted exactly.
- P6  Discover & test models → badges reflect THE test run (working/paid/missing),
      "tested <when>" is the real run time.
- P7  Connection-test failure shows endpoint, status, provider's own words,
      ReplyMate read, suggested fix — with no key material anywhere.

Voice:
- V1  Global: set tone=warm + emoji=plenty → preview visibly reflects both.
- V2  Contact override (e.g. humor=funny on one contact) → changes that
      contact's drafts only; global drafts unaffected.
- V3  OFF a dimension (e.g. length) → no length instruction in the prompt-audit
      payload for that contact.
- V4  About Them + relationship + custom instruction → all appear in the
      prompt-audit payload and steer the preview output.
- V5  Learned-style hint vs explicit contact setting → explicit wins; audit says so.

Diagnostics security:
- D1  Settings → Diagnostics: no API keys, no Supabase key, no Bearer/token
      strings — SQLite-ish state + listener stats + assistant ledger only.
- D2  Provoke a provider error containing key material → the persisted
      "last provider error" + ledger show *** where the material was.
- D3  Prompt audit full payload: no secrets; message/profile content intact.

Notifications:
- N1  New draft alert shows: "Replying to <contact> · <time>" + the EXACT
      incoming message + the draft + "drafted <time>"; shade timestamp is the
      incoming message time.
- N2  Regenerate / burst update → the card updates in place; no stacking.
- N3  Leave a waiting card 12h+ → it has retired itself.
- N4  Approve/send/copy → card becomes the one-line state ("Sent ✓…/Copied…"),
      then self-clears ≈2 minutes later.
- N5  Inline Edit from the alert → re-posted card still shows the incoming block.

Dismissed-send:
- S1  Dismiss the source app's notification, then Approve from ReplyMate →
      settles "via cached target" version with the unwatched honesty note, or an
      honest fallback — never a silent fake.
- S2  Same drill on a SECOND supported app (not WhatsApp) → same behavior.

Background:
- B1  Fresh state → toggle ON: approval flow names each missing lever; grant
      them; row re-reads real state on return; "on ✓" only when all pass.
- B2  App battery → Restricted → row must say blocked by "app battery
      restricted" (no fake ✓); set Unrestricted → ✓.
- B3  listener connected → notification captured → ingest → draft → alert
      within seconds; while one provider call is slow, a second conversation's
      draft still generates.
- B4  Noise drill (backup card, service chat, newsletter, missed call) → zero
      drafts, zero provider calls (Diagnostics counters + ledger stay clean).

## Gate status — updated 2026-08-14

- Audit → fixes → pins: DONE (§0–§2). JVM **820/820**; local engine build GREEN.
- CI proof build: (run id / artifact / sha256 filled after dispatch — see below).
- **Owner device verdicts — OPEN.** P1–P7, V1–V5, D1–D3, N1–N5, S1–S2, B1–B4
  are real-phone rows; no next feature phase until the provider screen, clean
  Diagnostics, useful non-stacking notifications, generalized dismissed-send and
  the real Android background state all PASS on-device. 1.5.9 stays unshipped
  (releases/RELEASES.md untouched) until then.
