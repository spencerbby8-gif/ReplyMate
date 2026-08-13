# Signing-key loss — search record, impact, and migration (P-release-2, 2026-08-12)

## 1. Exhaustive search record (all negative)

| Where | Coverage | Result |
|---|---|---|
| Workspace filesystem | `find /home/user /tmp /root /opt /usr/local` for `*.keystore`, `*.jks`, `*.pk8`, `*.p12`, `arena*` | **0 hits** |
| Old engine/secrets dirs | `find` for `apk-engine`, `dev-secrets` paths | **absent** (pruned with old sandbox) |
| Git history — ALL objects, ALL branches | `git rev-list --all --objects` grepped for keystore/jks/pk8/p12/dev-secrets/apk-engine (main **and** the arena/* prototype line) | **never committed** (correct security posture — but means git can't save us) |
| Arena branches bundle | `ReplyMate-arena-branches.bundle` (`git bundle verify` OK) | unrelated Kotlin prototype; contains **no signing material** |
| Shipped APK binaries | every `releases/*.apk` unpacked | contain only the **public cert** (`META-INF/ARENA.RSA`, v1 block) — the **private key is not in any APK and cannot be derived from it** |
| In-repo secrets | `res/values/builtin_stub.xml`, scans | empty BYOK stub; unrelated to app signing |

**Lost identity (from the public cert inside 1.4.6–1.5.8 APKs, stable across the shipped line):**
- Subject: `CN=Arena Demo, O=Arena, C=NG` (self-signed)
- Cert SHA-256: `B1:5F:2F:37:FC:E1:9B:56:46:83:FE:6B:85:72:5A:9D:31:92:DF:93:18:1E:F2:F3:62:86:B5:E2:1C:6A:85:ED` (matches the `b15f2f37…c6a85ed` recorded in the 1.5.8 checklist ✓)
- Validity: 2026-07-17 → 2056-07-09

**Verdict: the private key is unrecoverable** from every surface this workspace
and repository can reach. The only remaining possibility is an **owner-held copy
outside this system** (a personal backup of the old sandbox's
`/home/user/apk-engine/keystore/arena.keystore`, pass `android`). If you have
one, see §4 — do not let the engine overwrite it: point `REPLYMATE_JKS` at it.

## 2. Impact of proceeding on a new key (why Android blocks the update)

| Consequence | Detail |
|---|---|
| Update-in-place **impossible** | Android requires identical signer for updates; installing a new-key build over ≤1.5.8 fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES` |
| Clean install **wipes app data** | Local SQLite (contacts, threads, drafts, long-term memory summaries/facts, style learning, settings), Android-Keystore-wrapped **provider API keys** (usable-but-unreadable; must be re-entered), Supabase session/avatar cache — all deleted with the old package data |
| No soft-rotation path | APK Signature Scheme **v3 key-rotation lineage proofs must be signed by the OLD key** — without it, no in-place rotation exists. Same for Play App Signing enrollment-with-existing-key |
| Update channel hygiene | All ≥next-version builds must consistently use the NEW key; mixing keys fragments devices permanently |
| What is NOT lost | Package id (`com.replymate.app`), all source, tests, docs, RELEASES.md ledger, release APK history, tag `v1.5.8` |

## 3. New signing lineage (authoritative from this phase forward)

Generated once by the engine into gitignored `secrets/` of the release
workspace (**never committed**), first used by the P-release-2 verification
build:

- New cert SHA-256: **`446310ccc1289e5ea61a3f6978e13a4f422b4aa2452696642bb35c850f04e9d2`**
- Subject: `CN=ReplyMate, O=ReplyMate, C=NG` · RSA-2048 · self-signed · 30y
- Rule reinstated: **never regenerate.** `engine/build.sh` reuses existing
  `secrets/arena.pk8`+`arena.pem` and only generates when absent.
- **Owner action required:** keep an offline backup of `secrets/` (any safe
  place outside the sandbox). If this workspace's secrets are lost again, the
  cycle repeats.

## 4. Recovery path (if the original key resurfaces)

**Password:** the keystore password WAS documented all along in the repo's own
build docs — `docs/BLUEPRINT.md` §10, in every version up to and including the
1.5.8 release commit (`git show fbab98a:docs/BLUEPRINT.md`, line 458):

> `**Signing:** existing persistent key /home/user/apk-engine/keystore/arena.keystore (pass `android`).`

So: store password = `android` (the P-release-1 BLUEPRINT rewrite dropped that
paragraph when the key was presumed lost — the audit reports quoted the
"never regenerate" rule but not the password line; it lives on in git history
and matches the engine's `REPLYMATE_JKS_PASS` default of `android`).

**Verify BEFORE use** (never trust an unverified store):

```bash
# prompts silently; password never touches argv/logs/history — or use a 600-perm file:
#   KS_PASS_FILE=/path/to/pwfile scripts/verify_keystore.sh /path/to/arena.keystore
scripts/verify_keystore.sh /path/to/arena.keystore
# expect: cert-sha256 = B1:5F:2F:37:FC:E1:9B:56:46:83:FE:6B:85:72:5A:9D:
#                        31:92:DF:93:18:1E:F2:F3:62:86:B5:E2:1C:6A:85:ED
#         RESULT=MATCH
```

**Then build on the recovered identity** (the keystore stays where you keep
it — it is NOT copied into git; `*.keystore` is gitignore-blocked anyway):

```bash
REPLYMATE_JKS=/path/to/arena.keystore \
  bash scripts/release.sh 40 1.5.9 "…"   # REPLYMATE_JKS_PASS defaults to android
# confirm the build output prints CERT_SHA256 = b15f2f37…c6a85ed
```

If the fingerprint shows the **b15f…** cert, update-in-place over ≤1.5.8 is
restored and §2's data-loss column disappears.

## 5. Device-plan consequence

Phones currently running any ≤1.5.8 build keep working — **they just can't
over-install future builds.** Before the first new-lineage release is
installed, export/re-note anything you want to keep (provider keys must be
re-entered; local memory/learning does not migrate).
