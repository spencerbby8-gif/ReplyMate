# P4 Completion — ReplyMate 0.5.0 (versionCode 10)

**Date:** 2026-08-06 · **Scope:** owner-redefined P4 — *per-contact customization and learning
system* (the original "Memory layers" P4 scope is parked as **P4b**, groundwork kept).
No redesign of working architecture; prompt pipeline, notification framework, drafts,
diagnostics and release flow preserved. Local-first, contact-isolated, sync OFF,
no Auth/Paystack/cloud, no Accessibility/scraping/unofficial APIs/auto-replies.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.5.0.apk` | `b4b4780f6a1aac587fbefba9b75d06a38aa2853af8518663932cf3b946309b64` | 124,423 bytes (122K) |

Signer cert SHA-256 `b15f2f37…6a85ed` — **identical to every release since 0.2.2 → update-in-place over 0.4.1.**

## Owner scope → delivery matrix

| Owner item (verbatim) | Delivered | Where |
|---|---|---|
| A global user voice as the base style | **Yes** — 9 controls × 3 levels, rendered as one `Voice: …` rule line in every prompt; defaults are recoverable (tapping back to default removes the stored row) | `core/style/StyleControls` (phrase table = model contract), `VoiceActivity` (Settings → "My voice (global style)") |
| Per-contact style overrides | **Yes** — every control: Inherit → 0 → 1 → 2 → Inherit; contact rows win over global | `StyleSettings.resolve` (pure, tested), Contact edit screen "VOICE FOR THIS CONTACT" |
| Controls for tone, length, emoji use, formality, humor, confidence, slang, flirting, and follow-up behavior | **Yes — exactly these 9**, each with a UI label and a prompt phrase. Flirting ships at "never" by default (safe) | `StyleControls` |
| A custom prompt box for each contact | **Yes** — free text, capped at 400 chars, rendered as "Special instruction for this chat: …"; saved with the contact (also persisted on back-nav) | `custom.prompt` row in `style_setting`; Contact edit screen |
| A private 'About Me' section for the user | **Yes** — "More about you (private)" free text on the My profile screen; appears in prompts as "More about me: …" | `ProfileService.KEY_EXTRA`, `ProfileActivity` |
| Toggles for every personal info section so users control what the AI may use | **Yes — 5 toggles** (name / languages / bio / topics / about-me extra), default ON; OFF removes the section from every future prompt and records `… excluded by toggle` in the audit why | `ProfileService.use/setUse/loadFiltered/extraFiltered/excludedSections` |
| Learning from approved, edited, regenerated, and rejected replies | **Yes** — Copied-as-is → APPROVED · edited-then-copied → EDITED (with auto-classified delta: shorter/longer/emoji-up/emoji-down/tweaked) · Re-gen while drafts exist → REGENERATED · delete → REJECTED. Signals derive prompt hints after deterministic thresholds (≥3 signals overall; ≥3 edits for edit hints; regen ≥4>approved; rejected ≥3≥2×approved) | `core/learning/LearningEngine` (pure thresholds), `LearningService.record` wired in `ConversationActivity` copy/Re-gen/Delete paths |
| Learning controls to reset, pause, export, or disable per contact | **Yes — all four**, per contact: disable toggle, pause toggle (keeps history, applies nothing), Reset (wipes that contact's signals), Export (local JSON → clipboard: counters, settings, signals — never leaves the phone unless the user pastes it) | Contact edit screen "LEARNING FROM YOUR CHOICES"; `LearningService.reset/setOff/setPaused/exportJson` |
| A prompt audit view that shows why a reply sounded the way it did | **Yes** — every generation/transform now stores a `why` array inside the existing prompt snapshot; the audit screen renders it as "Why it sounded this way" bullets (global voice applied/defaults, each contact override, custom prompt, each learned hint w/ evidence, profile sections excluded by toggle, learning gate state) | `PromptBuilder.snapshot(…, why)`, `PromptAuditActivity` |
| Keep everything local-first and contact-isolated | **Yes** — composition reads exactly: global rows + THIS contact's rows + THIS contact's signals; learning can neither feed nor consume when the contact is private-mode, memory-disabled, learning-disabled, or paused; new isolation regression tests prove cross-contact silence | `StyleService.compose`, `LearningService.openFor` (one shared gate), `IsolationSuite` |

## Bugs found & fixed during the phase (test-layer only, no product bugs shipped)

| # | Bug | Fix |
|---|---|---|
| 1 | `StyleSettings.level` clamped out-of-range stored values ("7"→2), silently pinning a hard level on corrupt rows | Out-of-range now = invalid → inherit; test `badValuesFallBackToInherit` |
| 2 | 4 new tests cast `Json.parseObj` nested values to `JsonObj`/`JsonArr` — the parser returns **plain** `java.util.Map`/`List` | Tests use plain container types (Json API quirk documented in test comments) |
| 3 | Emoji test fixture tripped the "shorter" classifier too (surrogate pairs shrink UTF-16 length) | Fixture lengthened to isolate emoji direction |
| 4 | `MigrationV2Test` hard-coded `user_version == 2` | Now asserts `Migrations.LATEST` (3); renamed to `…ToLatest` |

## Storage model (schema v2 → v3, additive)

- `style_setting(id, contact_id NULL=global, key, value, updated_at)` with **partial unique indexes** —
  `uq_setting_global(key) WHERE contact_id IS NULL` and `uq_setting_contact(contact_id,key)` —
  because SQLite treats NULLs as distinct in plain unique constraints (test proves global keys can't duplicate).
- `style_signal(id, contact_id FK→contact ON DELETE CASCADE, kind CHECK in approved/edited/regenerated/rejected, detail, draft_id, created_at)` + `idx_signal_contact_ts`. Derivation window: last 500 signals/contact.
- Fresh installs land directly on v3; upgrades from v1/v2 keep all rows (tested via real SQLite JDBC fixtures).

## Evidence

| Gate | Result |
|---|---|
| JVM unit suites (`scripts/run_tests.sh`) | **228/228 OK** — incl. new StyleSettingsTest, StyleServiceTest, LearningEngineTest, LearningServiceTest, P4PromptTest, MigrationV3Test; IsolationSuite +2 (style rows/signals never cross contacts); DraftServiceTest +1 (voice+why flow end-to-end) |
| Robolectric regression (real AOSP MessagingStyle/API-34) | **13/13 OK** (NotifExtractorRoboTest 10 + PackageDetectionRoboTest 3 — P3-repair behavior intact) |
| APK verification (aapt2/apksigner on the shipped file) | vc=10 / name=0.5.0 · minSdk 24 / target 34 · permissions exactly `INTERNET` + `POST_NOTIFICATIONS` · `<queries>` block intact (11 catalog + vending) · 10 activities incl. `VoiceActivity` + listener service in binary manifest · dex contains all P4 sentinel classes (`VoiceActivity`, `StyleService`, `StyleControls`, `LearningService`, `LearningEngine`, `SqlStyleSettingStore`, `SqlLearningStore`, `SchemaV3`) · dex integrity gate passed · zip OK, no `.idsig` sidecars · signer cert unchanged |
| Docs | `BLUEPRINT.md` P4 row rewritten to owner scope (+ P4b parked row; §5.3/§5.6 markers updated); `SPEC.md` phase list mirrors it |

## On-device smoke suggestions (owner)

1. Settings → **My voice** → set Tone=direct, Emoji=none → generate a reply → the audit view (conversation → audit icon) should show "global voice applied (Tone=direct, Emoji=none)" under **Why it sounded this way**.
2. Contact → edit → set an override (e.g. Emoji=plenty) + a custom prompt → generate → reply should differ from the global voice; audit shows "(contact override of …)" + "custom prompt for <name> applied".
3. My profile → turn **Short bio** off → generate → audit shows "bio excluded by toggle".
4. Copy a draft as-is ×3, delete one → contact edit → LEARNING shows counters (approved 3 · rejected 1). Make 3 shorter edits → next replies visibly shorten; audit says "learned: keep replies noticeably shorter — 3 of 3 edits…".
5. **0.4.1 regression check:** Sources should still detect WhatsApp/Discord as installed (`<queries>` untouched, guarded by ManifestCatalogTest).

## Notes / parked decisions (need owner approval, NOT started)

- **P4b Memory layers** (original P4: facts extraction job, summaries, prompt L1/L2, memory browser) — groundwork (`core.memory` + store) is merged; the feature itself awaits your go.
- **P5 overlap:** blueprint P5 ("style engine") mentions per-contact style overrides, which P4 now covers — P5 will need re-scoping when we get there (proposed: keep it as sample→rules *learning from longer text samples* + per-contact language).

**Stopped here per process — awaiting owner approval before any further phase.**
