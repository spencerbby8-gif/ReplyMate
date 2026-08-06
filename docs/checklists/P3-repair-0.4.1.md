# P3 Repair Completion — ReplyMate 0.4.1 (versionCode 9)

**Date:** 2026-08-06 · **Scope:** owner-reported on-device issues only. No redesign
of working parts; no new providers added (incremental-providers rule respected —
the registry/framework stays the extension point, existing parsers fixed/verified,
nothing new bolted on). Local-first, sync OFF, no Auth/Paystack/cloud,
no Accessibility/scraping/unofficial APIs/auto-replies.

## Deliverable

| file | sha256 | size |
|---|---|---|
| `releases/ReplyMate-0.4.1.apk` | `b19235b65fe6218221cb3540276587e9e284882e16305d6f617e300dd597508d` | 102K |

Signer cert SHA-256 `b15f2f37…6a85ed` — **identical to 0.2.2–0.4.0 → update-in-place.**

## Root cause of the main report (found & fixed)

**Android 11+ package-visibility filtering.** With `targetSdk 34` and no `<queries>`
block in the manifest, the OS makes `PackageManager.getPackageInfo("com.whatsapp")`
throw `NameNotFoundException` even though WhatsApp IS installed — hence "WhatsApp
and Discord show as not installed". The same filtering also broke the deep-link
fallback (`getLaunchIntentForPackage` → null).
**Fix:** `<queries>` entries for exactly the 11 catalog packages + `com.android.vending`
(official store page for genuinely missing apps). Verified present in the shipped
binary manifest (aapt2 xmltree, lines 19–30).

## Repair checklist (owner's items)

| Owner item | Status |
|---|---|
| Sources detects installed apps (WA/Discord ≠ "not installed") | **Fixed** (`<queries>` + detection plumbing robo-tested) |
| Labels/status from real detection, not static defaults | **Fixed** — installed = live pm query; enabled = kv; counters = ListenerStats; tier moved into `WatchedApps.AppDef` next to the parser it describes (no parallel UI map); summary "N of 11 supported apps installed" |
| No dead Settings rows | **Fixed** — About now expands real build/runtime info (version, vc, min/target/device SDK, signer digest, listener state, watch flags, sync OFF); "ReplyMate notifications" always performs a real action (runtime grant on 33+, else system notification settings); access row & diagnostics accordion verified working |
| Broken nav/empty actions/placeholders | **Fixed** — Sources cards: installed → real toggle w/ immediate render; not installed → official store page; access row → system listener settings; full-render rebuild also fixed a stray `removeViewAt` cosmetic bug from 0.4.0 |
| Static/incomplete discovery & diagnostics logic | **Fixed** — Home chip counts enabled **and** installed apps only; Diagnostics per-app stats show `[on]/[off]` + stats ONLY for installed apps, with a compact "not installed: …" line |
| Don't redesign working parts | Kept — parsers, ingest, draft cards, tones, audit, usage untouched |

## Guards so this class of bug can't regress

- **`ManifestCatalogTest`** (static, JVM): every catalog package must appear in
  `<queries>` (and vice versa — no strays); permissions stay exactly
  INTERNET + POST_NOTIFICATIONS (no QUERY_ALL_PACKAGES); all 9 activities +
  listener service + Application registered.
- **Tier↔parser wiring test**: every `MessagingStyleParser` ⇒ FULL, Slack/Discord ⇒
  PARTIAL, category-gated ⇒ LIMITED, manual ⇒ no tier.
- **`PackageDetectionRoboTest`** (Robolectric, real PackageManager): installed ⇒
  detected, unknown ⇒ false/never crash. (Robolectric does not simulate `<queries>`
  filtering — that layer is covered by the manifest guard + binary-manifest dump.)
- Deep-link paths re-verified via `ChatLinkTest` + sentinel dex scan.

## Verification evidence

- **Unit (JVM): 164/164 OK** across 28 suites (`scripts/run_tests.sh`).
- **Robolectric (real AOSP API-34): 13/13** (10 extractor/pipeline + 3 detection).
- **APK:** vc=9 / 0.4.1 · minSdk 24 / targetSdk 34 · permissions exactly
  `INTERNET` + `POST_NOTIFICATIONS` · binary manifest: `<queries>` × 12 entries,
  9 activities, listener service w/ `BIND_NOTIFICATION_LISTENER_SERVICE`,
  launcher = HomeActivity · dex gate OK (Application + launcher) · sentinel
  classes present (ReplyMateApp, SourcesActivity, DeepLinks, WatchedApps,
  RmNotificationListener) · resources.arsc + 15 res entries · zip integrity OK ·
  **no .idsig** · signature valid + identical to prior releases.
- Notification parsing: unchanged logic re-green (10 robo + 13 parser/registry unit
  suites) — no behavior change intended or observed.

## For the owner to verify on device

1. Install **over 0.4.0** (same cert, keeps data).
2. Settings → Notification sources: WhatsApp **and Discord must read "installed ✓"**
   with real counters; tapping toggles ON/OFF immediately.
3. Settings rows: About expands, notifications row opens real system screen.
4. Home chip should read e.g. "Listener on · WhatsApp, Discord" once access is on.

**If this is clean on your device, I start P4 next per your instruction.**
