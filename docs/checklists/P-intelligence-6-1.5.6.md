# P-intelligence-6 — device verdict checklist (1.5.6 / vc37)

APK: `releases/ReplyMate-1.5.6.apk` · 276,810 bytes
sha256: `2bdf7b214f05144315648f7a53fe7bc40798e20e3de7ad180e121d6690fbebb0`
In-place update over 1.5.x (same signing cert `b15f2f37…c6a85ed`). All data kept.

Each item: what to do → what PROVES it worked. Reply per item: PASS / FAIL + what you saw.

What changed this build:
- **Automatic live web intelligence** — no toggle, no Research mode. ReplyMate now
  notices by itself when a message needs live facts (slang/Pidgin/memes it hasn't
  seen, scores, prices, news, release dates) and brings verified facts into the reply:
  the provider's OWN official search tool where it exists (Gemini, OpenAI, OpenRouter,
  Anthropic, Grok, Kimi), or a free encyclopedia lookup (Wikipedia/Wiktionary) BEFORE
  generation where it doesn't (DeepSeek, Mistral, Ollama, custom endpoints).
  Ordinary messages never trigger anything. Lookups cache on-device for 7 days —
  repeats are free and say "cached" in the audit trail.
- **Automatic reasoning depth** — simple messages stay fast; hard or ambiguous
  moments (corrections, disagreements, big bursts, several questions, anything
  search-grounded) get deeper thinking via each provider's OFFICIAL control.
  The thinking itself is never shown or stored — only the level + reason,
  in the trail.
- **Provider page binding** — picking a provider auto-SUGGESTS its official Base
  URL but the field stays fully editable; hand-typed URLs are never overwritten.
  A capability line under the model field says honestly what that provider+model
  can do (search transport, reasoning), and a red warning appears when the model
  doesn't belong to the provider's family. Wrong combos fail with the provider's
  own words at Test/generate time — never a silent switch.
- **Research toggle + static glossary: REMOVED.** Settings → Live context is now
  just the honest device date/time line. All research is automatic.
- **Hard anti-hallucination rule** — unless verified evidence is in the prompt,
  the model must say it doesn't know instead of guessing scores/prices/meanings,
  and never covers a gap with excuses.
- The 1.5.5 context-expiry fix (aura-farming → Arsenal) is included.

---

## 1. Update integrity
Install over the previous build → app opens on the splash/auth you already have;
contacts, messages, drafts, memory, styles, usage history all still there.

## 2. Automatic search — native provider (e.g. Gemini)
1. Have someone send: **"who won the arsenal game last night?"** → generate.
   → Expected: the draft answers with the ACTUAL result (the provider searched).
   Open the draft's trail → shows
   `live search: it asks about something that changes → the provider's native web
   search grounds this reply`.
2. If the provider executed searches, the trail also shows
   `live search ran inside the reply: N web search(es) executed by the provider —
   sources: …` and (where the provider bills them) `model thinking: N reasoning
   tokens billed…`. Sources are public page TITLES only — no private data.
3. Now have them send **"are we still on for dinner at 7"** → generate.
   → Expected: NO `live search` line in the trail at all. Ordinary chat never pays.
4. Have them send **"ur reply ate fr"** (common slang) → generate.
   → Expected: no live-search line, no `Live facts` block — known slang is known.

## 3. Search really influences the reply
Have someone ask about a term you know is NOT everyday English
(e.g. **"wetin be odogwu abeg"** on a fallback provider — DeepSeek/Mistral/Ollama —
or any fresh 2026 slang on a native provider).
→ Expected: the draft uses the REAL meaning (fallback trail shows
`live search: … → looked up just now (free encyclopedia, Xms): … — Wikipedia/Wiktionary`;
native trail shows the sources line). The reply must not invent a fake meaning.

## 4. Cache honesty
Regenerate a reply for the same term within minutes (fallback provider).
→ Expected: trail shows `answered from the on-device cache — repeat looks inside a
week are free` and the prompt line says `cached on-device … may be days old`.
Freshness is never faked.

## 5. Failure honesty
Airplane mode ON → generate on a fallback provider for a lookup-needing message.
→ Expected: the draft still forms, but the trail says
`live lookup failed or found nothing … — answer leans on the anti-hallucination
rule, not on invented facts`. The draft does NOT confidently state the unknown
fact as true (no guessing, no "I got distracted" excuses).
Airplane mode OFF again.

## 6. Automatic reasoning depth
1. A hard moment — several questions at once
   (**"who won the arsenal game last night? and what about city?"**) → generate.
   → Expected: trail shows `deeper thinking: HIGH (because …)` and
   `the model's private reasoning is never shown or stored`.
2. A simple moment (**"lol nice one"**) → generate.
   → Expected: NO `deeper thinking` line — simple stays fast.
3. The thinking itself (chain-of-thought) must NEVER appear in the draft text or
   anywhere in the app — only counts like `reasoning tokens billed`.

## 7. Provider page binding
1. Providers → + (or edit one) → pick **Anthropic**.
   → Expected: the Base URL fills itself with `https://api.anthropic.com` and the
   field is still editable (cursor works, you can type).
2. Type a custom URL (e.g. `https://my-proxy.example.com/v1`) → switch the provider
   picker to **OpenAI** and back → the custom URL stays exactly as typed
   (never silently replaced).
3. Tap the model field → a capability line under it updates per
   provider+model, e.g. `live web search: native (provider's official tool) ·
   reasoning: adjustable`, and for DeepSeek `… encyclopedia fallback (official,
   free) · reasoning: adjustable`.
4. Pick **Gemini** and type an obviously wrong model (`claude-haiku-4.5`).
   → Expected: a red warning that the model doesn't look like it belongs to this
   provider (not a hard block — a warning).
5. Use a wrong API key → tap Test.
   → Expected: a REAL error in the provider's own words (invalid key / 401),
   not a green tick, not a silent model/provider switch.

## 8. Settings hygiene
1. Settings → **Live context (date & time)** — the only "live" row now; its copy
   mentions automatic lookups for scores/prices/slang. The old Research toggle
   is GONE.
2. Generate any reply → the prompt's situation includes the current device date
   & time (device clock, not a hardcoded one).

## 9. Preservation spot-checks (pipeline was rewired — prove nothing broke)
1. **Context-expiry regression:** contact sends "that pic is aura farming fr" →
   generate (draft 1, don't approve) → contact then sends "who won —" (any NEW
   topic) → generate again.
   → Expected: draft 2 answers ONLY the new topic; trail shows
   `1 earlier message(s) already covered by a previous draft — answering only
   what's new`.
2. Tone/length/emoji settings for a contact still visibly change the draft.
3. A second contact's thread/memory/style still never leaks into the first
   contact's drafts.
4. Every send is still human-approved — nothing ever sends by itself.

## 10. Usage dashboard
After items 2–6, Settings → Usage shows search-grounded generations metered as
their own kind (`search`) separate from normal replies — native searches carry
the provider's token counts, free paths (cache/encyclopedia) show zero.

---

## Known honest boundaries (by design, not bugs)
- **Encyclopedia fallback is encyclopedic, not tick-live** — great for meanings,
  people, places, stable facts; a minute-old score may simply miss on
  DeepSeek/Mistral/Ollama. When nothing verifies, the trail says so and the
  model must not invent. (That's why native providers use their OWN search.)
- **Kimi search runs the official echo loop** (max 2 extra wire calls per reply).
- **Ollama cloud search needs a separate hosted key — not wired** (second
  credential + privacy); local models fall back to the encyclopedia.
- **Never stored:** chain-of-thought, search request bodies, other contacts'
  data. Saved per lookup/query: search level, tool counts, source titles, token
  counts — visible in Prompt Audit and Usage.
