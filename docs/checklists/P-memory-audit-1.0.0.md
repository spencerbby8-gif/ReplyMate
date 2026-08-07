# P-memory-audit — 1.0.0 (vc16) completion report

**Owner order:** 0.9.0 not trusted yet → full end-to-end audit + refix of the
context/generation/memory pipeline BEFORE any feature work; build the memory
continuity stack (hot context, rolling summary, pinned facts, learned style) with
contact-id-only retrieval, zero cross-contact leaks, restart persistence; exact
Prompt Audit field list; media honesty re-verified; provider behavior re-verified;
latest official docs re-checked before touching code; named regression tests;
local-first kept; no UI redesign / auto-replies / cloud / Accessibility / scraping.

**Release:** `releases/ReplyMate-1.0.0.apk` · 165,466 B · sha256
`97b022958e53110e60cc369cc3a25c58372dcf8ef9f1410bc2cafb4d42ce461c` · signed with the
constant arena cert (SHA-256 `b15f2f37…a85ed`) → updates in place.

---

## A. Issues found in the audit, and their fixes

| # | Issue (severity) | Fix |
|---|---|---|
| A1 | **Long-term memory was dead code (critical).** `contact_summary` + `memory_fact` existed since schema v1; MemoryDao/SqlMemoryStore/MemoryEngine all worked — but NOTHING ever built a summary, captured a fact, or read either into a prompt. Threads older than the 30-message hot window silently vanished from the model's world. | **MemoryService + ThreadSummarizer (new).** Deterministic, 100% local extractive summarizer (zero AI calls, zero cost) over everything older than the hot window; persisted as versioned `contact_summary` rows; identical input ⇒ identical text ⇒ no duplicate rows ⇒ restart-stable. Refreshed lazily at every generation. |
| A2 | **No way to create memory facts at all (critical).** The pinned-facts layer had no producer. | **replacePinnedFacts + contact editor box (new).** "Pinned facts about them" multiline in Edit Contact → `memory_fact` rows (pinned=1, importance=5, owner-authored, one per line, exact replace). Recalled ranked by MemoryEngine (pinned first, ≤8). |
| A3 | **Approved replies never shaped style (major).** Learning counted events (approve/edit/reject) but never read WHAT the owner approved — the strongest voice evidence. | **StyleProfiler (new — M4).** Derives ≤3 concrete lines from the contact's approved reply texts (length, final-full-stop habit, lowercase openings, emoji), each with evidence; persisted per-contact in kv → restart-proof; thresholded (min 3 approved). |
| A4 | **Group chats misattributed every message (major).** `NotifEvent.senderName` was parsed but dropped at ingest; a member's words were labeled as the group itself. | **Schema v6 (`sender_name`) + full pipeline.** Ingest stores the actual sender; ThreadMapper prefixes per-message senders; the task says "Kunle's latest in The Crew"; Prompt Audit shows "Actual sender". 1:1 chats unchanged. |
| A5 | **Media rows reached the model as ambiguous placeholder text (moderate).** "[media/voice — open in chat app]" was sent verbatim as a user turn. | **Structured media turns.** "Amara [sent a photo — its content is not readable by you; do not describe or guess it]" — honest, separate from text, zero hallucination surface. Captions still pass as real text. |
| A6 | **Prompt Audit missed mandated fields (major).** No memory layers, no visible thread history, no sender, no media MIME. | **Snapshot + UI extended.** New `memory` block (summary + meta + facts + learnedStyle), `latestIncoming.sender`, `latestIncoming.mediaMime`; the audit card now renders "Recent thread history used" and "Long-term memory used" sections. |
| A7 | **Draft fakes diverged from the store contract (test harness).** DraftStoreFake returned oldest-first; the port contract is newest-first. | Fake aligned to contract; two new tests initially failed on seeded-vs-generated draft confusion — caught BECAUSE of this. |
| A8 | **Migration tests hardcoded LATEST==5.** | Parameterized to track the registry (V6 suite added; shipped migrations untouched). |

**Verified clean (no change needed):** latest-text gate (media-only/empty latest →
kind-specific honest error, ZERO provider calls); TokenBudgeter (drops oldest turns,
never system/task/latest); per-contact reads (ContactStore/MessageStore/DraftStore/
StyleSettingStore/LearningStore all contact-scoped); base-URL auto-switch locked to
official endpoints (Custom excepted); model badges live+cached+refreshable,
known-fail demoted; real-vs-interpreted provider error split (Diagnostics blocks);
profile toggles filter before prompt + audit notes; learning gates (private/memory-off/
off/paused share one gate); Supabase sync OFF, no Auth, no Paystack, no Accessibility,
no scraping, no third-party libs, no UI redesign (additive box + audit sections only).

## B. The memory continuity stack (as mandated)

- **Hot context (M1):** the live thread window — last 30 messages, contact-scoped.
- **Rolling summary (M2):** everything older than the hot window → deterministic
  local `ThreadSummarizer` (questions/plans/dates/amounts kept; small talk dropped;
  media only COUNTED, never paraphrased) → persisted `contact_summary` versions.
  Continuity over time: every new message eventually slides the window and the
  summary advances a version.
- **Pinned facts (M3):** owner-authored stable details, `memory_fact` pinned rows,
  recalled ranked; AI reads but never writes them.
- **Learned style (M4):** from approved reply TEXTS; cached `style.<cid>.approved.v1`
  in kv; recomputed only when approved-count changes.

Retrieval is by **contact id only**, at every layer (SQL WHERE contact_id=?,
kv keys style.<cid>.*, in-memory per-contact maps). Isolation proven by
ContextIsolationTest, MemoryContinuityTest, MemoryPersistenceRoboTest and the
RequestDump gate (Uche's world never appears in Amara's request or snapshot).

## C. Test evidence — 403 unit + 21 Robolectric = 424 green (was 366+19)

New suites (all requested regressions covered):
- **missing latest message in payload** → ReplyContextHonestyTest (kept) + RequestDump gate (1).
- **stale or wrong contact context** → ContextIsolationTest (A/B fully seeded; `staleLatestIsNeverAnswered`).
- **memory leaking across contacts** → ContextIsolationTest + MemoryContinuityTest + MemoryPersistenceRoboTest + RequestDump gate (6).
- **restart keeps summaries + style** → MemoryPersistenceRoboTest (REAL SQLite: fresh DbHelper, facts/summary/signals/kv style cache read back) + MemoryContinuityTest (version reuse, no duplicate rows) + RequestDump gate (7).
- **media-only latest** → RequestDump gate (2) image/voice/video: 0 provider calls + honest copy.
- **provider switch updates Base URL** → RequestDump gate (9): audited endpoint follows the configured base (Gemini vs OpenRouter), unit suites keep the editor lock.
- **model badge classification** → ModelClassifierTest (13 suites kept green).
- **Prompt Audit completeness** → PromptAuditCompletenessTest: every mandated field asserted + snapshot byte-equality with the wire request.
- **real request context honesty** → ContextIsolationTest + PromptAuditCompletenessTest + full RequestDump transcript `docs/provider-probes/requestdump-memory-1.0.0.txt` (13/13 gates, incl. group sender attribution (8) and the live Gemini dummy-key send → HTTP 400 API_KEY_INVALID, exactly as Diagnostics predicts).

Also new: ThreadSummarizerTest (8), MemoryContinuityTest (8), StyleProfilerTest (6),
ThreadMapperTest (6), MigrationV6Test (3).

## D. Docs re-verified by web search (2026-08-07, before code was touched)

- Gemini `POST …/v1beta/models/{model}:generateContent`, `x-goog-api-key`, stateless multi-turn ✔
- OpenAI `POST /v1/chat/completions`, Bearer, stateless ✔
- Anthropic `POST /v1/messages`, `x-api-key` + `anthropic-version: 2023-06-01` ✔
- OpenRouter `/api/v1` · DeepSeek `api.deepseek.com` · xAI `api.x.ai/v1` · Mistral
  `api.mistral.ai/v1` (+ `GET /v1/models` `capabilities.completion_chat` — classifier ✔) ✔
- Android `Notification.MessagingStyle.Message` `getDataMimeType`/`getDataUri`/`setData` ✔
No provider changes were needed (paths unchanged); implementations match current docs.

## E. APK battery (1.0.0 / vc16) — all green

vc16/1.0.0 · minSdk 24 · targetSdk 34 · 2 permissions (INTERNET, POST_NOTIFICATIONS) ·
11 activities · 12 queries packages · zip integrity OK · no idsig · signer cert
SHA-256 `b15f2f37…` (constant — update-in-place) · Voice Charter sentinel in dex ·
schema v6 DDL + ThreadSummarizer/MemoryService + "Long-term memory used" strings in
dex · RmNotificationListener in manifest · ChatLink/listener strings present.
**One grep match to declare:** the frozen SchemaV1 provider_def DDL string contains
its historical `DEFAULT 'gemini-2.5-flash'` column default (v1 migration —
immutable by rule; never used at runtime: every provider row writes model_name
explicitly from live discovery). Not active code; documented for honesty.

## F. Intentionally deferred (unchanged policy, needs owner decision)

- **D1 — vision/STT media understanding.** Would send media off-device and cost
  money per attachment; the owner previously ruled media stays local. References
  (MIME/URI) are captured; nothing opens them. Owner decision required.
- **D2 — AI-written summaries.** The rolling summary is extractive by design
  (deterministic, free, private). An optional AI-polished summary would read
  better but burns tokens on every batch; owner decision required.
- **D3 — auto fact extraction from chats.** Facts are owner-pinned only. Auto
  extraction without AI is unreliable; with AI it re-reads whole threads on metered
  calls. Owner decision required.

## G. What the owner should see on-device

1. Update-in-place install over 0.9.0 (same cert).
2. Edit Contact → "Pinned facts about them" box (existing contacts only).
3. Generate after a long chat: system carries the running summary; Prompt Audit
   cards show "What was sent" + "Recent thread history used" + "Long-term memory
   used" + sender + content type + reason.
4. WhatsApp group chat: replies now name the actual member who sent the latest
   message.
5. Photo/voice latest → the same honest gates as 0.9.0; a photo EARLIER in history
   now shows as "sent a photo — not readable" instead of a fake message.
