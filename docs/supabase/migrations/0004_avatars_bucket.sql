-- =====================================================================
-- ReplyMate cloud migration 0004 — public `avatars` storage bucket.
-- P-intelligence-16 integrity audit finding F2.
--
-- Drift fixed: src/com/replymate/app/auth/AvatarStore.java POSTs
--   /storage/v1/object/avatars/<userId>.jpg   (Content-Type image/jpeg, x-upsert=true)
-- and otherwise degrades to its honest "kept on this phone" error forever,
-- because the bucket never existed. storage.buckets was EMPTY.
--
-- Contract created here (root-level object per user, public read — exactly
-- what the app implements):
--   * bucket `avatars`, public = true     → /storage/v1/object/public/avatars/<uid>.jpg serves
--   * SELECT  policy: anyone may read objects of this bucket (public bucket idiom)
--   * INSERT  policy: authenticated, only name = '<own uid>.jpg'
--   * UPDATE  policy: same scope (x-upsert issues POST that may overwrite → needs update)
--   * DELETE  policy: same scope (future avatar removal)
-- RLS on storage.objects is enabled by default on Supabase; these policies
-- are the complete access surface for the bucket. JPEG only in practice via
-- the app; server-side mime whitelist deliberately NOT set (app may switch
-- formats later without a migration; object name + ownership are enforced).
--
-- Idempotent: bucket upserted ON CONFLICT; policies dropped-if-exists then
-- created. No data rows are touched. Applied via the session pooler as
-- postgres (owner of storage schema objects).
-- =====================================================================
begin;

-- ---------- 1. bucket ----------
insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do update set public = excluded.public;

-- ---------- 2. read: public (matches the app's public URL usage) ----------
drop policy if exists avatars_public_read on storage.objects;
create policy avatars_public_read on storage.objects
  for select
  using (bucket_id = 'avatars');

-- ---------- 3. write: authenticated owner-scoped to '<uid>.jpg' ----------
drop policy if exists avatars_owner_insert on storage.objects;
create policy avatars_owner_insert on storage.objects
  for insert to authenticated
  with check (bucket_id = 'avatars' and name = (auth.uid()::text || '.jpg'));

drop policy if exists avatars_owner_update on storage.objects;
create policy avatars_owner_update on storage.objects
  for update to authenticated
  using (bucket_id = 'avatars' and name = (auth.uid()::text || '.jpg'))
  with check (bucket_id = 'avatars' and name = (auth.uid()::text || '.jpg'));

drop policy if exists avatars_owner_delete on storage.objects;
create policy avatars_owner_delete on storage.objects
  for delete to authenticated
  using (bucket_id = 'avatars' and name = (auth.uid()::text || '.jpg'));

-- ---------- 4. record migration ----------
insert into public.rm_schema_migrations (version, label)
values (4, 'P16 integrity: public avatars storage bucket + owner-scoped object policies (F2)')
on conflict (version) do nothing;

-- ---------- 5. PostgREST schema cache reload ----------
notify pgrst, 'reload schema';

commit;

-- ---------- verification (see verify_0003_0004.sql) ----------
-- storage.buckets now lists ('avatars','avatars',public=t); 4 policies on
-- storage.objects; as user A: insert (bucket_id='avatars', name='<A>.jpg') OK;
-- as user B: insert name='<A>.jpg' must be policy-denied.
