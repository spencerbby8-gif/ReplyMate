-- ReplyMate Supabase — verification for migrations 0003 + 0004 (P-intelligence-16).
-- Read-only structural checks; functional probes write NOTHING permanently
-- (every mutation happens inside a rolled-back transaction).
-- Run as postgres role via pooler session:  psql <conn> -f verify_0003_0004.sql

\echo '══ 1. grants after 0003 — anon must be EMPTY on all 11 ══'
select table_name, grantee, string_agg(privilege_type, ',' order by privilege_type) as privs
from information_schema.role_table_grants
where table_schema='public' and grantee in ('anon','authenticated')
group by table_name, grantee order by 1, 2;
-- expect: only authenticated rows, privs = DELETE,INSERT,SELECT,UPDATE on the 10
--         app tables; NO row for anon anywhere; NO authenticated row for rm_schema_migrations.

\echo '══ 2. migration ledger (expect versions 1..4) ══'
select version, label, applied_at from public.rm_schema_migrations order by version;

\echo '══ 3. RLS still enabled everywhere ══'
select relname as tbl, relrowsecurity from pg_class
where relnamespace='public'::regnamespace and relkind='r' order by 1;

\echo '══ 4. avatars bucket (expect public=t) ══'
select id, name, public from storage.buckets where id='avatars';

\echo '══ 5. avatars policies on storage.objects (expect 4) ══'
select policyname, cmd, roles from pg_policies
where schemaname='storage' and tablename='objects' and policyname like 'avatars%' order by 1;

\echo '══ 6. functional regression — RLS intact after revoke (rolled back) ══'
begin;
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
insert into public.contact (user_id, display_name)
  values ('11111111-1111-1111-1111-111111111111','RLS Smoke A');
select count(*) as user_a_sees from public.contact;            -- expect 1
select set_config('request.jwt.claims',
  '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}', true);
select count(*) as user_b_sees from public.contact;            -- expect 0 (isolation intact)
reset role;
rollback;

\echo '══ 7. anon SELECT now hard-denied at grant level (expect ERROR 42501) ══'
begin;
set local role anon;
select set_config('request.jwt.claims','{"role":"anon"}', true);
select count(*) from public.contact;                            -- ERROR: permission denied
reset role;
rollback;

\echo '══ 8. avatar object scoping (rolled back) ══'
begin;
set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}', true);
insert into storage.objects (bucket_id, name, owner_id)
values ('avatars','11111111-1111-1111-1111-111111111111.jpg','11111111-1111-1111-1111-111111111111');
\echo '(A inserting A.jpg must succeed)'
select set_config('request.jwt.claims',
  '{"sub":"22222222-2222-2222-2222-222222222222","role":"authenticated"}', true);
insert into storage.objects (bucket_id, name, owner_id)
values ('avatars','11111111-1111-1111-1111-111111111111.jpg','22222222-2222-2222-2222-222222222222');
\echo '(B inserting A.jpg must be policy-denied)'
select count(*) as b_reads_a_avatar from storage.objects
 where bucket_id='avatars';                                    -- 1 (public read by design)
reset role;
rollback;

\echo '══ 9. updated_at trigger still firing (rolled back) ══'
begin;
insert into auth.users (id, instance_id, aud, role, email, encrypted_password,
                        email_confirmed_at, created_at, updated_at, raw_app_meta_data)
values ('33333333-3333-3333-3333-333333333333','00000000-0000-0000-0000-000000000000',
 'authenticated','authenticated','smoke-c@rm.local','',now(),now(),now(),'{"provider":"email","providers":["email"]}');
insert into public.app_kv (user_id, key, value, updated_at)
values ('33333333-3333-3333-3333-333333333333','trigger.smoke','v1', now() - interval '1 hour');
create temp table _t as select updated_at as t1 from public.app_kv where key='trigger.smoke';
update public.app_kv set value='v2' where key='trigger.smoke';
select (updated_at > (select t1 from _t)) as trigger_bumped_updated_at
from public.app_kv where key='trigger.smoke';                   -- expect t
rollback;
