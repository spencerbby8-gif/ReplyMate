# P1 Completion Report — Manual-Mode MVP
**Shipped:** `releases/ReplyMate-0.1.0.apk` · versionCode 3 / 0.1.0 · **Status:** built + unit-verified; device validation = owner step.

## Scope delivered (owner's P1 list — every item)
| Requested | Delivered |
|---|---|
| My Profile editor | S07 `ProfileActivity` — name/languages/bio/topics → kv (feeds prompt L0) |
| Settings hub | S13 `SettingsActivity` — My profile · AI provider (live configured status) · Diagnostics (reuse of P0 self-test) · About |
| Keystore-backed secret vault | `KeystoreCrypto` (AndroidKeyStore AES-256/GCM, alias `rm_master`, random 12B IV per write) + `AndroidSecretVault` (private prefs, ciphertext only). Keys never in DB/logs; registered with ScrubLogger |
| Gemini provider setup | S09 `ProviderActivity` — label/base-URL/model/key; `provider_def` stores NON-SECRET config; key via vault (`gemini.main`); activate = single active row in txn |
| API key validation | "Validate key" → 1-token probe through real `GeminiProvider` (background thread, classified errors: AUTH/QUOTA/NETWORK with provider detail) |
| Manual message input | S05 `ConversationActivity` — paste/type as **＋them** (incoming) or **＋me** (outgoing), stored per contact with bubbles + timestamps |
| Gemini draft generation | `DraftService` (core, pure-JVM) → `PromptBuilder` (L0 profile/style + L3 thread + L4 task) → `GeminiProvider` (payloads, retries w/ Retry-After, backoff) → guard rails: no provider / private contact / no incoming msg = specific errors |
| Local draft storage | `draft` table rows per variant with full **prompt snapshot audit**, model, latency, token stats; usage_metering in `usage_event`; history section renders newest-first |
| Reply variants | `candidateCount=3` in one request → up to 3 variant cards, editable inline |
| Copy functionality | per-card **Copy** → clipboard + toast; edited text persists (status EDITED, else COPIED) |

Plus the plumbing the blueprint requires for the above: contacts CRUD (create/edit with relationship,
notes, tone, language), per-contact channels (manual), Home inbox (S02) with last-message preview +
empty states + storage health banner, `AppContainer` v2 wiring, `Tasks` bg/main runner, `Ui` toolkit.

## Explicitly NOT implemented (per instructions) ✓
No notification listener · no Telegram/WhatsApp integration · no automatic replies · no sending ·
no memory engine/jobs · no future-phase features. Manifest still has **zero permissions**.
Blueprint P1d extras (tone transforms, S06 audit screen, S11 usage dashboard UI) deferred —
data for all three is already stored; approval needed to surface them.

## Verification evidence
1. **Unit tests — 66/66 green** (+32 vs P0.2): PromptBuilderTest(6), TokenBudgeterTest(4),
   GeminiPayloadTest golden wire JSON(4), GeminiParserTest(8), DraftServiceTest(6 incl.
   **isolation assertion**: draft snapshot for contact A contains no byte of contact B's messages),
   plus all P0 suites still green.
2. **APK checks:** signature valid (persistent key → updates in place); badging vc3/0.1.0,
   minSdk 24/target 34; **0 permissions**; manifest xmltree: 6 activities, exported flags correct,
   `allowBackup=false`, `usesCleartextTraffic=false`; 70KB.
3. **Release log:** row appended with sha256 `0e7c8f95…a2b4f7`.

## Architecture notes (mechanical, per §11)
- `ChatRequest`/reply types live in `core.ai` (dependency rule) — recorded in P0 report.
- Manual channel key design: `remote_key = "manual:"+contactId` (blueprint's literal `'manual'`
  would collide with UNIQUE(channel, remote_key) across contacts). No behavior change.
- `ProviderGateway` port added so `DraftService` stays pure-JVM testable; `ProviderStore` port added
  for provider_def persistence (both mechanical).
- `Ui.java` toolkit + shape drawables introduced to render screens without appcompat at ~zero XML cost.
- Test count guard: two harness issues were caught by tests during the phase (package-visibility,
  one tautological assertion) — fixed; no production compromises.

## Owner device test script (5 min)
1. Install (updates 0.0.2 in place, data preserved).
2. **Settings → AI provider:** paste AI Studio key → Validate key → expect "Key valid ✓" → Save &
   activate → row turns green.
3. **Settings → My profile:** fill name/languages/bio/topics → Save.
4. **Home → ＋ New contact:** name + relationship → open it.
5. Paste an incoming message → ＋them → ✨ Generate → expect ≤3 variant cards (~2–6s on data).
6. Edit one card → Copy → paste anywhere to confirm clipboard; re-open contact → draft history persists.
7. **Diagnostics** row in Settings — DB self-test panel should be all-green.

## Gate (BLUEPRINT §8/P1)
"paste→draft ≤10s on mobile data · key survives lock/rotate · suites green · owner uses it a day"
→ pending 1-day owner usage before P2. **STOPPED for approval.**
