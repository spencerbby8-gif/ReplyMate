# P-release-2 — BLOCKING RELEASE + DEVICE BASELINE: phase record (2026-08-12)

## 1. Signing-key recovery — EXHAUSTED, then documented (per order: no APK before this)

Full search record + impact + migration: **`docs/signing-key-migration.md`**.

- Searched: entire workspace filesystem, old engine/secret dirs, ALL git
  objects on ALL branches (main + arena prototype), the arena git-bundle, and
  every shipped APK binary (certs only — private keys are never recoverable
  from APKs).
- **Verdict: original key unrecoverable** here. Lost identity recorded:
  `CN=Arena Demo, O=Arena` · cert sha256 `B1:5F:2F:37:…:6A:85:ED`
  (matches the 1.5.8 checklist's notation `b15f2f37…c6a85ed`; same identity on
  1.4.6 & 1.5.0 spot-checked ✓).
- **Open owner question:** do you hold a copy of the old
  `arena.keystore` anywhere? If yes → `REPLYMATE_JKS` recovery path
  (engine/native, added this phase) restores update-in-place continuity.
- **New lineage anchored:** `CN=ReplyMate, O=ReplyMate, C=NG` · cert sha256
  `446310ccc1289e5ea61a3f6978e13a4f422b4aa2452696642bb35c850f04e9d2`
  (gitignored `secrets/` of the release workspace; engine never regenerates
  it; owner asked to keep an offline backup).

## 2. Reproducible release — RE-PROVEN (two routes, both clean)

| Proof | Route | Result |
|---|---|---|
| **A. release workspace** | `engine/build.sh` vc40 `1.5.8-verifyA` | sha256 `e3ba9f60f4fcbd7f06b15703d46c23552c684443aa3cfe6e25955df8a5d5f24b` · 575818 B · cert `446310cc…f04e9d2` (new lineage) · apksigner+zipalign verified · package `com.replymate.app`, minSdk 24 ✓ |
| **B. clean clone (zero state)** | fresh `git clone` → `scripts/release.sh` vc40 `1.5.8-verifyB` — bootstrapped its own pinned toolchain (both pins verified) and its own key | **757/757 JVM inside the gate** ✓ · sha256 `bd9b72848f281f3a9a78510b499bb1479dc5f1c84df5c78281acd709bae62723` · 575818 B · badging + ledger row ✓ |

Both proof APKs are labeled non-shipped and live outside `releases/` (A in
gitignored `build/`; B was discarded with its disposable clone).
Hidden-path check: `grep -r /home/user scripts/ engine/` → only
documentation comments. PAT/API keys: absent from source, history, logs, APK.

## 3. 1.5.8 real-device baseline — OPEN (owner-executed)

The agent sandbox has **no adb/emulator/device** — this gate cannot be
executed here and is **not marked passed from tests**. Owner runbook:
**`docs/checklists/P-release-2-device-baseline.md`** (L1–L3 listener lifecycle
+ D1–D9 scenarios with evidence slots; D9 noise battery explicitly blocking).

Static pre-verification performed (does not count as device PASS):
- listener service declared with signature-level permission ✓
- `onListenerConnected` / `onListenerDisconnected` / `onNotificationPosted`
  implemented; lifecycle timestamps in Diagnostics ✓
- JVM pin coverage mapped per scenario (reaction/backup/missed-call/group/
  broadcast/media/1:1/burst/catch-up/research-budget/thinking-budget —
  all pinned and green in the 757 suite)
- **Gap flagged:** dismissed-notification SEND path has **no JVM pin**
  (cached-Intent machinery was robo-only) → device-only row D7
- Design note flagged: no `onNotificationRemoved` override (removal handled by
  active-set re-probe/refresh) — first suspect if dismissal issues appear

## 4. Known concern (filtering) — posture

Not assumed fixed **on-device**. In code, every ordered noise class
(backup/announcement/group/broadcast/service/reaction/call/media-only) has a
JVM regression pin that is currently green; device confirmation is exactly
runbook row D9, and any D9 failure is blocking per the phase order.

## 5. Repository state after this phase

- `main` advanced with this record + migration doc + runbook + engine
  `REPLYMATE_JKS` recovery hook. Tag `v1.5.8` unchanged (still → `fbab98a`).
- No product code touched (`src/`, `tests/`, `res/`, manifest untouched).
- No branches deleted (arena line archived via bundle from P-release-1).

## CI signing-continuity proof (workflow `release.yml`) — forensics log

The secure release workflow is live and fail-closed: JVM gate 757/757 green on
every run, nothing signed, nothing released, no keys generated anywhere.
Keystore materialization from `REPLYMATE_KEYSTORE_B64`/`REPLYMATE_KEYSTORE_PASS`
is hardened (dual-format JKS+PKCS#12, integrity/MAC arbitration of paste
injuries, historical-cert needle, two-stray scan, end-exact structure, content
free diagnostics).

Owner actions to unblock (run history newest → oldest):

- Run `31740863887` (bf13675): paste decodes to 2433 bytes but the store's own
  DER header claims 2469 — **the paste is truncated by ~47 base64 characters**
  (`base64 -w0 arena.keystore | wc -c` on the real file must equal ~3292; the
  secret held 3245). No 1- or 2-stray reconstruction contains the historical
  certificate bytes anywhere. **Fix: re-paste the complete single-line output
  in one action; verify the character count before saving the secret.**
- Runs `31721906092` / `31738465853`: same paste; earlier builds of the
  classifier proved PKCS#12 format + absence of one-stray repairs.

## Remaining blockers (honest list)

1. **Owner:** re-paste `REPLYMATE_KEYSTORE_B64` complete (~3292 chars; it was
   truncated at 3245) — then the CI proof re-runs.
2. **Owner:** run the device baseline runbook; reply per its completion rule.
3. **Owner:** confirm no personal backup of the original keystore (else use
   the recovery path BEFORE the first post-1.5.8 release).
4. **Owner:** secure an offline backup of the new `secrets/` key.
5. Rotate the GitHub PAT (was shared in plaintext chat in P-release-1).
6. Future work (not blocking): restore an in-repo device-stack harness
   (`tests/device/`) so rows like D7 gain automated pins again.
