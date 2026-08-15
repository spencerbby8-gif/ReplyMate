-- =====================================================================
-- ReplyMate cloud migration 0003 — revoke anon surface + extra grants.
-- P-intelligence-16 integrity audit finding F1.
--
-- Drift fixed: Supabase platform DEFAULT PRIVILEGES granted ALL privileges
-- on every new public table to BOTH `anon` and `authenticated`. Migration
-- 0001 added explicit CRUD grants to `authenticated` and documented
-- "anon gets NOTHING" — but never REVOKEd, so live state held:
--   anon:          SELECT INSERT UPDATE DELETE TRUNCATE REFERENCES TRIGGER  (×11 tables)
--   authenticated: the same 7 (0001 intended exactly CRUD ×4 on app tables)
-- RLS blocked every anon path (policies denied/no policy), so this was
-- defense-in-depth surface, not a live breach. This migration makes the
-- live grants equal the 0001 contract.
--
-- Idempotent: REVOKE is a no-op when the privilege is already absent;
-- ledger insert is ON CONFLICT DO NOTHING. Applied via psql/psycopg2 over
-- the Supavisor session pooler as the postgres role (owner of public tables).
-- =====================================================================
begin;

-- ---------- 1. anon: NOTHING on every ReplyMate table (0001 §5 contract) ----------
revoke all privileges on table
  public.style_profile, public.contact, public.contact_channel, public.message,
  public.memory_fact, public.contact_summary, public.provider_def, public.draft,
  public.usage_event, public.app_kv
from anon;

-- ---------- 2. authenticated: exactly CRUD (0001 §5) — drop default-privilege extras ----------
revoke truncate, references, trigger on table
  public.style_profile, public.contact, public.contact_channel, public.message,
  public.memory_fact, public.contact_summary, public.provider_def, public.draft,
  public.usage_event, public.app_kv
from authenticated;

-- ---------- 3. ledger is owner-tooling-only (postgres role via pooler writes it) ----------
revoke all privileges on table public.rm_schema_migrations from anon;
revoke all privileges on table public.rm_schema_migrations from authenticated;
-- (RLS on the ledger stays enabled with no policies; belt + braces.)

-- ---------- 4. record migration ----------
insert into public.rm_schema_migrations (version, label)
values (3, 'P16 integrity: anon revoked (0001 contract), authenticated extras removed, ledger locked')
on conflict (version) do nothing;

-- ---------- 5. PostgREST picks up the grant change without a restart ----------
notify pgrst, 'reload schema';

commit;

-- ---------- verification (see verify_0003_0004.sql) ----------
-- Anon must now hold zero privileges on all 11 tables; authenticated exactly
-- SELECT/INSERT/UPDATE/DELETE on the 10 app tables and nothing on the ledger.
-- Functional: as `anon`, SELECT public.contact must ERROR 42501 (previously
-- returned 0 rows through RLS); authenticated owner flows unchanged.
