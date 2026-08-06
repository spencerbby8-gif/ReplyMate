# P4 Audit & Stabilization — ReplyMate 0.5.1 (versionCode 11)

**Date:** 2026-08-06 · **Scope:** owner-ordered audit + stabilization of P1–P4. No redesign,
no new major features; the single authorized feature upgrade (My Voice: presets **and**
manual custom instructions) plus completing already-approved-but-unreachable scope.
Constraints kept: local-first source of truth · sync OFF · no Supabase Auth · no automatic
replies · no Accessibility · no scraping/unofficial APIs.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.5.1.apk` | `001ce81982532401a8c7d0c9e4cd73f78bd793d3a5c236598254f1c2e2f15bb3` | 124,423 bytes (122K) |

Signer cert SHA-256 `b15f2f37…6a85ed` — identical to 0.2.2–0.5.0 → **update-in-place over 0.5.0.**

---

## 1. What the audit covered (all screens, all rows/buttons, pipelines)

| Area | Method | Result |
|---|---|---|
| Home (S02) | full source walk: settings/new buttons, search filter, contact rows, health chip | all real actions; no placeholders |
| Settings hub (S13) | walked all 11 rows: profile, voice, provider, listening, sources, WA/TG watches, notifications, usage, diagnostics, about, footer | every row opens a real screen, a system screen, a live toggle, or expands real data ✓ |
| My profile (S07) | fields save; extra box saves; 5 use-toggles write kv | verified + test-covered (`profileTogglesFilterSectionsAndAreAudited`) |
| My voice (P4) | 9 control rows cycle + persist; reset restores defaults; custom instructions (NEW) persist | verified + tests added |
| Provider (S09) | validate-key (real network call), save & activate, keystore vault | untouched, real |
| Contact edit (S03/S04) | 5 base fields save; voice override rows cycle Inherit→0→1→2→Inherit; custom prompt persists; learning toggles/stats/reset/export all live; NEW privacy rows + delete contact | verified |
| Conversation (S05) | them/me capture, ✨ generate, draft cards (copy/★/re-gen/delete/open-in-app/6 tone chips), audit button, search | verified; learning signals wired (0.5.0) |
| Prompt audit (S06) | payload toggle + NEW-accurate "why" bullets | verified |
| Sources (P3), Usage (P3), Diagnostics | already real-state from 0.4.1 repair | confirmed unchanged |
| Navigation flows | every ‹ back header finishes; conversation reloads contact on resume; home re-renders on resume | 2 nav fixes (below) |
| Prompt pipeline | SystemComposer/PromptBuilder/TaskComposer/ThreadMapper/TokenBudgeter walk | voice/profile/learning injection verified end-to-end; naturalness rules improved (1 feature block) |
| Storage flow | ProfileService kv, StyleSettingDao upserts, signal inserts, FK cascades, migrations 1→3 | verified; 1 dead method-family removed |
| Learning pipeline | record (UI) → gate → insert → counters → hints → prompt line → audit why | verified end-to-end + test-covered |

## 2. Issues found → fixed (this build)

| # | Issue (evidence) | Severity | Fix |
|---|---|---|---|
| F1 | **Contact privacy flags unreachable.** `privateMode`, `memoryEnabled`, `aiEnabled` persisted since P1 and gate generation + learning (P4), but NO screen wrote them — the "learning off — private contact" UI paths were dead code and users couldn't mark a chat private | **High (privacy model incomplete)** | Contact edit now has "AI & PRIVACY FOR THIS CONTACT": Private mode / Memory / AI replies rows — immediate `contactService.update`, `privateMode ⇒ aiEnabled=false` contract enforced, learning section reacts live via `refreshLearningState()` |
| F2 | **My Voice couldn't take manual instructions.** Only the 9 preset controls existed globally (contacts already had a custom box) | Medium (owner's explicit upgrade) | Global custom instructions: stored as `custom.prompt` in the global style_setting scope, rendered as "The owner's own standing style instruction (applies to every chat): …" ahead of the contact custom prompt, audit why notes it (`global custom instruction applied (N chars)`). Voice screen: presets section labelled, free-text box + auto-save + live AI-told preview + reset clears it too |
| F3 | **Replies could sound AI-written.** Rules block lacked texting-realism guidance | Medium (owner's quality ask) | New grounded rule block in `SystemComposer`: real-person texting, no greetings/sign-offs unless the chat already uses them, no formal openers, never restate their question, no bullets/numbered lists/headers, avoid long dashes/balanced sentences, match the capitalization/punctuation/length habits visible in the owner's own side, short answers when enough. Every clause defers to conversation habits or user voice settings — nothing fights a control. Guard test added |
| F4 | **No way to delete a contact.** `ContactStore.delete` + FK cascade shipped in P1 ("contacts CRUD" scope) but had zero reachable callers | Medium (dead capability + orphan data) | "Delete contact" button (existing contacts) with two-tap inline confirm; FK cascade wipes messages, drafts, channels, style settings, learning signals, memory; conversation screen exits if its contact was deleted underneath |
| F5 | Dead code: `DraftService.regenerateForContact` (zero callers; UI uses `generate()`), `StyleSettingStore.deleteForContact` + DAO + Sql impl (redundant under ON DELETE CASCADE), `util/StdLogger` (zero references anywhere; tests use `NOOP_LOG`) | Low | All removed; Fakes updated |
| F6 | Dead no-op `if` + stale comment in `generateForContact`'s last-incoming scan (leftover debug branch) | Low | Simplified; comment now documents the oldest-first ordering invariant that makes the loop correct (verified against `MessageDao.lastMessages`) |
| F7 | Home tagline still said "manual mode" (listener shipped in P2) | Cosmetic | `home_sub` → "Private AI reply assistant — on-device only" |

## 3. Issues found → NOT fixed (deliberate, parked for owner decision)

| # | Item | Why untouched |
|---|---|---|
| L1 | `StyleProfile`/`StyleStore` ("derived rules") is the P5 sample→rules hook; nothing writes rules yet, so "Writing style:" uses the tested fallback | P5 scope ("Do not introduce new major features"); voice line covers style meanwhile |
| L2 | Supabase foundation config visible in Settings diagnostics (endpoint + table count, sync OFF) | Approved foundation phase; harmless read-only display |
| L3 | Per-contact language control lives as the P1 free-text "Language with them" field; blueprint P5 planned a structured control | P5 re-scope decision parked with owner (noted in 0.5.0 report) |
| L4 | `Watch WhatsApp`/`Watch Telegram` quick-toggles in Settings duplicate Sources cards by design (their original entries) | Redesigning Settings layout = out of scope; both write the same kv keys, both reflect real state |
| L5 | Home contact rows do per-row `lastMessages` queries (N+1) | 30-contact scale is trivial; perf phases come later (P6) |
| L6 | On-device DAO string-mapping for style_setting/style_signal verified by schema-level JDBC tests (constraints, cascade, NULL-uniqueness) + fake-based service tests; no device-instrumented DAO test in sandbox | No emulator/KVM here; robo suite covers listener extraction; flagged as known test-topology limit since P3 |

## 4. Owner verification items — evidence

| Owner ask | Proof |
|---|---|
| Every Settings item opens a real screen/action; no placeholders | §1 Settings row; all 11 entries mapped to real behavior |
| Every toggle changes runtime behavior | profile toggles → prompt sections (P4PromptTest) · voice controls → voice line (StyleServiceTest) · custom instructions → system prompt (new P4PromptTest) · learning off/pause → shared gate (LearningServiceTest gating matrix; StyleServiceTest) · private/memory/ai flags → `openFor` gate + generate refusal (`failsClosedForPrivateContact`, `blocksWithoutConfiguredProvider`…) · watch toggles → listener parse path (robo suite) · notification row → system permission flow |
| Profile + My Voice + Contact overrides injected & visible in Prompt Audit | `p4VoiceAndWhyFlowIntoPromptAndAudit` (end-to-end: store → compose → provider request → snapshot why), `globalCustomInstructionReachesTheSystemPrompt`, `contactCustomPromptStacksAfterTheGlobalInstruction` |
| My Voice presets + manual instructions | F2 above + VoiceActivity UI |
| Learning signals stored & influence future generations | `style_signal` constraints (MigrationV3Test: CHECK kinds, cascade, index) · LearningEngine thresholds (LearningEngineTest) · hints reach prompt only via gate (StyleServiceTest) · per-draft why shows each hint with evidence |
| Contact overrides always beat global | `contactOverrideBeatsGlobalAndIsExplained` + resolve() test matrix; UI labels show "Override:" vs "Inherit:" |
| Prompts more natural / less AI-written | F3 + `naturalTextingRulesGuardRepliesFromAiTells` |
| Dead code/placeholders/temp debugging removed | F5–F7; resource sweep: all 11 layouts, 5 drawables, 7 strings referenced; no TODO/FIXME/System.out left in product code |
| Full P1–P4 regression | below |

## 5. Regression evidence

| Gate | Result |
|---|---|
| JVM unit suite (P1–P4: json, prompt, budget, style, learning, profile, drafts, usage, contacts, migrations v1–v3, isolation, manifest guards, platform guards) | **232/232 OK** (was 228 at 0.5.0; +4 new, several strengthened) |
| Robolectric on real AOSP API-34 (listener extraction 10 + package detection 3) | **13/13 OK** |
| App-layer compile via engine (classic Views, pure Java 8, zero libs) | clean; dex gate OK |
| Shipped APK checks (aapt2/apksigner on the artifact) | vc=11 / 0.5.1 · label "ReplyMate" · launcher HomeActivity · minSdk 24 / target 34 · exactly INTERNET + POST_NOTIFICATIONS · `<queries>` = 11 catalog + vending · 10 activities + listener service · zip OK · 21 entries · no .idsig · signer cert unchanged · new-code sentinels in dex ("standing style instruction", "Really delete ", "real person texting", "Private mode") · StdLogger confirmed ABSENT from dex · release logged in RELEASES.md |

## 6. On-device smoke suggestions (owner)

1. Settings → My voice → type a custom instruction (e.g. "I never use full stops") → back → generate a reply → audit should show "global custom instruction applied" in **Why it sounded this way**.
2. Contact → edit → new "AI & PRIVACY" section: turn **Private mode** on → AI replies row flips to "off — private mode is on", learning section says learning off; ✨ Generate in that chat refuses with a clear message. Turn it back off.
3. Contact → edit → **Memory for this contact** off → learning section collapses with the reason; on again → stats/controls return.
4. Contact → edit → **Delete contact** → two-tap confirm → home list no longer shows them; their drafts/signals are cascade-wiped.
5. Generate replies for a real chat → noticeably less "assistant-y" (no greetings/sign-offs, shorter, matches your texting habits).

**Audit build complete — stopped per process, awaiting owner approval.**
