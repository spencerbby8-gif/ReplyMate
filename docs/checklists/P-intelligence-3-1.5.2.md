# P-intelligence-3 — 1.5.2 (vc33) owner device verification

Shipped in this build (audit first; every item backed by JVM/robo tests — **584 JVM + 88 robo green**):

## What changed per directive item

**1 Voice & settings UX**
- Every voice dial now has a real **Off** option (tone, confidence, humor, emoji, length, formality, slang, flirting, follow-ups). Off = **no instruction sent at all** for that dial (level-0 "none" stays the active-forbid choice, e.g. emoji→none). Off is credited in Prompt Audit exactly as off.
- Global **My voice** and per-contact voice pages regrouped: FEEL & TONE / REPLY SHAPE / EXPRESSION sections; clearer copy; per-contact page states your setting always beats learned guesses.
- Explicit Off still suppresses learned length/emoji guesses (precedence rule held, test-pinned).
- A deleted **finalized** draft no longer logs a false "rejected" signal (was corrupting learning after a copy).

**2 Intelligence & memory** — rolling summary + 30-turn hot window + pinned facts + profile proven in a dedicated **long-chat** test (90 messages: the beginning survives via summary, raw window stays bounded, isolation across contacts, cold-start honesty).

**3 Learning** — Prompt Audit's feedback line now names provenance: `N approved (x copied as-is, y sent via quick-reply, z manual matches) · edited (manual sends: matched word-for-word / corrected) · …`. Contact learning panel mirrors it. Copied stays folded into approved (DB CHECK predates P3; a schema migration adds no meaning — documented).

**4 Chat & notification UX** — the conversation is now ONE timeline: incoming / your messages / drafts as bubbles with obvious state chips (● waiting for your approval · ✓ approved & copied · ✓ edited, then copied · ✓ sent). Pending drafts keep edit + all actions + tone chips + Why panel. **Edit is inline on the draft alert** (RemoteInput) — type the fix on the banner, it never auto-approves: the card silently re-posts the corrected text still waiting for your Approve. Fallback to the editor screen only where inline input can't be shown. **Manual composer is a real composer (➤ Send)**: saves your words first, then sends straight through the connected app's quick-reply when the target is valid; otherwise keeps the words + copies + honest Copy-and-Open state line. Never a fake Sent.

**5 Background & filtering** — re-verified green: right system screens (focused battery dialog + OEM pages), status/backup/progress/reaction/media-only/groups/calls filtered, dismissal-safe live→reposted→cached→copy send chain, dedupe + shared settle window.

**6 Burst** — re-verified: same-draft refresh while typing, re-arm after send.

**7 Privacy & API-key mode** — detection from the setup, not "a key exists": built-in key ⇒ **free mode forever**; Ollama/localhost/private-network ⇒ **local private**; your own key ⇒ free until you flip **Paid/private plan** on the provider row (providers have no official billing-read API, so your account flag is the honest input — the notice says so). Home banner always shows the detected mode with the honest warning/softened text + tap → AI providers. New providers explain free-by-default; Solo-builder plumbing: `REPLYMATE_BUILTIN_PROVIDER`/`REPLYMATE_BUILTIN_KEY` in dev-secrets ⇒ the release build bundles them (stub restored, never committed); this build bundles **no key** (pure BYOK) because none was provided.

**8 Platform** — Android-only, per SPEC; nothing touched.

## Your device verification (reply per number as usual)

1. My voice → open Tone (or any dial): the picker now shows 4 rows incl. **Off**. Set a couple to Off, preview — replies stay natural on that dial; the "AI is told" preview line drops the dial with no weird gaps.
2. Contact → voice: set **Reply length: Off** AND make 3+ shorter-edits beforehand if possible — the learned "shorter" hint must NOT come back until you clear the override (Prompt Audit shows the suppression note).
3. Prompt Audit on a fresh draft: feedback line names copies / quick-reply sends / manual matches & corrections when you have them.
4. Draft banner: tap **Edit**, type a fix *inside the heads-up card* — text can't be sent yet; the card comes back with your words and Approve still required. (If your shade can't show inline typing, the editor screen opens instead — tell me which happened.)
5. Conversation screen: it now reads like a chat app — drafts are right-side bubbles with the exact state chip; pending ones act like before (edit/Copy/★/Re-gen/Delete/tone/Why). Old copied/sent drafts show as compact receipts.
6. Type in the composer and hit ➤ Send while the WhatsApp target is fresh — it should go straight through (Sent ✓ line). Dismiss the WhatsApp notification first, wait a while, then Send again → either cached send (honest "couldn't watch it land") or the honest "Couldn't send straight to WhatsApp… copied ✓ tap to open" — never a fake Sent and never lost text.
7. Home banner: with your normal provider it says **Free mode** + warning (amber) unless the provider is local or you flipped the plan switch. In Settings → AI providers → your row: flip **Paid/private plan** on → banner goes green "private (paid plan)". Turn it back if your key isn't actually paid.
8. Unchanged-behavior smoke: background assistant still pops once per burst, edits update silently, Regenerate works, no double-pops.

Honest boundaries (unchanged, test-pinned where possible): bots/broadcast lists have no official distinguishing signal (best-effort filters + strict conversation matching); Home/provider-screen rendering of the banner is device-verified this phase (its detection core is unit-pinned); the cached-target send is the only unwatched flavor and it says so every time.