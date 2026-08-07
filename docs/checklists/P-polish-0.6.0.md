# P-polish — Final Audit & Polish — ReplyMate 0.6.0 (versionCode 12)

**Date:** 2026-08-07 · **Scope:** owner-ordered final audit + polish pass over the whole app.
Mandate (verbatim, abridged): audit every claimed feature (exists, works, fully wired; fix
incomplete/broken/static first) · UX improvements **without redesign** · replace the
reply-generation system prompt with the owner's charter **byte-for-byte** · rewrite the AI
provider engine into a true provider abstraction (unlimited providers, encrypted keys,
connection testing, model discovery, dynamic selection, friendly diagnostics, graceful
fallback, **never hardcode model names**; verify latest official docs first) · expandable
"Why this reply?" panel on every generated reply · audit + full test suite + verified APK +
this report, then **STOP for approval**.
Constraints kept: pure Java 8 · classic Views · zero third-party libs · minSdk 24 / target 34 ·
manual mode (NO automatic replies) · local-first source of truth · Supabase sync OFF · no
Supabase Auth · no Paystack · no Accessibility Service · no scraping / unofficial APIs.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.6.0.apk` | `e7c41051611cf52ef401f143fb79d9b3743eed47db50502a1cc9ba14bda37da7` | 140,890 bytes (137.6K) |

Signer cert SHA-256 `b15f2f37…6a85ed` — identical to every build since 0.2.2 →
**update-in-place over 0.5.1.** APK verified: zip OK, no `.idsig`, signature valid.

---

## 1. Mandate item → what shipped → evidence

### M1 — Whole-app audit (every claimed feature exists, works, is wired)

Walked every screen, row, button, pipeline (same method as the 0.5.1 audit). Results:

| Area | Result |
|---|---|
| Home (S02) | settings/new-contact buttons, search filter, contact rows, health chip — all real |
| Settings hub (S13) | 9 rows, 10 click handlers, **zero dead rows** (see F3): profile, voice, AI providers (live subtitle), message listening, notification sources, ReplyMate notifications, usage, diagnostics, about |
| My profile (S07) | fields + extra box + 5 use-toggles persist; sections feed the prompt |
| My voice (P4) | 9 preset rows cycle + persist; custom instructions autosave; **NEW live preview** (§M5b) |
| **AI providers (rewritten, §M4)** | list screen (label·type, model, key ✓/missing, ACTIVE) + editor (10 types, discovery, test, save&activate, delete) — all live code paths |
| Contact edit (S03/S04) | reorganized into **ABOUT THEM / AI & PRIVACY / VOICE / LEARNING** sections (§M5); all rows write real stores; delete contact two-tap red |
| Conversation (S05) | them/me capture, ✨ generate, draft cards (copy/★/re-gen/delete/open-in-app/tone chips), **NEW "▸ Why this reply?" panel** (§M5c), delete now red |
| Prompt audit (S06) | payload toggle + why-bullets (now shares the `WhyLines` extractor with draft cards) |
| Sources (P3) · Usage (P3) · Diagnostics | unchanged, real-state since 0.4.1 |
| Listener (P2) | manifest ↔ class binary-name match **proved at APK level** (§F1) |
| Prompt pipeline | charter + voice + profile + contact override + learning hints composition verified by unit tests and by the preview path exercising the full L0 stack |

Feature claims verified present-in-code this pass (grep-level): relationship chips (17 refs),
provider summary in Settings, voice preview in both voice screens + `DraftService.previewVoice`,
why-panel (4 refs), discover-models + test-connection in provider editor, red destructive
actions (Conversation 3, ContactEdit 3, Voice 2, ProviderEdit 8), charter bytes (1:1 pinned).

### M2 — UX improvements (no redesign)

- **Wording simplified** across Settings/Voice/Contact helper text (plain-English subtitles).
- **Duplicate settings removed** — the two "Watch WhatsApp/Telegram" quick rows (flagged as
  L4 in the 0.5.1 audit) are gone; Sources remains the single per-app watch-list home. Dead
  `toggle()` method deleted. (F3)
- **Edit Contact reorganized** into 4 labelled sections: ABOUT THEM → AI & PRIVACY → VOICE →
  LEARNING. Same rows, logical grouping, no navigation changes.
- **Relationship field → quick chips**: friend / close friend / family / partner / work /
  client / **Other…** (conditional free-text). Values still land in the same contact field, so
  existing contacts load unchanged.
- **Live reply previews** added to My Voice (global, synthetic sample thread) and Contact
  Voice Override (that contact's real recent thread). Preview runs the **full** prompt path
  (charter + voice + profile + override + learned hints), writes **no drafts**, counts usage
  as REPLY, and respects the private-mode / AI-off gates. Errors render red, variants green.
- **Destructive actions are visually distinct** (red): draft delete, contact delete, reset
  learning, voice reset, provider delete (two-tap confirm where data is lost).
- **Every Settings row performs a real action** — re-verified row-by-row after the dedupe.

### M3 — System prompt replaced by the owner's charter (byte-identical)

- `core/prompt/VoiceCharter.java` holds the charter text exactly as dictated — including
  "hundred percent", "as it's coming to they head", "over space", "I hate it fr", and the
  closing "I don't want her to ever get even half percent instincts that she's talking to an
  AI." Not rewritten, not summarized, not "improved".
- **Byte-pin test** (`VoiceCharterTest`) asserts the constant equals the owner's text
  byte-for-byte and that the composed system prompt **starts with it** (it is the first thing
  the model reads, replacing the old hand-written identity/naturalness paragraphs).
- Voice system, contact overrides, custom instructions and learning hints are integrated
  **after** the charter as additional context layers ("About the owner", "Writing style",
  contact block, rules line) — they cannot edit, reorder or soften the charter. Layer-presence
  tests assert each survives composition.
- The old P4 naturalness guard test was replaced by `charterFoundationReplacesOldIdentity`
  (starts-with assertion). The anti-AI-tell *intent* of the 0.5.1 rules is now carried by the
  charter itself; the compact rules line keeps only non-redundant mechanics (reply in the
  chat's language, no meta commentary, output only the message text).
- Charter bytes verified inside the shipped dex (`hundred percent like ai`, `don't ever sound
  that way` present).

### M4 — Provider engine → true provider abstraction

Official docs re-verified (2026-08-06) before coding: Gemini, OpenAI, OpenRouter, Anthropic,
DeepSeek, xAI (Grok), Moonshot (Kimi), Mistral, Ollama.

| Piece | What shipped |
|---|---|
| One interface | `AiProvider` = `generate(ChatRequest)` + `validateKey()` + **`listModels()`** |
| 10 provider types | Gemini · OpenAI · OpenRouter · Anthropic · DeepSeek · Grok (xAI) · Kimi (Moonshot) · Mistral · Ollama (local, keyless) · **Custom (any OpenAI-compatible API — unlimited services through one adapter)** |
| Adapters | `GeminiProvider` (native v1beta), `OpenAiCompatProvider` (chat/completions dialect — 8 of 10 types), `AnthropicProvider` (messages dialect). `ProviderFactory` is the sole dialect-choosing point |
| Model discovery | every type: `GET /v1beta/models` (Gemini, filtered to generateContent, `models/` prefix stripped) · `GET /models` (OpenAI-style) · `GET /v1/models` (Anthropic). Editor's "Discover models (live)" lists them tappable |
| **No hardcoded model names, anywhere** | provider_def model default is now `''` (schema v4); empty model → friendly "set a model" error; dex grep across 12 model-name patterns = only matches are the word "Ollama" and the *immutable* v1 migration DDL (see F2) |
| Encrypted keys | keys stay in the existing keystore vault (`provider.<type>.key`); keyless types (Ollama) never touch the vault; vault entries registered sensitive only when non-empty |
| Connection testing | Gemini probe = **free** models list (no generation quota burned); OpenAI-style + Anthropic = models probe too |
| Graceful fallback | OpenAI-compat servers rejecting the `n` (variants) param → automatic retry without it, single variant; friendly diagnostics for AUTH/RATE/QUOTA/NETWORK/PARSE/UNKNOWN with fix hints ("make sure the server is running" for Ollama, etc.) |
| Anthropic dialect faithfully mapped | top-level `system`, consecutive same-role turns merged (their roles-must-alternate rule), required `max_tokens`, `x-api-key` + `anthropic-version: 2023-06-01` headers. Honest limit: their API returns exactly one completion per call (no `n`) — see L2 |
| Editor UX | type chips (locked when editing), label, base URL prefilled from the official default per type (editable — self-host/proxy friendly), key field disabled for keyless types, "Test connection", "Save & activate", red two-tap delete (removes vault key + row) |
| Multi-provider UX | providers list with ACTIVE marker; tap a row = activate + open editor; Settings subtitle shows active label·model or "key missing"/"no model" state |

### M5 — "Why this reply?" panel + supporting work

- **Every generated reply** now carries an expandable "▸ Why this reply?" panel (tap the draft
  card's why row): which voice settings, contact overrides, custom instructions and learning
  signals influenced it, plus model/provider context — parsed from the existing prompt-audit
  snapshot via the new shared `WhyLines` extractor. Older drafts show a one-line
  "(generated before prompt auditing…)" note instead of breaking.
- Prompt Audit screen now uses the same extractor (single source of truth, no duplicated logic).

---

## 2. Issues found → fixed (this build)

| # | Issue (evidence) | Severity | Fix / status |
|---|---|---|---|
| F1 | **Play Console pre-launch CNFE: `com.replymate.app.RmNotificationListener`** (owner-pasted report, 16 OEMs, crash on `aload_0` before any app code) | **Critical** | Root cause: the session's listener-trial APK had a manifest pointing at class `com.replymate.app.RmNotificationListener`, but the real service class was in the `.listener` subpackage → the OS tried to instantiate a class that doesn't exist. Current tree verified clean: manifest declares `.listener.RmNotificationListener`; class exists at exactly that binary name; `ListenerStatus` builds its ComponentName from the **class token** (`new ComponentName(ctx, RmNotificationListener.class)`) so it can never diverge again; zero string references to the wrong component anywhere. APK-level proof for 0.6.0: manifest service name ↔ dex descriptor `Lcom/replymate/app/listener/RmNotificationListener;` match 1:1 → CNFE structurally impossible |
| F2 | **v1 schema hardcoded a model default** (`model_name … DEFAULT 'gemini-2.5-flash'` baked into the immutable baseline DDL; string still present in dex) | Medium (violates "never hardcode models" on fresh installs) | Schema **v4** rebuilds `provider_def` (any type allowed, model default `''`, rows + key_refs preserved). Shipped migrations must never be edited, so v1 stays as history; every install (fresh or upgraded) ends at v4. `MigrationV4Test` asserts: fresh-install model default is `''`, rows survive v3→v4, any type storable, v3 tables untouched |
| F3 | Duplicate "Watch WhatsApp/Telegram" quick rows in Settings + dead `toggle()` (0.5.1 audit item L4) | Low (owner flagged in this mandate) | Rows removed; Sources is the single home for per-app watching. Dead method deleted. Settings now 9 rows / 10 handlers, all real |
| F4 | `HttpClient` was `final` — providers couldn't be tested against documented server behaviors (no fake seam) | Medium (testability gate for M4) | Class opened (methods unchanged); dialect suites now stub HTTP at the seam (`NoNHttp`, `ModelsHttp` fakes). Production behavior identical |
| F5 | `ProviderType.fromWire` **threw** on unknown wire values; a stale/edited DB row would crash provider resolution | Medium (crash risk) | Unknown → graceful `OPENAI_COMPAT` fallback + label shows the raw type; test-pinned |
| F6 | `ProviderDef` ctor injected Gemini defaults (label/baseUrl/model) — another hardcoded-model leak; Gemini `validateKey` burned a real generation per test | Medium | Defaults emptied (test-pinned `""`); validateKey is now the free `GET /v1beta/models` probe |

## 3. Issues found → NOT fixed (deliberate; parked for owner decision)

| # | Item | Why |
|---|---|---|
| L1 | **No real-API-key end-to-end test was possible for this build.** The owner's keys live on his phone; this sandbox has none, and no test keys may be embedded in the repo or APK | Honest limitation, explicitly documented per the mandate. Mitigation shipped: every adapter's request/response handling is unit-tested against the **official documented shapes** via HTTP fakes (147 provider assertions across dialect/discovery/payload/parser suites), and the editor's **Test connection** + **Discover models** buttons are the on-device live-key validation path — 30 seconds per provider on the owner's device (smoke list §5) |
| L2 | Anthropic returns exactly **one** completion per call (API has no `n` param) | Platform constraint, not a bug; variants list = 1 for Anthropic. Regenerate to see alternatives |
| L3 | Some third-party OpenAI-compatible servers reject the `n`（variants) parameter | Handled: automatic retry without `n`, single variant, no error shown. Behavior test-pinned (`NoNHttp` fake) |
| L4 | One saved configuration **per provider type** (activating OpenAI again after deleting = re-enter key) | Matches single-active-provider model; multi-account support would be new scope |
| L5 | OpenRouter attribution headers (`HTTP-Referer`, `X-OpenRouter-Tile`) intentionally **not** sent | Privacy: no app metadata leaked to a third party beyond the required API call itself |
| L6 | No on-device instrumented DAO test of the new provider DAO paths (JDBC schema tests + service fakes only) | No emulator/KVM in this sandbox (documented since P3); robo suite (13/13) covers listener extraction against real AOSP code |
| L7 | `StyleProfile`/derived-rules hook still dormant (P5 scope) | Unchanged from 0.5.1 audit |

## 4. Verification battery (0.6.0 APK, this machine)

| Check | Result |
|---|---|
| Unit suites | **266 tests / 43 suites — all green** (232 → 266: +VoiceCharter, +ProviderType, +OpenAiDialect, +AnthropicApi, +GeminiDiscovery, +MigrationV4, composer/charter guard swap) |
| Robolectric (real AOSP API-34 listener extraction) | **13/13 green** (harness rebuilt this session against 0.6.0 sources) |
| Package | `com.replymate.app`, versionCode **12**, versionName **0.6.0** |
| minSdk / targetSdk | 24 / 34 ✓ |
| Permissions | exactly 2 (INTERNET, POST_NOTIFICATIONS) ✓ |
| Activities | 11 (incl. `.ui.ProviderEditActivity`) ✓ |
| `<queries>` entries | 12 ✓ (unchanged) |
| Listener service | name ↔ dex class 1:1, label "ReplyMate listener", BIND_NOTIFICATION_LISTENER_SERVICE, exported ✓ |
| Signer cert SHA-256 | `b15f2f37…6a85ed` (constant since 0.2.2 → update-in-place) ✓ |
| Zip integrity / `.idsig` | OK / absent ✓ |
| Dex sentinels | ProviderEditActivity · OpenAiCompatProvider(+Payloads/Parser) · AnthropicProvider · ProviderFactory · VoiceCharter · WhyLines · listener class — all present ✓ |
| Hardcoded model-name scan (12 patterns) | only innocuous matches: the word "Ollama" in UI strings + the immutable v1 DDL string (superseded by v4, see F2) ✓ |
| Official base URLs in dex | all 9 present exactly as documented (openai.com, openrouter.ai, anthropic.com, deepseek.com, x.ai, moonshot.ai, mistral.ai, localhost:11434, generativelanguage) ✓ |
| Charter bytes in dex | present verbatim ✓ |
| Temp build artifacts | removed (polish-check.apk deleted; build/ dir wiped post-commit) |

## 5. On-device smoke list for the owner (~5 min)

1. Install **ReplyMate-0.6.0.apk** over 0.5.1 → opens, contacts/drafts intact (schema v3→v4 preserves provider row + key).
2. **Settings → AI providers** → your Gemini row still ACTIVE; open it → **Test connection** (live key check) and **Discover models** → pick a model → Save & activate.
3. Optional: **+ Add provider** → e.g. OpenAI → paste key → Test connection → Discover models → activate. Switch ACTIVE between providers by tapping rows.
4. Any chat → ✨ generate → tap **▸ Why this reply?** on a draft → expand/collapse; reasons match your voice setup.
5. **My voice** → tweak a control → watch the **preview** box regenerate (uses your real current settings; no drafts written).
6. A contact → Edit → VOICE → **Preview voice** there (uses that contact's real thread); ABOUT THEM → set relationship via chips (try **Other…** → custom box).
7. Destructive-distinctness eyeball: delete/reset buttons render red; contact delete asks twice.
8. Reply sanity: generate 2–3 replies in a real chat → confirm the charter's effect (typed-not-polished feel) vs 0.5.1.

## 6. STOP

Phase complete. No further coding until owner approval. Build archived in
`releases/RELEASES.md` (0.6.0 row). If approved → next phase per SPEC/BLUEPRINT
(P5: style-from-samples) or owner's new directive.
