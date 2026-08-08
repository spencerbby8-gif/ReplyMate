# P-background-8 — 1.4.7/vc28 — BLOCKING PRODUCT AUDIT (heads-up · dismissal · burst · learning · customization · directness)

Owner directive: re-audit 1.4.6 against real device behavior before changing anything;
no unrelated features; add regression tests for every confirmed failure; do not move
on until proven on-device.

---

## 1 · ReplyMate notification — CONFIRMED BUGS, FIXED

| Audit finding (code-verified) | Fix in 1.4.7 | Pin |
|---|---|---|
| Channel `rm_assistant` created at `IMPORTANCE_DEFAULT` → heads-up **impossible**; channel importance is immutable after creation, so the owner could never see a pop, ever | New channel `rm_assistant_heads_up` at `IMPORTANCE_HIGH`; legacy muted channel deleted on first run (safe migration) | `AssistantHeadsUpRoboTest.channelIsHighImportanceAndLegacyMutedChannelIsRetired` |
| No message category / priority / visibility set | `CATEGORY_MESSAGE` + `PRIORITY_HIGH` (pre-26 contract) + `VISIBILITY_PUBLIC` | `…draftAlertCarriesReplyMateIdentityMessageClassAndDirectActions` |
| Small icon was the launcher vector = full-bleed blue square → gray blob in the status bar | Dedicated monochrome `ic_stat_replymate` glyph + launcher icon as large icon + brand accent `#0A84FF` | same test (icon res id, color) |
| Actions existed but the card never surfaced them (no pop) | HIGH importance pops with the 3 actions directly attached; draft keeps `BigTextStyle` caption | same test (3 actions, labels) |
| Every regeneration would re-alert if heads-up existed → buzz-spam during bursts | One audible pop per draft cycle: fresh ⇒ cancel+re-post alert; burst updates / Regenerate refresh the SAME card silently (`onlyAlertOnce`); settled cards always silent | `…freshDraftAlertsButSilentUpdatesAndSettledCardsDoNotRePop` |
| Swipe-away had no consequence → dismissed once = never pops again | `deleteIntent` → `ACTION_DISMISS` → clears the per-contact "already popped" flag so the next genuine burst pops again; ledgered | `…swipeDismissalRoutesToTheDismissActionAndReArmsTheAlertFlag` |
| Grouping — audited | **Deliberately no group key**: bundling can mute per-conversation heads-up on several OEM builds; revisit only if shade crowding shows up on-device | design note (this file) |

## 2 · Dismissed WhatsApp notification — approve path hardened

Code audit of the 1.4.6 cached-PI path: the mechanism itself matches the official
contract (`PendingIntent.writePendingIntentOrNullToParcel` → rehydrate →
`RemoteInput.addResultsToIntent` ClipData wire → `pi.send`). What it did NOT do:
if WhatsApp re-posted the same chat under a NEW key (very common on WhatsApp),
approval fell straight to the cache — and a real device offers no ledger telling
WHICH path fired or whether anything landed.

New approve order (all ledgered, distinct flavors):

1. original notification still live (exact sbn key) → send (watched + post-send observation);
2. **dismissed but the SAME conversation is live again under a new key** → strict
   identity match (conversationId > conversationTitle > title, exact, same package)
   → resolve the reply action on the FRESH notification (either surface) → send,
   adopt its geometry, re-cache its PendingIntent;
3. **cached PendingIntent** fires — settled card now says plainly: "Sent ✓ via
   cached target … ReplyMate couldn't watch it land. Check the chat if you want
   to be sure." (Never a bare certain "Sent" for an unwatched path.)
4. honest Copy fallback — draft preserved, no Sent anywhere.

Pins: `approveAfterDismissalSendsThroughRepostedSameConversation` (money test —
real PI fire through the reposted notification, results readable via official
`RemoteInput.getResultsFromIntent`, geometry+PI adopted, approval learned),
`approveWithNoLiveNoCacheNoIdentityCopiesHonestly`,
`conversationMatchIgnoresWrongPackageAndWrongConversation`,
plus the 1.4.6 pins (cache-hit / no-cache) still green.

Honest boundary: only an on-device run proves WhatsApp's acceptance of the CACHED
token for a given build; 1.4.7 makes the live path (which WhatsApp indisputably
accepts — it's the same flow as tapping Reply in the shade) the strongly preferred
one, and refuses to overclaim the cache flavor.

## 3 · Burst — locked + human rules added

Machinery re-verified correct (rolling 5s window + 1.5s settle, one job per
conversation, refresh-in-place under the stable tag, re-arm pinned by
`nextIncomingAfterSendRearmsGeneration`). What was missing the owner asked for:
mid-burst corrections and topic shifts. `burstTask` now also says:
"if they correct themselves mid-burst, the correction wins" · "if the topic shifts
mid-burst, answer the newest topic".

Pins: `BurstTaskTest` now +4 scenario tests (question→correction, topic change,
repeats/filler, rule presence); fragmented messages = existing tail-collection pins.

## 4 · Learning — CONFIRMED GAP, FIXED

Shade actions recorded nothing; only the manual conversation screen fed the engine.

- Approve & send (any flavor) → `APPROVED ("sent-quick-reply")` (draft id attached)
- Copy button → `APPROVED ("copied-as-is")` — identical to the manual screen
- Regenerate button → `REGENERATED ("re-generate")` — identical detail → same counters
- All through `AssistantLearning` (pure helper) → the existing gates
  (private / memory-off / learning off / paused) apply; per-contact stores, no leakage.
- **Decision, logged honestly:** shade swipes are NOT recorded as REJECTED — Android
  fires the same delete-intent on auto-cancel after ANY button tap, so a true swipe
  can't be told apart from an action tap; counting it would inject false rejections.
  True rejections keep coming from the conversation screen's Delete button.

Pins: `AssistantLearningTest` (6 JVM), robo: `regenerateButtonRecordsRegenerated…`,
approval inside the money test; `notificationSignalsDriveTheSameHintEngine` proves
shade signals reach hint derivation (≥4 regen vs approvals ⇒ "vary the wording").

## 5 · Customization — re-audit live + strengthened

- Every control still reaches `ChatRequest.system` (CustomizationEffectTest keeps
  the 18-level pin; green).
- Contact custom instruction reframed as **"This contact's own rules (they override
  the global voice wherever the two clash — follow them exactly)"** — the owner's
  "never call her bro" is now a hard rule, not a soft note. UI label updated to match.
- About Them now leaves audit credits for Prompt Audit: relationship, notes (chars),
  tone note, language preference (`StyleService.contactProfileNotes`).
- Prompt Audit continues to render the why-lines ("Why it sounded this way").

Pins: `contactRulesAreFramedAsOverridingTheGlobalVoice`,
`relationshipTypeDifferentiatesPromptsAcrossContacts`, audit-credit assertions.

## 6 · Directness — added standing rule

System rules now include: "when the honest reply is to disagree, correct <name> or
say no, say it plainly and give the real reason — firm is fine, hostile or insulting
never." Pin: `directnessIsPermittedButHostilityIsForbidden`.

## 7 · Evidence

- 497 unit tests green (was 476; +21), 73 robo green (was 65; +8) — full output in CI logs.
- APK battery: sha256 `421da5e0be0108e77a778d7cb4357a614190642ba971d4b259253775adc09216`,
  223,562 B, cert SHA256 `b15f2f37…85ed` (unchanged → in-place update),
  vc28/1.4.7, minSdk 24 / target 34, launcher `.ui.SplashActivity`, secret scan clean.
- Untouched by design: providers, auth, splash, memory engine, SQLite schema
  (no migration — new persisted fields live in kv JSON only).

## ON-DEVICE checklist (owner)

1. **Heads-up**: get a WhatsApp message → ReplyMate banner should POP over whatever
   app is open (sound once), showing the R glyph + Approve & send / Regenerate /
   Open conversation directly. If it only sits in the shade: system Settings → Apps →
   ReplyMate → Notifications → "Reply assistant" → enable pop-up/heads-up (some OEMs
   ship it off per-channel) — report back which OEM spoke.
2. **Burst**: fire 3+ rapid texts → ONE pop; the card refreshes in place silently;
   final draft answers the burst once (esp. a correction like "no wait, 6").
3. **Dismissal**: clear the WhatsApp notification BEFORE tapping Approve & send →
   the send must still land in WhatsApp (banner says "Sent ✓" or "Sent ✓ via cached
   target"). Then Settings → assistant diagnostics: report the ledger lines
   (look for `RE-POSTED`, `cached`, `unwatched`).
4. **No-target honesty**: approve after a long-cleared notification with no repost →
   "Couldn't quick-send (…) Copied instead ✓" — never a bare Sent.
5. **Second burst**: after approving, a fresh message must pop heads-up AGAIN.
6. **Customization**: set contact rules "never call her bro" + relationship → generate
   → Prompt Audit must show `contact profile applied (…)` + `custom prompt for … applied`;
   the reply must obey the rule.
7. **Learning**: approve 2 sends, press Regenerate twice → contact → learning shows
   growing counters; after repeated regens, Prompt Audit gains a `learned:` line.
8. **Dismissal re-arm**: swipe a draft away → next burst pops again (see ledger line
   "heads-up re-armed").

Gate: next phase only after these pass on the owner's device.
