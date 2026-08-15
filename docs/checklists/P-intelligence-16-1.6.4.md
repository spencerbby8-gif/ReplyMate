# P-intelligence-16 — REAL BUILD + SUPABASE INTEGRITY AUDIT (2026-08-15)

Scope (owner mandate): (1) verify current proof build on the real Oppo; (2) full live-Supabase
integrity audit vs repo migrations and code — read-only until this drift report was complete;
(3) prove the build end-to-end again. Connection used: IPv4 Supavisor **session pooler**
(direct host is IPv6-only, unreachable from this sandbox; pooler is the official free-tier
path — verified live: IPv4 egress 200, IPv6 egress dead, TCP 5432 to pooler OPEN).
**Credential handling:** pooler URI staged only at `/tmp/.supa` (mode 600), read by throwaway
`/tmp/*.py` runners, never written to source/logs/docs/diagnostics/APK; shredded at turn end.
Rotate the DB password after this phase (it transited chat).

Result banner: **gate 891/891 · live audit PG 17.6 · 2 findings fixed (migrations 0003+0004
applied + re-audited) · 1 documented by-design debt · devcheck vc911 · CI proof pending below ·
device rows OWNER-PENDING.**

## Part A — previous-build verification (1.6.3 / vc910, HEAD `011de97`)

Off-device re-verification (this sandbox):

| Check | Method | Verdict |
|---|---|---|
| HEAD == CI-proven commit | `git log` → `011de97` (matches CI 31884648127) | ✅ |
| Full JVM gate | `scripts/run_tests.sh` | ✅ **OK (891 tests)** |
| Proof APK on disk == CI artifact | sha256 `1f82a6d9…4613` | ✅ byte-identical |
| Signing cert | apksigner after devcheck SDK provision — Part D | see Part D |
| Project ref pooler == app config | `xdcsxxwhvpiissetbrdr` == `SupabaseConfig.PROJECT_URL` | ✅ same project the app uses |

§1 device matrix — **sandbox has no device; only the owner can mark these. OPEN:**

| Row | Behavior (mandate §1) | Where proven in JVM gate | Device verdict (owner) |
|---|---|---|---|
| FIN-1 | Background readiness (incl. ColorOS routing + readiness re-check) | AssistantPrereq/ColorOS suites, BG gate | ☐ OPEN |
| FIN-2 | Listener capture incl. batches | ingest/listener suites | ☐ OPEN |
| FIN-3 | Group understanding (history, group flag, member attribution) | GroupHistoryTest, GroupChatUnderstandingTest | ☐ OPEN |
| FIN-4 | Memory / retrieval | memory suites + MemoryRestartTest | ☐ OPEN |
| FIN-5 | Per-contact customization end-to-end | EditContactWireProofTest, AllDialsWireProofTest | ☐ OPEN |
| FIN-6 | Intentional generation (opener/follow-up/clarify/continue) | IntentionalComposeTest | ☐ OPEN |
| FIN-7 | Search grounding behavior | live-search gate suites | ☐ OPEN |
| FIN-8 | Reasoning effort behavior | reasoning suites | ☐ OPEN |
| FIN-9 | Auto follow-up (OFF default, never auto-sends) | AutoFollowUpTest | ☐ OPEN |

Also still OPEN from earlier phases on this same build family: P-14-ext §D (vc909) and
P-15 §D (vc910) rows. **Blocking rule honored: no new product feature is started.**

## Part B — live Supabase drift report (read-only stage; **no mutation performed before this report**)

Method: stage-1 automated audit (`/tmp/supa_audit.py`, psycopg2, `readonly` session,
`statement_timeout=30s`) comparing live objects against `docs/supabase/migrations/0001+0002`
expectations encoded from the VM sources; outputs archived at `/tmp/audit_stage1.json` (turn-local).
Server: **PostgreSQL 17.6**, db `postgres`, project `xdcsxxwhvpiissetbrdr` (us-east-1).

### B.1 Verified CLEAN (claim → live evidence)

| Area | Expectation (repo) | Live result | Verdict |
|---|---|---|---|
| App tables | 10 (`SupabaseConfig.TABLES`) + `rm_schema_migrations` | exactly those 11; `tables_missing=[]`, `tables_extra=[]` | ✅ |
| Columns | 107 app + 3 ledger, types/nullability per 0001 | `column_diffs={}` (zero type/null/extra/missing) | ✅ |
| PKs/Uniques | incl. user-scoped `(user_id,channel,remote_key)`, `(user_id,channel,notif_key)`, `(contact_id,text_norm)`, `(contact_id,version)`, app_kv PK `(user_id,key)` | `unique_missing=[]` | ✅ |
| Foreign keys | 19 per README (incl. circular style_profile↔contact, `user_id→auth.users` cascade) | `fk_missing=[]` (19/19) | ✅ |
| Channel CHECK (0002) | 11 wires | live def lists all 11; `channel_check_covers_11=true` | ✅ **0002 confirmed live** |
| CHECK sets | scope/direction/source/category/type/status/kind | all present w/ exact value sets | ✅ |
| Indexes | 4 named (+implicit) | `indexes_missing=[]` | ✅ |
| Triggers + fn | `set_updated_at` on all 10; `tg_set_updated_at()` | `trigger_tables_missing=[]`, fn exists | ✅ |
| RLS enabled | all 10 app tables | `rls_disabled=[]` (ledger RLS-on too) | ✅ |
| Policies | 10 `<table>_owner` `FOR ALL TO authenticated` `auth.uid() = user_id` | all 10 present, exact qual+check | ✅ |
| Project ledger | rows 1,2 | `(1, foundation, 2026-08-06 18:05:26Z)`, `(2, p3 parity, 2026-08-06 20:42:40Z)` | ✅ |
| Data | none expected (sync OFF) | all 10 tables 0 rows; `auth.users`=4 (auth phase live, sync off by design) | ✅ |
| Extensions | platform defaults | pgcrypto, uuid-ossp, pg_stat_statements, supabase_vault, plpgsql | ✅ |
| Manually-created/stray objects | none expected | none in `public` | ✅ |

### B.2 FINDINGS (drift → action)

| # | Finding | Evidence | Severity | Action |
|---|---|---|---|---|
| F1 | **`anon` holds full table-level grants on ALL 11 tables** (SELECT/INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER), contradicting 0001 §5 "anon gets NOTHING". Cause: Supabase platform *default privileges* grant ALL on new public tables to anon+authenticated; 0001 only added authenticated grants and never revoked. RLS still blocked anon (defense held), but grant surface ≠ contract. `authenticated` also held 3 extras (TRUNCATE/REFERENCES/TRIGGER) beyond 0001's CRUD. | role_table_grants dump | Medium (defense-in-depth) | **0003** revoke all from anon; revoke extras from authenticated; revoke client roles from ledger |
| F2 | **`avatars` Storage bucket missing** — `AvatarStore.syncToCloud()` POSTs `/storage/v1/object/avatars/<uid>.jpg` (x-upsert, image/jpeg) and otherwise always returns its honest "needs a public 'avatars' bucket" error. Code/schema mismatch; avatar cloud sync non-functional. | `storage.buckets` = ∅ | Medium (feature broken) | **0004** create public bucket + owner-scoped policies |
| F3 | **Official CLI migration history absent**: `supabase_migrations.schema_migrations` does not exist (migrations were applied via psql-over-pooler, never `db push`), and local files are `docs/supabase/migrations/000N_*.sql` (not CLI timestamp names, not under `supabase/`). `supabase migration list` therefore shows nothing on either side. Project runs its own verified ledger `public.rm_schema_migrations` instead. | `to_regclass(...)` = NULL | Low (process debt, no runtime risk) | Documented as **accepted debt**: rm ledger stays source of truth; adopt CLI-shaped names + `migration repair` only if owner later wants CLI parity. No remote change. |

## Part C — migrations produced & applied (after this report)

| Ver | File | Idempotent | Content |
|---|---|---|---|
| 0003 | `0003_revoke_anon_surface.sql` | ✅ (REVOKE + OIC ledger insert) | revoke ALL on 11 tables from `anon`; revoke TRUNCATE/REFERENCES/TRIGGER on 10 app tables from `authenticated` (keeps exact CRUD per 0001); revoke ALL on `rm_schema_migrations` from anon+authenticated; ledger row 3; `notify pgrst` |
| 0004 | `0004_avatars_bucket.sql` | ✅ (OIC upsert + drop-policy-if-exists) | public bucket `avatars`; 4 `storage.objects` policies: public select, authenticated insert/update/delete scoped to `name = auth.uid()\|\|'.jpg'` (root-level objects, matches app); ledger row 4; `notify pgrst` |
| verify | `verify_0003_0004.sql` | ✅ rollback-only probes | structural re-checks + functional RLS regression (anon SELECT now errors 42501; owner A/B isolation intact; B can't write A's avatar) |

"Prove locally": sandbox has **no docker / no local Postgres** — strongest available proof was run
instead on the real engine with **zero persistence**: each file executed inside
`BEGIN … <verify queries> … ROLLBACK` (dry-run proof), then applied with COMMIT, then the full
stage-1 audit re-run + rollback-only functional probes. Results recorded in Part C-proof below
and in git history.

### Part C-proof (executed 2026-08-15 13:21–13:23 UTC, transcript evidence)

**2a — rollback dry-run (zero persistence):** 0003 executed clean; inside the txn: anon zero-grants PASS,
authenticated exact-CRUD-on-10 PASS, ledger locked PASS, ledger showed `[1,2,3]`; rolled back ✓.
0004 executed clean; bucket row `('avatars', true)` visible, 4 object policies visible, ledger `[1,2,4]`;
rolled back ✓.

**2b — apply:** both files APPLIED (committed). Ledger now **1,2,3,4** — versions 3,4 timestamped
`2026-08-15 13:21:54.8Z` / `13:21:55.3Z` (live-read above).

**2c — post-apply structural verify:** anon holds **zero grants on all 11 tables**; `authenticated`
holds exactly `DELETE,INSERT,SELECT,UPDATE` on the 10 app tables; neither client role touches
`rm_schema_migrations`; bucket `('avatars','avatars',public=t)`; 4 policies
(`avatars_public_read` SELECT, `avatars_owner_insert` INSERT, `avatars_owner_update` UPDATE,
`avatars_owner_delete` DELETE); RLS still enabled on every public table.

**2d — functional probes (rolled back, residue-verified):** owner isolation intact (A sees 1, B sees 0);
A can write `A.jpg`, B writing `A.jpg` denied **42501**; `avatars` public read works (B reads 1);
**`anon` SELECT on `public.contact` now hard-errors 42501** (was silently-empty through RLS before 0003 —
defense-in-depth restored to the 0001 §5 contract). Residue check: `auth.users=4`, `contact=0`,
`storage.objects=0` — probes left nothing.

**Post-apply full stage-1 re-audit:** every B.1 row still clean AND `grant_anomalies=[]`,
`avatars_bucket=true` — **F1 and F2 closed; zero regressions; zero remaining drift.**

## Part D — build proof (this phase)

| Check | Result |
|---|---|
| JVM gate @ HEAD `011de97` + docs | **891/891 ALL SUITES PASSED** (run twice this phase) |
| Engine devcheck | vc911 / `1.6.4-devcheck16` — sha256 `b0e62afe…a8664f`, 624,970 B, local debug cert `446310cc…d2` MATCH; verified then deleted |
| vc910 proof cert (apksigner) | **B1:5F:2F:37…6A:85:ED MATCH** (`b15f2f37…6a85ed`), vc910 / 1.6.3-groups-proof, sha256 `1f82a6d9…4613` == CI artifact 9246933080 |
| CI proof | **run 31887144498 GREEN** (success @ 13:26:00Z); artifact **9247568498** `ReplyMate-1.6.4-integrity-proof-vc911`; logs: KEYSTORE-VERDICT MATCH ×2, APK-CERT-PROOF MATCH ×2 |
| Off-CI independent verify | artifact 624,970 B; sha256 `595f99ac858a352083b5284a283b1c965ff0aba16c1a6c3da99f55cb105d3e08`; cert **B1:5F:2F:37…6A:85:ED MATCH**; badging vc911 / 1.6.4-integrity-proof / minSdk 24 / target 35 |

CI vs device separation: CI run 31887144498 proves source `f5b8d7b`+docs → tested (891/891 in
workflow), packaged, historically signed APK. It does **not** certify on-device behavior —
Part A rows FIN-1…9 stay owner-marked. `ReplyMate-1.6.4-integrity-proof.apk` is in the repo
root beside the earlier proofs for the owner's Oppo install.

Device-proof vs CI-proof separation: CI proves packaging/signing/tests; **device rows in Part A
remain the owner's** — never self-certified.

## Part E — blockers / remaining

1. ☐ Owner device pass of vc910 (rows FIN-1…9 + earlier §D rows) — nothing else may start first.
2. ☐ Owner: rotate the Supabase DB password (it transited chat) and, optionally, the anon key is fine (public by design, RLS-guarded).
3. Accepted debt F3: CLI history baseline deferred to an owner-approved tooling phase.
4. `auth.users` count 4 with sync OFF — expected; sync flip remains a separately-approved phase.
