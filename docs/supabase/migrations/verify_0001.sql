-- ReplyMate Supabase foundation — verification for migration 0001.
-- Run as postgres role via pooler session:  psql <conn> -f verify_0001.sql
\echo '══ 1. tables present (expect 11: 10 app + rm_schema_migrations) ══'
select table_name from information_schema.tables
 where table_schema='public' and table_type='BASE TABLE' order by 1;

\echo '══ 2. migration ledger ══'
select * from public.rm_schema_migrations order by version;

\echo '══ 3. foreign keys ══'
select conrelid::regclass as tbl, conname, pg_get_constraintdef(oid)
from pg_constraint where contype='f' and connamespace='public'::regnamespace order by 1;

\echo '══ 4. unique checks (user-scoped where required) ══'
select conrelid::regclass as tbl, conname, pg_get_constraintdef(oid)
from pg_constraint where contype in ('u','p') and connamespace='public'::regnamespace
  and conrelid::regclass::text in ('contact_channel','message','memory_fact','contact_summary','app_kv')
order by 1;

\echo '══ 5. indexes ══'
select tablename, indexname from pg_indexes where schemaname='public' order by 1,2;

\echo '══ 6. triggers ══'
select event_object_table as tbl, trigger_name, action_timing, event_manipulation
from information_schema.triggers where trigger_schema='public' order by 1;

\echo '══ 7. RLS enabled on all 10 ══'
select relname as tbl, relrowsecurity from pg_class
where relnamespace='public'::regnamespace and relkind='r' and relname <> 'rm_schema_migrations'
order by 1;

\echo '══ 8. policies ══'
select tablename, policyname, roles, cmd from pg_policies where schemaname='public' order by 1;

\echo '══ 9. check constraints ══'
select conrelid::regclass as tbl, pg_get_constraintdef(oid)
from pg_constraint where contype='c' and connamespace='public'::regnamespace order by 1,2;

\echo '══ 10. functional RLS test — two authenticated users (shell users, rolled back) ══'
begin;
-- throwaway auth.users shells so FK holds; rolls back at the end (no residue)
insert into auth.users (id, instance_id, aud, role, email, encrypted_password,
                        email_confirmed_at, created_at, updated_at, raw_app_meta_data)
values
 ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','smoke-a@rm.local','',now(),now(),now(),'{"provider":"email","providers":["email"]}'),
 ('22222222-2222-2222-2222-222222222222','00000000-0000-0000-0000-000000000000',
  'authenticated','authenticated','smoke-b@rm.local','',now(),now(),now(),'{"provider":"email","providers":["email"]}');
set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}', true);
insert into public.contact (user_id, display_name) values
  ('11111111-1111-1111-1111-111111111111','RLS Smoke A') returning id as a_contact_id \gset
select count(*) as user_a_sees from public.contact;
select set_config('request.jwt.claims',
  '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}', true);
select count(*) as user_b_sees from public.contact;
\echo '(user_b_sees must be 0)'
update public.contact set display_name='hijack' \gset
\echo '(user_b update must affect 0 rows → blocked by RLS)'
\echo '══ 10b. anon INSERT must be policy-denied ══'
set local role anon;
select set_config('request.jwt.claims','{"role":"anon"}', true);
insert into public.app_kv (user_id, key, value)
  values ('11111111-1111-1111-1111-111111111111','anon.poke','x');
reset role;
rollback;

\echo '══ 11. anon access test (expect permission denied) ══'
begin;
set local role anon;
select count(*) from public.contact;
reset role;
rollback;

\echo '══ 12. updated_at trigger smoke ══'
begin;
insert into auth.users (id, instance_id, aud, role, email, encrypted_password,
                        email_confirmed_at, created_at, updated_at, raw_app_meta_data)
values ('33333333-3333-3333-3333-333333333333','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','smoke-c@rm.local','',now(),now(),now(),'{"provider":"email","providers":["email"]}');
-- note: now()/transaction_timestamp is constant inside one txn, so we seed an
-- explicitly OLD updated_at to prove the trigger's touch actually happens.
insert into public.app_kv (user_id, key, value, updated_at)
values ('33333333-3333-3333-3333-333333333333','trigger.smoke','v1', now() - interval '1 hour');
create temp table _t as
 select updated_at as t1 from public.app_kv where key='trigger.smoke';
update public.app_kv set value='v2' where key='trigger.smoke';
select (updated_at > (select t1 from _t)) as trigger_bumped_updated_at
from public.app_kv where key='trigger.smoke';
rollback;
