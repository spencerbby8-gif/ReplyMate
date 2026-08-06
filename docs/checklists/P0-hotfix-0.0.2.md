# P0 Hotfix Report — 0.0.2 (device DB-init crash)
**Date:** 2026-07-17 · **Trigger:** v0.0.1 failed on physical device with "Database error - see logs".
Scope: P0 fixes only. No P1 work started.

## 1. Root cause (confirmed)
`Pragmas.apply()` ran inside `SQLiteOpenHelper.onConfigure()` and executed
`db.execSQL("PRAGMA journal_mode=WAL")`. **`journal_mode` is a row-returning PRAGMA** (it yields
the row `"wal"`). Android's `execSQL` → `nativeExecute` throws `SQLiteException`
("Queries can be performed using SQLiteDatabase query or rawQuery methods only.") for **any**
statement that returns rows. The exception fired out of `onConfigure`, so the very first
`getReadableDatabase()` (triggered by `HomeActivity`'s diagnostics) failed, and the UI showed the
fallback text. Everything downstream (KvDao write) depended on the same failed open.

**Why JVM tests didn't catch it:** `Pragmas`/`DbHelper` import `android.database.*` and are
excluded from the sandbox test compile; JDBC's sqlite driver tolerates the returned row
(`sqlite3_exec` discards rows), so identical SQL *runs fine* here. This is an Android-runtime-only
behavior difference — exactly the class of bug the on-device code had to self-diagnose.

**Reproduction path (documented since no emulator exists in this environment):**
fresh install → launch → `ReplyMateApp.onCreate` (container ok) → `HomeActivity.onCreate` →
`getReadableDatabase()` → `onConfigure` → execSQL(row-returning PRAGMA) → throw → catch →
"Database error - see logs". Verified by code-path analysis + `PragmaRowBehaviorTest` proving the
row-returning nature that Android rejects.

## 2. Fix
- **Pragmas.apply()** now uses only official no-SQL framework APIs, both documented for `onConfigure`:
  `db.enableWriteAheadLogging()` (WAL) and `db.setForeignKeyConstraintsEnabled(true)` (FK cascade).
- The optional tuning pragma `synchronous=NORMAL` moved **post-open** into `DbHealth`, executed via a
  rawQuery-based, row-safe, failure-tolerant path.
- New **`DbHealth.check(DbHelper)`** — runtime initialization self-test (hotfix task 6): exercises the
  real open path → schema version, table count, FK flag, journal mode, once-per-process tuning pragma,
  and a real write probe (insert+delete in a transaction). Returns a structured `Report` instead of a crash.
- **Safe user-facing fallback (task 5):** `HomeActivity` now shows
  - OK: `Ready ✓ (schema v1 · 10 tables · wal)` — tap for the full self-test detail panel;
  - Failure: `Local storage couldn't start — ReplyMate is still running.` + specific reason
    (exception class + message) + tap-for-details; app process remains alive/usable.
- `DbHelper` hardened: added an explicit `onDowngrade` refusal (no silent destructive rollbacks);
  removed the now-superseded `diagnostics()` (replaced by `DbHealth`).

## 3. Regression prevention (so this class of bug can't return)
- `PlatformGuardTest` — static source scan (runs on JVM, comments stripped): fails the build if any
  `execSQL("PRAGMA…")` reappears in the data layer, and asserts WAL/FK go through framework APIs.
- `PragmaRowBehaviorTest` — JDBC-proof that assignment PRAGMAs return rows (living documentation of
  the trap; runs on JVM).
- The test harness's whitelist guard already fails loudly if platform imports leak into
  platform-neutral packages (it caught `DbHealth` during this hotfix — added to exclusion list).

## 4. Files changed
| File | Change |
|---|---|
| `src/com/replymate/data/db/Pragmas.java` | **Rewritten — root-cause fix** (framework APIs only; crash documented in header) |
| `src/com/replymate/data/db/DbHealth.java` | **New** — runtime DB self-test + row-safe pragma helpers + write probe + post-open tuning |
| `src/com/replymate/data/db/DbHelper.java` | Removed `diagnostics()` (superseded by DbHealth); added `onDowngrade` refusal |
| `src/com/replymate/app/ui/HomeActivity.java` | Safe fallback UI: specific reason, tap-for-details, app keeps running; OK state shows live health line |
| `tests/src/com/replymate/data/PragmaRowBehaviorTest.java` | **New** — documents/proves the row-returning trap |
| `tests/src/com/replymate/data/PlatformGuardTest.java` | **New** — source-scan regression guard |
| `scripts/run_tests.sh` | Two new suites wired; `-Dreplymate.src` for guards; `DbHealth` in platform exclusions |

## 5. Tests performed
- **38/38 JVM tests green** (`scripts/run_tests.sh`): previous 34 + PragmaRowBehavior(2) + PlatformGuard(2).
- During the hotfix the guards themselves caught 2 real issues (comment-text false positive → guard now
  strips comments; `:memory:` DBs can't WAL → assertion generalized). No production-code changes needed from those.
- Migration suite re-verified unchanged on real SQLite: version stamp, 10 tables, indices, FK cascade,
  unique merge key, NULL-tolerant dedupe, CHECK rejections, idempotent re-run.

## 6. New APK validation result
- **Build:** `releases/ReplyMate-0.0.2.apk` — 37,409 bytes, sha256
  `e8a9e87009a36f0ac775be975e95e48c582ad362c85fa91d21d31efce72c3e7a` (logged in `releases/RELEASES.md`).
- **Badging:** `com.replymate.app`, **versionCode 2 / 0.0.2**, minSdk 24 · target 34, launcher present,
  **0 permissions**.
- **Signature:** `apksigner verify` → OK with the persistent arena key → installs **in place over 0.0.1**
  (no uninstall needed; the broken DB file from 0.0.1 will simply complete its first open on next launch —
  the earlier crash happened *before* any table creation).

## 7. What the owner should see on device now
Launch → home shows **`Ready ✓ (schema v1 · 10 tables · wal)`**; tapping the status line shows the full
self-test panel (opened, version, FK on, journal mode, write probe passed). If anything still fails, the
same panel shows the exact reason — send it back verbatim.

## Remaining caveat (honest)
No emulator exists in this workspace, so final confirmation is on your device. The failing mechanism was
pinned to a documented platform behavior, the fix removes it at the source, and the app now self-verifies
and reports precisely on every launch — any residual issue will name itself.
