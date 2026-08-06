-- ReplyMate cloud migration 0002 — widen contact_channel.channel CHECK (P3 parity).
--
-- On-device schema v2 (data/db/SchemaV2.java) now accepts the full watched-app set:
-- whatsapp, telegram, manual, signal, gmessages, messenger, slack, discord,
-- instagram, x, tiktok. The cloud foundation (0001) still limited channel to the
-- P2 trio. Sync stays DISABLED in the app (SupabaseConfig.SYNC_ENABLED=false),
-- so no rows flow yet — this keeps the cloud contract parity-ready for the phase
-- where sync is switched on.
--
-- Idempotent: constraint is dropped (if present) and recreated with the widened set.
-- Existing rows are untouched (the new CHECK is a superset of the old one).

begin;

alter table public.contact_channel
  drop constraint if exists contact_channel_channel_check;

alter table public.contact_channel
  add constraint contact_channel_channel_check
  check (channel in (
    'whatsapp','telegram','manual','signal','gmessages','messenger',
    'slack','discord','instagram','x','tiktok'
  ));

insert into public.rm_schema_migrations (version, label)
values (2, 'p3 parity: widen contact_channel.channel check to the full watched-app set')
on conflict (version) do nothing;

commit;

-- ---------- verification (run manually; expect the 11 wires + version row) ----------
-- select conname, pg_get_constraintdef(oid)
--   from pg_constraint where conname = 'contact_channel_channel_check';
-- select version, label from public.rm_schema_migrations order by version;
