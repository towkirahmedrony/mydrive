# MyDrive — Supabase Database Schema (Full)

**Project:** MyDrive
**Project ID:** `gpiuxcdjmrzcouhjapcs`
**Database:** PostgreSQL 17 (Supabase, ap-southeast-1)
All `public` schema tables have Row Level Security (RLS) enabled.

---

## Tables

### profiles
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, FK → auth.users.id |
| email | text | unique, nullable |
| full_name | text | nullable |
| role | text | default `'user'`, check: `admin`, `user` |
| status | text | default `'active'`, check: `active`, `suspended` |
| last_seen_at | timestamptz | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| department_id | uuid | nullable, FK → departments.id |
| employee_id | text | nullable, unique (partial index, non-null only) |
| designation | text | nullable |
| storage_quota_bytes | bigint | nullable, check ≥ 0 |
| storage_used_bytes | bigint | default 0, check ≥ 0 — auto-synced by trigger `trg_media_assets_storage_used` |

### devices
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| user_id | uuid | FK → profiles.id |
| device_uid | text | unique |
| device_name | text | nullable |
| brand | text | nullable |
| model | text | nullable |
| android_version | text | nullable |
| status | text | default `'active'`, check: `active`, `disabled` |
| last_seen_at | timestamptz | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| push_token | text | nullable — FCM/push token |
| push_token_updated_at | timestamptz | nullable |
| wifi_only_sync | boolean | default false |
| auto_delete_after_backup | boolean | default false |

### media_assets
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| owner_id | uuid | FK → profiles.id |
| device_id | uuid | FK → devices.id |
| local_media_id | bigint | nullable |
| file_name | text | |
| mime_type | text | |
| file_size | bigint | check ≥ 0 |
| width | integer | nullable |
| height | integer | nullable |
| duration_ms | bigint | nullable, check ≥ 0 |
| storage_provider | text | check: `imagekit`, `cloudinary` |
| storage_asset_id | text | |
| storage_path | text | nullable |
| storage_url | text | nullable |
| thumbnail_url | text | nullable |
| sha256_hash | text | nullable |
| client_upload_id | uuid | |
| is_favorite | boolean | default false |
| status | text | default `'UPLOADING'`, check: `UPLOADING`, `READY`, `FAILED`, `DELETED` |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| uploaded_at | timestamptz | nullable |
| deleted_at | timestamptz | nullable |
| drive_archived_at | timestamptz | nullable |
| primary_cleanup_status | text | default `'none'`, check: `none`, `cleanup_pending`, `cleanup_processing`, `cleanup_success`, `cleanup_failed` |
| primary_cleanup_attempts | integer | default 0 |
| primary_cleanup_error | text | nullable |
| primary_cleanup_started_at | timestamptz | nullable |
| primary_cleanup_completed_at | timestamptz | nullable |
| primary_deleted_at | timestamptz | nullable |
| cleanup_telegram_override | boolean | default false |
| user_hidden_at | timestamptz | nullable — NULL = visible; timestamp = hidden from user's library/gallery (backup files untouched); restore sets back to NULL — writable only via the hardened, row-count-verified `set_media_library_visibility()`; direct UPDATE rejected by column-level privileges and the `trg_media_assets_guard_user_hidden_at` trigger |

**Owner UPDATE privileges (20260923000300):** `authenticated` holds column-level `UPDATE` on `is_favorite` only. Every other column (`owner_id`, `device_id`, local/identity fields, `storage_*`, `thumbnail_url`, `sha256_hash`, `status`, `uploaded_at`, `deleted_at`, `user_hidden_at`, `drive_archived_at`, `primary_cleanup_*`, `primary_deleted_at`, `cleanup_telegram_override`, `created_at`, `updated_at`) is server-controlled and rejected with SQLSTATE 42501. `anon` has no UPDATE at all; `service_role` and the table owner keep full table-level UPDATE, so the Edge Function workers, `finalize-media`, and the SECURITY DEFINER lifecycle RPCs are unaffected. Trash/Restore goes through `set_media_library_visibility()`.

### media_variants
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| media_id | uuid | FK → media_assets.id |
| variant_type | text | check: `thumbnail`, `telegram`, `preview` |
| file_name | text | nullable |
| mime_type | text | nullable |
| file_size | bigint | nullable, check ≥ 0 |
| width | integer | nullable |
| height | integer | nullable |
| duration_ms | bigint | nullable, check ≥ 0 |
| storage_provider | text | nullable, check: `imagekit`, `cloudinary` |
| storage_asset_id | text | nullable |
| storage_path | text | nullable |
| storage_url | text | nullable |
| created_at | timestamptz | default now() |

### drive_accounts
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| name | text | |
| google_email | text | unique |
| refresh_token_secret_id | uuid | |
| root_folder_id | text | nullable |
| priority | integer | default 100 |
| status | text | default `'active'`, check: `active`, `quota_full`, `reauth_required`, `disabled`, `error` |
| storage_limit_bytes | bigint | nullable |
| storage_used_bytes | bigint | nullable |
| storage_available_bytes | bigint | nullable |
| last_quota_check_at | timestamptz | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| display_name | text | nullable |
| refresh_token_updated_at | timestamptz | nullable |
| token_expires_at | timestamptz | nullable |
| enabled | boolean | default true |
| connection_status | text | default `'unknown'`, check: `connected`, `disconnected`, `reauth_required`, `error`, `unknown` |
| health_status | text | default `'unknown'`, check: `healthy`, `degraded`, `unhealthy`, `unknown` |
| reserved_bytes | bigint | default 0, check ≥ 0 |
| last_health_check_at | timestamptz | nullable |
| last_error | text | nullable |
| last_error_at | timestamptz | nullable |
| notes | text | nullable |

### drive_folders
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| drive_account_id | uuid | FK → drive_accounts.id |
| parent_folder_id | uuid | nullable, FK → drive_folders.id (self) |
| owner_id | uuid | nullable, FK → profiles.id |
| folder_name | text | |
| google_folder_id | text | nullable |
| folder_type | text | default `'media'`, check: `root`, `media`, `user`, `year`, `month`, `custom` |
| created_at | timestamptz | default now() |
| folder_status | text | default `'active'`, check: `pending`, `active`, `error` |
| create_lease_until | timestamptz | nullable |
| create_attempts | integer | default 0, check ≥ 0 |
| last_error | text | nullable |
| updated_at | timestamptz | default now() |

### replication_jobs
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| media_id | uuid | FK → media_assets.id |
| destination_type | text | check: `telegram`, `google_drive` |
| telegram_config_id | uuid | nullable, FK → telegram_configs.id |
| drive_account_id | uuid | nullable, FK → drive_accounts.id |
| drive_folder_id | uuid | nullable, FK → drive_folders.id |
| variant_id | uuid | nullable, FK → media_variants.id |
| status | text | default `'PENDING'`, check: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `RETRYING`, `SKIPPED` |
| attempt_count | integer | default 0, check ≥ 0 |
| last_error | text | nullable |
| next_retry_at | timestamptz | nullable |
| telegram_message_id | bigint | nullable |
| telegram_file_id | text | nullable |
| google_drive_file_id | text | nullable |
| started_at | timestamptz | nullable |
| completed_at | timestamptz | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| failed_drive_account_ids | uuid[] | default `'{}'` |
| google_drive_upload_url | text | nullable |
| google_drive_upload_chunk | bigint | default 0, check ≥ 0 |
| google_drive_upload_attempts | integer | default 0, check ≥ 0 |

### telegram_configs
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| user_id | uuid | unique, FK → profiles.id |
| chat_id | text | |
| bot_token_secret_id | uuid | nullable |
| enabled | boolean | default true |
| status | text | default `'unverified'`, check: `unverified`, `active`, `invalid`, `disabled` |
| last_tested_at | timestamptz | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |

### app_settings (single-row global config)
| Column | Type | Constraints |
|---|---|---|
| id | boolean | PK, default true |
| compression_enabled | boolean | default true |
| telegram_enabled | boolean | default true |
| drive_enabled | boolean | default true |
| telegram_target_mb | integer | default 45, check > 0 |
| telegram_hard_limit_mb | integer | default 50, check > 0 |
| max_retry | integer | default 5, check ≥ 0 |
| retry_base_delay_seconds | integer | default 60, check > 0 |
| auto_delete_primary_after_replication | boolean | default false |
| auto_delete_telegram_on_media_delete | boolean | default false |
| auto_delete_drive_on_media_delete | boolean | default false |
| drive_safety_margin_bytes | bigint | default 1073741824, check ≥ 0 |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |

### sync_logs
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK, identity |
| media_id | uuid | nullable, FK → media_assets.id |
| replication_job_id | uuid | nullable, FK → replication_jobs.id |
| event_type | text | |
| status | text | nullable |
| message | text | nullable |
| metadata | jsonb | nullable |
| created_at | timestamptz | default now() |

### notifications
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| user_id | uuid | FK → profiles.id |
| title | text | |
| body | text | nullable |
| notification_type | text | nullable |
| is_read | boolean | default false |
| created_at | timestamptz | default now() |
| read_at | timestamptz | nullable |

### oauth_states
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| user_id | uuid | FK → auth.users.id |
| state | text | unique |
| created_at | timestamptz | default now() |
| expires_at | timestamptz | default now() + 10 minutes |

### admin_audit_logs
| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK, identity |
| actor_id | uuid | FK → profiles.id |
| action | text | |
| media_id | uuid | nullable, FK → media_assets.id |
| target_user_id | uuid | nullable, FK → profiles.id |
| details | jsonb | default `{}` |
| success | boolean | default true |
| created_at | timestamptz | default now() |

### departments
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| name | text | unique |
| description | text | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |
| storage_quota_bytes | bigint | nullable, check ≥ 0 |

### backup_sessions
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| device_id | uuid | FK → devices.id |
| started_at | timestamptz | default now() |
| completed_at | timestamptz | nullable |
| status | text | default `'RUNNING'`, check: `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| files_count | integer | default 0, check ≥ 0 |
| files_uploaded | integer | default 0, check ≥ 0 |
| files_failed | integer | default 0, check ≥ 0 |
| total_size_bytes | bigint | default 0, check ≥ 0 |
| error_message | text | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now() |

---

### device_accessibility_status
Current accessibility monitoring state; exactly one row per registered device via a unique `device_id` constraint.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| device_id | uuid | NOT NULL, unique, FK → devices.id, cascade delete |
| user_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| is_enabled | boolean | NOT NULL, default false |
| service_connected | boolean | NOT NULL, default false |
| last_connected_at | timestamptz | nullable |
| last_disconnected_at | timestamptz | nullable |
| last_event_at | timestamptz | nullable |
| last_heartbeat_at | timestamptz | nullable |
| accessibility_api_level | integer | nullable |
| service_version | text | nullable |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now(), maintained by `set_updated_at()` |

### device_accessibility_events
Selected, privacy-filtered events only. The Android event processor filters event types and debounces repeated high-frequency events before upload. Password-field text is never persisted, and the database enforces `NOT is_password_field OR event_text IS NULL`.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| device_id | uuid | NOT NULL, FK → devices.id, cascade delete |
| user_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| event_type | text | NOT NULL |
| package_name | text | nullable |
| activity_name | text | nullable |
| event_time | timestamptz | NOT NULL |
| window_id | integer | nullable |
| window_title | text | nullable; only when safely exposed |
| event_text | text | nullable; never stored for password fields |
| content_description | text | nullable; only when appropriate |
| class_name | text | nullable |
| is_password_field | boolean | NOT NULL, default false |
| is_editable | boolean | nullable |
| is_clickable | boolean | nullable |
| is_scrollable | boolean | nullable |
| event_metadata | jsonb | nullable |
| created_at | timestamptz | default now() |

Indexes support recent per-device reads and retention cleanup. Events are append-only for authenticated clients; only admins may delete them.

### device_accessibility_sessions
Summarized foreground app/activity sessions; `duration_ms` is populated only when a reliable end event closes the session.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| device_id | uuid | NOT NULL, FK → devices.id, cascade delete |
| user_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| package_name | text | NOT NULL |
| activity_name | text | nullable |
| started_at | timestamptz | NOT NULL |
| ended_at | timestamptz | nullable |
| duration_ms | bigint | nullable, check ≥ 0 |
| start_event_id | uuid | nullable, FK → device_accessibility_events.id, set null on delete |
| end_event_id | uuid | nullable, FK → device_accessibility_events.id, set null on delete |
| created_at | timestamptz | default now() |

A partial unique index permits at most one open session (`ended_at IS NULL`) per device.

### device_monitoring_settings
Per-device collection switches with privacy-preserving defaults; `retention_days` defaults to 14 and is constrained to 1–365.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| device_id | uuid | NOT NULL, unique, FK → devices.id, cascade delete |
| user_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| accessibility_monitoring_enabled | boolean | NOT NULL, default false |
| event_collection_enabled | boolean | NOT NULL, default false |
| collect_window_events | boolean | NOT NULL, default false |
| collect_interaction_events | boolean | NOT NULL, default false |
| collect_text_events | boolean | NOT NULL, default false |
| collect_notification_events | boolean | NOT NULL, default false |
| retention_days | integer | NOT NULL, default 14, check 1–365 |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now(), maintained by `set_updated_at()` |

All four tables have RLS. Owners can access their own device rows and admins can manage/read across devices. `purge_expired_accessibility_data()` removes expired events and sessions using each device's retention setting and is executable only by `service_role`.

> Note: the Android app no longer produces this data (the AccessibilityService was removed in `6838a5f`); the tables are retained with their data and RLS.

### media_vault_items
Private Vault: one row per media asset currently held in a device's vault. Stores the **metadata** of the transition only — never the encrypted bytes, the key, the PIN or a path outside the app sandbox. `media_id` references the **same** `media_assets` row the normal gallery uses, so hiding media never creates a second Cloudinary asset.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| media_id | uuid | NOT NULL, FK → media_assets.id, cascade delete |
| owner_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| device_id | uuid | NOT NULL, FK → devices.id, cascade delete |
| vault_status | text | NOT NULL, default `ENCRYPTING`, check in (`ENCRYPTING`,`READY`,`RESTORING`,`FAILED`,`DELETED`) |
| vault_version | integer | NOT NULL, default 1, check ≥ 1 |
| encrypted_storage_path | text | device-relative file name inside app-private storage |
| encrypted_file_size | bigint | ciphertext size |
| encrypted_sha256 | text | SHA-256 of the ciphertext, for integrity verification |
| original_mime_type | text | NOT NULL |
| original_file_name | text | NOT NULL |
| original_file_size | bigint | NOT NULL |
| created_at | timestamptz | default now() |
| updated_at | timestamptz | default now(), maintained by `set_updated_at()` |
| hidden_at | timestamptz | when the item entered the vault |
| restored_at | timestamptz | when the item was returned to MediaStore |

**Constraints / indexes:** `uq_media_vault_items_active_media` — partial unique index on `media_id` where `vault_status <> 'DELETED'`, so one asset cannot have two active vault records while re-hiding after a permanent delete stays possible. `media_vault_items_ciphertext_present` — a row in `ENCRYPTING`/`READY`/`RESTORING` must name its ciphertext file, so media can never be marked hidden without a stored vault copy. Indexes: `(owner_id, created_at DESC)`, `(device_id, created_at DESC)`, `(vault_status)`.

### media_vault_settings
Per-device vault configuration. Non-sensitive only: **the PIN verifier and its salt are never stored here** — they live on the device in Keystore-protected private preferences.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| owner_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| device_id | uuid | NOT NULL, unique, FK → devices.id, cascade delete |
| biometric_enabled | boolean | NOT NULL, default false |
| pin_enabled | boolean | NOT NULL, default false |
| lock_timeout_seconds | integer | NOT NULL, default 60, check 0–3600 |
| created_at / updated_at | timestamptz | default now(); `updated_at` maintained by `set_updated_at()` |

### media_vault_events
Append-only audit trail of high-level vault actions. Records **what** happened, never why an unlock failed: no PIN, no biometric result detail, no keys, no media content.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK, default gen_random_uuid() |
| owner_id | uuid | NOT NULL, FK → profiles.id, cascade delete |
| device_id | uuid | NOT NULL, FK → devices.id, cascade delete |
| vault_item_id | uuid | nullable, FK → media_vault_items.id, `ON DELETE SET NULL` |
| event_type | text | NOT NULL, check in (`HIDE`,`UNHIDE`,`VAULT_UNLOCK_SUCCESS`,`VAULT_UNLOCK_FAILURE`,`PERMANENT_DELETE`,`RESTORE`) |
| event_at | timestamptz | NOT NULL, default now() |
| metadata | jsonb | small, non-sensitive context only |
| created_at | timestamptz | default now() |

Indexes: `(owner_id, event_at DESC)`, `(device_id, event_at DESC)`, `(vault_item_id)`.

**No `media_vault_devices` table exists on purpose** — `public.devices` already models the device and `media_vault_items.device_id` references it, so a second mapping table would be redundant.

**RLS / access model** (all three tables, `TO authenticated`): `media_vault_items` — SELECT `(owner_id = auth.uid()) OR private.is_admin()`, INSERT/UPDATE owner-only, DELETE admin-only. `media_vault_settings` — SELECT/UPDATE owner or admin, INSERT owner-only. `media_vault_events` — SELECT `(owner_id = auth.uid()) OR private.is_admin()`, INSERT owner-only, no UPDATE policy (history cannot be rewritten), DELETE admin-only. Verified: the anon key returns zero rows on all three tables.

## Private Vault architecture

```
Normal media (MediaStore)
        ↓  user selects "Hide"  (+ vault authentication if the vault is locked)
Encrypted vault copy written to app-private storage
        ↓  ciphertext verified (existence + size + SHA-256)
media_vault_items row persisted (vault_status = READY)
        ↓  ONLY after verification succeeds
Original MediaStore item removed / moved to the system trash
        ↓
Normal Photos / Albums / search exclude the item
Cloud media_assets row: UNCHANGED (still READY, still backed up, single Cloudinary asset)
```

**Hide flow.** The encrypted copy is always created and verified *before* the original is touched. If encryption or storage fails the original is kept, nothing is marked hidden, and the row is left `FAILED` for retry. The `ENCRYPTING` status marks the in-between state, and the partial unique index plus the `ciphertext_present` check make a crash mid-hide recoverable instead of producing duplicate vault copies.

**Unhide / restore flow.** Authenticate → decrypt the vault copy to a private temporary file → insert it back into MediaStore (filename, MIME type, size and timestamps preserved where Android allows) → verify the restored item exists → mark the row `RESTORING`→`DELETED`/remove it → only then delete the ciphertext. The vault copy is never deleted before the restored MediaStore item is verified.

**Permanent delete flow.** Separate from Hide and from Unhide. Requires authentication plus a strong confirmation, deletes the ciphertext, marks the vault row `DELETED`, and follows the existing cloud-first deletion rules for the associated `media_assets` row (Trash semantics stay with `media_assets.deleted_at`/`user_hidden_at`). The operation is idempotent.

**Authentication model.** Two factors are supported and neither is ever stored: biometric (Android `BiometricPrompt` — the app only learns success/failure; no biometric data is stored or transmitted) and a dedicated Vault PIN. The PIN is never stored in plaintext: a salted, iterated PBKDF2-HMAC-SHA256 verifier is kept in Keystore-protected private preferences, with a constant-time comparison and lockout/back-off after repeated failures. If biometric enrollment changes, the biometric binding is treated as invalid and the vault requires the PIN again rather than silently weakening. The device lock-screen PIN/password is never requested.

**Key management.** Vault media is encrypted at rest with AES-256-GCM using a key held by the **Android Keystore** (the same pattern already used by `TelegramSettingsStore`): key material never leaves the Keystore and is never written to Room, Supabase or logs. Each file carries its own random IV; the Keystore key alias is versioned via `vault_version`.

**Cloud-first behaviour.** Hiding is not deleting. The cloud `media_assets` row keeps its status, storage fields and Drive archival, and no second Cloudinary asset is created — the vault's encrypted copy and the cloud asset are separate concerns.

**Backup / sync interaction.** After a hide, the local MediaStore item is gone, so the backup scan must not treat that as a user deletion of cloud media: the vault row is the record that the media still exists. Re-discovery, re-upload and duplicate `media_assets` rows are all prevented by the existing dedup path (`local_media_id`-based, independent of the vault).

## Views

### device_storage_usage
`security_invoker = on` (respects querying user's RLS, not the view creator's).

| Column | Source |
|---|---|
| device_id | devices.id |
| user_id | devices.user_id |
| device_name | devices.device_name |
| total_bytes | SUM(media_assets.file_size) where status='READY' and deleted_at is null |
| file_count | COUNT(media_assets.id) where status='READY' and deleted_at is null |

---

## Functions

### `private` schema
| Function | Args | Returns | Security |
|---|---|---|---|
| `is_admin()` | — | boolean | DEFINER |

### `public` schema
| Function | Args | Returns | Security |
|---|---|---|---|
| `admin_allow_cleanup_despite_telegram` | p_media_id uuid, p_allow boolean, p_reason text | media_assets | DEFINER |
| `admin_create_drive_account_with_refresh_token` | p_google_email text, p_name text, p_refresh_token text | uuid | DEFINER |
| `admin_store_drive_refresh_token` | p_drive_account_id uuid, p_refresh_token text | uuid | DEFINER |
| `assign_drive_replication_job` | p_job_id uuid, p_drive_account_id uuid, p_drive_folder_id uuid | void | DEFINER |
| `claim_cloudinary_cleanup` | p_limit integer | media_assets | DEFINER |
| `claim_drive_folder` | p_drive_account_id uuid, p_owner_id uuid, p_folder_name text, p_folder_type text, p_parent_folder_id uuid | record | DEFINER |
| `claim_drive_job` | — | replication_jobs | DEFINER |
| `claim_telegram_job` | — | replication_jobs | DEFINER |
| `complete_cloudinary_cleanup` | p_media_id uuid, p_status text, p_error text | void | DEFINER |
| `complete_drive_folder` | p_folder_row_id uuid, p_google_folder_id text | drive_folders | DEFINER |
| `complete_drive_job` | p_job_id uuid, p_status text, p_last_error text, p_drive_account_id uuid, p_drive_folder_id uuid, p_google_drive_file_id text, p_next_retry_at timestamptz, p_upload_url text, p_upload_chunk bigint, p_upload_attempts integer | void | DEFINER |
| `complete_telegram_job` | p_job_id uuid, p_status text, p_last_error text, p_message_id bigint, p_telegram_file_id text, p_next_retry_at timestamptz | void | DEFINER |
| `enqueue_drive_replication_job` | p_media_id uuid | replication_jobs | DEFINER |
| `fail_drive_folder` | p_folder_row_id uuid, p_error text | void | DEFINER |
| `failover_drive_replication_job` | p_job_id uuid, p_failed_account_id uuid, p_error text, p_next_retry_at timestamptz | void | DEFINER |
| `guard_profile_privileged_columns` | — (trigger) | trigger | DEFINER |
| `handle_new_user` | — (trigger) | trigger | DEFINER |
| `list_eligible_drive_accounts` | p_required_bytes bigint, p_exclude_account_ids uuid[], p_safety_margin_bytes bigint | drive_accounts (set) | DEFINER |
| `mark_drive_account_result` | p_drive_account_id uuid, p_health_status text, p_status text, p_last_error text | drive_accounts | DEFINER |
| `release_drive_quota` | p_drive_account_id uuid, p_bytes bigint | void | DEFINER |
| `reserve_drive_account` | p_required_bytes bigint, p_exclude_account_ids uuid[], p_safety_margin_bytes bigint | drive_accounts | DEFINER |
| `select_drive_account` | p_required_bytes bigint, p_exclude_account_ids uuid[], p_safety_margin_bytes bigint | drive_accounts | DEFINER |
| `set_media_library_visibility` | p_media_id uuid, p_hidden boolean | integer | DEFINER — hardened, row-count-verified. Returns the number of rows actually updated (1 on success); raises `media_not_found` (P0001) when the id is missing or belongs to another user, so a zero-row update can never be reported as success. EXECUTE granted to `authenticated`/`service_role`, revoked from `anon`/`PUBLIC` |
| `guard_media_assets_user_hidden_at` | — (trigger) | trigger | DEFINER — rejects direct UPDATE of `media_assets.user_hidden_at` by client roles (SQLSTATE 42501); visibility changes must go through `set_media_library_visibility()` |
| `purge_expired_accessibility_data` | — | integer | DEFINER — service_role only; per-device retention cleanup |
| `set_updated_at` | — (trigger) | trigger | INVOKER |
| `sync_profile_storage_used` | — (trigger) | trigger | DEFINER — EXECUTE revoked from `anon`/`authenticated` (trigger-only, not exposed via REST) |
| `trigger_drive_worker` | — | bigint | DEFINER |
| `worker_lookup_drive_refresh_token` | p_secret_id uuid, p_drive_account_id uuid | text | DEFINER |
| `worker_lookup_telegram_token` | p_secret_id uuid, p_user_id uuid | text | DEFINER |

---

## Edge Functions

| Slug | Purpose (inferred from name) | JWT verification |
|---|---|---|
| `cloudinary-upload-auth` | Issues signed upload params for Cloudinary | on |
| `finalize-media` | Finalizes a media_assets row after upload completes | on |
| `sync-device` | Device check-in / registers device, triggers sync | on |
| `telegram-replicate` | Worker: replicates media to Telegram | on |
| `drive-admin` | Admin operations on Drive accounts/folders | on |
| `process-backup` | Orchestrates a device's backup run | on |
| `drive-replicate` | Worker: replicates media to Google Drive | on |
| `google-oauth-initiate` | Starts Google OAuth flow for a Drive account | on |
| `google-oauth-callback` | Handles Google OAuth redirect/token exchange | off (public callback endpoint) |
| `drive-cred-diagnostic` | Diagnoses Drive account credential/health issues | on |
| `drive-health-verify` | Verifies Drive account health/connectivity | on |
| `admin-media` | Admin media management endpoint | on |

---

## Relationship overview

- `profiles` → `departments` (many-to-one), owns `devices`, `media_assets`, `drive_folders`, `notifications`, `telegram_configs`
- `devices` → source of `media_assets`, `backup_sessions`; has `push_token` for FCM delivery
- `media_assets` → has many `media_variants`, `replication_jobs`, `sync_logs`; drives `profiles.storage_used_bytes` via trigger; `user_hidden_at` controls user-facing visibility independent of backup state
- `drive_accounts` → has many `drive_folders` (self-referencing parent/child tree)
- `replication_jobs` → links a `media_asset`/`media_variant` to a destination (`telegram_configs` or `drive_accounts` + `drive_folders`)
- `sync_logs` and `admin_audit_logs` are append-only logs
- `backup_sessions` summarizes each device's backup run (separate from the per-file `replication_jobs`)
- `device_storage_usage` is a reporting view, not a stored table
