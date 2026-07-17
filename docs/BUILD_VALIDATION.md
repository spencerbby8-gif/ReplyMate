# ReplyMate — Android Build Validation Report

Date: 2026-07-17
Branch: `arena/019f71f7-replymate`

## 1. Architecture review (before changes)

ReplyMate is an offline-first, single-activity Jetpack Compose Android app
(`com.replymate`, `minSdk = 29`, `targetSdk/compileSdk = 35`) organized into the
package boundaries documented in `docs/REPLYMATE_SPECIFICATION.md`:

```
core/model          domain types (profile, writing style, conversation, memory)
core/persistence    encrypted Room schema (17 entities, 6 DAOs, version 6, 5 migrations)
core/security       Keystore-backed EncryptedSharedPreferences (SQLCipher passphrase + API keys)
core/settings       DataStore app settings
core/ai             deterministic prompt assembly, token budgeting, provider boundary, Gemini REST client
core/conversation   conversation/memory engine, retrieval policy, update planner, memory inspector
core/platform       notification-listener pipeline, Telegram/WhatsApp adapters, event queue/dispatcher
core/reasoning      heuristic analyzers, response planning, quality evaluation, response pipeline
core/draft          review-only draft model/repository/generation service
core/diagnostics    sanitized local diagnostics + in-memory performance monitor
core/network        connectivity monitor
core/notifications  notification-access helpers + listener service shell
feature/*           Compose screens (onboarding, home, settings, AI playground, memory inspector,
                    drafts, draft review, platform events, diagnostics)
ui/theme            Material 3 theme
```

The data layer is SQLCipher-encrypted Room (`net.sqlcipher` legacy package) with
the random DB passphrase stored separately in Keystore-backed
`EncryptedSharedPreferences`; Gemini API keys live in a second encrypted store.
The notification listener normalizes/queues events and never calls AI or sends.

The repository had **no Gradle wrapper**, **no JDK/Android SDK present**, and
(per the spec's own Phase 8 note) had **never been compiled**. This validation
restored the build system and corrected every verified compile-time defect found
during a full source audit of all 54 Kotlin sources + 9 unit tests.

## 2. Environment / toolchain restoration

| Item | Action |
|---|---|
| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`) | **Restored.** Authentic Gradle 8.7 wrapper files (the version AGP 8.5.2 requires) fetched from the Gradle project and committed. `gradlew` is executable; the jar is a valid `PK` archive (43,453 B, 33 entries). |
| Gradle distribution | Pinned to **8.7** (`gradle-8.7-bin.zip`) — the matched version for AGP 8.5.2. |
| AGP / Kotlin / KSP / Compose | Left as authored: AGP 8.5.2, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.06.00 (Material3 1.2.1), Room 2.6.1. These are mutually compatible. |
| `compileSdk = 35` vs AGP 8.5.2 | AGP 8.5.x was tested up to SDK 34. Added `android.suppressUnsupportedCompileSdk=35` to `gradle.properties` to silence the compatibility warning while keeping SDK 35. |
| `android.nonTransitiveRClass=true` | Added (default for new AGP projects; faster/resource-isolating R classes). |

> **Build prerequisite on a developer machine:** set `sdk.dir` in `local.properties`
> (or `ANDROID_HOME`) to an Android SDK containing platform `android-35` and the
> latest `build-tools`/`platform-tools`, and use JDK 17. `local.properties` is
> git-ignored by design.

## 3. Compile errors found and fixed

All fixes are minimal and behavior-preserving (no redesign, no new features).

| # | File | Defect | Fix |
|---|---|---|---|
| 1 | `core/platform/PlatformEventPipeline.kt` (`EventValidator.validate`) | `n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0` — Kotlin parses this as `n.flags and (FLAG_GROUP_SUMMARY != 0)` because named infix `and` has **lower precedence than `!=`**. That yields `Int and Boolean`, a type error. | Added parentheses: `(n.flags and Notification.FLAG_GROUP_SUMMARY) != 0`. |
| 2 | `feature/diagnostics/DiagnosticsScreen.kt` | The fourth `Card { Column { … } }` was missing its closing `}` (one unmatched `{`), so the file's braces did not balance → "expecting `}`" compile error. The "Export" button/`exportPath` row were accidentally being nested inside the unclosed Card. | Added the missing `}` to close the Card; reformatted the block for clear nesting. |
| 3 | `feature/ai/AiPlaygroundScreen.kt` (`GenerationHistory`) | The `GenerationHistory` composable had 20 `{` vs 19 `}` (one missing closing brace) → unbalanced file. | Reformatted `GenerationHistory` to a correctly-braced multi-line form with identical logic (empty-state, per-item card with Copy/Compare/Delete, comparison-target card, regenerate button). |
| 4 | `gradle/libs.versions.toml` | `net.zetetic:android-database-sqlcipher:4.6.1` **does not exist**. Zetetic deprecated the legacy `android-database-sqlcipher` artifact at 4.5.5 (Aug 2023) and stopped publishing it to Maven Central; version 4.6.1 only exists for the *new* `net.zetetic:sqlcipher-android` artifact, which uses a different package (`net.zetetic.database.sqlcipher.*` / `SupportOpenHelperFactory`). The code uses the **legacy** `net.sqlcipher.database.SupportFactory` / `SQLiteDatabase.loadLibs` APIs and the 3-arg `SupportFactory(byte[], SQLiteDatabaseHook, boolean)` constructor. | Pinned SQLCipher to **4.5.4** — the last legacy version published to Maven Central — which matches the existing imports and constructor exactly (verified against Zetetic's published `SupportFactory` constructor list). This is the minimal change that makes the dependency resolve and the existing code compile. |

After the fixes, an automated brace/paren/bracket balance check across **all 63
Kotlin files reports zero imbalances**, and all cross-module references (DI wiring
in `ReplyMateApplication`, Compose screen signatures, DAO return types, sealed-type
exhaustiveness, `ReasonedDraft`/`ReplyDraft` field accesses) were cross-checked
and are consistent.

## 4. Build status / APK generation

**Not executed in this sandbox — blocked by a hard network restriction, not by code.**

This environment has no JDK, no Android SDK, no Gradle distribution, and no cached
Maven artifacts. The only reachable hosts are `github.com`, `codeload.github.com`,
`api.github.com`, `registry.npmjs.org`, and `pypi.org` / `files.pythonhosted.org`.
Every host required for an Android build is blocked at the TLS layer
(`SSL_ERROR_SYSCALL`):

| Required for | Host | Reachable? |
|---|---|---|
| Gradle distribution | `services.gradle.org`, `downloads.gradle.org` | ❌ blocked |
| Android SDK (platform-tools, build-tools, platforms, cmdline-tools) | `dl.google.com` | ❌ blocked |
| Google Maven (AGP, KSP, Compose, Room, navigation, datastore, security-crypto) | `maven.google.com` | ❌ blocked |
| Maven Central (Kotlin, SQLCipher, lifecycle, activity, etc.) | `repo1.maven.org` / `repo.maven.apache.org` | ❌ blocked |
| GitHub release assets | `*.githubusercontent.com` | ❌ blocked |

There is no legitimate npm/pip/GitHub mirror that carries the Android SDK platform
(`android.jar` for API 35), `aapt2`/`d8`, the Gradle distribution, or the full
transitive Maven dependency set. `apt`/`conda`/GitHub release downloads are also
blocked. Therefore an actual `./gradlew assembleDebug` cannot run here: the wrapper
itself starts correctly but halts at the JDK check (`ERROR: JAVA_HOME is not set and
no 'java' command could be found`), and even with a JDK it could not download the
Gradle distribution or resolve dependencies.

This matches the spec's Phase 8 report, which states the project "could not be
executed" in this environment for the same reason.

**To produce the debug APK**, run on an internet-connected machine with JDK 17 and
the Android SDK installed:

```bash
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lint test                   # optional: unit tests + lint
```

## 5. Runtime considerations (verified by static review)

- `ReplyMateApplication.onCreate` builds the whole object graph eagerly. All
  constructor arities in the wiring were verified to match their declarations.
- DB migrations (1→6) SQL matches the entity column sets, including the v3→4
  `ALTER TABLE memory_records` columns and the v5→6 draft tables.
- The notification listener only routes normalized events into the conversation
  engine and never invokes AI/sends, matching the spec.
- `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE` and the
  `BIND_NOTIFICATION_LISTENER_SERVICE` service are declared in the manifest.

## 6. Remaining known issues / recommendations (not addressed — out of build scope)

1. **SQLCipher 4.5.4 + 16 KB page-size devices.** Legacy SQLCipher < 4.6.0 does
   not support Android 15+ devices that use a 16 KB native page size. It runs
   correctly on standard 4 KB-page emulators/devices. If targeting such devices,
   migrate to `net.zetetic:sqlcipher-android:4.6.1+` (requires switching imports
   to `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` and
   `System.loadLibrary("sqlcipher")`). Tracked as a future migration, not a
   build blocker.
2. **Room `exportSchema = true` with no `room.schemaLocation`.** This emits a
   warning (not an error) at compile time. For migration validation, add
   `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` and commit the
   exported JSON schemas.
3. **`getParcelableArray("android.messages")` / deprecated `Bundle` accessors.**
   These compile with deprecation warnings under `compileSdk = 35`. Optionally
   migrate to the typed `getParcelableArray(key, Class)` overloads.
4. Instrumentation tests, real Room/SQLCipher migration validation on retained
   data, on-device profiling, and the accessibility audit remain open per the
   spec's Phase 8 checklist.
