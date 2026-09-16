import { serve } from "jsr:@std/http/server";
import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin, getSupabaseAuth } from "../shared/auth.ts";

/**
 * Finalize Media - Records a successful Cloudinary upload as a Supabase
 * media_assets row so later server-side replication jobs can process it.
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
 *     }
 *   }
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const MEDIA_SELECT =
  "id, owner_id, status, client_upload_id, storage_asset_id, storage_path, storage_url, uploaded_at";

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

serve(async (req: Request) => {
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
      return json({ success: true, media: existing.data }, 200);
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
      return json({ success: true, media: inserted }, 200);
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
    return json({ success: true, media: raced.data }, 200);
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