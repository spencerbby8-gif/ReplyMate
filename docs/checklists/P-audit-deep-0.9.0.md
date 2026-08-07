# P-audit-deep — provider • generation • notification pipeline audit (0.9.0 / vc15)

Owner-ordered audit phase: full end-to-end audit of the provider, generation and
notification pipeline; fix every verified issue first; no new major features;
regression tests; verified APK; live request inspection; honest report.

Docs re-verified by web search this phase (no cached knowledge): Android
`Notification.MessagingStyle.Message` (`getDataMimeType`/`getDataUri`, setData
semantics — developer.android.com) · Gemini (`/v1beta/models` + `:generateContent`,
`x-goog-api-key`) · OpenAI (POST `/v1/chat/completions`, Bearer) · OpenRouter
(`openrouter.ai/api/v1`, `:free` suffix) · Anthropic (`/v1/messages`, `x-api-key` +
`anthropic-version: 2023-06-01`) · DeepSeek (`api.deepseek.com`, OpenAI dialect) ·
xAI (`api.x.ai/v1`, Bearer) · Kimi (`api.moonshot.ai/v1`; `kimi-latest` deprecated — we
hardcode no models, so no change needed) · Mistral (`api.mistral.ai/v1`,
`capabilities` in model list). WhatsApp media kinds (image/video/audio/sticker,
voice note = opus audio) cross-checked with WhatsApp Business media docs.

---

## A. Issues found & FIXED (each with proof)

### A1 — Media fallback text reached the provider as "real text" (hallucination hole)
WhatsApp-style media notifications carry the app fallback text `"📷 Photo"`,
`"🎤 Voice message (0:07)"`, `"Sticker"` etc. as the message text. 0.8.0 only gated
the *legacy placeholder*, so the provider was asked to reply to `"📷 Photo"` —
and would happily invent the photo's content.
**Fix:** evidence-based kind detection at parse time; media-only items are stored
with kind-specific honest placeholders; `PromptBuilder.usableText` rejects every
placeholder shape; `ThreadMapper` keeps them honestly labelled in history.
*Tests:* `ContentSignalsTest`, `MessagingStyleParserTest`, `IngestCoordinatorTest`,
`ReplyContextHonestyTest` (image/voice/video gates), `RequestDump` section (2).

### A2 — Media MIME + attachment URI were discarded
`NotifExtractor` read `"uri"`/`"type"` only as a boolean.
**Fix:** extractor now captures `dataMimeType`/`dataUri` values → `RawNotif.Entry`
→ `NotifEvent` → `Message` (schema v5: `content_type`, `media_mime`, `media_uri`).
**The reference stays on-device. It is never opened, never uploaded** (explicitly
deferred, see D1). Gate copy says this to the user.
*Tests:* extractor robo test `extractorKeepsAttachmentMimeAndUriValues`,
`photoNotificationStoredAsImageKindWithMediaReference`, `MigrationV5Test`.

### A3 — No content model separate from source identity
**Fix:** new `core/model/ContentKind` (`TEXT, IMAGE, VIDEO, AUDIO, VOICE, STICKER,
CALL, UNKNOWN`) + `core/listener/ContentSignals`. Detection never looks at package/
sender/conversation: MIME first, then the posting app's canonical fallback shapes
matched **exactly after normalization** (lowercased, variation-selectors stripped,
trailing `"(0:07)"` durations dropped). Bare single words (`photo`, `video`,
`sticker`, `gif`…) are only trusted **with attachment evidence** — a human can
type those words. Sentences like "send me the photo" stay TEXT (test-pinned).

### A4 — Single-shot notifications had zero media detection
`📷 Photo` posted via plain title/text (no MessagingStyle array) used to slip
through as text.
**Fix:** both parsers classify single-shot text too; unreadable kinds force
`hasAttachment` so the store-only verdict covers them.

### A5 — Call notifications were either dropped entirely or ingestible as text
Ongoing/ringing calls were ignored (good), but a finished `Missed voice call` was
dropped too — a real conversation event lost.
**Fix:** call-category + finished-outcome text (`missed…`/`declined…`) → CALL event,
**store-only, never pinged**. Stored body is the app's own text
(`"Missed voice call"`) — readable, so a reply *is allowed*, with a task-level
disclosure: never invent what was said on the call. Ongoing calls stay ignored
(test-pinned unchanged).

### A6 — Identity was display-name-only (collision + no per-app native ids)
Contacts were keyed purely by normalized title: two chats displaying the same name
on one channel could merge; native identifiers were ignored.
**Fix:** `RawNotif`/extractor now capture the notification's **shortcut id**
(API 29+; WhatsApp publishes its thread JID — the owner's "thread data" allowance)
and the parsers propagate it. `IdentityResolver.keyCandidates()` prefers
`cid:<native id>` (HIGH confidence), keeps the legacy title key (MEDIUM), and
`unknown` (LOW) as fallbacks. **Backward compat:** legacy title keys are unchanged,
and `ContactService.ensureChannelContact(..., aliases)` *re-links* a native id to
the pre-existing contact instead of forking (robo + unit tests).
**No WhatsApp rules forced on any app:** the ids are stored verbatim; every app
contributes whatever its notifications publish.

### A7 — Direction decided by display *name* (collides when a contact shares yours)
**Fix:** sender/owner **Person keys** (extracted from both runtime shapes: Bundle
on 24-27/compat, framework `Person` Parcelable on 28+) decide direction when both
are present; name compare remains the legacy fallback. Unit + robo tests pin the
"two Kelechis" case.

### A8 — Truncated provider responses could be saved as "finished" drafts
Gemini `finishReason=MAX_TOKENS`, OpenAI-dialect `finish_reason="length"`,
Anthropic `stop_reason="max_tokens"` were not handled: half a sentence could be
saved, or (thinking models burning the whole output budget on reasoning) a
misleading "no reply text" surfaced.
**Fix:** cut-off candidates are dropped; if nothing finished, an honest
`TRUNCATED — output-token limit… 'thinking' models can spend the whole budget on
reasoning` error is shown with the actionable next step. Parser tests for all
three dialects.

### A9 — Media gate copy was generic ("media (photo, video, sticker…) — can't read")
**Fix:** kind-specific copy with app name and media-reference honesty, e.g.:
*"The latest WhatsApp item from Amara is a voice note — ReplyMate can't hear voice
notes. The notification did share a file reference; it stays on this phone —
ReplyMate never opens or uploads media. I won't invent a reply…"*

### A10 — Prompt Audit missed mandated fields
**Fix:** the snapshot + Prompt Audit "What was sent" block now include, on top of
provider/endpoint/model/request settings/context turns: the exact latest message,
its **content type**, the **source app package**, **media reference availability**,
the resolved **source identity + confidence**, and the plain-language **reason**
("Manual reply request — answering Amara's latest message on WhatsApp (received…)").
Rendering extensions in `PromptAuditActivity.requestSummary`.

### A11 — Reason text glitches found by my own transcript review
Self-found during RequestDump review: "latest a text message on manual" (grammar +
leaked wire id). Fixed: "latest message in this chat / on WhatsApp".

---

## B. Re-audited and VERIFIED CLEAN (no change needed)

- **Latest incoming message in the request** — gate, turns, task quote, wire body
  and snapshot all re-proven live (`docs/provider-probes/requestdump-latest-message.txt`,
  dummy-key POST to the real Gemini endpoint, HTTP 400 API_KEY_INVALID honesty path).
- **Sender name / app name / conversation history / contact profile / global voice /
  per-contact overrides / custom instructions / learning** — SystemComposer +
  StyleService paths traced; 9-control voice, contact blocks, custom prompts, learned
  hints (same gate for record & apply) all reach the system prompt (existing 300+
  tests still green).
- **Provider & model selection** — single-active `provider_def` semantics intact;
  model comes only from live discovery/user entry.
- **Base URL switching** — `applyBaseUrlRule()` auto-fills and locks official URLs
  on chip select/load/save; only the custom OpenAI-compatible type is editable
  (`baseUrlEditable`; `resolveBaseUrlForUi` — unit-tested, incl. stale-URL swap cases).
- **Model badges** — `ModelClassifier` live-probes each discovered model (1 output
  token, user-initiated), badges from the REAL response only, caches per
  (provider, baseUrl) in kv with "tested <when>", refresh on demand via Discover,
  known-fail always sorted below working (`rank()` pinned by tests).
- **Error surface split** — Diagnostics blocks still separate "Provider said" from
  "ReplyMate read" with raw body on every failure surface + Settings → Diagnostics.
- **No hardcoded model names** — dex string scan of 0.9.0: zero matches.

---

## C. Test suite delta

- **+64 unit tests (302 → 366, 52 suites):** ContentSignalsTest (new, 10),
  ContentKindTest (new, 4), MessagingStyleParserTest (+8), TitleTextParserTest (+4),
  IngestCoordinatorTest (+8), MessageClassifierTest (+3), IdentityResolverTest (+5),
  ContactServiceTest (+2), ReplyContextHonestyTest (+7), PromptBuilderTest (+3),
  GeminiParserTest (+3), OpenAiDialectTest (+2), AnthropicApiTest (+1),
  ProviderTypeTest (base-URL switching already covered), MigrationV5Test (new, 4).
- **+6 Robolectric tests (13 → 19):** extraction of mime/URI values, photo→image
  kind + reference stored, captioned photo, missed call → CALL event no ping, Person
  keys → direction with ambiguous names, shortcutId → native identity + HIGH confidence.
- Regression owner-asked list covered: missing latest message in payload • media-only
  latest • image/video/audio/voice/sticker/call/empty cases • provider-switch base URL •
  badge classification • Prompt Audit completeness • request context honesty.

## D. Intentionally deferred (no product decisions without approval)

- **D1 — Media content understanding (vision/STT keyframes).** The pipeline now
  *captures* media references + kinds + mime locally. Actually opening a file,
  extracting frames, or sending anything to a vision model burns money and sends
  contact media off-device → owner decision required. Until then the honest
  fallback gate above is the behavior.
- **D2 — Memory engine wiring (P4b).** MemoryStore/MemoryEngine exist and are
  tested, but nothing in the app path writes/merges facts yet (re-scoped in P4).
  Learning signals are wired and re-verified.
- **D3 — maxOutputTokens (220) vs thinking-model budgets.** Truncation is now
  honest instead of silent; raising the budget is a cost/latency product call.
- **D4 — One-off dedupe re-hash on identity upgrade.** A chat that first surfaced
  a native conversation id after this upgrade can store one duplicate of the live
  notification (dedupe seed changed from title-key to cid-key). Self-heals from the
  next post; documented, accepted.

## E. Release proof

- 366 unit + 19 Robolectric green; engine verify build clean (all android code).
- APK battery: vc15/0.9.0, minSdk 24, target 34, perms 2, activities 11,
  queries 12, listener manifest↔dex 1:1 (35 refs), zip clean, no idsig,
  signer cert SHA-256 `b15f2f37…` (update-in-place), charter sentinel in dex,
  v5 DDL in dex, ContentKind/ContentSignals in dex, zero hardcoded model ids.
- sha256 `d6c2beeb4f647e2dab213e92178e58c184bc05a30efe76de08dee4762796f3b5`, 157,274 B.
