# MyDrive - Supabase Edge Functions

Edge functions for the MyDrive Android app. These run on Supabase's Deno-based
serverless runtime and can be called from the Android app via HTTP.

## Functions

### `hello-world`
Test function to verify edge functions are deployed and working.

### `sync-device`
Syncs device metadata (name, platform, storage) with the database.
Requires authenticated user (JWT).

### `process-backup`
Records backup metadata after files are uploaded. Creates backup and file
records in the database. Requires authenticated user (JWT).

### `finalize-media`
Records a successful Cloudinary upload as a `media_assets` row so server-side
replication jobs can process it later. Authenticates the user, verifies the
Cloudinary asset is in the user's own folder, verifies `device_id` ownership,
and is idempotent on `client_upload_id` (retries never duplicate records).

When the user has a Telegram destination enabled/configured, it also creates a
`PENDING` row in `replication_jobs` (`destination_type = 'telegram'`) for the
future server-side Telegram worker. Job creation is server-side only and
idempotent on `(media_id, destination_type, telegram_config_id)`, so retried or
concurrent finalize requests never create duplicate jobs. It NEVER uploads
media to Telegram and NEVER exposes the Telegram bot token.
Requires authenticated user (JWT).

Deployment applies the queue migration too:
```bash
supabase db push
supabase functions deploy finalize-media
```

### `telegram-replicate`
Server-side worker that processes PENDING/RETRYING Telegram replication jobs.
Automatically called by the `finalize-media` function when Telegram backup is enabled.

**Worker behavior:**
- Atomically claims one job at a time using `SELECT FOR UPDATE SKIP LOCKED` via the `claim_telegram_job()` SQL function
- Loads the media's Cloudinary URL and the user's Telegram bot token from the server-side secrets store
- Downloads media from Cloudinary via server-side fetch (never from Android)
- Sends the media to Telegram using the appropriate Bot API method (sendPhoto/sendVideo/sendDocument)
- Handles Telegram rate limits (429) by requeueing with the Telegram-suggested retry-after delay
- Handles transient failures (5xx, network errors) with exponential backoff
- Handles permanent failures (401/403/400) by marking the job FAILED
- Skips files exceeding Telegram's size limits (10 MB photo / 50 MB video+document)
- Never logs the Telegram Bot Token
- Never exposes the Bot Token to Android clients
- Is idempotent — a completed job is never re-processed

**Processing limits:**
- Maximum 5 jobs per invocation (stays within Edge Function timeout)
- Maximum 3 concurrent in-process workers

**Required server-side secrets (Supabase Edge Function secrets):**
```
SUPABASE_URL=auto-provided
SUPABASE_SERVICE_ROLE_KEY=auto-provided
```
Plus bot tokens stored via the Supabase Vault extension or as individual secrets:
```
# Option A: Supabase Vault (recommended)
# Store bot tokens via the Vault UI or vault.write() SQL function
# referenced by bot_token_secret_id in telegram_configs

# Option B: Individual secrets (fallback)
# Set via: supabase secrets set TELEGRAM_BOT_TOKEN_USER_<uuid>=<token>
```

**Trigger modes:**
- HTTP POST: `supabase functions invoke telegram-replicate --body '{}'`
- Public URL (for cron/manual test): append `?public_url=true`
- Cron scheduling (recommended): set up a pg_cron job or external scheduler to call the function periodically

**Deployment:**
```bash
supabase db push                   # applies the worker SQL migration
supabase functions deploy telegram-replicate
```

### `cloudinary-upload-auth`
Generates signed Cloudinary upload authorization for authenticated users.
The API Secret is never exposed to the client. Requires authenticated user (JWT).

**Server-side secrets required:**
```
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_api_secret   # NEVER returned to client
```

### `drive-admin`
Admin-only management of the Google Drive archive account pool. Requires a
valid JWT for a user whose `profiles.role = 'admin'`. Supports any number of
accounts (no hardcoded limit).

**Actions (POST body):**
```json
{ "action": "list" }
{ "action": "create", "google_email": "...", "display_name": "...", "name": "...", "priority": 100, "enabled": true, "root_folder_id": "...", "notes": "...", "refresh_token": "..." }
{ "action": "update", "id": "<uuid>", "display_name": "...", "...": "..." }
{ "action": "set_secret", "id": "<uuid>", "refresh_token": "..." }
{ "action": "set_enabled", "id": "<uuid>", "enabled": true }
```

- Refresh tokens are written to Supabase Vault via
  `admin_store_drive_refresh_token()`; only the secret reference is stored on
  `drive_accounts`. Tokens are never returned.
- Never uploads media. The Drive upload worker is not part of this foundation.

**Deployment:**
```bash
supabase functions deploy drive-admin
```

## Drive foundation modules (shared)

These modules are the server-side foundation the future Drive worker will
compose. They perform **no media upload**.

- `shared/google-drive.ts` — OAuth refresh-token exchange and folder
  find/create against the Drive v3 API. Requires
  `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` Edge Function secrets.
- `shared/drive-router.ts` — selects/reserves an eligible Drive account across
  any number of accounts (`list_eligible_drive_accounts`,
  `reserve_drive_account`), with failover exclusions and health feedback.
- `shared/drive-folders.ts` — idempotent, concurrency-safe per-user folder
  resolution backed by `drive_folders` (advisory lock + creation lease).

## Development

### Prerequisites
- [Supabase CLI](https://supabase.com/docs/guides/cli) installed
- Logged in: `supabase login`

### Local Development
```bash
# Start local Supabase stack (DB, Auth, Edge Functions, Studio)
supabase start

# Serve a single function locally for testing
supabase functions serve hello-world
```

### Deploy to Production
```bash
# Deploy all functions
supabase functions deploy

# Deploy a specific function
supabase functions deploy sync-device
supabase functions deploy process-backup

# Deploy with no verify (skip JWT verification check)
supabase functions deploy --no-verify-jwt
```

### Calling Functions from Android

```kotlin
import io.github.jan.supabase.functions.functions

// Call an edge function
val response = supabase.functions.invoke("sync-device") {
    body = buildJsonObject {
        put("device_id", "abc-123")
        put("device_name", "Pixel 8")
        put("platform", "android")
    }
}
```

### Environment Variables

These are automatically available in edge functions:
- `SUPABASE_URL` - Your project's API URL
- `SUPABASE_ANON_KEY` - Your project's anonymous/public key
- `SUPABASE_SERVICE_ROLE_KEY` - Admin key (bypasses RLS)
- `CLOUDINARY_CLOUD_NAME` - Cloudinary cloud name (for cloudinary-upload-auth)
- `CLOUDINARY_API_KEY` - Cloudinary API key (for cloudinary-upload-auth)
- `CLOUDINARY_API_SECRET` - Cloudinary API secret (for cloudinary-upload-auth, never returned to client)

## Database Tables Expected

For `sync-device`:
```sql
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  platform TEXT DEFAULT 'android',
  os_version TEXT,
  app_version TEXT,
  storage_total_gb NUMERIC DEFAULT 0,
  storage_used_gb NUMERIC DEFAULT 0,
  last_synced_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now()
);
```

For `cloudinary-upload-auth`, no database tables are required — it is a pure signing service.

For `process-backup`:
```sql
CREATE TABLE backups (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id),
  backup_type TEXT DEFAULT 'incremental',
  file_count INTEGER DEFAULT 0,
  total_size_bytes BIGINT DEFAULT 0,
  status TEXT DEFAULT 'pending',
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE backup_files (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  backup_id UUID REFERENCES backups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id TEXT,
  file_path TEXT NOT NULL,
  file_name TEXT NOT NULL,
  file_size BIGINT DEFAULT 0,
  mime_type TEXT,
  thumbnail_path TEXT,
  status TEXT DEFAULT 'pending',
  created_at TIMESTAMPTZ DEFAULT now()
);
```
