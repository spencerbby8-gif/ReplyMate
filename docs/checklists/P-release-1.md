# P-release-1 — REPRODUCIBLE BUILD + RELEASE HARDENING (2026-08-12)

Owner-ordered gate phase *before any new product/intelligence work*. Outcome:
a fresh clone can now compile, test, build, sign, hash and ledger a ReplyMate
APK with **zero hidden workspace dependencies**.

## 1. Reproducible build — PROVEN on a disposable fresh clone of `main`

Fresh clone → `scripts/release.sh 40 1.5.8-rebuild …` ran end-to-end:

| Step | Result |
|---|---|
| Toolchain bootstrap | `build-tools_r34-linux.zip` + `platform-35_r02.zip` downloaded from Google into `.engine-sdk/`, **sha256 pins from `engine/TOOLS.txt` verified on fetch** |
| Compile | aapt2 compile + link (R.java generated), javac all 214 app sources |
| Dex | d8 min-api 24 |
| Pack/align/sign | dex stored → `zipalign` → `apksigner` v1+v2 |
| Self-verify | `zipalign -c` PASS · `apksigner verify` PASS · badging dump: `com.replymate.app vc40/1.5.8-rebuild minSdk 24` |
| Hash + metadata | sha256 `f79e04441345fe24bb05d6ab7f4aa32227f3058c82864e157eda24bb567fc15c` · size 575818 · cert-sha256 `124ebe38…64cb8cc2` → ledger row appended ✓ |
| Leak gate | `rm_builtin_key` / `rm_builtin_provider_wire` = empty strings in binary; raw string scan for `sk-`/`AIza`/`ghp_`/`sb_secret` clean ✓ |
| Ignores | `build/`, `.engine-sdk/`, `secrets/` all gitignored (`git check-ignore -v` proven; tree clean after builds) |

**Signing continuity (recorded honestly):** the ≤1.5.8 signing key is LOST
(old external workspace). Proof-build cert `124ebe38…` ≠ historical cert
`b15f2f37…c6a85ed` → future builds need a **clean install** over ≤1.5.8.
New key stays in local gitignored `secrets/` and is never committed.
(BLUEPRINT §10 updated to state this.)

Known honest divergences vs historical APKs: build-tools rev (34.0.0),
platform jar rev (35 → `platformBuildVersion 15` vs 14), no bit-identity
claimed. Same commit + same pins + same key ⇒ same APK, going forward.

## 2. GitHub release access

Owner PAT used exclusively via runtime credential helper from a chmod-600
tmpfs file; never written to source, docs, scripts, git config, or history.
Post-push verification: `git config` clean, history scan for the token clean.
(PAT was shared in plaintext chat — rotation after this phase is recommended.)

## 3. Release integrity — VERIFIED

- `releases/ReplyMate-1.5.8.apk` sha256 == `RELEASES.md` ledger entry ✓
- Release commit `fbab98a` identified: its message names 1.5.8 and it is the
  commit that introduced the APK + ledger row ✓
- `main` HEAD (`fa7b2b1`) vs `fbab98a`: `.gitignore`-only diff → current source
  ≡ shipped 1.5.8 source ✓
- Annotated tag **`v1.5.8` → `fbab98a`** created with the evidence above. No
  history rewritten; no misleading tags (HEAD's cleanup commit intentionally
  NOT tagged as a release).

## 4. Test reproducibility

- **JVM from fresh clone: 757/757 green** (run twice: standalone gate and as
  the release gate inside `release.sh`). This is the only reproducible suite.
- **Robo/device-stack: harness is permanently LOST** (was never committed).
  Decision: **retire the old counts as gates** (recorded in BLUEPRINT §9.1
  addendum + the 1.5.8 checklist's Gate-status box). Rebuilding a device-stack
  harness is future work (`tests/device/`), and its counts only count once the
  harness itself is in this repo. Nothing was fabricated.

## 5. 1.5.8 device gate

`docs/checklists/P-background-8-1.5.8.md` §1–§8: **OPEN — owner-only.**
Not marked PASS from code or unit tests. A Gate-status box now separates
reproducible engineering verification from owner real-device verification.

## 6. Repository hygiene

- Stale `arena/*` branches (unrelated Kotlin prototype line, no merge base):
  archived to a complete-history bundle **outside the repo**:
  `ReplyMate-arena-branches.bundle` (git-bundle verified OK). Branches NOT
  deleted — deletion awaits owner approval.
- Release artifacts: all `releases/*.apk` retained (release history).
  `RELEASES.md` prune-note drift already flagged in the takeover audit.
- No destructive cleanup performed.

## 7. Security re-scan (tree + full history + binaries)

- Tree: no real secrets (only labeled dummy probe keys).
- Full git history scan for key/token patterns (incl. `ghp_`): clean.
- Shipped ALL APKs on disk scanned for embedded key strings: clean (1.5.8
  re-verified BYOK-empty).
- New engine enforces at build time: sha256-pinned toolchain, empty-`rm_builtin`
  APK gate, `secrets/` + `.engine-sdk/` gitignored and permission-tightened.
- PAT handling as in §2; no credentials in any log/diagnostic/commit.

## 8. Files added / changed in this phase

**Added:** `engine/build.sh`, `engine/TOOLS.txt`, `engine/README.md`,
`docs/checklists/P-release-1.md` (this file).
**Changed:** `scripts/release.sh` (in-repo engine + repo-local secrets +
BYOK default), `scripts/run_tests.sh` (repo-relative), `scripts/live_probe.sh`
(repo-relative), `.gitignore` (`/.engine-sdk/`, `/secrets/`),
`docs/BLUEPRINT.md` (§9.1 addendum + §10 pipeline/signing reality),
`docs/checklists/P-background-8-1.5.8.md` (Gate-status box).
**Product code (`src/`, `tests/`, `res/`, `AndroidManifest.xml`): untouched.**
