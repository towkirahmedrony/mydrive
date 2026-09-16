# MyDrive — Database Schema Reference

Supabase project: **MyDrive** (`gpiuxcdjmrzcouhjapcs`, region `ap-southeast-1`, Postgres 17)
Schema: `public`
Purpose (inferred): Android media backup/replication system — devices upload media, which gets compressed/variant-processed and replicated to Telegram and/or one of several pooled Google Drive accounts via a job queue.

All tables have Row Level Security (RLS) **enabled**. Admin bypass is via a `private.is_admin()` function used across nearly every policy.

---

## profiles
User account/profile record, 1:1 with `auth.users`.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, FK → `auth.users.id` |
| email | text | nullable, unique |
| full_name | text | nullable |
| role | text | default `'user'`, check: `admin` \| `user` |
| status | text | default `'active'`, check: `active` \| `suspended` |
| last_seen_at | timestamptz | nullable |
| created_at | timestamptz | default `now()` |
| updated_at | timestamptz | default `now()` |

**Referenced by:** `devices.user_id`, `telegram_configs.user_id`, `media_assets.owner_id`, `drive_folders.owner_id`, `notifications.user_id`

**RLS:**
- SELECT: `id = auth.uid() OR is_admin()`
- UPDATE: `id = auth.uid() OR is_admin()`
- ALL (admin): `is_admin()`

---

## devices
Registered Android devices per user.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| user_id | uuid | FK → `profiles.id` |
| device_uid | text | unique |
| device_name | text | nullable |
| brand | text | nullable |
| model | text | nullable |
| android_version | text | nullable |
| status | text | default `'active'`, check: `active` \| `disabled` |
| last_seen_at | timestamptz | nullable |
| created_at / updated_at | timestamptz | default `now()` |

**Referenced by:** `media_assets.device_id`

**RLS:**
- SELECT/UPDATE/DELETE: `user_id = auth.uid() OR is_admin()`
- INSERT: `with_check: user_id = auth.uid()`

---

## media_assets
Core file/photo/video records.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| owner_id | uuid | FK → `profiles.id` |
| device_id | uuid | FK → `devices.id` |
| local_media_id | bigint | nullable |
| file_name | text | |
| mime_type | text | |
| file_size | bigint | check `>= 0` |
| width / height | integer | nullable |
| duration_ms | bigint | nullable, check `>= 0` |
| storage_provider | text | check: `imagekit` \| `cloudinary` |
| storage_asset_id | text | |
| storage_path | text | nullable |
| storage_url | text | nullable |
| thumbnail_url | text | nullable |
| sha256_hash | text | nullable |
| client_upload_id | uuid | |
| is_favorite | boolean | default `false` |
| status | text | default `'UPLOADING'`, check: `UPLOADING` \| `READY` \| `FAILED` \| `DELETED` |
| created_at / updated_at | timestamptz | default `now()` |
| uploaded_at | timestamptz | nullable |
| deleted_at | timestamptz | nullable |

**Referenced by:** `media_variants.media_id`, `replication_jobs.media_id`, `sync_logs.media_id`

**RLS:**
- SELECT/UPDATE/DELETE: `owner_id = auth.uid() OR is_admin()`
- INSERT: `with_check: owner_id = auth.uid()`

---

## media_variants
Derived versions of a media asset (thumbnail, telegram-sized, preview).

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| media_id | uuid | FK → `media_assets.id` |
| variant_type | text | check: `thumbnail` \| `telegram` \| `preview` |
| file_name | text | nullable |
| mime_type | text | nullable |
| file_size | bigint | nullable, check `>= 0` |
| width / height | integer | nullable |
| duration_ms | bigint | nullable, check `>= 0` |
| storage_provider | text | nullable, check: `imagekit` \| `cloudinary` |
| storage_asset_id | text | nullable |
| storage_path | text | nullable |
| storage_url | text | nullable |
| created_at | timestamptz | default `now()` |

**Referenced by:** `replication_jobs.variant_id`

**RLS:**
- SELECT/INSERT: allowed if the parent `media_assets` row is owned by the user (or admin) — via `EXISTS` subquery
- ALL (admin): `is_admin()`

---

## drive_accounts
Pool of Google Drive accounts used as replication destinations. Unbounded — the
admin can add/manage any number of accounts; no account is hardcoded.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| name | text | short label |
| display_name | text | nullable, admin-facing name |
| google_email | text | unique account identifier |
| refresh_token_secret_id | uuid | reference to Supabase Vault secret (token NEVER stored inline) |
| refresh_token_updated_at | timestamptz | nullable |
| token_expires_at | timestamptz | nullable |
| root_folder_id | text | nullable; Google Drive archive root folder id |
| priority | integer | default `100` (lower = preferred) |
| enabled | boolean | default `true` |
| status | text | default `'active'`, check: `active` \| `quota_full` \| `reauth_required` \| `disabled` \| `error` |
| connection_status | text | default `'unknown'`, check: `connected` \| `disconnected` \| `reauth_required` \| `error` \| `unknown` |
| health_status | text | default `'unknown'`, check: `healthy` \| `degraded` \| `unhealthy` \| `unknown` |
| storage_limit_bytes | bigint | nullable |
| storage_used_bytes | bigint | nullable |
| storage_available_bytes | bigint | nullable |
| reserved_bytes | bigint | default `0`; per-account safety hold |
| last_quota_check_at | timestamptz | nullable |
| last_health_check_at | timestamptz | nullable |
| last_error | text | nullable |
| last_error_at | timestamptz | nullable |
| notes | text | nullable |
| created_at / updated_at | timestamptz | default `now()` |

**Referenced by:** `replication_jobs.drive_account_id`, `drive_folders.drive_account_id`

**Indexes:** `drive_accounts_routing_idx` (enabled, status, priority, storage_available_bytes),
`drive_accounts_priority_idx`, `drive_accounts_connection_health_idx` (partial, enabled).

**RLS:**
- ALL: `is_admin()` only (no direct user access)

---

## drive_folders
Authoritative per-user Drive folder mapping. `drive_folder_id`/`google_folder_id`
is only the external storage mapping; `owner_id`/`media_assets.id` stay
authoritative.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| drive_account_id | uuid | FK → `drive_accounts.id` |
| parent_folder_id | uuid | nullable, FK → `drive_folders.id` (self-referencing) |
| owner_id | uuid | nullable, FK → `profiles.id` (NULL for root/archive folders) |
| folder_name | text | |
| google_folder_id | text | nullable until created |
| folder_type | text | default `'media'`, check: `root` \| `media` \| `user` \| `year` \| `month` \| `custom` |
| folder_status | text | default `'active'`, check: `pending` \| `active` \| `error` |
| create_lease_until | timestamptz | nullable; short creation lease |
| create_attempts | integer | default `0` |
| last_error | text | nullable |
| created_at / updated_at | timestamptz | default `now()` |

**Indexes / constraints:**
- `drive_folders_user_mapping_key` unique `(drive_account_id, owner_id)` where `folder_type='user'`
- `drive_folders_root_key` unique `(drive_account_id)` where `folder_type='root' and owner_id is null`
- `drive_folders_account_owner_idx`, `drive_folders_owner_idx`, `drive_folders_pending_idx`

**Referenced by:** `replication_jobs.drive_folder_id`

**RLS:**
- ALL: `is_admin()` only

---

## telegram_configs
Per-user Telegram bot destination config.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| user_id | uuid | unique, FK → `profiles.id` |
| chat_id | text | |
| bot_token_secret_id | uuid | nullable (secret store reference) |
| enabled | boolean | default `true` |
| status | text | default `'unverified'`, check: `unverified` \| `active` \| `invalid` \| `disabled` |
| last_tested_at | timestamptz | nullable |
| created_at / updated_at | timestamptz | default `now()` |

**Referenced by:** `replication_jobs.telegram_config_id`

**RLS:**
- SELECT/UPDATE/DELETE: `user_id = auth.uid() OR is_admin()`
- INSERT: `with_check: user_id = auth.uid()`

---

## replication_jobs
Queue of replication tasks (media → Telegram / Google Drive).

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| media_id | uuid | FK → `media_assets.id` |
| destination_type | text | check: `telegram` \| `google_drive` |
| telegram_config_id | uuid | nullable, FK → `telegram_configs.id` |
| drive_account_id | uuid | nullable, FK → `drive_accounts.id` |
| drive_folder_id | uuid | nullable, FK → `drive_folders.id` |
| variant_id | uuid | nullable, FK → `media_variants.id` |
| status | text | default `'PENDING'`, check: `PENDING` \| `PROCESSING` \| `COMPLETED` \| `FAILED` \| `RETRYING` \| `SKIPPED` |
| attempt_count | integer | default `0`, check `>= 0` |
| last_error | text | nullable |
| next_retry_at | timestamptz | nullable |
| telegram_message_id | bigint | nullable |
| telegram_file_id | text | nullable |
| google_drive_file_id | text | nullable |
| failed_drive_account_ids | uuid[] | default `'{}'`; Drive accounts already tried (failover exclusion list) |
| started_at / completed_at | timestamptz | nullable |
| created_at / updated_at | timestamptz | default `now()` |

**Indexes:**
- `replication_jobs_media_destination_config_key` unique `(media_id, destination_type, telegram_config_id)`
- `replication_jobs_media_drive_key` unique `(media_id)` where `destination_type='google_drive'` (one Drive job per media; account can change via failover)
- `replication_jobs_queue_idx`, `replication_jobs_media_idx`, `replication_jobs_pending_retry_idx`, `replication_jobs_user_idx`
- `replication_jobs_drive_queue_idx` (status, next_retry_at, created_at) where `destination_type='google_drive'`
- `replication_jobs_drive_account_idx`, `replication_jobs_drive_folder_idx` (partial, `google_drive`)

**Referenced by:** `sync_logs.replication_job_id`

**RLS:**
- SELECT: allowed if parent `media_assets` row is owned by user (or admin) — via `EXISTS` subquery
- ALL (admin): `is_admin()`

---

## sync_logs
Event/audit log for replication activity.

| Column | Type | Notes |
|---|---|---|
| id | bigint | PK, identity (`ALWAYS`) |
| media_id | uuid | nullable, FK → `media_assets.id` |
| replication_job_id | uuid | nullable, FK → `replication_jobs.id` |
| event_type | text | |
| status | text | nullable |
| message | text | nullable |
| metadata | jsonb | nullable |
| created_at | timestamptz | default `now()` |

**RLS:**
- SELECT: allowed if parent `media_assets` row is owned by user (or admin) — via `EXISTS` subquery
- ALL (admin): `is_admin()`

---

## notifications
In-app notifications per user.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK, default `gen_random_uuid()` |
| user_id | uuid | FK → `profiles.id` |
| title | text | |
| body | text | nullable |
| notification_type | text | nullable |
| is_read | boolean | default `false` |
| created_at | timestamptz | default `now()` |
| read_at | timestamptz | nullable |

**RLS:**
- SELECT: `user_id = auth.uid() OR is_admin()`
- UPDATE: `user_id = auth.uid()`
- ALL (admin): `is_admin()`

---

## app_settings
Single-row global config table (PK is a boolean, effectively a singleton).

| Column | Type | Notes |
|---|---|---|
| id | boolean | PK, default `true` |
| compression_enabled | boolean | default `true` |
| telegram_enabled | boolean | default `true` |
| drive_enabled | boolean | default `true` |
| telegram_target_mb | integer | default `45`, check `> 0` |
| telegram_hard_limit_mb | integer | default `50`, check `> 0` |
| max_retry | integer | default `5`, check `>= 0` |
| retry_base_delay_seconds | integer | default `60`, check `> 0` |
| auto_delete_primary_after_replication | boolean | default `false` |
| auto_delete_telegram_on_media_delete | boolean | default `false` |
| auto_delete_drive_on_media_delete | boolean | default `false` |
| drive_safety_margin_bytes | bigint | default `1073741824` (1 GiB) |
| created_at / updated_at | timestamptz | default `now()` |

**RLS:**
- ALL: `is_admin()` only

---

## Drive foundation functions & views

All functions below are `SECURITY DEFINER` and `EXECUTE` is revoked from
`PUBLIC` / granted to `service_role` only — Android clients (`authenticated`,
`anon`) can never call them.

**Drive Router (server-side, any number of accounts):**
- `list_eligible_drive_accounts(p_required_bytes, p_exclude_account_ids, p_safety_margin_bytes)` → set of eligible accounts in routing order
- `select_drive_account(...)` → single best eligible account (read-only)
- `reserve_drive_account(...)` → atomically selects + reserves quota (`FOR UPDATE SKIP LOCKED`)
- `release_drive_quota(p_drive_account_id, p_bytes)`
- `mark_drive_account_result(p_drive_account_id, p_health_status, p_status, p_last_error)`

**Idempotent per-user folder resolution:**
- `claim_drive_folder(p_drive_account_id, p_owner_id, p_folder_name, p_folder_type, p_parent_folder_id)` → `(folder, acquired)`; advisory-locked + creation lease
- `complete_drive_folder(p_folder_row_id, p_google_folder_id)`
- `fail_drive_folder(p_folder_row_id, p_error)`

**Secret access (Supabase Vault):**
- `worker_lookup_drive_refresh_token(p_secret_id, p_drive_account_id)`
- `admin_store_drive_refresh_token(p_drive_account_id, p_refresh_token)` → stores in Vault, persists only the secret id

**Drive job lifecycle (state only, no uploads):**
- `enqueue_drive_replication_job(p_media_id)` → idempotent single Drive job per media
- `assign_drive_replication_job(p_job_id, p_drive_account_id, p_drive_folder_id)`
- `failover_drive_replication_job(p_job_id, p_failed_account_id, p_error, p_next_retry_at)` → records excluded account, clears assignment, `RETRYING`
- `list_media_drive_jobs(p_media_id)`

**Admin aggregation views (`security_invoker = true`):**
- `admin_user_storage_summary` → per user: media count/size, Telegram job states, Drive job states, distinct Drive account count, Drive stored bytes
- `admin_user_drive_distribution` → per user × Drive account: media count and stored bytes

---

## Entity relationship summary

```
auth.users ──1:1── profiles
profiles ──1:N── devices
profiles ──1:N── media_assets
devices  ──1:N── media_assets
media_assets ──1:N── media_variants
media_assets ──1:N── replication_jobs
media_assets ──1:N── sync_logs
profiles ──1:1── telegram_configs
telegram_configs ──1:N── replication_jobs
drive_accounts ──1:N── drive_folders
drive_accounts ──1:N── replication_jobs
drive_folders  ──1:N── drive_folders (self, parent/child)
drive_folders  ──1:N── replication_jobs
media_variants ──1:N── replication_jobs
replication_jobs ──1:N── sync_logs
profiles ──1:N── notifications
```

## RLS pattern summary

- Most user-owned tables (`profiles`, `devices`, `media_assets`, `telegram_configs`, `notifications`) follow: **owner can SELECT/UPDATE/DELETE/INSERT their own rows; admin can do anything** via `private.is_admin()`.
- Child tables without a direct owner column (`media_variants`, `replication_jobs`, `sync_logs`) check ownership through an `EXISTS` subquery back to `media_assets.owner_id`.
- Infrastructure/admin-only tables (`drive_accounts`, `drive_folders`, `app_settings`) have **no end-user access at all** — admin only.
