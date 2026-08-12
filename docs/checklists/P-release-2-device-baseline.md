# P-release-2 — 1.5.8 real-device baseline runbook (OWNER-EXECUTED)

**Status: OPEN.** Prepared and statically pre-verified by the agent on
2026-08-12. The agent has **no device/ADB/emulator in its sandbox** (`adb`
absent), so per the phase order this gate is yours to execute on the real
phone. **Nothing below is marked PASS by code, unit tests, or assumption.**
Fill `Result` + `Evidence` per row; paste diagnostics/screenshots as proof.

Target APK: `releases/ReplyMate-1.5.8.apk` · vc39 · sha256
`38f85b397a1046837b6a951e444c3bc058871212b35a4cb469c1811bef754e7d` (verify
with `sha256sum` after transfer). Install is **in-place-safe over any ≤1.5.8**
(same old identity); the key break only affects *future* builds — see
`docs/signing-key-migration.md`.

Static pre-verification already done in-repo (does NOT count as device PASS):
- Manifest declares `.listener.RmNotificationListener` with signature-level
  `BIND_NOTIFICATION_LISTENER_SERVICE` + standard intent-filter ✓
- `onListenerConnected` / `onListenerDisconnected` / `onNotificationPosted`
  implemented; connection timestamps recorded in Diagnostics ✓
- Noise/1:1 classification pins exist in the JVM gate (NoiseGateTest,
  NoiseIngestTest, StatusFilterTest, MessageClassifierTest,
  ListenerFilterTest, MessagingStyleParserTest, ContentSignalsTest,
  IngestCoordinatorTest — reaction/backup/missed-call/group/broadcast/media
  shapes all covered; 757/757 green) ✓
- **No JVM pin exists for the dismissed-notification SEND path** (cached
  PendingIntent/parcel machinery was only covered by the lost robo harness)
  → row D7 is device-only, verify carefully.
- Note for the record: the service does NOT override `onNotificationRemoved`;
  removal-awareness is by active-set re-probe + cached intents. If you see
  dismissal-related misbehavior, that design note is the first suspect.

| # | Scenario | Steps | Expected | Evidence to capture | Result |
|---|---|---|---|---|---|
| L1 | **Listener lifecycle** (platform contract) | Setup → grant notification access → open Settings ▸ Diagnostics | Listener shows **connected** timestamp | screenshot of Diagnostics | ☐ |
| L2 | Disconnect handling | Revoke notification access in system settings → Diagnostics | **disconnected** timestamp logged; no crash | screenshot | ☐ |
| L3 | Reconnect | Re-grant access | **reconnected**; capture resumes (send a test 1:1 message) | screenshot | ☐ |
| D1 | **First message after install** (1.5.7 guard) | Clean install → setup → provider key → allow notifications → close app → receive ONE WhatsApp 1:1 message | ≤~10s: "Reply ready" alert; draft on-topic, in your voice; draft trail shows why-credits | screenshot of draft + trail | ☐ |
| D1b | First message, notifications DENIED | Repeat D1 but deny the one-time ask | No alert; draft still waiting in-app; Diagnostics explains | screenshot | ☐ |
| D2 | Capture correctness | Send 3 real 1:1 texts (WA + Telegram) | Each captured exactly once (content-hash dedupe); correct sender/app attribution incl. group sender names | Diagnostics/draft trail | ☐ |
| D3 | **Background generation** | App fully closed → burst of 4–5 messages in one chat | ONE alert (single pop), ONE draft answering the LATEST message; never one-per-message | screen recording/screenshots | ☐ |
| D4a | **Background research** | App closed → someone asks a meaning ("wetin be X") or a current-affairs question | Draft still arrives; trail names the path (native search / encyclopedia fallback+ms / cache / honest "found nothing"); provider cost matches path | trail screenshot | ☐ |
| D4b | **Background reasoning** | Busy moment: several questions at once, or a careful disagreement | Draft arrives; trail shows "deeper thinking: LOW/HIGH (because …)"; **reply complete — any empty/cut-off draft = the 1.5.6 starvation bug = FAIL** | trail + full draft | ☐ |
| D4c | Fast path intact | Ordinary banter ("lol ok") | No thinking line; same speed as before | trail | ☐ |
| D5 | **Slow network** | Throttled/2G profile (or airplane+flapping wifi) → repeat D4a | Draft arrives bounded to seconds of research overhead, never minutes; while one draft crawls, send a NEWER message → exactly ONE paid generation; old job diagnostics = "superseded"; no stale draft appears | Diagnostics + trail | ☐ |
| D6 | **Catch-up** | Alerts denied → two messages arrive minutes apart → THEN allow notifications | Two drafts generated silently (newest replaced older); catch-up re-alerts ONLY the draft matching the LATEST message — never both; Diagnostics says when it chose fresh generation over stale re-alert | screenshots | ☐ |
| D7 | **Dismissed-notification send** | Generate draft → dismiss the ReplyMate alert AND the source chat notification → approve the draft from inside the app | Send fires via cached intent with real RemoteInput results; if the cached target is expired: honest reason, draft kept, Copy/Open fallback — **"Sent" must never be faked**; *(no JVM pin — device-only verdict)* | screen recording | ☐ |
| D8 | **Real 1:1 keeps working** | Normal 1:1 conversations over the day | Drafts generate normally; voice/memory credits visible in trail | trail | ☐ |
| D9 | **NOISE BATTERY — any item below creating a chat or a reply = BLOCKING FAIL** | From a quiet state, trigger each: ① WA backup/summary card ("3 new messages") ② group chatter ③ channel/broadcast ④ service/status card ⑤ reaction-only ("liked your message") ⑥ missed-call card ⑦ media-only (photo without caption) | **Zero** new Home chats, zero drafts, zero phantom "WhatsApp"-named contact, zero provider cost; real 1:1 messages arriving around them still generate | Home screenshot + Diagnostics after the battery | ☐ |

### Completion rule for this gate

All rows PASS ⇒ reply "D-gate PASS" and the phase closes. **Any D9 row, or
D3/D4b/D5/D7 FAIL ⇒ reply "BLOCKING FAIL: <row> + evidence" and no product
phase starts** — that becomes the next blocking fix.
