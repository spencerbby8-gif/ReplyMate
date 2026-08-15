# ReplyMate — Supabase backend (foundation phase, 2026-08-06)

Owner-approved scope: **foundation only**. The cloud mirrors the blueprint schema and is
locked down with RLS so a future, separately-approved Auth+sync phase can build on it.
**The app remains local-first: local storage is the source of truth, no sync runs.**

## Endpoints (public by design)
- Project URL: `https://xdcsxxwhvpiissetbrdr.supabase.co`
- Publishable (anon) key: embedded in-app at `core/supabase/SupabaseConfig.java`
  (public-by-design — DB is guarded by RLS policies, not by key secrecy).
- **`SYNC_ENABLED = false`** in the config, pinned by `SupabaseConfigTest`.

## Migrations
| Version | File (repo) | Applied to live DB | Ledger row (`public.rm_schema_migrations`) |
|---|---|---|---|
| 0001 | `docs/supabase/migrations/0001_foundation.sql` | 2026-08-06 18:05 UTC | version 1 |
| verify | `docs/supabase/migrations/verify_0001.sql` | re-runnable structural + functional check | — |
| 0002 | `docs/supabase/migrations/0002_widen_channel_checks.sql` | 2026-08-06 20:42 UTC | version 2 |
| 0003 | `docs/supabase/migrations/0003_revoke_anon_surface.sql` | P-16 audit apply (see checklist P-intelligence-16-1.6.4) | version 3 |
| 0004 | `docs/supabase/migrations/0004_avatars_bucket.sql` | P-16 audit apply (see checklist P-intelligence-16-1.6.4) | version 4 |
| verify | `docs/supabase/migrations/verify_0003_0004.sql` | re-runnable structural + functional check | — |

Ledger note: the project tracks applied migrations in `public.rm_schema_migrations`
(verified live by the P-16 audit). The official CLI history table
`supabase_migrations.schema_migrations` is intentionally absent — migrations were applied
via psql over the pooler, never `supabase db push`, and the repo files use `000N_*.sql`
names rather than CLI timestamps. Accepted debt (P-16 finding F3): adopt CLI-shaped names
+ `supabase migration repair` only if a future owner-approved tooling phase wants
`supabase migration list` parity.

How applied: `psql` over the Supavisor **session pooler** (us-east-1). The owner's Direct
Connection URI host is IPv6-only and this sandbox has no IPv6 route; the pooler
(`aws-0-us-east-1.pooler.supabase.com`, user `postgres.<project-ref>`) reaches the same
database with the same credentials. Passwords/keys are NOT stored in this repo.

## What 0001 provisions (verified live — see completion report)
- 10 app tables mirroring `data/db/SchemaV1.java` + `rm_schema_migrations` ledger
- 19 foreign keys (incl. `user_id → auth.users` everywhere, circular style_profile/contact handled via deferred constraints)
- user-scoped uniques: `(user_id,channel,remote_key)`, `(user_id,channel,notif_key)`, app_kv PK `(user_id,key)`
- blueprint CHECK constraints translated verbatim (channels, directions, sources, categories, kinds, statuses, scopes)
- `set_updated_at` BEFORE UPDATE trigger on all 10 tables (firing — verified)
- `deleted_at` tombstones + now() timestamps for the future sync protocol
- RLS enabled on all 10 tables; per-table `FOR ALL TO authenticated` owner policies
  (`using/with check auth.uid() = user_id`), verified: user A sees own rows, user B sees
  none and updates 0, anon reads none and INSERT is policy-denied.

## Deliberately NOT done this phase
Auth flows, REST/Realtime sync code, Gemini changes, Paystack, premium, sending,
accessibility, notification changes beyond config display, any local schema change.
Schema type translations (sqlite-millis → timestamptz; 0/1 → boolean) are mapped at the
future sync layer and documented here so the local app is untouched.
