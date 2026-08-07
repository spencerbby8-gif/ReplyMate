# P-context-honesty + Model Classification — ReplyMate 0.8.0 (versionCode 14)

**Date:** 2026-08-07 · **Mandate (owner):** audit & fix the reply-generation/provider pipeline
— prove the real conversation context reaches the model; never generate from only the
contact name; honest fallback for media/empty notifications; Prompt Audit shows latest
incoming / context / final prompt / provider / endpoint / model / request settings / why;
regression tests that fail if the latest message is missing; model picker actively tests and
badges every discovered model; base URL auto-replaced on provider switch and locked except
for Custom; docs re-verified before touching provider code; local-first; no redesign.
Constraints kept: local-first · sync OFF · manual replies only · no new libs.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.8.0.apk` | `9ea4bf4e5b268126bc17bea546c0838eb74cb0c62ebbee2b7cb69b402746d620` | 149,082 bytes (145.6K) |

Signer cert `b15f2f37…6a85ed` (constant) → update-in-place over 0.7.0.
Docs re-verified 2026-08-07 (Gemini 3.x lineup/thinking, Anthropic model aliases, OpenAI
specialized-model list, DeepSeek base+models, OpenRouter :free/pricing, Mistral model
capabilities, xAI) — classification relies on live responses, not docs-derived names.

## 1. Pipeline audit verdict (the 11 mandated context items)

| Item | State found | Evidence now |
|---|---|---|
| latest incoming message | ⚠️ present only when the last message had readable text | quoted in the task turn verbatim; gate + regression tests |
| sender name | ✅ "Name: text" prefixes | unchanged, asserted |
| app name | ⚠️ never reached the prompt or audit | task: "This chat is on WhatsApp." (real chats); audit shows channel |
| conversation history | ✅ last 30, oldest trimmed first | unchanged; `latestUsableIncoming` survives budgeting (verified) |
| contact profile | ✅ relationship/notes/tone/language block | unchanged |
| per-contact voice overrides | ✅ | unchanged |
| global voice | ✅ | unchanged |
| custom instructions | ✅ | unchanged |
| learning & memory hints | ✅ (gated by privacy toggles) | unchanged |
| notification extraction | ✅ media→placeholder, dedupe, sender-vs-owner | unchanged (robo 13/13) |
| final prompt to provider | ✅ charter + owner style + voice + partner + rules + turns + task | full wire body dumped in `docs/provider-probes/requestdump-latest-message.txt` |

## 2. Issues found → fixed

| # | Issue (evidence) | Fix |
|---|---|---|
| G1 | **Silent context hole:** with a media-only or empty latest notification, generation proceeded over stale turns — the model answered an OLDER message while the user believed it answered the new one (the "generic drafts" smell). ThreadMapper skipped empty bodies silently; only the no-incoming case was gated | Hard gate in `generateForContact`: latest incoming must be usable text; otherwise an honest, specific error ("…is media — can't read its content. I won't invent a reply without their actual words. Open WhatsApp to read it, or type their message here…"), **provider never called, no tokens burned, no draft written** (`ReplyContextHonestyTest` × 4 cases) |
| G2 | **Drafts looked generic because the task was generic:** "Read the conversation above" — no anchor to the message being answered | Task now quotes the exact latest message ("The message you're replying to — Amara's latest: \"…\". Answer THAT message, not an older one.") and names the app |
| G3 | Audit snapshot lacked provider/endpoint/settings/latest-message; Prompt Audit showed only raw JSON | Snapshot gains `provider{wire,label,baseUrl,model,endpoint}`, `latestIncoming{text,channel,at}`, `contextTurns`, `maxInputTokens`; Prompt Audit card renders "What was sent" (provider·model·endpoint·settings·latest-message·context) + keeps the raw payload toggle; pre-0.8 snapshots degrade gracefully |
| G4 | Model picker listed everything equally; a new-key user could pick a closed/zero-quota model and hit immediate errors (exactly your Gemini experience) | `ModelClassifier`: every discovered model is **actively probed** (1-token live request through the real dialect) and badged from the REAL response — Working · Free tier · Paid · Unsupported · Deprecated · Quota unavailable · Requires billing · Authentication failed. Results cached in kv with timestamp and always renderable offline; re-test on demand via the same button. Known-fail models are always sorted below working ones (never recommended) |
| G5 | Switching provider types left the previous provider's Base URL in the field | `ProviderType.resolveBaseUrlForUi` + editor enforcement on chip-select/screen-load/save: known providers auto-fill AND lock their official docs endpoint; only "Other (OpenAI-compatible)" is editable |
| G6 | "This chat is on manual." leaked into prompts for manually captured chats (noise) | app label only included for real chat channels (caught during the request-dump inspection) |

## 3. Mandated exit checks — all green

- **Full suite: 302 unit tests / 49 suites + 13 Robolectric — all green.** New:
  `ReplyContextHonestyTest` (latest-message-reaches-provider pins, media/empty gates,
  history-media honesty, audit fields), `ModelClassifierTest` (badge matrix from real
  response shapes incl. the owner's fresh-key captures; dialect-correct probes;
  known-fail never outranks working), ProviderType editor-url tests (2).
- **Real provider request inspected:** `tools/probe/RequestDump` drives the REAL
  DraftService→PromptBuilder→GeminiPayloads path and prints the exact wire body;
  the same bytes were sent to Gemini live (400 auth on dummy key — mapping shown).
  Transcript + gate output: `docs/provider-probes/requestdump-latest-message.txt`.
- **Latest message confirmed reaching the model:** gate prints `true` for turns, task,
  wire body, and audit snapshot.
- **Base-URL switch verified:** `ProviderTypeEditorUrlTest` (locked for 9 known types,
  custom keeps input; always resolves to the official endpoint) + editor wiring on
  select/load/save.
- **Badges accurate:** classification logic pinned by unit tests built on the verbatim
  live responses (200 / 404-phasing / 429-limit:0 / 401-key / 402-balance / transport);
  consistent with the owner-key Gemini matrix from the 0.7.0 audit (2.5-flash→Deprecated,
  2.5-pro→Quota unavailable, 3.5-flash-lite→Working).
- **APK verified:** vc14/0.8.0 · minSdk 24 · target 34 · 2 permissions · 11 activities ·
  12 queries · sentinels (`ModelClassifier`, `AuditContext`, listener) + new copy strings
  present in dex · zip OK · no `.idsig` · signer constant.

## 4. Remaining limitations (honest)

| # | Limitation | Note |
|---|---|---|
| LL1 | Classification probes cost 1 output token per model and run sequentially (user-initiated only) | Deliberate: badge accuracy > zero traffic; cache persists |
| LL2 | "Free tier" is badged only from REAL signals (OpenRouter `:free`, Ollama local); other providers can't prove free-ness without billing metadata, so working models read plain "Working" | Never claims free without evidence |
| LL3 | Cached badges reflect the key that tested them; switching keys keeps the cache until re-test | Cache is per base-URL; the button refreshes in one tap |
| LL4 | Live classification demo run in-sandbox used dummy keys (every model → Authentication failed — itself an accuracy proof of the mapping chain) | Owner's one-tap Discover & test on-device is the live proof with his key |
| LL5 | Media messages carry one shared placeholder ("[media/voice — open in chat app]") — type (photo vs sticker vs call) isn't distinguished at extraction | MessagingStyle payloads don't reliably carry the mime type; placeholder is intentionally generic and honestly labeled |
| LL6 | Prompt Audit "What was sent" appears only on drafts created from 0.8.0 onward | Older drafts keep the raw snapshot view |

## 5. STOP

Phase complete. On-device demo path: update → open a real WhatsApp chat in ReplyMate →
✨ generate → Prompt audit → "What was sent" shows provider/endpoint/model/settings and
the exact message answered. Then Settings → AI providers → your Gemini row → Discover &
test models → watch each model get its live badge. No further coding until approval.
