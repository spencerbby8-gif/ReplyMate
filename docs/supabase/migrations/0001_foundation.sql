-- =====================================================================
-- ReplyMate — Supabase foundation migration 0001
-- Mirrors the app blueprint schema v1 (BLUEPRINT §3.2, data/db/SchemaV1.java)
-- into the cloud, translated to PostgreSQL idiom, and adds ONLY the
-- plumbing a future, separately-approved Auth+sync phase will need:
--   * user_id (auth.users FK) on every table      → row ownership for RLS
--   * created_at/updated_at timestamptz defaults  → timestamps everywhere
--   * updated_at touch trigger on every table     → change tracking
--   * deleted_at tombstone                        → future delete propagation
--   * secure per-user RLS on every table          → zero cross-user access
-- NOT included (by owner order): sync logic, auth flows, Gemini, Paystack,
-- notifications, sending, premium. Local storage remains source of truth.
-- =====================================================================
begin;

-- ---------- 0. migration ledger ----------
create table if not exists public.rm_schema_migrations (
  version     integer primary key,
  label       text not null,
  applied_at  timestamptz not null default now()
);

-- ---------- 1. shared trigger function ----------
create or replace function public.tg_set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- ---------- 2. tables (blueprint order: style_profile first; contact refs it) ----------

create table public.style_profile (
  id               bigint generated always as identity primary key,
  user_id          uuid references auth.users(id) on delete cascade,
  scope            text not null check (scope in ('global','contact')),
  contact_id       bigint null, -- FK attached after contact exists (circular ref)
  sample_messages  text not null default '',
  derived_rules    text not null default '',
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

create table public.contact (
  id                 bigint generated always as identity primary key,
  user_id            uuid references auth.users(id) on delete cascade,
  display_name       text not null,
  relationship_type  text not null default '',
  relationship_notes text not null default '',
  tone_override      text not null default '',
  language_pref      text not null default '',
  style_profile_id   bigint null, -- FK attached after style_profile exists (circular ref)
  ai_enabled         boolean not null default true,
  memory_enabled     boolean not null default true,
  private_mode       boolean not null default false,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  deleted_at         timestamptz
);

-- close the circular reference now that both tables exist
alter table public.contact
  add constraint contact_style_profile_fk
  foreign key (style_profile_id) references public.style_profile(id) on delete set null;
alter table public.style_profile
  add constraint style_profile_contact_fk
  foreign key (contact_id) references public.contact(id) on delete cascade;

create table public.contact_channel (
  id           bigint generated always as identity primary key,
  user_id      uuid references auth.users(id) on delete cascade,
  contact_id   bigint not null references public.contact(id) on delete cascade,
  channel      text not null check (channel in ('whatsapp','telegram','manual')),
  remote_key   text not null,
  last_seen_at timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  -- user-scoped: 'Amara' on my WA must never collide with another user's
  unique (user_id, channel, remote_key)
);

create table public.message (
  id         bigint generated always as identity primary key,
  user_id    uuid references auth.users(id) on delete cascade,
  contact_id bigint not null references public.contact(id) on delete cascade,
  channel    text not null,
  direction  text not null check (direction in ('in','out')),
  body       text not null,
  sent_at    timestamptz not null,
  notif_key  text,
  source     text not null check (source in ('listener','manual','import')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (user_id, channel, notif_key)
);
create index idx_message_contact_ts on public.message (contact_id, sent_at);

create table public.memory_fact (
  id                 bigint generated always as identity primary key,
  user_id            uuid references auth.users(id) on delete cascade,
  contact_id         bigint not null references public.contact(id) on delete cascade,
  category           text not null check (category in ('person','preference','event','relation','comm_style','boundary')),
  text               text not null,
  text_norm          text not null,
  importance         integer not null default 3,
  confidence         real not null default 0.7,
  pinned             boolean not null default false,
  disabled           boolean not null default false,
  source_message_id  bigint null references public.message(id) on delete set null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  deleted_at         timestamptz,
  unique (contact_id, text_norm)
);
create index idx_fact_contact_active on public.memory_fact (contact_id, disabled);

create table public.contact_summary (
  id               bigint generated always as identity primary key,
  user_id          uuid references auth.users(id) on delete cascade,
  contact_id       bigint not null references public.contact(id) on delete cascade,
  summary_text     text not null,
  covers_until_ts  timestamptz not null,
  version          integer not null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  unique (contact_id, version)
);

create table public.provider_def (
  id         bigint generated always as identity primary key,
  user_id    uuid references auth.users(id) on delete cascade,
  type       text not null check (type in ('gemini')),
  label      text not null default 'Gemini',
  base_url   text not null default 'https://generativelanguage.googleapis.com',
  model_name text not null default 'gemini-2.5-flash',
  key_ref    text not null,               -- vault reference label only; secrets never leave the device
  is_active  boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.draft (
  id              bigint generated always as identity primary key,
  user_id         uuid references auth.users(id) on delete cascade,
  contact_id      bigint not null references public.contact(id) on delete cascade,
  in_reply_to_id  bigint null references public.message(id) on delete set null,
  prompt_snapshot text not null,
  reply_text      text not null,
  model           text not null,
  variant_group   text not null,
  status          text not null default 'generated' check (status in ('generated','edited','copied','sent')),
  latency_ms      integer not null default 0,
  tokens_in       integer not null default 0,
  tokens_out      integer not null default 0,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create index idx_draft_contact_ts on public.draft (contact_id, created_at);

create table public.usage_event (
  id         bigint generated always as identity primary key,
  user_id    uuid references auth.users(id) on delete cascade,
  ts         timestamptz not null,
  model      text not null,
  tokens_in  integer not null,
  tokens_out integer not null,
  kind       text not null check (kind in ('reply','summary','extract','style')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index idx_usage_ts on public.usage_event (ts);

create table public.app_kv (
  user_id    uuid not null references auth.users(id) on delete cascade,
  key        text not null,
  value      text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  primary key (user_id, key)
);

-- ---------- 3. updated_at triggers on every table ----------
create trigger set_updated_at before update on public.style_profile   for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.contact         for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.contact_channel for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.message         for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.memory_fact     for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.contact_summary for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.provider_def    for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.draft           for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.usage_event     for each row execute function public.tg_set_updated_at();
create trigger set_updated_at before update on public.app_kv          for each row execute function public.tg_set_updated_at();

-- ---------- 4. row-level security (secure by default; future authenticated sync) ----------
alter table public.style_profile   enable row level security;
alter table public.contact         enable row level security;
alter table public.contact_channel enable row level security;
alter table public.message         enable row level security;
alter table public.memory_fact     enable row level security;
alter table public.contact_summary enable row level security;
alter table public.provider_def    enable row level security;
alter table public.draft           enable row level security;
alter table public.usage_event     enable row level security;
alter table public.app_kv          enable row level security;

create policy style_profile_owner   on public.style_profile   for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy contact_owner         on public.contact         for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy contact_channel_owner on public.contact_channel for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy message_owner         on public.message         for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy memory_fact_owner     on public.memory_fact     for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy contact_summary_owner on public.contact_summary for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy provider_def_owner    on public.provider_def    for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy draft_owner           on public.draft           for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy usage_event_owner     on public.usage_event     for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy app_kv_owner          on public.app_kv          for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------- 5. role grants: authenticated users may CRUD their own rows (RLS enforces);
--               anon gets NOTHING (all content is private; auth phase comes later). ----------
grant select, insert, update, delete on public.style_profile   to authenticated;
grant select, insert, update, delete on public.contact         to authenticated;
grant select, insert, update, delete on public.contact_channel to authenticated;
grant select, insert, update, delete on public.message         to authenticated;
grant select, insert, update, delete on public.memory_fact     to authenticated;
grant select, insert, update, delete on public.contact_summary to authenticated;
grant select, insert, update, delete on public.provider_def    to authenticated;
grant select, insert, update, delete on public.draft           to authenticated;
grant select, insert, update, delete on public.usage_event     to authenticated;
grant select, insert, update, delete on public.app_kv          to authenticated;

-- ---------- 6. record migration ----------
insert into public.rm_schema_migrations (version, label) values (1, 'foundation: blueprint v1 cloud schema + RLS + sync plumbing')
on conflict (version) do nothing;

commit;
