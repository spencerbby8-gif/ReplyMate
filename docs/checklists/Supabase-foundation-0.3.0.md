# Phase report — P2 verification + Supabase foundation (0.3.0 / vc7) — 2026-08-06

Build: `ReplyMate-0.3.0.apk` · vc7 / 0.3.0 · sha256 `803a5c19fa4669ee907fd74c9e0354a7cb6456c7d75a312c082293ff69568e44` · ~83 KB · arena signature (updates in place) · minSdk 24 / target 34 · permissions: INTERNET + POST_NOTIFICATIONS only.

## Part 1 — P2 verification pass results
| Area checked | Result |
|---|---|
| Source tree integrity (git) + full 108-file compile | OK |
| Unit suites | **105/105 PASS** (incl. new SupabaseConfig contract test) |
| Robolectric listener fixtures (real AOSP-34 MessagingStyle) | **7/7 PASS** |
| Manifest (binary) — service exported+bound+intent-filter, launcher, label | OK |
| **Bug found & fixed: `INTERNET` permission missing** — every Gemini network call on device would have been denied at socket level; key-entry screens worked offline, masking it. Added; also required for future Supabase REST | FIXED |
| Release integrity — dex gate, signature, zip, resources, no stray `.idsig` (v4 sidecars now disabled in build engine) | OK |

## Part 2 — Supabase foundation (applied LIVE, then verified LIVE)
Connection: owner's Direct URI host is IPv6-only and this sandbox has no IPv6 route, so
migrations ran over the Supavisor session pooler (us-east-1) with the same credentials.
No secrets committed to the repo.

Applied migration `0001_foundation.sql` (commit + ledger row recorded):
- **10 cloud tables** mirroring blueprint schema v1 + `rm_schema_migrations`
- **19 FKs** incl. `user_id → auth.users` on every table (Auth-ready)
- **User-scoped uniques** (`(user_id,channel,remote_key)`, `(user_id,channel,notif_key)`, `app_kv(user_id,key)`) — no cross-user collisions
- **CHECK constraints** verbatim (whatsapp/telegram/manual; in/out; listener/manual/import; 6 fact categories; draft statuses; usage kinds; scopes; provider type)
- **timestamps + `set_updated_at` BEFORE-UPDATE trigger** on all 10 tables and `deleted_at` tombstones (sync-protocol plumbing only)
- **RLS on all 10 tables**, per-table owner policies `FOR ALL TO authenticated` (`auth.uid() = user_id`)
- grants: `authenticated` CRUD (gated by RLS); **anon gets zero rows and is policy-denied on write**

Live functional verification (script `docs/supabase/migrations/verify_0001.sql`, all rolled back):
user A sees own row; **user B sees 0 rows and UPDATEs 0 rows**; anon SELECT returns 0, anon INSERT rejected by RLS; update trigger fires (`updated_at` advanced).

## What the app does with Supabase (precisely nothing extra)
`core/supabase/SupabaseConfig.java` holds the publishable endpoint + `SYNC_ENABLED = false`
(pinned by test). Settings → Diagnostics shows "Cloud (foundation): endpoint …, 10 tables
provisioned, sync OFF (local-first)". No auth, no REST calls, no background sync, no local
schema change, no feature changes. Local storage remains the source of truth.

## Explicitly NOT done (per order)
Full sync, Supabase Auth, Gemini changes, Paystack, notifications changes, sending,
Accessibility, premium features, redesign.

## Test totals
105/105 JVM unit · 7/7 Robolectric fixtures · live-DB structural inventory 12/12 sections green · live RLS functional checks green · APK artifact checks all green.

**Stopped here. Awaiting your approval before any further phase (e.g., P3 draft-UX, Supabase Auth, or sync).**
