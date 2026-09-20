# P20 Device-Proof Procedure — ReplyMate 1.7.1 (vc918)

**Scope:** executable on-device proof for the P-intelligence-20 rows. **This document changes no behavior and no verdicts.** All P20 rows remain **OPEN** until the owner executes them on the real phone and returns the evidence in the format below.

**Unit under test (must be this exact build — nothing else counts):**
- File: `ReplyMate-1.7.1-final-proof.apk` (CI artifact **10565309912**, run **35392806603**, commit **3994644**)
- **sha256 = `afa5a2474c1bc84e91cc109343d1a50c54b8cbc89863c8c3291b28a84af908e1`**, size **678,218 bytes**, versionName **1.7.1-final-proof**, versionCode **918**
- Verify before starting: after installing, check package info (adb: `adb shell dumpsys package com.replymate.app | grep versionCode` → `918`; compare the downloaded APK's sha256 on the PC: `sha256sum ReplyMate-1.7.1-final-proof.apk`). If the APK on the phone differs from this hash, STOP — the run is void.

**Hardware/setup prerequisites (all rows):**
1. The real Oppo (ColorOS) phone, ReplyMate installed from the APK above, NOT from an older proof build.
2. On the phone: Discord installed + logged in; at least one other messaging app ReplyMate watches (WhatsApp/Telegram) for non-Discord rows.
3. **Second device or second accounts** that can send you messages: Discord alt account(s) **Ada** and **Bim** (any two real non-owner accounts) with access to one shared server/channel `#general`; a WhatsApp/Telegram contact (or group with 2+ people) for non-Discord rows.
4. PC with `adb` if available (needed for P20-7/P20-8 convenience and logcat capture; the UI-only fallback is documented per row). No root needed.
5. ReplyMate configured: **Settings → AI providers** = one working provider (OpenRouter/other key present); **Settings → My profile**: name set to the owner's real display name **Spencer** (mention detection depends on the exact name token); **Settings → Notification sources**: Discord = watching; **Settings → Message listening**: Background reply assistant = ON **and shows "Background replies on — Ready ✓"** (complete P20-6 flow first if not).
6. Group policy: groups enabled for the test contacts (Group "enable groups for this contact" if the app asks).
7. Battery saver **off** during the run unless a row says otherwise.
8. A way to timestamp evidence: screen-record (ColorOS recorder) OR a second phone filming; note the real clock time at each step. Screenshots must include the shade clock or system time.

**Evidence capture standard (applies to every row):**
- Capture (a) the app screen showing the observable result, (b) **Settings → Diagnostics** text before and after the action (screenshot of the whole diagnostics panel), (c) the conversation screen in ReplyMate, (d) where delivery is involved, the chat app (Discord/WhatsApp) showing where the reply actually landed.
- Diagnostics lines that matter (exact labels):
  - `listener bound right now: true/false`
  - `connected at:` / `disconnected at:`
  - `last background pipeline: queue Xs · generate Ys · message→alert Zs · <contact> · <time>`
  - `ColorOS background switch (unreadable by Android): …`
  - ring/event lines, e.g. `listener (re)connected · reconciled N active notif(s)`, `learned · <name> · …`
- **Settings → Usage dashboard** provider-call counters: record BEFORE and AFTER for any row whose point is "no provider call" or "exactly one generation".
- When filling the result block, every field gets real content or `none recorded` — never "worked / verified / looks correct".

**Result block (copy per row):**
```
P20-X
Setup:
Input/action:
Observed:
Relevant diagnostics:
Generated result:
Target/delivery:
Evidence:
Verdict:   (PASS / FAIL / OPEN)
Reason:
```

---

## Execution order (safest → highest value)

Execute in this order; each early row de-risks later ones:

`P20-6 → P20-7 → P20-1 → P20-2 → P20-4 → P20-3 → P20-5 → [C-6 owner-active cross-check] → P20-8 → P20-9 → P20-12 → P20-13 → P20-10 → P20-11 → P20-14`

---

## P20-6 — ColorOS readiness (RUN FIRST; everything depends on it)

1. **Preconditions:** fresh install or fresh state on the Oppo; ReplyMate just installed; nothing granted yet.
2. **Setup:** open ReplyMate → Settings → **Message listening** → toggle **Background reply assistant** ON.
3. **Action:** follow every screen the app opens, in the exact order it opens them — do not exit to launcher mid-flow.
4. **Sequence to expect:** notification-access system page (enable ReplyMate) → Android 13+ notifications prompt (allow ReplyMate's own notifications) → battery-optimization approval (allow/"Don't optimize") → ColorOS **Battery usage** page for ReplyMate where you must set **"Allow background activity"** (the dialog explains this because Android cannot read that switch) → the neutral confirm **"I've enabled it ✓"**. Then leave Settings with recents-swipe and re-enter ReplyMate: the Ready subtitle must be re-derived from live state, not remembered.
5. **Must display/do:** only after the whole chain and the confirm step: subtitle **"Background replies on — Ready ✓"**. Then **Settings → Diagnostics** shows `listener bound right now: true` with a `connected at:` timestamp from a real bind (not a stale one), and `ColorOS background switch (unreadable by Android): owner-confirmed ON ✓ …`.
6. **Capture:** screen-record the whole flow (each system screen + the confirm dialog); shot of the subtitle; diagnostics shot with the three lines named above.
7. **PASS criteria:** all screens actually opened in order; Ready ✓ appeared only after the confirm step; diagnostics shows `listener bound right now: true` + matching `connected at:`; after a leave/return the subtitle still derives from live state (test by revoking one lever mid-check if you want the negative case).
8. **FAIL criteria:** Ready ✓ shown while any lever is genuinely missing (prove with the locked-out state per lever); the flow didn't open the real system screens; `listener bound right now: false` while the subtitle claims Ready (that pairing = defect, record both).
9. **Proving line(s):** subtitle text + `listener bound right now:` + `connected at:` + the ColorOS-switch line together.
10. **Do NOT infer:** "Notification access granted" from the system settings page is NOT evidence the listener is bound. The ColorOS background switch is **not readable by Android** — do not claim it was machine-verified; the owner-confirm step is the honest mechanism by design.

**Result block:** fill P20-6.

---

## P20-7 — Listener rebind recovery (RUN SECOND — needs P20-6 green)

1. **Preconditions:** P20-6 PASS. Background assistant ON, Ready ✓, `listener bound right now: true`.
2. **Setup:** note current `connected at:` from Diagnostics.
3. **Action:** kill the listener's trace without revoking the setting — from recents, swipe ReplyMate away (process kill). Optional/stronger with adb: `adb shell cmd notification disallow_listener com.replymate.app/com.replymate.app.listener.RmNotificationListener` then `adb shell cmd notification allow_listener com.replymate.app/com.replymate.app.listener.RmNotificationListener` and/or `adb logcat -s NLS` to capture `listener disconnected` / `listener connected`. If ColorOS needs it, relaunch ReplyMate after the kill (some ColorOS builds need the app re-opened to let the system rebind — that is itself relevant evidence, record it).
4. **Message sequence:** AFTER the kill+bind, have Ada send **one new WhatsApp (or Discord) message** to the owner. Do NOT reuse a notification that existed before the kill.
5. **Must display/do:** Diagnostics `listener bound right now: true` and **`connected at:` newer than the kill** (a stale timestamp = no real rebind proof). The new message appears in ReplyMate as a captured/stored message and the assistant schedule fires (an AI draft card appears within the normal debounce window OR with the sweep delay — note which).
6. **Capture:** before/after diagnostics shots; logcat NLS lines if adb; the conversation screen showing the new message; the alert card "Reply to Ada in …".
7. **PASS criteria:** a genuinely new post-rebind notification is captured (its store/ping event lands) AND the bound flag flips to true with a fresh `connected at:`. If an extra step (reopen, toggle OFF/ON) was required on ColorOS, that behavior must be written into the result block verbatim.
8. **FAIL criteria:** access stays enabled (system secure-settings string) but notifications after the rebind are silently NOT captured, with no honest signal anywhere; OR the app claims Ready while `bound right now` is false.
9. **Proving line(s):** `listener (re)connected · reconciled …` ring line, fresh `connected at:`, store/ping event for the post-rebind message.
10. **Do NOT infer:** `getActiveNotifications` reconciles only notifications still active at rebind time — a message posted while the listener was dead and dismissed before rebind cannot be "recovered" and must not be claimed as recovered. Access-enabled ≠ recovered.

**Result block:** fill P20-7.

---

## P20-1 — Discord attribution / burst understanding

1. **Preconditions:** P20-6/7 PASS; Discord watched; groups enabled for the Discord channel contact in ReplyMate; the channel `#general` contact visible in ReplyMate home (if not, one prior captured message creates it; a played-in single message first is fine and resets cleanly anyway).
2. **Setup:** Discord alt accounts **Ada** and **Bim**, both in `#general`, phone unlocked-during (or screen-off is fine once Ready ✓ — record which).
3. **Sequence:** within ~10 seconds, Ada sends 2 messages (one of them a question), Bim sends 1 reply-ish message. E.g. Ada: `standup moved to 10:30` / `anyone got the figma link?`; Bim: `on it, one sec`.
4. **Must display/do:** ReplyMate conversation for `#general` shows **three lines under Ada, Ada, Bim respectively — never one line attributed to "#general"**. The conversation's participants/peers reflect Ada and Bim (not the channel as a member). ONE aggregate ping (not 3 separate alerts). No `[announcement]` style marking anywhere on these.
5. **Capture:** the conversation screen (full thread with per-sender attribution visible); diagnostics ring excerpt; the ping/alert card.
6. **PASS criteria:** exact 3-line attribution to the real senders; single ping; participants learned = Ada & Bim (confirm in whatever the conversation header/peers view shows); ping card names the conversation.
7. **FAIL criteria:** any line owned by `#general`; the channel appearing as a participant/member; multiple pings for the burst; any `[announcement]` demote on normal group traffic.
8. **Proving line(s):** the stored thread itself (attribution) + ring/store events; alert card title "Reply to <triggering sender> in #general".
9. **Do NOT infer:** do not count a run where all three messages came from one account (that proves nothing about multi-sender attribution). Do not credit "historic" messages shown in later screens if the three target lines are wrong.

**Result block:** fill P20-1.

---

## P20-2 — Discord targeted draft (owner addressed)

1. **Preconditions:** P20-1 done in the same `#general` conversation (uses its context).
2. **Setup:** owner name in **Settings → My profile = Spencer** (token must match what Ada types).
3. **Sequence:** Ada sends `@Spencer can you push the build tonight?` (or `@spencer` exactly as your profile token reads). Wait for the debounce (~5–8 s) + generation.
4. **Must display/do:** an alert card **"Reply to Ada in #general"** (targets Ada, NOT the channel, NOT Bim). Conversation thread keeps Ada→Ada→Bim→Ada order. "Why this reply?" expansion explains the engagement (mention/addressed). Generated draft answers Ada's question.
5. **Capture:** alert card, conversation thread, expanded "Why this reply?", the AI draft text, diagnostics (`last background pipeline:` line appears with `queue 0s · generate ~Ns · message→alert ~Ns`).
6. **PASS criteria:** card names **Ada**; draft responds to the push-the-build question; context ordering intact; no message attributed to the channel; the pipeline line exists and its three numbers are separately populated (P20-9 will stress them).
7. **FAIL criteria:** card/draft targets Bim or the channel; draft answers a prior/borrowed message; understanding says "announcement".
8. **Proving line(s):** alert title text; why-line; the draft content itself mapped to Ada's question.
9. **Do NOT infer:** a plausible-sounding draft without the visible target = fail. Mentions must come from the owner profile token — if you typed a mismatch ("@Spunky"), that's a setup failure, not a defect; redo.

**Result block:** fill P20-2.

---

## P20-4 — Discord announcement honesty (RUN BEFORE P20-3 — free of provider cost)

1. **Preconditions:** any Discord channel you can post to that can emit a notification WITHOUT a free-form Reply action and WITHOUT MessagingStyle message history — practically: post from a Discord account into a channel where the phone receives it but the shade notification offers **no Reply action** (e.g., Discord noise/bot/system-style post; if you cannot produce one, use a Slack single-shape equivalent or state the exact notification style you used). Goal: a REAL notification the platform identifies as non-replyable, not an imitation.
2. **Setup:** record **Settings → Usage dashboard** counters (or provider-console spend) BEFORE.
3. **Sequence:** have the announcement shape posted; note shade: it must genuinely lack the Reply action (if the shade shows Reply: redo, the shape wasn't non-replyable).
4. **Must display/do:** ReplyMate stores it as announcement-class (store-only, no alert for a draft). Opening the conversation and tapping **Generate** yields an honest explanation containing **"This message can't be replied to from ReplyMate — …"** — no draft, no provider call.
5. **Capture:** shade shot (no Reply action), ReplyMate conversation row markings, the Generate explanation, Usage counters AFTER (unchanged compared to BEFORE proves no paid call).
6. **PASS criteria:** classification [announcement]-equivalent, zero alert, zero generated draft, provider-call counter unchanged.
7. **FAIL criteria:** any draft generated, any provider call (counter moved), or a reply target invented ("normal message" treatment).
8. **Proving line(s):** classifier stamp in conversation UI + Usage counter delta = 0 + the honest explanation string.
9. **Do NOT infer:** "no alert arrived" alone is NOT the row — only counter-static + the honest Generate explanation close it. Two DIFFERENT-independent signals required.

**Result block:** fill P20-4.

---

## P20-3 — Discord delivery (approve the P20-2 draft)

1. **Preconditions:** P20-2's alert card exists, still pending approval.
2. **Setup:** open Discord on the phone (or your PC) positioned in `#general` BEFORE tapping Approve.
3. **Sequence:** in ReplyMate, from the alert card (or conversation → pending draft chip "● AI draft — waiting for your approval") tap **Approve/Send**. Keep Discord in view.
4. **Must display/do:** the reply lands in Discord as a **reply to Ada's exact message** (Discord's reply-quote ribbon points at the build question). ReplyMate's conversation chip flips to **"✓ sent through quick-reply"**; delivery lines name the conversation (`#general`'s own target — LIVE_ORIGINAL, or the documented honest fallback REBIND_REPOST/CACHED_TOKEN with its own unique identity — never a borrow).
5. **Capture:** Discord thread shot with the reply ribbon anchored to Ada's message; ReplyMate chip state; diagnostics/ring delivery line; if it fails, the honest-failure text (e.g. "…never offered a quick-reply box") — an honest failure is a recorded FAIL/analyzed outcome, not a hidden swap.
6. **PASS criteria:** native reply anchored to the correct message/conversation; no cross-conversation borrow; ReplyMate's recorded delivery tier matches what Discord shows.
7. **FAIL criteria:** text arrives as a plain message w/o the native reply linkage when the shade's own Reply action existed at approve time (that would be a delivery-tier regression); text lands in a different conversation; any "borrowed" target indicators.
8. **Proving line(s):** Discord reply ribbon + "✓ sent through quick-reply" chip + the delivery-tier line in diagnostics.
9. **Do NOT infer:** "text appeared in the channel" ≠ delivery proof — the native reply anchor is the evidence. A send that lands 20+ min later after you opened Discord manually is a different tier; record the actual tier, don't upgrade it.

**Result block:** fill P20-3.

---

## P20-5 — WAIT → chime-in (intentional timing)

1. **Preconditions:** another group (WhatsApp/Telegram/Discord) watched with background assistant ON; a helper (Ada) available to send on a schedule. Fresh topic (no prior same-topic burst in the last minutes).
2. **Setup:** clear/idle the conversation for ReplyMate (existing conversation is fine if the content is new).
3. **Sequence:** (t=0:00) Ada: `sunday still on?` → **nobody answers** → observe ~2 min. Then (t≈2:05) Ada: `nvm, saw the calendar invite`.
4. **Must display/do:** at t=0–~90 s: **no draft alert** (WAIT — the room was asked; a why-line/ring or conversation state communicates the wait). On the second message: exactly ONE generation covering both lines (draft should read the "nvm" — i.e., NOT generate an answer to "sunday still on?" as if still open; it may reasonably choose silence/no-answer with a visible reason instead). Exact behavior accepted: (a) no premature draft before the second message; (b) at most one post-context draft reflecting BOTH messages, or a visible why-line explaining silence/no-reply.
5. **Capture:** timestamped thread in ReplyMate; alert (or its absence) at each phase noted with clock times; the why-line text; `last background pipeline:` line for the generation that did/didn't happen.
6. **PASS criteria:** no alert between t≈0 and the second message; after it, one coherent treatment of the two-message exchange (draft or explicit silence reason); a WAIT re-check happened once, not repeatedly.
7. **FAIL criteria:** a draft answering "sunday still on?" fires before the second message; two separate drafts for the exchange; the second message drops entirely (never stored / never reflected).
8. **Proving line(s):** thread timestamps vs alert timestamps (screenshots), why-line/ring wait notation, ONE pipeline line (or none + visible silence reason).
9. **Do NOT infer:** "no notification arrived" from Doze or listener downtime is NOT a WAIT pass — the listener must have captured the first message (visible in the thread) while withholding generation. Prove capture-then-wait, not silence-then-nothing.

**Result block:** fill P20-5.

---

## Cross-checklist row (committed checklist P20-6): owner-active → reply-required

The committed checklist keeps one extra row between the owner's WAIT and ColorOS rows; execute it here (between P20-5 and P20-8):

1. **Setup:** in the same group as P20-5; FIRST send one short message **as the owner** from your own account in that chat app (e.g. reply `sweet` to Ada), then within ~30 s have Ada ask: `you bringing the cables tomorrow?`
2. **Must display/do:** engagement = reply-required with the visible reason **"they asked right after your message — likely addressed to you"** (why-line); alert card naming the reply target ("Reply to Ada in `<group>`"); a draft that speaks for the owner to Ada.
3. **PASS criteria:** reason string exactly about your prior message; target = Ada.
4. **FAIL criteria:** treated as broadcast/ignore; target wrong; no reason shown.
5. **Do NOT infer:** doesn't pass from P20-2's mention — this row is about OWNER-RECENCY without an @-mention.

**Result block:** fill as "Checklist-P20-6 (owner-active)".

---

## P20-8 — Sweep vs live queue ordering

1. **Preconditions:** adb on the PC (strongly preferred); P20-6 green. Plan: generate catch-up backlog, then one live message, and capture queue timings.
2. **Setup:** create genuine inboxes: send yourself, while the phone is OFFLINE to ReplyMate (airplane mode ON for ~10 min, or ReplyMate killed + battery-saver to hold the sweep), **≥6 messages across ≥3–4 conversations** (WhatsApp contacts + Discord). Record send clock times.
3. **Sequence:** (t=0) airplane OFF (and/or reopen ReplyMate if killed) → the listener-(re)connect/sweep path starts recovering (watch diagnostics; the `listener (re)connected · reconciled N…` ring line fires). The MOMENT recovery begins (within ~10 s), have Ada send a **new** live message in a fresh conversation (`live check — queue priority`). Then don't touch anything for ~2 min.
4. **Must display/do:** the **live conversation's draft completes first**; its `last background pipeline:` line (record whenever it becomes its turn: pipeline line is single-slot-last, so capture it right after the live draft lands, then again when the backlog finishes) shows **`queue` near the scheduled-debounce residue (~0–2 s of lane wait)**, NOT tens of seconds; the catch-up drafts complete later in send order among themselves; per contact, exactly one draft each (never two concurrent nor two drafts per contact).
5. **Capture:** diagnostics shots at the moment the live draft lands (`queue Xs…` line visible), then final shot after the backlog completes; ReplyMate home/conversation list times; optionally `adb logcat` for scheduling evidence.
6. **PASS criteria:** live draft lands before the queued backlog completes; live `queue` ≈ only the debounce remainder (its delay is not job count × backlog time); catch-up completes in FIFO-ish order; one-draft-per-contact.
7. **FAIL criteria:** the live conversation waits behind the full backlog (queue ≈ dozens of seconds while lanes idle); a contact gets two parallel drafts; catch-up never completes.
8. **Proving line(s):** the live conversation's `queue/g…/…` line vs. backlog completion times; `reconciled N` ring line; per-contact single draft.
9. **Do NOT infer:** "everything eventually drafted" is NOT an ordering pass — queue numbers and completion order are the evidence. Receiving the live message BEFORE recovery started (wrong sequence) is a setup failure — the live send must fire after the sweep has queued.

**Result block:** fill P20-8.

---

## P20-9 — Slow-provider latency split

1. **Preconditions:** a way to make the provider genuinely slow: (a) switch to a provider/endpoint known-slow, or (b) congest the network (throttled hotspot / airplane-then-2G-style), or (c) if none available, use heavy concurrent rows: run this DURING P20-8's backlog so one conversation's provider crawl is running when another needs a draft. Record which mechanism you used.
2. **Setup:** record Usage dashboard BEFORE. Trigger drafts for two conversations (A = slow path, you engineer the slowness; B = normal).
3. **Sequence:** initiate A's draft under slowness; while A is crawling, B needs a draft; wait for both. Re-check **Settings → Diagnostics → `last background pipeline: queue Xs · generate Ys · message→alert Zs · <who> · <time>`** the moment each completes (single slot: capture twice).
4. **Must display/do:** A's line: large `generate` (provider dominated), small `queue`; B's line: small `queue` (B was NOT serialized behind A's provider crawl); if any wait exceeded 20 s queue or 90 s total, a named slow-queue/slow-pipeline diag line appears in the ring with numbers.
5. **Capture:** both pipeline lines (two shots at the right moments), diag ring excerpt, Usage counters delta = 2.
6. **PASS criteria:** three numbers separately present and consistent with the induced condition; B unblocked by A; slow lines (when thresholds tripped) name the cause honestly.
7. **FAIL criteria:** the split collapses (e.g., `queue 0s · generate 0s · message→alert 120s` implies unmeasured gap = instrumentation defect); B waited ≈ A's generate time (serialization defect); no diag line despite >90 s total.
8. **Proving line(s):** the two verbatim pipeline lines + ring entries.
9. **Do NOT infer:** do NOT call provider time "queue time". Do NOT pass from one line alone — you need the pair, taken at the right moments.

**Result block:** fill P20-9.

---

## P20-12 — +Them (manual missed-message context)

1. **Preconditions:** any watched conversation with real recent context (Discord `#general` from P20-1 is ideal).
2. **Setup:** pick a **fact-bearing** line that never passed through ReplyMate's listener (type it nowhere in Discord): e.g. Bim said by voice/DM outside this flow: `booking moved to Friday`. Note the exact text + sender.
3. **Sequence:** ReplyMate → conversation `#general` → **"+ Them"** (dialog title mentions missed incoming messages) → pick participant **Bim** → enter the exact text → confirm toast **"Added as their message (by you, not captured)."** Then ask/generate: have Ada ask `so when was it again?` and generate.
4. **Must display/do:** the added line renders in the thread under **Bim** with the provenance suffix **"· added by you (not captured)"**; the thread/topic/why-line treats it as real incoming context; the generated answer uses **Friday** attributed to Bim's added line (not to the owner, not invented).
5. **Capture:** the +Them dialog, the rendered line with suffix, the why-line/understanding view, the generated result.
6. **PASS criteria:** correct participant attribution; provenance suffix visible; the fact drives the next generation correctly; no fabricated notification metadata anywhere (the line never pretends to have come from the shade).
7. **FAIL criteria:** attributed to owner or to Ada; enters as OUTGOING; the added line missing from understanding; fabricated reply-target metadata attached to it.
8. **Proving line(s):** the rendered line text + suffix + the generated sentence citing Friday.
9. **Do NOT infer:** do not pass from "thread contains the text" — the participant chip + provenance suffix + downstream generation must all be right.

**Result block:** fill P20-12.

---

## P20-13 — Learning isolation (pairwise)

1. **Preconditions:** two distinct ReplyMate contacts/groups with open drafts possible (e.g. WhatsApp contact **Adele** (A) and WhatsApp contact **Bola** (B)); both will generate.
2. **Setup:** pick a distinctive nonce-style A-signature: slang phrase like `say less chief` — something never previously used anywhere in either conversation.
3. **Sequence:** (a) A-conversation: get a draft, EDIT it to add `say less chief`, Approve/send through ReplyMate (or send the same text manually in the chat app so the manual-send learner catches it). Watch diagnostics for a learn event, e.g. ring `learned · Adele · …`. Wait a beat for the learn to persist. (b) B-conversation: have B send a fresh message → generate a B draft + regenerate once. (c) A-conversation again: one more draft.
4. **Must display/do:** A's later drafts may adopt the learned hint (that's correct learning); **B's drafts must contain none of A's phrase or signature habits**; B stays on B's own (or default/global) style.
5. **Capture:** the exact edited/sent A text, the `learned ·` ring line, B's draft(s) full text, A's follow-up draft; contact-style views if surfaced in ContactEdit ("Tone with them") as supporting evidence.
6. **PASS criteria:** learn event recorded for A; B's output fully free of `say less chief` and A's pattern; A shows the habit.
7. **FAIL criteria:** any A-learned artifact present in B's draft (cross-contact leak = real defect); no learn event despite qualifying approval/edit.
8. **Proving line(s):** ring `learned · A · …` + the two drafted texts side by side.
9. **Do NOT infer:** "different sounding" ≠ isolation — only the absence/presence of the specific taught artifact discriminates.

**Result block:** fill P20-13.

---

## P20-10 — Voice controls (global + contact + OFF)

1. **Preconditions:** B-conversation and A-conversation from P20-13 (learned artifacts won't collide: use NEW dials for this row).
2. **Setup:** three-arm configuration: **Global**: Settings → **My voice (global style)**: set **Emoji = OFF** (explicitly Off, not "natural") and Formality = High (any clearly observable non-default). **Contact A (Adele)**: ContactEdit → set contact-level controls that visibly contradict global for one dial (e.g. allow emoji ON / casual tone) where the screen offers it. **Contact C (a third untouched contact)**: nothing set.
3. **Sequence:** trigger a draft each in A, C. Also open **My voice → "Preview replies with this voice"** to capture the wire description **"The AI is told:"** (screenshot — it shows exactly which instructions the pipeline will send; with Emoji OFF that line must contain NO emoji instruction).
4. **Must display/do:** C's draft: formal, **zero emoji**, matches global. A's draft: the **contact override wins** on the overridden dial(s); emoji behavior follows A's setting not the global OFF. Across several drafts, OFF = **absent entirely** (C never gets a single emoji).
5. **Capture:** the "The AI is told:" shot + the three conversations' drafts + the style screens' states.
6. **PASS criteria:** OFF truly absent (0 occurrences over ≥3 C-drafts); contact-level beats global where configured; A's settings don't leak into C (recheck C after A).
7. **FAIL criteria:** any emoji in C's output after OFF; A behaving like global on the overridden dial; C changing after A's settings changed (unless global itself changed).
8. **Proving line(s):** "The AI is told:" text + the drafts themselves + the settings states.
9. **Do NOT infer:** UI showing the toggle set is not output proof. "Sounds more formal" as a sole testimony is not proof — the OFF state check must be objective (0 occurrences); subjective dials need a quote-worthiness note.

**Result block:** fill P20-10.

---

## P20-11 — Follow-up OFF by default + never auto-send

1. **Preconditions:** a conversation with a fresh approved reply still pending an answer from the other side (from P20-13B or new).
2. **Setup:** ContactEdit for that contact: check **"Follow-ups"** toggle state — **record it must be OFF for a never-configured contact** (default). Keep the phone idle.
3. **Sequence:** (a) leave OFF: approve/send one normal reply → wait 15+ min with NO response from the contact. (b) Then explicitly enable **Follow-ups** in ContactEdit → approve one more reply → wait → when a follow-up exists, ReplyMate may surface only **one suggestion** (notification/card titled **"Follow-up idea for <name>"**, or the conversation's "Follow-up — bump my unanswered message" affordance) — it is a **draft**, and nothing is in the chat app yet. (c) Do NOT approve the follow-up → confirm it never appears in the chat app after another 15 min.
4. **Must display/do:** with OFF default: **zero** follow-up suggestion artifacts of any kind for that contact (Usage counters also static). With ON + approved reply: exactly ONE follow-up **draft** artifact, quoting/anchored to the approved reply; chat app stays empty until YOU approve.
5. **Capture:** ContactEdit toggle states (before/after), the chat app's empty thread after OFF window, the "Follow-up idea for …" card with its quoted anchor, the still-empty thread after unapproved wait, Usage counter deltas.
6. **PASS criteria:** OFF = total absence including no provider call; ON = one suggestion, anchored, no autonomous send under any wait.
7. **FAIL criteria:** any follow-up notification/draft while OFF; anything arriving in the chat app without your Approve; follow-up chaining (a second suggestion after the first).
8. **Proving line(s):** toggle screenshots + empty-chat-app screenshots + the single anchored card (when ON).
9. **Do NOT infer:** no notification ≠ OFF-correct if a draft was still generated or sent-check skipped — Usage counters must confirm no paid call; unapproved-15-min empty thread confirms no auto-send.

**Result block:** fill P20-11.

---

## P20-14 — Persistence / restart honesty (RUN LAST)

1. **Preconditions:** P20-6 green and several rows above completed (so real state exists: style settings, About Them notes, learned facts, provider keys, conversation states).
2. **Setup — snapshot BEFORE:** screenshots/notes of: Settings → My voice (dial states), My profile, AI providers (key tail visible), one ContactEdit (About Them + Follow-ups state), one conversation thread (topic/participants/why-line), Settings → Message listening (Ready ✓), Diagnostics full panel (all lines incl. `connected at:`), Usage dashboard counters.
3. **Sequence:** close ReplyMate (recents-swipe) → **reboot the phone** → unlock → wait 30 s → open ReplyMate → re-take the identical shots. Do not uninstall/reinstall, do not clear data.
4. **Must display/do:** every persistent item equals the snapshot: dials, profile, provider keys, About Them, follow-up toggle, learned style, conversation threads; Message listening still Ready ✓ (or honestly names a lever that genuinely degraded with reboot, e.g. ColorOS re-restricting background — if so that is device evidence, not necessarily a defect). Diagnostics `connect at:` is allowed to change ONLY by an actually fresher bind after boot; `listener bound right now:` must reflect LIVE truth after boot (if the system hasn't rebound yet at that second, it may read false — the honest behavior; re-check after one minute).
5. **Capture:** before/after screenshot pairs for every listed screen.
6. **PASS criteria:** no persistent loss/regression; runtime-only fields re-derived (no frozen pre-boot `connected at:` sold as live); Ready verdict re-evaluated from post-boot state.
7. **FAIL criteria:** any persistent setting/memory reset without user action; readiness claimed Ready while a post-boot lever is visibly missing; stale timestamps presented as current state.
8. **Proving line(s):** the paired shots + diagnostics deltas.
9. **Do NOT infer:** does not pass from reopening without reboot, nor from "the app still opens".

**Result block:** fill P20-14.

---

## Appendix A — quick failure triage (what to send back on any FAIL)

1. The filled result block for the failing row (verbatim).
2. The two Diagnostics shots (before/after) + the failing screen.
3. Exact device/ColorOS version (Settings → About phone).
4. Whether the unit was the exact proof APK (hash check line).
5. Do NOT attempt your own conclusion about the root cause beyond the observed facts — the reconcile phase isolates and reproduces in-gate first.

## Appendix B — honesty reminders carried into every row

- `listener bound right now:` (live service handle) is the ONLY binding proof; notification-access being enabled is not.
- `getActiveNotifications()` can only recover still-active notifications — never historical ones.
- The ColorOS "Allow background activity" switch cannot be read by Android; the owner-confirm step is the designed mechanism — do not claim machine-verification of it.
- Delivery tier (LIVE_ORIGINAL / REBIND_REPOST / CACHED_TOKEN / HONEST_FAIL) must be recorded as-observed; an honest fail + named tier outranks a silent success claim.
- Approval is mandatory everywhere; nothing auto-sends.
