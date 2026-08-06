# P2 Repair Pass — ReplyMate 0.2.1 (vc5) — 2026-07-18

Scope: **repair only** (per owner order). No new features, no new architecture, no P3,
no sending, no Accessibility Service, no cloud sync, no AI generation from notifications.

Build: `ReplyMate-0.2.1.apk` · vc5 / 0.2.1 · sha256 `8d8d7605a58a9e0a1976a32c4b88a89eba806a41ae3fc85f2d92e5f9fadde2d0` · 57 KB · same `arena` signature (updates in place over 0.2.0) · minSdk 24 / target 34 · exactly one permission (`POST_NOTIFICATIONS`).

---

## 1. Reproduction attempt (honest account)

Owner reported "P2 not working properly on device" without a specific symptom, so the pass
started with a full end-to-end audit plus independent verification rigs:

- **Emulator reproduction — blocked by sandbox, abandoned after ~2 h.** Modern emulator
  (36.6) refuses x86(_64) guests without `/dev/kvm` (absent in sandbox) and dropped ARM
  guest support; 2021-era emulator build 7425822 + `libpulse0` + 2 GB swapfile *does* boot
  an x86_64 Android-25 image under TCG, but the guest needs >1 h to finish booting on a
  rate-limited 2-core/2 GB host. Not viable as a test loop here. The full harness
  (`rm-harness/`: poster APKs + `e2e_p2.sh`) is kept for any KVM-capable host/CI and for
  future phases.
- **Substitute verification — two new rigs, both green (see §5):**
  1. Static audit of **all** P2 source + generated binary manifest of 0.2.0/0.2.1
     (service exported=true, `BIND_NOTIFICATION_LISTENER_SERVICE`, correct intent-filter;
     one permission only).
  2. **Robolectric fixture suite**: the REAL AOSP API-34 `Notification.MessagingStyle` /
     `Bundle` implementation builds the extras; the app's REAL `NotifExtractor` and
     REAL `IngestCoordinator` process them. This closed the exact gap P2 shipped with:
     the platform-facing parser layer had zero executable tests.

## 2. Root cause(s)

The pipeline is logically sound — re-post dedupe, group/own/media policy, toggles, contact
auto-discovery and pings all verified passing against real framework data structures
(7/7 fixtures). So **no total-logic breakage was found**, and three real defects + one
blind spot were found and fixed. Any one of R1–R3 can present as "not working properly":

- **R1 — duplicate contacts on listener (re)bind bursts (structural).**
  `ContactService.ensureChannelContact()` implemented get-or-create with no
  synchronization. When the listener (re)connects — every re-grant, and **after every
  reboot** — Android replays all active conversations at once; the background pool
  (2 threads) could interleave two get-or-creates for the same `remoteKey` and create
  **two contacts for one chat**, splitting that chat's message history. This directly
  threatens the soak target "0 duplicate texts / listener survives reboot".
  → `ensureChannelContact` is now serialized.

- **R2 — one bad message bundle could silently kill a whole notification (fragility).**
  `NotifExtractor` processed all MessagingStyle messages in one unguarded pass, and the
  service caught `RuntimeException` around the *entire* extract call. On any platform
  surprise (malformed/lazy-parcelled value, an OEM/compat type deviation inside
  `sender_person`), the whole notification was dropped with **zero on-device evidence**.
  Also, `sender_person` was read only via `Bundle.getBundle()`, which is correct for
  API 24–27/compat shapes but relies on a framework-internal ClassCast warning path for
  the API 28+ `android.app.Person` shape.
  → extraction is now per-message isolated (one bad message can never drop the others),
  and `sender_person` is read **type-safely** (Bundle → Person → legacy `sender` fallback),
  each step degrading gracefully.

- **R3 — failure paths were invisible on device (diagnostics blind spot).**
  Parse failures, ingest exceptions, and "watched app posted something with nothing
  readable" (calls/status/backup notices) were logcat-only. On a phone the feature then
  looks dead for unknown reasons.
  → counters `listener.parse_errors` + `listener.unparsed_total` recorded in `app_kv`;
  content-free lines (package + exception class only) appended to the diagnostics ring;
  Settings → Diagnostics shows all three alongside the existing counters/ring.

- **R4 — hygiene:** `onListenerDisconnected()` didn't call super. Fixed.

## 3. Files changed (product — exactly 4)

| File | Change |
|---|---|
| `src/com/replymate/core/usecase/ContactService.java` | serialized `ensureChannelContact` (R1) |
| `src/com/replymate/app/listener/NotifExtractor.java` | per-message try/catch; type-safe `sender_person` handling; `personName` kept for the owner extra (R2) |
| `src/com/replymate/app/listener/RmNotificationListener.java` | super.onListenerDisconnected; parse/ingest error counters + content-free ring lines; unparsed counter (R3, R4) |
| `src/com/replymate/app/ui/SettingsActivity.java` | Diagnostics shows the two new counters (R3) |

Tooling (not product): `rm-harness/` (poster builder, e2e emulator script, Robolectric
suite), this report.

## 4. What was NOT changed (by design)

Package whitelist, 5 s debounce, ping policy (no ping for groups/own/media/13+-without-grant),
content-hash dedupe key, schema, signing — all match the approved P2 blueprint; fixture tests
confirm them unchanged and passing.

## 5. Final test results

| Suite | Result |
|---|---|
| JVM unit suites (`scripts/run_tests.sh`) | **101/101 PASS** |
| Robolectric fixtures on real AOSP-34 MessagingStyle (Person payload, fallback, group, own, media, unknown pkg, re-post dedupe + pings, toggles) | **7/7 PASS** |
| APK validation (badging vc5/0.2.1, minSdk 24/target 34, single permission, listener service exported + bound-permission + intent-filter, `apksigner verify`) | PASS |
| Emulator full-stack run | NOT RUN (sandbox incapable — see §1) |

## 6. What still does not work / open unknowns

1. **The owner's original symptom remains unidentified.** Nothing found explains a *total*
   failure on a compliant device with access properly granted; the fixes above cover the
   plausible partial-failure modes. **0.2.1 is built to reveal the cause:** after a soak
   with real WhatsApp + Telegram, Settings → Diagnostics will show exactly which stage is
   silent (`connected_at` vs `stored_total` vs `unparsed_total` vs `parse_errors` vs ring).
2. **Possible environment causes the code cannot fix (need owner data):**
   - testing with **WhatsApp Business** (`com.whatsapp.w4b`) or a Telegram fork
     (TG X, Plus, Nekogram) — NOT in the approved whitelist → nothing captured;
   - `POST_NOTIFICATIONS` never granted on Android 13+ → pings alone stay silent
     (messages still stored); grant row exists in Settings;
   - OEM battery killers (Transsion/Xiaomi/Oppo) freezing the app — Android disconnects
     the listener until access is toggled off/on (`listener.disconnected_at` shows this).
3. Robolectric cannot reproduce cross-process Parcel lazy-value edges; those are now
   non-fatal + observable (R2/R3) instead of fatal + invisible.

## 7. Information needed from owner to close P2

With 0.2.1 installed (updates in place over 0.2.0):
1. exact previous symptom (nothing stored? no pings? duplicates? crash?);
2. device model + Android version;
3. which WhatsApp/Telegram app (`adb shell pm list packages | grep -i whatsap`) — regular or Business/fork;
4. after 24 h: screenshot of Settings → Diagnostics (it now answers enablement, storage, and failure counts).
