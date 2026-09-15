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

### `cloudinary-upload-auth`
Generates signed Cloudinary upload authorization for authenticated users.
The API Secret is never exposed to the client. Requires authenticated user (JWT).

**Server-side secrets required:**
```
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_api_secret   # NEVER returned to client
```

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
