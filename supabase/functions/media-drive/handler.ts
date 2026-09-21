import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin } from "../shared/auth.ts";
import { accessTokenForAccount } from "../shared/drive-folders.ts";
import {
  driveMediaFailure,
  openDriveFileContent,
  openDriveThumbnail,
  readDriveFileMetadata,
} from "../shared/drive-media-read.ts";

/**
 * media-drive — ADMIN-ONLY read path for media that lives in the Google Drive
 * archive.
 *
 * Why this exists
 * ---------------
 * The production lifecycle is:
 *
 *   Android -> Cloudinary -> media_assets -> Google Drive archive
 *                                              |
 *                              primary Cloudinary asset deleted
 *                              only AFTER the Drive copy is verified
 *
 * So a historical `media_assets` row can be perfectly valid while its
 * Cloudinary original no longer exists. Every admin surface that resolved
 * media from the Cloudinary delivery URL alone therefore reported a normal,
 * healthy archived file as "no longer present in storage". This function is
 * the missing read direction: it serves the verified Drive copy.
 *
 * What it is NOT
 * --------------
 *   - it does not upload, copy, delete, move or restore anything, and it makes
 *     no change to the archive, to `media_assets` or to `replication_jobs`;
 *   - it does not re-create a deleted Cloudinary asset;
 *   - it does not introduce a second Google OAuth/token system: it reuses the
 *     production credential helper (`accessTokenForAccount`), which reads the
 *     refresh token from Supabase Vault through the same backend-only RPC the
 *     replication worker uses.
 *
 * Contract
 * --------
 *   POST /functions/v1/media-drive
 *   Headers: Authorization: Bearer <admin user JWT>, apikey: <anon key>
 *            Range: bytes=... (optional, forwarded to Drive for seeking)
 *   Body:    { "media_id": "<uuid>", "variant": "thumb" | "original",
 *              "owner_id": "<uuid>" (optional, must match the row when sent) }
 *
 *   Success: the media bytes are streamed back (200, or 206 for a Range
 *            request) with Content-Type / Content-Length / Content-Range /
 *            Accept-Ranges / ETag forwarded from Drive.
 *   Failure: JSON `{ success:false, error, reason, retryable }`.
 *
 * Account selection
 * -----------------
 * The Drive account is NEVER hardcoded and is never chosen by the caller. It
 * is read from the media's own completed replication job
 * (`replication_jobs.drive_account_id`), so a file is always read through the
 * account that actually owns it and the path works unchanged as more accounts
 * are added to the pool.
 *
 * Security
 * --------
 *   - the platform verifies the JWT before this code runs, and the caller must
 *     additionally be `profiles.role = 'admin'`;
 *   - the caller supplies an internal `media_id` only. A Google Drive file id
 *     is never accepted from the client: it is looked up from the database, so
 *     an admin cannot turn this endpoint into a Drive-wide file reader;
 *   - when `owner_id` is supplied it must match the row, so a request cannot be
 *     repointed at another employee's media;
 *   - no refresh token, access token, Vault reference, service-role key or
 *     provider secret is ever returned to the caller or written to a log.
 */

/** Rows the resolver reads. `storage_provider` is diagnostic only. */
const MEDIA_SELECT_COLUMNS = [
  "id",
  "owner_id",
  "file_name",
  "mime_type",
  "file_size",
  "storage_provider",
  "primary_cleanup_status",
  "primary_deleted_at",
  "drive_archived_at",
].join(", ");

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

/**
 * Everything the handler needs from its runtime. Injecting these is what makes
 * the exact production request path reusable by a verification harness (and by
 * tests) without a second copy of the logic, and without weakening the real
 * entrypoint: `index.ts` supplies the platform's JWT verification and the real
 * service-role client.
 */
export interface MediaDriveDependencies {
  /** Resolves the authenticated caller, or throws when the JWT is unusable. */
  authenticate: (req: Request) => Promise<{ userId: string }>;
  /** Service-role client. Called only after the caller is authenticated. */
  adminClient: () => AdminClient;
  /** true when the authenticated user has `profiles.role = 'admin'`. */
  isAdmin: (userId: string, admin: AdminClient) => Promise<boolean>;
}


type Variant = "thumb" | "original";

/**
 * Small, bounded, per-isolate thumbnail cache.
 *
 * A grid render asks for the same tiles repeatedly (scrolling, paging back, a
 * second admin opening the same folder). Google's own thumbnail is immutable
 * for a given file revision, so caching it briefly removes the duplicate Drive
 * round-trip without ever holding a large original in memory. Only thumbnails
 * are cached, only up to `THUMB_CACHE_MAX_BYTES`, and only for
 * `THUMB_CACHE_TTL_MS` — an isolate restart simply drops it.
 */
const THUMB_CACHE_TTL_MS = 10 * 60 * 1000;
const THUMB_CACHE_MAX_ENTRIES = 256;
const THUMB_CACHE_MAX_BYTES = 512 * 1024;

interface CachedThumbnail {
  body: ArrayBuffer;
  contentType: string;
  expiresAt: number;
}

const thumbnailCache = new Map<string, CachedThumbnail>();

function readThumbnailCache(key: string): CachedThumbnail | null {
  const hit = thumbnailCache.get(key);
  if (!hit) return null;
  if (hit.expiresAt <= Date.now()) {
    thumbnailCache.delete(key);
    return null;
  }
  // Refresh recency: Map preserves insertion order, so re-inserting the entry
  // makes the oldest untouched key the first eviction candidate.
  thumbnailCache.delete(key);
  thumbnailCache.set(key, hit);
  return hit;
}

function writeThumbnailCache(key: string, value: CachedThumbnail): void {
  if (value.body.byteLength > THUMB_CACHE_MAX_BYTES) return;
  thumbnailCache.set(key, value);
  while (thumbnailCache.size > THUMB_CACHE_MAX_ENTRIES) {
    const oldest = thumbnailCache.keys().next().value;
    if (oldest === undefined) break;
    thumbnailCache.delete(oldest);
  }
}

function json(
  payload: Record<string, unknown>,
  status: number,
): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/**
 * Failure response. `reason` is the stable machine-readable code the Admin
 * Panel branches on so it can distinguish a genuinely absent file from an
 * authentication, timeout or provider fault.
 */
function fail(
  reason: string,
  message: string,
  status: number,
  retryable: boolean,
): Response {
  return json({ success: false, error: message, reason, retryable }, status);
}

/** Where a failure reason lands on the HTTP status line. */
function statusForReason(reason: string, upstreamStatus: number): number {
  switch (reason) {
    case "archive_missing":
    case "no_preview":
      return 404;
    case "credential_error":
      return 503;
    case "range_not_satisfiable":
      return 416;
    case "provider_unavailable":
      // Preserve a rate-limit answer so a caller can back off correctly.
      return upstreamStatus === 429 ? 429 : 502;
    default:
      return 502;
  }
}

export async function assertAdmin(admin: AdminClient, userId: string): Promise<boolean> {
  const { data, error } = await admin
    .from("profiles")
    .select("role")
    .eq("id", userId)
    .maybeSingle();

  if (error) throw new Error(`Admin check failed: ${error.message}`);
  return (data as { role?: string } | null)?.role === "admin";
}

function asVariant(value: unknown): Variant {
  return value === "thumb" ? "thumb" : "original";
}

interface ResolvedArchive {
  accountId: string;
  fileId: string;
  /** Persisted user folder id the worker uploaded into, when resolvable. */
  expectedFolderId: string | null;
}

/**
 * Resolves the archived file for one media row.
 *
 * Ownership/relationship chain (all existing production relationships — no new
 * schema):
 *
 *   media_assets.id
 *     -> replication_jobs (media_id, destination_type='google_drive')
 *          .drive_account_id  -> which pooled Drive account owns the file
 *          .drive_folder_id   -> drive_folders.google_folder_id (its folder)
 *          .google_drive_file_id
 *
 * Returns null when no completed Drive copy is recorded, which is a legitimate
 * "this media was never archived" answer rather than an error.
 */
async function resolveArchive(
  admin: AdminClient,
  mediaId: string,
): Promise<ResolvedArchive | null> {
  const { data: job, error: jobError } = await admin
    .from("replication_jobs")
    .select("id, status, google_drive_file_id, drive_account_id, drive_folder_id")
    .eq("media_id", mediaId)
    .eq("destination_type", "google_drive")
    .maybeSingle();

  if (jobError) {
    throw new Error(`Drive job lookup failed: ${jobError.message}`);
  }

  const row = job as {
    status?: string | null;
    google_drive_file_id?: string | null;
    drive_account_id?: string | null;
    drive_folder_id?: string | null;
  } | null;

  const fileId = row?.google_drive_file_id?.trim() ?? "";
  const accountId = row?.drive_account_id?.trim() ?? "";
  // A Drive copy only counts once the worker completed it and persisted the
  // file id; a half-finished job is never used as a read source.
  if (row?.status !== "COMPLETED" || !fileId || !accountId) return null;

  let expectedFolderId: string | null = null;
  const folderRowId = row.drive_folder_id?.trim() ?? "";
  if (folderRowId) {
    const { data: folder } = await admin
      .from("drive_folders")
      .select("google_folder_id")
      .eq("id", folderRowId)
      .maybeSingle();
    expectedFolderId =
      (folder as { google_folder_id?: string | null } | null)
        ?.google_folder_id?.trim() ?? null;
  }

  return { accountId, fileId, expectedFolderId };
}

/** Streams an upstream Drive response onward, preserving range semantics. */
function streamThrough(
  upstream: Response,
  options: {
    contentType: string;
    cacheControl: string;
    integrity: string;
    variant: Variant;
  },
): Response {
  const headers = new Headers({
    ...corsHeaders,
    "Content-Type": options.contentType,
    "Cache-Control": options.cacheControl,
    "X-Content-Type-Options": "nosniff",
    "X-MyDrive-Source": "drive-archive",
    "X-MyDrive-Variant": options.variant,
    "X-MyDrive-Archive-Integrity": options.integrity,
  });

  for (
    const header of [
      "content-length",
      "content-range",
      "accept-ranges",
      "etag",
      "last-modified",
    ] as const
  ) {
    const value = upstream.headers.get(header);
    if (value) headers.set(header, value);
  }
  // Byte ranges are what let an HTML5 player seek instead of downloading the
  // whole file first; advertise support even when Drive omits the header.
  if (!headers.has("accept-ranges")) headers.set("accept-ranges", "bytes");

  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers,
  });
}

/**
 * The production request path for one admin media read.
 *
 * Exported rather than defined inline in `Deno.serve` so the identical code can
 * be driven by a verification harness or a test; `index.ts` only supplies the
 * runtime dependencies.
 */
export async function handleMediaDriveRequest(
  req: Request,
  deps: MediaDriveDependencies,
): Promise<Response> {

  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  if (req.method !== "POST") {
    return fail("invalid_request", "Method not allowed", 405, false);
  }

  try {
    // ── 1. Authenticate the admin caller ──────────────────────────────────
    const { userId } = await deps.authenticate(req);
    const admin = deps.adminClient();

    if (!(await deps.isAdmin(userId, admin))) {
      return fail("forbidden", "Admin privileges required", 403, false);
    }

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch {
      return fail("invalid_request", "Invalid JSON body", 400, false);
    }

    const mediaId = typeof body.media_id === "string"
      ? body.media_id.trim()
      : "";
    if (!UUID_RE.test(mediaId)) {
      return fail("invalid_request", "media_id must be a valid UUID", 400, false);
    }
    const variant = asVariant(body.variant);

    // ── 2. Load and validate the media record ─────────────────────────────
    const { data: mediaRow, error: mediaError } = await admin
      .from("media_assets")
      .select(MEDIA_SELECT_COLUMNS)
      .eq("id", mediaId)
      .maybeSingle();

    if (mediaError) {
      throw new Error(`Media lookup failed: ${mediaError.message}`);
    }
    if (!mediaRow) {
      return fail("media_not_found", "Media record not found", 404, false);
    }

    const media = mediaRow as unknown as Record<string, unknown>;
    const ownerId = typeof media.owner_id === "string" ? media.owner_id : "";

    // Requested-scope check: a caller that names the employee must be naming
    // the employee that owns this row, so a media id can never be repointed.
    const requestedOwner = typeof body.owner_id === "string"
      ? body.owner_id.trim()
      : "";
    if (requestedOwner && requestedOwner !== ownerId) {
      return fail(
        "ownership_mismatch",
        "This media does not belong to the requested employee",
        403,
        false,
      );
    }

    // ── 3. Resolve the archived copy + the account that owns it ───────────
    const archive = await resolveArchive(admin, mediaId);
    if (!archive) {
      return fail(
        "not_archived",
        "No completed Google Drive copy is recorded for this media",
        404,
        false,
      );
    }

    const { data: accountRow, error: accountError } = await admin
      .from("drive_accounts")
      .select("id, enabled, status, google_email")
      .eq("id", archive.accountId)
      .maybeSingle();

    if (accountError) {
      throw new Error(`Drive account lookup failed: ${accountError.message}`);
    }
    const account = accountRow as
      | { enabled?: boolean | null; status?: string | null }
      | null;
    if (!account) {
      return fail(
        "account_missing",
        "The Drive account that holds this file no longer exists",
        503,
        false,
      );
    }
    if (account.enabled === false || account.status === "disabled") {
      return fail(
        "account_disabled",
        "The Drive account that holds this file is disabled",
        503,
        false,
      );
    }

    // ── 4. Access token through the existing Vault-backed helper ──────────
    let accessToken: string;
    try {
      accessToken = await accessTokenForAccount(admin, archive.accountId);
    } catch (tokenError) {
      const message = (tokenError as Error)?.message ?? "unknown";
      console.error(
        "[media-drive] drive credential unusable:",
        JSON.stringify({
          media_id: mediaId,
          account_id: archive.accountId,
          // Message only: the helper never puts token material in it.
          detail: message.slice(0, 200),
        }),
      );
      return fail(
        "credential_error",
        "The Google credential for this archive account is not usable right now.",
        503,
        false,
      );
    }

    // ── 5. Verify the archived file still exists ──────────────────────────
    const metadata = await readDriveFileMetadata({
      accessToken,
      fileId: archive.fileId,
    });

    if (metadata.trashed) {
      // The only case where the archived copy is genuinely unavailable.
      return fail(
        "archive_missing",
        "The archived Drive file has been trashed",
        404,
        false,
      );
    }

    // Integrity note: the file id itself is authoritative (it came from our
    // own database), so a folder difference is reported rather than turned
    // into a refusal — a file that an administrator moved inside Drive must
    // still be viewable.
    const integrity = archive.expectedFolderId === null
      ? "unverified"
      : metadata.parents.includes(archive.expectedFolderId)
      ? "ok"
      : "folder_mismatch";
    if (integrity === "folder_mismatch") {
      console.warn(
        "[media-drive] archive folder mismatch:",
        JSON.stringify({
          media_id: mediaId,
          file_id: archive.fileId,
          expected_folder: archive.expectedFolderId,
        }),
      );
    }

    const fallbackType = typeof media.mime_type === "string" && media.mime_type
      ? media.mime_type
      : "application/octet-stream";

    // ── 6. Serve the bytes ────────────────────────────────────────────────
    if (variant === "thumb") {
      if (!metadata.thumbnailLink) {
        // Drive generates no preview for this file. That is a "no poster"
        // answer, never a "file deleted" one.
        return fail(
          "no_preview",
          "Google Drive exposes no preview image for this archived file",
          404,
          false,
        );
      }

      const cacheKey = `${archive.fileId}:${metadata.md5Checksum ?? "n/a"}`;
      const cached = readThumbnailCache(cacheKey);
      if (cached) {
        return new Response(cached.body, {
          status: 200,
          headers: {
            ...corsHeaders,
            "Content-Type": cached.contentType,
            "Content-Length": String(cached.body.byteLength),
            "Cache-Control": "private, max-age=600",
            "X-Content-Type-Options": "nosniff",
            "X-MyDrive-Source": "drive-archive",
            "X-MyDrive-Variant": "thumb",
            "X-MyDrive-Thumbnail-Cache": "hit",
            "X-MyDrive-Archive-Integrity": integrity,
          },
        });
      }

      const thumbnail = await openDriveThumbnail({
        accessToken,
        thumbnailLink: metadata.thumbnailLink,
        // 480px covers a 4:3 grid tile on a 2x display without shipping the
        // original, and is one Drive request.
        size: 480,
      });

      const contentType = thumbnail.headers.get("content-type") ?? "image/jpeg";
      const bytes = await thumbnail.arrayBuffer();
      writeThumbnailCache(cacheKey, {
        body: bytes,
        contentType,
        expiresAt: Date.now() + THUMB_CACHE_TTL_MS,
      });

      return new Response(bytes, {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type": contentType,
          "Content-Length": String(bytes.byteLength),
          "Cache-Control": "private, max-age=600",
          "X-Content-Type-Options": "nosniff",
          "X-MyDrive-Source": "drive-archive",
          "X-MyDrive-Variant": "thumb",
          "X-MyDrive-Thumbnail-Cache": "miss",
          "X-MyDrive-Archive-Integrity": integrity,
        },
      });
    }

    const range = req.headers.get("range");
    const upstream = await openDriveFileContent({
      accessToken,
      fileId: archive.fileId,
      range,
    });

    console.log(
      "[media-drive] serving archived original:",
      JSON.stringify({
        media_id: mediaId,
        account_id: archive.accountId,
        variant,
        ranged: Boolean(range),
        status: upstream.status,
        integrity,
      }),
    );

    return streamThrough(upstream, {
      contentType: upstream.headers.get("content-type") ?? fallbackType,
      // The Admin Panel re-caches under the lifetime of its own short-lived
      // grant; this only tells intermediaries not to hold the bytes.
      cacheControl: "private, no-store",
      integrity,
      variant,
    });
  } catch (error) {
    const failure = driveMediaFailure(error);
    const message = (error as Error)?.message ?? "unknown";

    // Auth errors are the only failures safe to answer with 401.
    const isAuthError = message.includes("Missing Authorization") ||
      message.includes("Invalid or expired token");
    if (isAuthError) {
      return fail("unauthenticated", "Authentication required", 401, false);
    }

    console.error(
      "[media-drive] read failed:",
      JSON.stringify({
        reason: failure.reason,
        upstream_status: failure.status,
        detail: failure.message.slice(0, 200),
      }),
    );

    return fail(
      failure.reason,
      failure.reason === "archive_missing"
        ? "The archived Drive file could not be found"
        : "The Google Drive archive could not be read right now",
      statusForReason(failure.reason, failure.status),
      failure.retryable,
    );
  }
}
