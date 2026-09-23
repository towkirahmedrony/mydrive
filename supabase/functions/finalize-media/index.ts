import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin, getSupabaseAuth } from "../shared/auth.ts";

/**
 * Finalize Media - Records a successful Cloudinary upload as a Supabase
 * media_assets row (status READY) and, when the user has Telegram backup
 * enabled/configured, queues a PENDING Telegram replication job for a future
 * server-side worker.
 *
 * The Android client uploads media directly to Cloudinary (signed via
 * `cloudinary-upload-auth`) and then calls this function with only the
 * upload result it received back. This function:
 *
 *   - authenticates the Supabase user from the JWT
 *   - verifies the Cloudinary asset lies in that user's own folder
 *   - verifies the supplied device_id belongs to that user
 *   - validates required fields
 *   - creates the media_assets row with owner_id taken from the JWT
 *     (never from the client), status READY, storage_provider cloudinary
 *   - is idempotent on client_upload_id, so retrying the same request
 *     never creates a duplicate media record
 *   - ALWAYS creates the PENDING Google Drive replication job for the media
 *     (enqueue_drive_replication_job), which is what makes the media lifecycle
 *     run: media_assets -> Drive archive -> verified -> Cloudinary cleanup.
 *     Idempotent: one Drive job per media. Google Drive is a server-side
 *     archive — the uploaded media is copied to storage the app user never
 *     touches, so no Drive terminology appears in the Android UI.
 *   - when a Telegram destination exists and is enabled, creates a PENDING
 *     replication_jobs row — idempotent on
 *     (media_id, destination_type, telegram_config_id), so retried or
 *     concurrent finalize requests never create duplicate jobs
 *
 * This function NEVER uploads media to Telegram or Google Drive, NEVER calls
 * Cloudinary, and NEVER exposes the Telegram bot token or any Drive
 * credential. Replication is queued for the server-side workers.
 *
 * The Cloudinary API Secret and Supabase service-role key never appear in
 * the response and are never logged.
 *
 * Required server-side secrets: none beyond SUPABASE_URL/SERVICE_ROLE_KEY
 * (auto-provided by Supabase Edge Runtime).
 *
 * Usage:
 *   POST https://<project-ref>.supabase.co/functions/v1/finalize-media
 *   Headers:
 *     apikey: <anon-key>
 *     Authorization: Bearer <user-jwt>
 *     Content-Type: application/json
 *
 * Request body:
 *   {
 *     "client_upload_id": "<uuid>",        // idempotency key, required
 *     "device_id": "<uuid>",               // registered device row id, required
 *     "local_media_id": 12345,             // optional MediaStore id
 *     "file_name": "IMG_1234.jpg",         // required
 *     "mime_type": "image/jpeg",           // required
 *     "file_size": 1234567,                // required (bytes, >= 0)
 *     "width": 4032,                       // optional
 *     "height": 3024,                      // optional
 *     "duration_ms": null,                 // optional
 *     "asset_id": "<cloudinary asset_id>", // required -> storage_asset_id
 *     "public_id": "<cloudinary public_id>",// required -> storage_path
 *     "secure_url": "https://res.cloudinary.com/...", // required -> storage_url
 *     "version": 1726000000,               // optional Cloudinary version
 *     "format": "jpg",                     // optional Cloudinary format
 *     "resource_type": "image"             // optional Cloudinary resource type
 *   }
 *
 * Response (200):
 *   {
 *     "success": true,
 *     "media": {
 *       "id": "uuid",
 *       "owner_id": "uuid",
 *       "status": "READY",
 *       "client_upload_id": "uuid",
 *       "storage_asset_id": "...",
 *       "storage_path": "...",
 *       "storage_url": "...",
 *       "uploaded_at": "..."
 *     },
 *     "telegram_job": {                    // null when Telegram is not
 *       "id": "uuid",                      // enabled/configured
 *       "destination_type": "telegram",
 *       "status": "PENDING"
 *     },
 *     "drive_job": {                       // server-side archive queue
 *       "id": "uuid",
 *       "destination_type": "google_drive",
 *       "status": "PENDING"
 *     }
 *   }
 *
 * Response compatibility: `drive_job` is an ADDITIVE field. The Android
 * request/response contract for the existing fields is unchanged, so a client
 * that ignores it keeps working.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const MEDIA_SELECT =
  "id, owner_id, status, client_upload_id, storage_asset_id, storage_path, storage_url, uploaded_at";

const TELEGRAM_JOB_SELECT =
  "id, media_id, destination_type, status, telegram_config_id, attempt_count, last_error, created_at";

const DRIVE_JOB_SELECT =
  "id, media_id, destination_type, status, attempt_count, last_error, created_at";

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

type DriveJobRow = {
  id: string;
  media_id: string;
  destination_type: string;
  status: string;
  attempt_count: number;
  last_error: string | null;
  created_at: string;
};

type TelegramJobRow = {
  id: string;
  media_id: string;
  destination_type: string;
  status: string;
  telegram_config_id: string | null;
  attempt_count: number;
  last_error: string | null;
  created_at: string;
};

function json(payload: unknown, status: number): Response {
  return new Response(
    JSON.stringify(payload),
    { headers: { ...corsHeaders, "Content-Type": "application/json" }, status },
  );
}

function toNonNegativeLong(value: unknown): number | null {
  if (typeof value === "number" && Number.isInteger(value) && value >= 0) return value;
  if (typeof value === "string" && /^\d+$/.test(value.trim())) {
    return Number.parseInt(value, 10);
  }
  return null;
}

function toPositiveInt(value: unknown): number | null {
  if (typeof value === "number" && Number.isInteger(value) && value > 0) return value;
  if (typeof value === "string" && /^\d+$/.test(value.trim()) && Number(value) > 0) {
    return Number.parseInt(value, 10);
  }
  return null;
}

/**
 * Creates a PENDING Telegram replication job for a finalized media asset,
 * entirely server-side. Returns the job row, or null when the user has no
 * enabled/configured Telegram destination. Idempotent: the unique index on
 * (media_id, destination_type, telegram_config_id) plus an ignore-duplicates
 * insert guarantees a retried or concurrent finalize never creates a second
 * job. Never uploads media to Telegram and never returns the bot token.
 */
async function ensureTelegramReplicationJob(
  admin: AdminClient,
  userId: string,
  mediaId: string,
): Promise<TelegramJobRow | null> {
  // Only queue work when the user actually has a usable Telegram destination.
  const { data: config, error: configError } = await admin
    .from("telegram_configs")
    .select("id, chat_id, enabled")
    .eq("user_id", userId)
    .maybeSingle();

  if (configError) {
    throw new Error(`Telegram config lookup failed: ${configError.message}`);
  }
  if (!config || config.enabled !== true) return null;
  if (typeof config.chat_id !== "string" || config.chat_id.trim().length === 0) return null;

  // Fast path: the job for this media/config already exists.
  const { data: existing, error: existingError } = await admin
    .from("replication_jobs")
    .select(TELEGRAM_JOB_SELECT)
    .eq("media_id", mediaId)
    .eq("destination_type", "telegram")
    .eq("telegram_config_id", config.id)
    .maybeSingle();

  if (existingError) {
    throw new Error(`Telegram job lookup failed: ${existingError.message}`);
  }
  if (existing) return existing;

  const now = new Date().toISOString();
  const { data: created, error: insertError } = await admin
    .from("replication_jobs")
    .insert(
      {
        media_id: mediaId,
        destination_type: "telegram",
        telegram_config_id: config.id,
        status: "PENDING",
        attempt_count: 0,
        created_at: now,
        updated_at: now,
      },
      {
        onConflict: "media_id,destination_type,telegram_config_id",
        ignoreDuplicates: true,
      },
    )
    .select(TELEGRAM_JOB_SELECT)
    .maybeSingle();

  if (insertError) {
    // 23505 = unique_violation: a concurrent request created the job first.
    if (insertError.code === "23505") {
      const { data: raced } = await admin
        .from("replication_jobs")
        .select(TELEGRAM_JOB_SELECT)
        .eq("media_id", mediaId)
        .eq("destination_type", "telegram")
        .eq("telegram_config_id", config.id)
        .maybeSingle();
      return raced ?? null;
    }
    throw new Error(`Failed to create Telegram replication job: ${insertError.message}`);
  }
  if (created) return created;

  // Insert was skipped because the job already exists (ignoreDuplicates).
  const { data: raced } = await admin
    .from("replication_jobs")
    .select(TELEGRAM_JOB_SELECT)
    .eq("media_id", mediaId)
    .eq("destination_type", "telegram")
    .eq("telegram_config_id", config.id)
    .maybeSingle();
  return raced ?? null;
}

/**
 * Queues the media for server-side Google Drive archival.
 *
 * This is the producer for `destination_type = 'google_drive'` jobs: without it
 * a finalized media would never be copied to the Drive storage pool and would
 * never become eligible for Cloudinary cleanup. The job itself is created by
 * the database function `enqueue_drive_replication_job`, which is idempotent
 * (one Drive job per media, advisory-locked) and records the
 * DRIVE_JOB_CREATED event. No Drive account is chosen here — the Drive Router
 * picks a healthy account with enough quota when the worker runs, so this call
 * works with any number of Drive accounts and even before one is connected.
 *
 * Never returns or logs a Drive credential; the job carries no secret.
 * A failure to enqueue is logged and reported, but MUST NOT fail the finalize
 * request: the Cloudinary upload and the media record are already durable.
 */
async function ensureDriveReplicationJob(
  admin: AdminClient,
  mediaId: string,
): Promise<DriveJobRow | null> {
  const { data: created, error } = await admin.rpc(
    "enqueue_drive_replication_job",
    { p_media_id: mediaId },
  );

  if (error) {
    console.error(
      `Failed to enqueue Drive replication job for media ${mediaId}: ${error.message}`,
    );
    return null;
  }

  const row = (created as DriveJobRow | null) ?? null;
  if (row?.id) return row;

  // The RPC returned a NULL composite (PostgREST shape) — fall back to a read.
  const { data: existing } = await admin
    .from("replication_jobs")
    .select(DRIVE_JOB_SELECT)
    .eq("media_id", mediaId)
    .eq("destination_type", "google_drive")
    .maybeSingle();

  return (existing as DriveJobRow | null) ?? null;
}

function successWithJob(
  media: unknown,
  job: TelegramJobRow | null,
  driveJob: DriveJobRow | null,
): Response {
  return json(
    {
      success: true,
      media,
      telegram_job: job
        ? { id: job.id, destination_type: job.destination_type, status: job.status }
        : null,
      drive_job: driveJob
        ? {
          id: driveJob.id,
          destination_type: driveJob.destination_type,
          status: driveJob.status,
        }
        : null,
    },
    200,
  );
}

Deno.serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    // ── 1. Authenticate the caller ────────────────────────────────────
    // owner_id is always taken from the JWT; the client can never pick it.
    const { user } = await getSupabaseAuth(req);
    const admin = getSupabaseAdmin();

    // ── 2. Parse and validate the body ─────────────────────────────────
    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch {
      return json({ error: "Invalid JSON body" }, 400);
    }

    const clientUploadId = typeof body.client_upload_id === "string"
      ? body.client_upload_id.trim()
      : "";
    const deviceId = typeof body.device_id === "string" ? body.device_id.trim() : "";
    const fileName = typeof body.file_name === "string" ? body.file_name.trim() : "";
    const mimeType = typeof body.mime_type === "string" ? body.mime_type.trim() : "";
    const assetId = typeof body.asset_id === "string" ? body.asset_id.trim() : "";
    const publicId = typeof body.public_id === "string" ? body.public_id.trim() : "";
    const secureUrl = typeof body.secure_url === "string" ? body.secure_url.trim() : "";
    const format = typeof body.format === "string" ? body.format.trim() : "";
    const resourceType = typeof body.resource_type === "string"
      ? body.resource_type.trim()
      : "auto";

    if (!UUID_RE.test(clientUploadId)) {
      return json({ error: "client_upload_id must be a valid UUID" }, 400);
    }
    if (!UUID_RE.test(deviceId)) {
      return json({ error: "device_id must be a valid UUID" }, 400);
    }
    if (!fileName) return json({ error: "file_name is required" }, 400);
    if (!mimeType) return json({ error: "mime_type is required" }, 400);
    if (!assetId) return json({ error: "asset_id is required" }, 400);
    if (!publicId) return json({ error: "public_id is required" }, 400);
    if (!secureUrl) return json({ error: "secure_url is required" }, 400);

    const fileSize = toNonNegativeLong(body.file_size) ?? 0;

    // ── 3. Verify the device belongs to this user ──────────────────────
    const { data: device, error: deviceError } = await admin
      .from("devices")
      .select("id, status")
      .eq("id", deviceId)
      .eq("user_id", user.id)
      .maybeSingle();

    if (deviceError) {
      throw new Error(`Device lookup failed: ${deviceError.message}`);
    }
    if (!device) {
      return json({ error: "Device not found or does not belong to this user" }, 403);
    }
    if (device.status === "disabled") {
      return json({ error: "Device is disabled" }, 403);
    }

    // ── 4. Verify the Cloudinary asset sits in this user's folder ──────
    // cloudinary-upload-auth always signs folder "mydrive/<user-id>", so a
    // valid public_id must begin with that prefix. This prevents a client
    // from registering assets uploaded by someone else.
    const ownerFolder = `mydrive/${user.id}/`;
    if (!publicId.startsWith(ownerFolder)) {
      return json({ error: "Cloudinary asset does not belong to this user" }, 403);
    }

    // ── 5. Idempotency: reuse an existing record for this key ──────────
    const existing = await admin
      .from("media_assets")
      .select(MEDIA_SELECT)
      .eq("client_upload_id", clientUploadId)
      .maybeSingle();

    if (existing.data) {
      if (existing.data.owner_id !== user.id) {
        return json({ error: "client_upload_id is already registered to another user" }, 409);
      }
      // Ensure the Telegram and Drive jobs exist even on a retry (self-healing
      // when the previous attempt failed after the media row was written).
      const job = await ensureTelegramReplicationJob(admin, user.id, existing.data.id);
      const driveJob = await ensureDriveReplicationJob(admin, existing.data.id);
      return successWithJob(existing.data, job, driveJob);
    }

    // ── 6. Create the media record (all identity fields from the JWT) ──
    const uploadedAt = new Date().toISOString();
    const { data: inserted, error: insertError } = await admin
      .from("media_assets")
      .insert(
        {
          owner_id: user.id,
          device_id: deviceId,
          local_media_id: toNonNegativeLong(body.local_media_id),
          file_name: fileName,
          mime_type: mimeType,
          file_size: fileSize,
          width: toPositiveInt(body.width),
          height: toPositiveInt(body.height),
          duration_ms: toNonNegativeLong(body.duration_ms),
          storage_provider: "cloudinary",
          storage_asset_id: assetId,
          storage_path: publicId,
          storage_url: secureUrl,
          client_upload_id: clientUploadId,
          status: "READY",
          uploaded_at: uploadedAt,
        },
        { onConflict: "client_upload_id", ignoreDuplicates: true },
      )
      .select(MEDIA_SELECT)
      .maybeSingle();

    if (insertError) {
      throw new Error(`Failed to record media: ${insertError.message}`);
    }
    if (inserted) {
      const job = await ensureTelegramReplicationJob(admin, user.id, inserted.id);
      const driveJob = await ensureDriveReplicationJob(admin, inserted.id);
      return successWithJob(inserted, job, driveJob);
    }

    // A concurrent request won the race. Return its row only if it is ours.
    const raced = await admin
      .from("media_assets")
      .select(MEDIA_SELECT)
      .eq("client_upload_id", clientUploadId)
      .maybeSingle();

    if (!raced.data) {
      return json({ error: "Media record could not be created" }, 500);
    }
    if (raced.data.owner_id !== user.id) {
      return json({ error: "client_upload_id is already registered to another user" }, 409);
    }
    const racedJob = await ensureTelegramReplicationJob(admin, user.id, raced.data.id);
    const racedDriveJob = await ensureDriveReplicationJob(admin, raced.data.id);
    return successWithJob(raced.data, racedJob, racedDriveJob);
  } catch (error) {
    const message = (error as Error).message;
    const isAuthError =
      message.includes("Missing Authorization") ||
      message.includes("Invalid or expired token");
    // Logger intentionally only sees generic errors, never secrets.
    console.error("finalize-media failed:", isAuthError ? "auth error" : message);
    return json({ error: message }, isAuthError ? 401 : 500);
  }
});
