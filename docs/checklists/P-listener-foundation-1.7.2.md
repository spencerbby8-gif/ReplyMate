# P-Listener-Foundation Repair — 1.7.2 / vc919

**Phase:** GOD-TIER LISTENER FOUNDATION REPAIR (owner's device evidence: WhatsApp background listening broken on Oppo/ColorOS; Discord still PARTIAL). P20 device-proof execution remains PAUSED; P21 not started.

---

## 1. Previous build verification (pre-flight, all consistent — no STOP)

| Check | Result |
|---|---|
| HEAD / parent at start | `5b24738` / `3994644` ✓ (live origin re-read; `.git` had not drifted this turn — restored from fresh clone per hygiene) |
| P20 implementation | 1.7.1 / vc918, commit `3994644` (+ docs-only `5b24738`) ✓ |
| CI `35392806603` | completed / success (re-queried) ✓ |
| Artifact `10565309912` | `ReplyMate-1.7.1-final-proof-vc918`, not expired; local proof sha `afa5a247…` re-verified ✓ |
| Release certificate | `b15f2f37…6a85ed` MATCH on 1.7.1 proof (re-verified twice previously, incl. via fresh build-tools) ✓ |
| Tree | clean except untracked proofs; no reset/rebase/force-push/delete performed ✓ |

## 2. Audit — actual code at `5b24738`, findings confirmed/refuted

| Owner finding | Verdict |
|---|---|
| A. `onListenerDisconnected()` clears ACTIVE + logs but never `requestRebind()` | **CONFIRMED — REAL DEFECT.** Official docs (developer.android.com, NotificationListenerService reference, read 2026-09-20): after this callback *"You will not receive any events after this call, and may only call requestRebind(ComponentName) at this time"*; and requestRebind is *"the only one that is safe to call before onListenerConnected() or after onListenerDisconnected()"* (static, API 24+, no-op when access was never granted). Our disconnect path had zero recovery while the process lived. |
| B. `ReplyMateApp.onCreate()` performs `requestRebind()` at process start | **CONFIRMED — exists and correct**, but only helps on process START. A ColorOS unbind-while-alive (or system-initiated unbind after pressure) had no recovery until the next launch/reboot. Now routed through the single bounded mechanism (§4) instead of its own raw call — one mechanism, no competition. |
| C. WhatsApp = FULL via generic `MessagingStyleParser` | **CONFIRMED as wired.** Decision on a dedicated parser is **DEFERRED, evidence-gated**: the generic contract is sound against the documented MessagingStyle keys (research: getMessages/getHistoricMessages/getConversationTitle/isGroupConversation, sender Person name/key/uri, RemoteInput free-form on standard OR wearable surfaces — all already extracted by `NotifExtractor`). Which is why the §3 structural probe exists: the OWNER's build now records the real WhatsApp structural shapes so the FULL-vs-dedicated decision is made on observed payloads, not assumptions. No downgrade, no premature FULL claim on anything new. |
| D. Discord = PARTIAL via `TitleTextParser(…, preferHistory=true)` | **CONFIRMED as wired — remains PARTIAL by design.** What blocks a FULL upgrade is exactly what the LF-D rows must capture from real payloads (sender stable keys per shape, channel/server identity, thread/reply relationships, no-history fallback shapes, announcement honesty). FULL is NOT claimed. |
| E. WhatsApp tests are synthetic-fixture based | **CONFIRMED.** New sanitized doc-derived shape-contract suite added (separately named, provenance obvious) + on-device shape probe for real payloads; no synthetic suite deleted or treated as device proof. |

Immutability verified by full-file re-read: DeliveryGuard / ConversationMatch / ReplyTarget / SendPath / approval flow — **untouched** (no diff at all in those files).

## 3. Official documentation research (executed before lifecycle edits)

| API (developer.android.com, all read 2026-09-20) | Binding semantics |
|---|---|
| `NotificationListenerService.onListenerConnected()` | The connected state; service should wait for it "before performing any operations". |
| `onListenerDisconnected()` | "You will not receive any events after this call, and may only call requestRebind(ComponentName) at this time." → disconnect-then-request is the sanctioned recovery point. |
| `requestRebind(ComponentName)` | static, API 24+; the ONLY call safe pre-connect/post-disconnect; no-op for ungranted listeners. |
| `getActiveNotifications()` | Recovery source for CURRENTLY-SHOWN notifications only — never history. (Our reconcile already honors this.) |
| `Notification.MessagingStyle` | getMessages / getHistoricMessages (26+) / getConversationTitle / isGroupConversation(pre-P vs post-P semantics) / Person sender resolution — matches our extractor's key reads. |
| RemoteInput / direct reply | free-form RemoteInput may live in standard OR `Notification.WearableExtender` actions — both surfaces must be scanned (ours already does). |

## 4. Root cause + fix (minimal, in-gate-proven where provable)

**Established defect (class A — LIFECYCLE):** on ColorOS the system can unbind a granted NLS while the app process stays alive (and re-fixtures across pressure); with no `requestRebind` from `onListenerDisconnected`, ALL watched capture (WhatsApp included) silently stops until the next process start or manual toggle. Fix: `onListenerDisconnected` now requests a rebind through the **single bounded mechanism**:

- `core/listener/RebindPolicy.java` (pure JVM): shared stamped throttle — at most one request per 30s across ALL callers; fail-open on malformed/missing kv or >60s backward clock skew; stamp-before-call so even a throwing OEM framework leaves the bound.
- `app/listener/ListenerRebind.java` (wrapper): the ONE path; API-guarded (24+); traces `rebind requested/skipped-throttled/failed · <reason>`; **never claims a bind** — `listener bound right now` remains driven strictly by real connect/disconnect events.
- `ReplyMateApp.onCreate()` nudge **delegates** to the same path (no competing mechanisms); container-init-failure still best-effort.
- Settings → Diagnostics gains `last rebind requested:`.

**Evidence instrument (needed to classify A–G for WhatsApp on the real device):** `core/listener/ListenerTrace.java` — bounded (12), privacy-safe boundary ring: `callback→lane · pkg · k#hash · bound=` → `extract · pkg · ok/null/error` → structural **shape probe** (per-app, line only on shape CHANGE: `cat, msgs/hist counts, named-sender count, action counts by surface, free-form presence, convId/group/presence flags, ongoing/progress`) → `route · pkg · PARSED events=N / IGNORED|FAILED · category-only reason label` → `ingest · pkg · stored=N dupes=N filtered=N pings=N` → `schedule · pkg · contact#N`. NO bodies, NO titles, NO names, NO PendingIntent contents, NO API keys (Secrets.redact is the durable choke point anyway; parser reasons embedded after ":" are intentionally cut from the trace). Rendered in Settings → Diagnostics as "Listener boundary trace (newest first)".

## 5. Failure-class map (owner's 10 WhatsApp shapes) — instrumented, verdict on-device

| Class | How this build makes it visible | Current verdict |
|---|---|---|
| A. LIFECYCLE (no callback) | `bound=false/true` trace lines + `callback→lane` presence/absence + rebind lines + `connected at:`/`disconnected at:` | **Defect found (missing disconnect recovery) → fixed.** Whether ColorOS also kills the process beyond requestRebind's reach (class G) is exactly what rows LF-W2/W3/L2 evidence. |
| B. EXTRACTION | `extract · error/null` lines | None seen in gate; device rows decide. |
| C. PARSER | `route · IGNORED|FAILED · <category>` + shape signature | None seen in gate; device rows decide. |
| D. INGEST | `ingest · stored/dupes/filtered` | None seen in gate; device rows decide. |
| E. SCHEDULING | `schedule · …` present/absent after pings | None seen in gate; device rows decide. |
| F. GENERATION | Existing `assistant.latency.last`/diag ledger + trace `schedule` before it | None seen in gate; device rows decide. |
| G. OEM/BATTERY | `listener bound right now`, BackgroundReadiness verdict, `connected at:` vs process age, trace rebind history | NOT asserted prematurely — measured, not downgraded by hand-waving. |

## 6. Tests (all green)

- Gate: **1046/1046** (= previous 1025 + 21 new), 23.1 s.
- New suites (whitelisted in `scripts/run_tests.sh` — the runner list is explicit): `RebindPolicyTest` (due → suppress → rearm → malformed-fails-open → backward-clock → broken-store no-throw), `ListenerTraceTest` (bounded+newest-first ring, stable short k# tags with no key leak, structure-only signatures, shape-flip dedupe: late action/history appearing/burst growth, **no body ever enters a trace line**), `DeviceShapeContractTest` (SANITIZED doc-derived fixtures via REGISTERED parsers + real ingest — WhatsApp 1:1 MessagingStyle+capability, burst→1 aggregated ping, group drop-world/opt-in world, re-post exact & extended-history & shape-upgrade dedupe, self-status rejected before anything, Discord DM single-sender attribution, Discord no-history announcement stays fail-closed even with groups ON). One expectation during authoring initially contradicted the shipped groups-OFF-drop policy — caught by the gate and aligned to the canonical `GroupOptInTest` semantics (the pin, not the code, was wrong).
- Engine devcheck (vc9190)/app+data compile: clean; key SUT files not touched: parsers, DeliveryGuard, SendPath.

## 7. Build proof

| Item | Value |
|---|---|
| Commits | `650d0bf` (1/2) — code; this file (2/2) |
| CI run | `35514329679` — **completed / success** (head `650d0bf`) |
| Keystore verdicts | `KEYSTORE-VERDICT: MATCH — historical ReplyMate identity confirmed`; `APK-CERT-PROOF: MATCH — update-in-place over ≤1.5.8 is preserved` (CI logs) |
| Artifact | `10606301543` — `ReplyMate-1.7.2-listenfix-proof-vc919`, not expired |
| Off-CI verify | 682,314 bytes; **sha256 `9aa108a6736638d197b9415404ee946a8288d155cffeb3b679fc7136161b9c8f`**; apksigner Signer#1 SHA-256 `b15f2f37…6a85ed` **MATCH**; badging `com.replymate.app` vc919 / `1.7.2-listenfix-proof`, min 24 / target 34 / compileSdk 35 |
| Prior artifact | 1.7.1 proof sha `afa5a247…` re-verified pre-flight (§1) |

## 8. Device rows — ALL OPEN (owner-execution only; trace lines named are the evidence)

Preconditions for all: install the exact `ReplyMate-1.7.2-listenfix-proof.apk` (sha above; `dumpsys package` = vc919), P20-6 readiness flow green first. Every row's evidence = filled trace-aware block (Setup/Input/Observed/**Relevant diagnostics**: boundary trace lines verbatim + bound line + connected/disconnected stamps/Generated/Target/Evidence/Verdict/Reason).

| Row | Scenario | Pass-pivot (trace/diagnostics lines) |
|---|---|---|
| LF-W1 | WhatsApp 1:1 foreground | `callback→lane` → `route PARSED events=1` → `ingest stored=1 … pings=1` → `schedule` → draft; `shape · com.whatsapp` recorded |
| LF-W2 | WhatsApp 1:1 **backgrounded** (owner's failure) | same chain with app in background; if `callback→lane` never appears → class A confirmed on-device; if appears but stops later → exact failing stage named |
| LF-W3 | WhatsApp 1:1 **screen-off** + later unlock | pre/post screen-off trace; any `bound=false`/rebind lines recorded verbatim |
| LF-W4 | WhatsApp 1:1 burst 2–3 | `PARSED events=3` → `ingest stored=3 … pings=1` (one aggregated ping) |
| LF-W5 | WhatsApp group | with groups OFF: `route`+`ingest` show drop-honesty; with groups ON: stored + 1 ping + per-sender shape `named=N` |
| LF-W6 | after process death/restart | `bound=true (onListenerConnected)` + reconcile line + post-restart message captured; stale `connected at:` does NOT count |
| LF-W7 | late Reply action | SECOND `shape · com.whatsapp` line with `act=…` changing / `reply=Y` appearing — plus no duplicate row (`dupes` counter) |
| LF-W8 | re-post/update (extended history) | new-shape line + `ingest stored=<only new>` + no dupes |
| LF-W9 | self-status/backup card | `route · IGNORED · app self-status` (or equivalent category), zero stored, Usage counters static |
| LF-W10 | no-MessagingStyle shape (if producible) | shape shows `msgs=0/0`; route PARSED via fallback or honest IGNORE — recorded verbatim |
| LF-D1 | Discord DM | `PARSED events=1`, sender attribution = person (conversation screen), shape `grp=0` |
| LF-D2 | Discord server channel multi-sender burst | `PARSED events=N`, per-sender rows, 1 ping, shape `named=N` |
| LF-D3 | owner @-mention | engagement evidence + `schedule` + draft targeting the sender |
| LF-D4 | reply-to-message/thread context | what Discord actually publishes (shape line) + anything the parser could/could not represent — verbatim, no guessing |
| LF-D5 | MessagingStyle-history notification | preferHistory path evidence (events=N>1, ordered) |
| LF-D6 | no-history notification | fallback path verdict event-honesty; announcement honesty preserved |
| LF-D7 | Reply action present | `reply=Y` in shape + P20-3-style native delivery on Approve |
| LF-D8 | re-post/update | dedupe evidence (`dupes`, no second row) |
| LF-D9 | announcement/system/no-reply shape | fail-closed: stored context, no ping, no provider call (Usage static), Generate explains |
| LF-L1 | disconnect recovery | `bound=false` → `rebind requested · onListenerDisconnected` → `bound=true (onListenerConnected)` sequence within the 30s bound — then LF-W2 re-run |
| LF-L2 | storm guard | after repeated system unbinds (toggle spam), ≤1 request per 30s per the trace; `last rebind requested:` ≤ 30s cadence |
| LF-L3 | honesty | `listener bound right now:` MUST reflect live events only — never a requested-but-unconfirmed rebind (verify by toggling access OFF → OFF is reported) |

**P20 status:** P20 rows remain OPEN and paused; they resume on this build (or its successor) after the LF rows pass or defect-fix cycles close. **P21: not started.** WhatsApp tier: unchanged (FULL wiring, decision deferred to LF-W evidence). Discord tier: **PARTIAL — unchanged**; upgrade only if LF-D evidence supports it without weakening any guard.

## 9. Regressions

None known. Listener capture path additions are append-only diagnostics; the lifecycle change adds one officially-sanctioned call with a shared bound. All P-16…P-20 suites green, delivery/NC/cross-conversation suites unchanged and green.
