# P2 Repair Pass v2 — ReplyMate 0.2.2 (vc6) — 2026-07-18

**Owner symptom:** 0.2.1 force-closes immediately on launcher tap.
**Outcome:** found, reproduced in-sandbox, fixed, verified, shipped as 0.2.2 (vc6).
**0.2.1 (vc5) is a BAD BUILD — do not install; if installed, install 0.2.2 over it (same signature).**

Build: `ReplyMate-0.2.2.apk` · vc6 / 0.2.2 · sha256 `c63f868ff0212c9afa3182b6a9732442820bd531445c12810055d0b4fc423f7f` · 134 KB · arena signature · minSdk 24 / target 34 · one permission (`POST_NOTIFICATIONS`).

---

## 1. Root cause (full honesty — two chained failures)

**F1 — accidental source deletion (my mistake, process failure):** while reorganizing the
test harness during the 0.2.1 repair pass, I ran `rm -rf` on a path that traversed a
**symlink into the project tree** (`/tmp/robo-harness/src/main/java/com` →
`ReplyMate/src/com`). This deleted the ENTIRE app-layer source directory
`com/replymate/app/` (18 files: Application, DI container, platform layer, listener
package, all activities).

**F2 — blind build+test gates (toolchain failure):**
- the JUnit gate compiles only core/provider/data — never app sources → **101/101 green
  while a third of the app was missing**;
- `javac` succeeded on the truncated input set (nothing referenced the deleted classes
  except each other), d8 happily packaged the remaining 114 classes, and the APK was
  archived and shipped with **no Application class and no Activities** → guaranteed
  `ClassNotFoundException` at first tap. The 47 KB dex shrink (169 → 114 classes) went
  unnoticed: 0.2.1 was exactly 20,480 bytes smaller than 0.2.0.

So 0.2.1's crash is NOT a product-logic bug; it's a broken artifact. Note the P2 listener
fixes reported separately (contact-creation race, parser hardening, diagnostics) were
correct and are carried in 0.2.2 unchanged.

## 2. Recovery method

- No VCS existed. Reconstructed the 18 app-layer files by:
  (a) decompiling **the 0.2.0 release artifact with jadx** (authoritative compiled state);
  (b) rewriting from this session's exact file reads the 9 files where that source was
      equal or better fidelity (`ReplyMateApp`, `Tasks`, all 4 listener files incl. the
      P2-repair versions, `HomeActivity`, `SettingsActivity` incl. the repair diagnostics);
- Re-applied the P2-repair deltas; `ContactService` (core) was never lost and keeps its fix.
- Compilation correctness enforced by `javac` + the full test battery (see §4).
- Incidental correction during restore: About row now reads versionName/versionCode from
  PackageManager instead of a hardcoded stale string.

## 3. Prevention (toolchain, now active)

| Safeguard | Where |
|---|---|
| **Dex integrity gate**: after d8, fail the build unless the dex contains the `<application android:name>` class AND the launchable activity (grep -a, SIGPIPE-safe). Manually verified it REJECTS the broken 0.2.1 artifact. | `apk-engine/build.sh` |
| **Local git**: repo initialized; baseline + safeguard commits recorded. | `ReplyMate/.git` |
| **Harness hygiene rule**: harness now uses copied files (no symlinks into the product tree anywhere). | `rm-harness/` |

## 4. Final test results (0.2.2)

| Check | Result |
|---|---|
| JVM unit suites | **101/101 PASS** |
| Robolectric fixtures (real AOSP-34 MessagingStyle → real extractor + pipeline) | **7/7 PASS** |
| Dex content vs 0.2.0 baseline | **169/169 classes restored** (0.2.1 had 114) — incl. `ReplyMateApp`, `AppContainer`, all activities, listener package |
| APK badging / perms / listener service / signature | vc6·0.2.2 · sdk 24/34 · POST_NOTIFICATIONS only · service exported+bound+filter ✓ · `apksigner` OK |
| Build dex-gate | OK (and proven to reject the broken 0.2.1 artifact) |
| Device launch (owner) | awaiting re-test — expected resolved |

## 5. What still does not work / open items (unchanged from P2-repair-0.2.1.md)

The longer-standing P2 questions remain open and 0.2.2's new Diagnostics counters
(`parse errors`, `unparsed (non-chat) notifs`) are there to answer them on the next soak:
exact original symptom, device model + Android version, regular vs Business/forked
WhatsApp/Telegram, and OEM battery-manager behavior toward the listener.

## 6. Files changed in this pass

- Restored 18 files under `src/com/replymate/app/**` (recovery; semantically 0.2.0 + repair).
- `apk-engine/build.sh`: +dex integrity gate (+SIGPIPE-safe grep).
- `src/com/replymate/app/ui/SettingsActivity.java`: About row reads live version.
- `releases/RELEASES.md`: 0.2.1 marked broken; 0.2.2 row added.
- `rm-harness/`: pom antrun `<target>` fix; README note (no symlinks).
