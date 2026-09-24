import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin, getSupabaseAuth } from "../shared/auth.ts";
import {
  destroyCloudinaryAsset,
  CloudinaryDeleteError,
} from "../shared/cloudinary.ts";
import {
  clearThumbnailReference,
  ensurePersistentThumbnail,
  isThumbnailPublicId,
  resolveThumbnailPublicId,
  type EnsureThumbnailOutcome,
  type ThumbnailMediaRow,
} from "../shared/thumbnail-lifecycle.ts";

/**
 * media-lifecycle — the two ends of the PERSISTENT THUMBNAIL lifecycle.
 *
 * The thumbnail is created by `finalize-media` and preserved by the Cloudinary
 * cleanup worker (`drive-replicate`), which deletes only the ORIGINAL. This
 * function covers the operations neither of those owns:
 *
 *   action = "thumbnail_ensure"    (owner)
 *     Materializes/repairs the persistent thumbnail of one or more owned media.
 *     Idempotent. Used for self-healing and by callers that detect a media row
 *     with no `thumbnail_url`.
 *
 *   action = "thumbnail_backfill"  (admin)
 *     Reports — and optionally applies, in bounded batches — the backfill of
 *     media rows that predate persistent thumbnails. DRY RUN BY DEFAULT: it
 *     never writes unless `dry_run: false` is passed explicitly. It also reports
 *     the rows that can NO LONGER be backfilled because their Cloudinary
 *     original is already gone, which is the limit of what a backfill can fix.
 *
 *   action = "purge"               (owner)
 *     The FINAL deletion step, called by the app when a user permanently deletes
 *     a My Drive item. `user_hidden_at` (My Drive Trash) is deliberately NOT a
 *     trigger for this: an item in Trash can be restored, so its thumbnail must
 *     survive. This action is the only place a thumbnail becomes eligible for
 *     deletion, and it also deletes the original — but only behind the same
 *     safety rule the cleanup worker uses: a COMPLETED Drive job holding a real
 *     Drive file id must exist. The Drive archive itself is never touched.
 *
 * Required server-side secrets: SUPABASE_URL / SERVICE_ROLE_KEY (auto-provided)
 * plus the Cloudinary secrets already used by the cleanup worker.
 *
 * Never logs or returns a credential, a signature or a signed URL.
 */

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Bounded per invocation: each media costs up to 2 Cloudinary round trips. */
const MAX_BATCH = 10;

const MEDIA_COLUMNS =
  "id, owner_id, file_name, mime_type, file_size, storage_path, storage_url, thumbnail_url, " +
  "status, drive_archived_at, primary_cleanup_status, primary_deleted_at";

interface MediaRow {
  id: string;
  owner_id: string;
  file_name: string | null;
  mime_type: string | null;
  file_size: number | null;
  storage_path: string | null;
  storage_url: string | null;
  thumbnail_url: string | null;
  status: string | null;
  drive_archived_at: string | null;
  primary_cleanup_status: string | null;
  primary_deleted_at: string | null;
}

function json(payload: unknown, status = 200): Response {
  return new Response(
    JSON.stringify(payload),
    { headers: { ...corsHeaders, "Content-Type": "application/json" }, status },
  );
}

function bad(message: string, status = 400): Response {
  return json({ success: false, error: message }, status);
}

/**
 * Narrows a PostgREST result to typed media rows.
 *
 * The column list is built at runtime, so the client's generic result type does
 * not overlap with the row interface; the shape is asserted here once instead of
 * at every call site.
 */
function asMediaRows(data: unknown): MediaRow[] {
  return Array.isArray(data) ? (data as MediaRow[]) : [];
}

function toThumbnailMediaRow(row: MediaRow): ThumbnailMediaRow {
  return {
    id: row.id,
    owner_id: row.owner_id,
    mime_type: row.mime_type,
    storage_path: row.storage_path,
    storage_url: row.storage_url,
    thumbnail_url: row.thumbnail_url,
    status: row.status,
    primary_cleanup_status: row.primary_cleanup_status,
    primary_deleted_at: row.primary_deleted_at,
  };
}

function sanitizeIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is string => typeof item === "string" && UUID_RE.test(item.trim()))
    .map((item) => item.trim())
    .slice(0, MAX_BATCH);
}

async function isAdmin(admin: AdminClient, userId: string): Promise<boolean> {
  const { data } = await admin.from("profiles").select("role").eq("id", userId).maybeSingle();
  return (data as { role?: string } | null)?.role === "admin";
}

// ─── thumbnail_ensure ───────────────────────────────────────────────────────

async function thumbnailEnsure(
  admin: AdminClient,
  userId: string,
  body: Record<string, unknown>,
): Promise<Response> {
  const ids = sanitizeIds(
    Array.isArray(body.media_ids) ? body.media_ids : [body.media_id].filter(Boolean),
  );
  if (!ids.length) return bad("Provide one or more valid media_ids.");

  // Owner-scoped: a caller can only ever materialize a thumbnail for its own
  // media, and the Cloudinary folder is derived from the JWT owner id.
  const { data, error } = await admin
    .from("media_assets")
    .select(MEDIA_COLUMNS)
    .in("id", ids)
    .eq("owner_id", userId);
  if (error) throw error;

  const rows = asMediaRows(data);
  const results: Array<{ media_id: string; status: string; reason: string; url: string | null }> = [];
  for (const row of rows) {
    const outcome: EnsureThumbnailOutcome = await ensurePersistentThumbnail(
      admin,
      toThumbnailMediaRow(row),
    );
    if (outcome.ok) {
      console.log(
        `[THUMBNAIL_PERSISTED] media_id=${row.id} result=${outcome.reason} ` +
          `uploaded=${outcome.uploaded === true} persisted=${outcome.persisted === true}`,
      );
    } else {
      console.warn(`[THUMBNAIL_SKIPPED] media_id=${row.id} reason=${outcome.reason}`);
    }
    results.push({
      media_id: row.id,
      status: outcome.ok ? "PERSISTENT" : "UNAVAILABLE",
      reason: outcome.reason,
      url: outcome.thumbnailUrl ?? null,
    });
  }

  const missing = ids.filter((id) => !rows.some((row) => row.id === id));
  return json({
    success: true,
    processed: results.length,
    missing_media_ids: missing,
    results,
  });
}

// ─── thumbnail_backfill ─────────────────────────────────────────────────────

async function thumbnailBackfill(
  admin: AdminClient,
  userId: string,
  body: Record<string, unknown>,
): Promise<Response> {
  if (!(await isAdmin(admin, userId))) {
    return bad("Admin privileges required.", 403);
  }

  // DRY RUN BY DEFAULT. A backfill touches many rows and calls Cloudinary, so it
  // must be requested explicitly.
  const apply = body.dry_run === false;
  const limit = Math.min(
    Math.max(Number.isInteger(Number(body.limit)) ? Number(body.limit) : MAX_BATCH, 1),
    MAX_BATCH,
  );

  // Candidate: a media row that should keep a thumbnail but has none.
  const { data, error } = await admin
    .from("media_assets")
    .select(MEDIA_COLUMNS)
    .eq("storage_provider", "cloudinary")
    .is("thumbnail_url", null)
    .neq("status", "DELETED")
    .neq("primary_cleanup_status", "cleanup_success")
    .is("primary_deleted_at", null)
    .not("storage_path", "is", null)
    .order("created_at", { ascending: true })
    .limit(limit);
  if (error) throw error;
  const candidates = asMediaRows(data);

  // Rows whose Cloudinary original is ALREADY gone can never get a persistent
  // Cloudinary thumbnail again: they rely on the Drive thumbnail fallback. Called
  // out explicitly so the backfill's limits are reported, not hidden.
  const { count: unrecoverable } = await admin
    .from("media_assets")
    .select("id", { count: "exact", head: true })
    .eq("storage_provider", "cloudinary")
    .is("thumbnail_url", null)
    .neq("status", "DELETED")
    .or("primary_cleanup_status.eq.cleanup_success,primary_deleted_at.not.is.null");

  const results: Array<{ media_id: string; status: string; reason: string }> = [];
  if (apply) {
    for (const row of candidates) {
      const outcome = await ensurePersistentThumbnail(admin, toThumbnailMediaRow(row));
      console.log(
        `[THUMBNAIL_BACKFILL] media_id=${row.id} ok=${outcome.ok} reason=${outcome.reason}`,
      );
      results.push({
        media_id: row.id,
        status: outcome.ok ? "PERSISTENT" : "UNAVAILABLE",
        reason: outcome.reason,
      });
    }
  }

  return json({
    success: true,
    dry_run: !apply,
    batch_limit: limit,
    candidates_in_batch: candidates.length,
    // A full batch means more rows are waiting; call again with dry_run=false.
    has_more: candidates.length === limit,
    unrecoverable_without_original: unrecoverable ?? null,
    sample: candidates.slice(0, 3).map((row) => ({
      media_id: row.id,
      mime_type: row.mime_type,
      has_original: !row.primary_deleted_at,
    })),
    results,
  });
}

// ─── purge (final deletion lifecycle) ───────────────────────────────────────

interface PurgeOutcome {
  media_id: string;
  status: "PURGED" | "ALREADY_PURGED" | "PARTIAL" | "FAILED" | "NOT_FOUND";
  thumbnail: string;
  original: string;
  detail?: string;
}

/**
 * True when a verified Drive archive exists for the media.
 *
 * The same precondition the Cloudinary cleanup worker uses: without it the
 * original is the only copy of the bytes and must be preserved.
 */
async function hasVerifiedDriveArchive(admin: AdminClient, mediaId: string): Promise<boolean> {
  const { data } = await admin
    .from("replication_jobs")
    .select("id, status, google_drive_file_id")
    .eq("media_id", mediaId)
    .eq("destination_type", "google_drive")
    .maybeSingle();
  const job = data as
    | { status?: string; google_drive_file_id?: string | null }
    | null;
  return job?.status === "COMPLETED" &&
    typeof job.google_drive_file_id === "string" &&
    job.google_drive_file_id.length > 0;
}

/** Deletes one Cloudinary asset, treating "already absent" as success. */
async function destroyLogging(params: {
  mediaId: string;
  publicId: string | null;
  mimeType: string | null;
  tag: "ORIGINAL" | "THUMBNAIL";
  reason: string;
}): Promise<{ result: string; publicId: string | null }> {
  if (!params.publicId) {
    console.log(
      `[CLOUDINARY_CLEANUP_${params.tag}]\nmedia_id=${params.mediaId}\nresult=SKIPPED\n` +
        `reason=NO_PUBLIC_ID`,
    );
    return { result: "SKIPPED", publicId: null };
  }
  try {
    const result = await destroyCloudinaryAsset({
      publicId: params.publicId,
      mimeType: params.tag === "THUMBNAIL" ? "image/jpeg" : params.mimeType,
      resourceType: params.tag === "THUMBNAIL" ? "image" : null,
    });
    const outcome = result.deleted
      ? "DELETED"
      : result.alreadyAbsent
      ? "ALREADY_ABSENT"
      : "UNKNOWN";
    console.log(
      `[CLOUDINARY_CLEANUP_${params.tag}]\nmedia_id=${params.mediaId}\nresult=${outcome}\n` +
        `reason=${params.reason}`,
    );
    return { result: outcome, publicId: params.publicId };
  } catch (error) {
    const retryable = !(error instanceof CloudinaryDeleteError) || error.retryable;
    console.error(
      `[CLOUDINARY_CLEANUP_${params.tag}]\nmedia_id=${params.mediaId}\nresult=FAILED\n` +
        `reason=${retryable ? "RETRYABLE" : "PERMANENT"}_${params.reason}`,
    );
    return { result: "FAILED", publicId: params.publicId };
  }
}

async function purge(
  admin: AdminClient,
  userId: string,
  body: Record<string, unknown>,
): Promise<Response> {
  const ids = sanitizeIds(body.media_ids);
  if (!ids.length) return bad("Provide one or more valid media_ids.");

  const { data, error } = await admin
    .from("media_assets")
    .select(MEDIA_COLUMNS)
    .in("id", ids)
    .eq("owner_id", userId);
  if (error) throw error;
  const rows = asMediaRows(data);

  const outcomes: PurgeOutcome[] = [];
  for (const id of ids) {
    const row = rows.find((candidate) => candidate.id === id);
    if (!row) {
      outcomes.push({
        media_id: id,
        status: "NOT_FOUND",
        thumbnail: "NONE",
        original: "NONE",
      });
      continue;
    }
    if (row.status === "DELETED") {
      outcomes.push({
        media_id: id,
        status: "ALREADY_PURGED",
        thumbnail: "NONE",
        original: "NONE",
      });
      continue;
    }

    // 1. The THUMBNAIL is deleted first, while its identity is still resolvable.
    //    Only this action — never Trash — makes a thumbnail eligible.
    const thumbnailPublicId = await resolveThumbnailPublicId(admin, id, row.thumbnail_url);
    const thumbnail = await destroyLogging({
      mediaId: id,
      publicId: thumbnailPublicId,
      mimeType: "image/jpeg",
      tag: "THUMBNAIL",
      reason: "PERMANENT_DELETE",
    });

    // 2. The ORIGINAL, but only behind a verified Drive archive.
    const originalPublicId = (row.storage_path ?? "").trim() || null;
    const alreadyCleaned = row.primary_deleted_at != null ||
      (row.primary_cleanup_status ?? "").toLowerCase() === "cleanup_success";
    let original = "KEPT";
    let originalDeleted = false;
    if (alreadyCleaned) {
      original = "ALREADY_CLEANED";
      console.log(
        `[CLOUDINARY_CLEANUP_ORIGINAL]\nmedia_id=${id}\nresult=SKIPPED\nreason=ALREADY_CLEANED`,
      );
    } else if (!originalPublicId || isThumbnailPublicId(originalPublicId)) {
      original = "KEPT";
      console.log(
        `[CLOUDINARY_CLEANUP_ORIGINAL]\nmedia_id=${id}\nresult=SKIPPED\n` +
          `reason=NO_ADDRESSABLE_ORIGINAL`,
      );
    } else if (await hasVerifiedDriveArchive(admin, id)) {
      const destroyed = await destroyLogging({
        mediaId: id,
        publicId: originalPublicId,
        mimeType: row.mime_type,
        tag: "ORIGINAL",
        reason: "PERMANENT_DELETE",
      });
      original = destroyed.result;
      originalDeleted = destroyed.result === "DELETED" ||
        destroyed.result === "ALREADY_ABSENT";
    } else {
      // No verified archive: the original is the only remaining copy.
      original = "KEPT";
      console.log(
        `[CLOUDINARY_CLEANUP_ORIGINAL]\nmedia_id=${id}\nresult=SKIPPED\n` +
          `reason=NO_VERIFIED_DRIVE_ARCHIVE`,
      );
    }

    // 3. Clear the thumbnail reference and tombstone the media record. The Drive
    //    archive is deliberately left untouched — it is the durable archive, and
    //    the app_settings drive-deletion switch is out of this lifecycle's scope.
    if (thumbnail.result === "DELETED" || thumbnail.result === "ALREADY_ABSENT") {
      await clearThumbnailReference(admin, id);
    }

    const patch: Record<string, unknown> = {
      status: "DELETED",
      deleted_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      thumbnail_url: thumbnail.result === "FAILED" ? row.thumbnail_url : null,
    };
    if (originalDeleted) {
      patch.primary_cleanup_status = "cleanup_success";
      patch.primary_cleanup_completed_at = new Date().toISOString();
      patch.primary_deleted_at = new Date().toISOString();
    }
    const { error: updateError } = await admin.from("media_assets").update(patch).eq("id", id);
    if (updateError) throw updateError;

    const failed = thumbnail.result === "FAILED" || original === "FAILED";
    outcomes.push({
      media_id: id,
      status: failed ? "PARTIAL" : "PURGED",
      thumbnail: thumbnail.result,
      original,
      detail: failed ? "A Cloudinary asset could not be deleted and was retried later." : undefined,
    });
    console.log(
      `[MEDIA_PURGED] media_id=${id} thumbnail=${thumbnail.result} original=${original}`,
    );
  }

  return json({
    success: true,
    purged: outcomes.filter((outcome) => outcome.status === "PURGED").length,
    results: outcomes,
  });
}

// ─── Entry point ────────────────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    // owner_id always comes from the verified JWT; a client can never act on
    // another user's media, and the Cloudinary folder is derived from it.
    const { user } = await getSupabaseAuth(req);
    const admin = getSupabaseAdmin();

    let body: Record<string, unknown>;
    try {
      body = await req.json();
    } catch {
      return bad("Invalid JSON body");
    }

    const action = typeof body.action === "string" ? body.action.trim() : "";
    switch (action) {
      case "thumbnail_ensure":
        return await thumbnailEnsure(admin, user.id, body);
      case "thumbnail_backfill":
        return await thumbnailBackfill(admin, user.id, body);
      case "purge":
        return await purge(admin, user.id, body);
      default:
        return bad("Unsupported action. Use thumbnail_ensure, thumbnail_backfill or purge.");
    }
  } catch (error) {
    const message = (error as Error).message;
    const isAuthError = message.includes("Missing Authorization") ||
      message.includes("Invalid or expired token");
    // Never surfaces a credential: only the message produced by this module.
    console.error("media-lifecycle failed:", isAuthError ? "auth error" : message);
    return json({ success: false, error: message }, isAuthError ? 401 : 500);
  }
});
