# ReplyMate build engine (P-release-1)

In-repo, self-bootstrapping replacement for the old external
`/home/user/apk-engine` (lost with the previous sandbox; never versioned —
flagged by the 2026-08-12 takeover audit).

## Fresh-clone proof (what "reproducible" means here)

```bash
git clone https://github.com/spencerbby8-gif/ReplyMate.git && cd ReplyMate
bash scripts/run_tests.sh                                    # JVM gate
VERSION_CODE=40 VERSION_NAME=1.5.9 bash scripts/release.sh 40 1.5.9 "notes"
```

The engine downloads the pinned toolchain (see `TOOLS.txt`) into
`.engine-sdk/` (gitignored), verifies every artifact's sha256 on every fetch,
compiles, dexes, aligns, signs, self-verifies, and prints
`SHA256 / SIZE / CERT_SHA256` for the ledger.

## Layout

| Path | Role |
|---|---|
| `engine/build.sh` | full pipeline (aapt2 → javac → d8 → zipalign → apksigner) |
| `engine/TOOLS.txt` | pinned toolchain URLs + sha256 (change = repo decision) |
| `.engine-sdk/` | bootstrap cache — **gitignored, never committed** |
| `secrets/` | local signing key + optional `secrets.env` — **gitignored, never committed** |

## Signing & update continuity (IMPORTANT)

The ≤1.5.8 signing key lived outside git and is **lost**. The engine generates
a fresh local key at first build and prints a loud warning: builds signed with
it **will not update-in-place over ≤1.5.8** — devices need a clean install
(app data resets), or the original key must be recovered into `secrets/`.

## Host requirements

`curl unzip sha256sum javac zip openssl` (JDK 11+; note: `jar`/`keytool` are
NOT required — the engine avoids them deliberately) — nothing else.

## Environment overrides

- `REPLYMATE_SDK` — use an existing SDK instead of `.engine-sdk/`
- `REPLYMATE_SECRETS` — alternate secrets dir
- `MIN_SDK` / `TARGET_SDK` — default 24 / 34 (matches shipped 1.5.8 metadata)

## Known divergence from historical builds

Bit-reproducibility vs the ≤1.5.8 APKs is **not** claimed: the original engine
was never committed, and the original signing key is gone. Reproducibility
guarantee applies going forward: same commit + same pins + same key ⇒ same APK.
