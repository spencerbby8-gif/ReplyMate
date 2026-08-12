# P-background-8 — device verdict checklist (1.5.8 / vc39)

APK: `releases/ReplyMate-1.5.8.apk` · size + sha256 are posted with the release
message (spotty-network rule). In-place update over 1.5.7 (same signing cert
`b15f2f37…c6a85ed`) — all data kept. §1 should also be done once on a clean
install, because §1 is the exact "background generation after install" bullet.

Each item: what to do → what PROVES it worked. Reply per item: PASS / FAIL + what you saw.

---

## 0. The re-audit (what was proven in code before anything was fixed)

The full background path was re-traced line by line on 1.5.7: notification →
listener bind → classification → debounced schedule → DraftService → alert →
catch-up sweep. The "automatic research" additions (1.5.6) were verified
payload-by-payload against the CURRENT official docs (DeepSeek `thinking`/
`reasoning_effort`, OpenRouter `openrouter:web_search` server tool, Moonshot
`$web_search` + thinking rule, Anthropic thinking + `web_search_20250305`,
Gemini `google_search` + thinking configs). Three REAL breaks were found, all
three invisible to the old suites:

1. **Reasoning starved the reply budget (the device-kill).** When automatic
   reasoning decided LOW/HIGH (any busy burst, ambiguous chat, several '?'),
   thinking controls were attached to the **chat-tuned 220-token output cap** —
   and every provider counts thinking INSIDE that cap. Proven arithmetic on the
   shipped 1.5.7 code: `gemini-2.5 LOW → maxOutputTokens=220 with
   thinkingBudget=512` (thinking EXCEEDS the cap → the reply comes back
   empty/MAX_TOKENS → `GeminiParser` err → **no draft at all**). Same starvation
   shape for DeepSeek (`thinking enabled` + `max_tokens=220`; official docs:
   reasoning shares the allowance), Kimi thinking, OpenRouter reasoning effort
   and Mistral effort. Anthropic and OpenAI-Responses already added headroom —
   now every dialect does (cap = reply + thinking budget).
2. **A slow external lookup could park the draft thread.** The encyclopedia
   fallback (providers without native search) ran on the shared provider
   timeouts (15s connect / 45s read, ×2 endpoints) INSIDE the generation — a
   hung/crawling network held the background draft up to ~2 minutes, and two
   such drafts saturate the 2-thread background pool: "background generation is
   broken". Fix: research gets its own tight client (2.5s/4s) + a 9s wall-clock
   TOTAL budget; beyond that it degrades to the honest
   anti-hallucination line, and the answer goes out without invented facts.
3. **A superseded job still paid + saved a stale draft; catch-up could re-alert
   a stale draft.** A job stuck in a slow lookup passed the coalescer check
   BEFORE the lookup and then burned a provider call for a message that was no
   longer newest. Fix: one last staleness gate immediately before the paid call
   (abort ⇒ explicit "superseded" diagnostic, zero calls, zero drafts).
   And the catch-up sweep now re-alerts ONLY a draft whose `inReplyToId` is the
   CURRENT latest message — a newer message triggers a fresh generation instead
   of being silently marked "answered".

Kept intact per the directive: planning depth, live research, long-chat memory,
Prompt Audit, contact customization, provider-aware behavior, the 1.5.7
first-message and noise-filtering work. No new features — repairs only.

---

## 1. Background generation after install (clean install pass)
1. Uninstall ReplyMate → install 1.5.8 clean. Set up, add your provider key,
   allow the notification approval.
2. Close the app. Have someone send ONE WhatsApp message.
   → Within ~10s: "Reply ready" alert; open it — draft is on-topic, in your
   voice. Draft trail shows the why credits.
3. Send a quick burst of 4–5 messages from the same chat while the app is closed.
   → ONE alert update, ONE draft answering the LATEST messages — never one
   alert per message, never a draft answering an older message after a newer
   one arrived.

## 2. Background generation with research enabled
1. Have someone ask a meaning ("wetin be 'odogwu' abeg") or a current-affairs
   question in a real 1:1 chat while the app is closed.
   → Draft STILL arrives (§2 is where the old build stalled/died). Open the
   draft trail: it must say WHICH path answered — provider native search,
   encyclopedia fallback (with ms timing), cache, or the honest
   "found nothing → no invented facts" line. No silent behavior.
2. Ask the SAME thing again within the week.
   → Trail says "answered from the on-device cache" (repeat lookups are free).

## 3. Background generation with reasoning enabled
1. Real busy moment: several questions at once, or a careful disagreement —
   from background.
   → Draft arrives; trail credits "deeper thinking: LOW/HIGH (because …)" as
   audit metadata. The reply itself must be complete — **a cut-off or empty
   draft here is the exact 1.5.6 bug; any FAIL screenshot of this is gold**.
2. Ordinary "lol ok" banter keeps the fast path: no thinking line, same speed
   as before.

## 4. Provider with no search support
(DeepSeek / Mistral / Ollama / custom endpoint)
1. The §2 meaning question on this provider.
   → Draft arrives via the encyclopedia fallback (trail says so), or the honest
   nothing-verified line. It must NEVER fail the draft because search is
   unavailable.

## 5. Slow network
1. Put the phone on a throttled profile (or 2G / weak signal / airplane-with-
   wifi-flapping). Trigger §2's research question from background.
   → The draft may take a little longer but it ARRIVES — bounded to seconds of
   research overhead, never minutes; and when research can't verify, the reply
   leans on the honesty rule (visible in the trail).
2. While one draft is crawling, send a NEWER message in the same chat.
   → Exactly ONE paid generation happens for the newest message; no stale draft
   appears first; Diagnostics records the older job as "superseded".

## 6. First real message after install (1.5.7 regression guard)
1. Fresh install path from §1 again, but this time DENY the notification
   approval when asked.
2. First real message → NO alert (expected), but the draft IS waiting in
   ReplyMate (open the chat). Diagnostics says why honestly.
3. Allow notifications later → the catch-up re-alerts that SAME draft (no
   duplicate generation, no provider cost).

## 7. Non-message noise (1.5.7 regression guard)
From a quiet state: backup/summary cards ("3 new messages"), group/broadcast
chatter, missed-call cards, reaction-only notices, media-only notices.
→ No new chats on Home, no drafts, no "WhatsApp" phantom contact, zero provider
cost. Real 1:1 messages around them keep generating normally.

## 8. Catch-up correctness
1. Alerts denied; two messages arrive minutes apart in one chat → two drafts
   generate silently (the newest replaces the older).
2. THEN allow notifications / save a provider key.
   → The catch-up re-alerts ONLY the draft matching the LATEST message —
   never the older one, never both, and Diagnostics says when it chose a fresh
   generation over a stale re-alert.

## 9. Known/accepted behaviors (unchanged, intentional)
- Provider-billed native search shows the provider's own usage/ sources in the
  trail; free paths (cache/encyclopedia) show zero cost.
- The encyclopedia is weak for minute-live prices/scores — a native-search
  provider is the right pick for those; when nothing verifies, the reply must
  not invent.
- Alerts: ONE audible pop per draft cycle; Regenerate never re-pops.
- Old noise rows created before 1.5.7 stay in history (only new ingest is
  filtered).

---

### Engineering evidence behind this build
- Repro-before-fix: the starved arithmetic executed against the shipped 1.5.7
  payload code (`maxOutputTokens=220` vs `thinkingBudget=512`; `thinking
  enabled` vs `max_tokens=220`).
- JVM gates: **757/757** — incl. new `OutputBudgetTest` (per-dialect thinking
  headroom pins), `RetrievalBudgetTest` (hang/crawl/abandon pins),
  `BackgroundDraftGuardTest` (supersede-during-lookup never pays).
- Device-stack harness: **110/110** — incl. new `BackgroundResearchRoboTest`
  (research/reasoning/no-search-support/slow-lookup/supersede-race/noise/
  stale-catch-up) driven through the real listener→ingest→schedule→provider→
  alert chain.

---

## Gate status — updated 2026-08-12 (P-release-1)

**Engineering verification (reproducible from repo):** JVM gate re-ran from a
fresh clone — **757/757 green**; the build engine is now in-repo (`engine/`) and
the full compile → sign → hash pipeline was proven end-to-end on a disposable
fresh checkout of `main`.

**Retired as a gate:** the "110/110 device-stack" figure above (and similar
counts in older release notes) came from an external harness that was never
committed and no longer exists anywhere. The number stays here as the honest
historical record, but it is **no longer an official gate** — see BLUEPRINT
§9.1 addendum. It was NOT re-verified in P-release-1 and no older APK's device
behavior is re-asserted from it.

**Owner device verdicts — STILL OPEN.** §1–§8 above are real-phone PASS/FAIL
checks and none are filled in. Nothing in engineering verification marks them;
they are not PASS until the owner runs them against `releases/ReplyMate-1.5.8.apk`.
