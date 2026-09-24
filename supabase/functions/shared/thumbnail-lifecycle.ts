/**
 * Persistent Cloudinary thumbnails.
 *
 * ── Why a second Cloudinary asset is required ────────────────────────────────
 * The gallery must keep rendering after the Cloudinary ORIGINAL is deleted
 * (which happens once the Drive archive is verified). A Cloudinary
 * *transformation* cannot serve that role: a transformed delivery is a derived
 * resource of the original and is destroyed together with it — Cloudinary's
 * Upload API `invalidate` / `destroy` documentation states that deleting an
 * asset also invalidates "all its transformed versions that share the same
 * public ID". So the persistent thumbnail is stored as an INDEPENDENT uploaded
 * asset with its own public ID, which the original's deletion cannot reach.
 *
 * ── Cloudinary layout ───────────────────────────────────────────────────────
 *   originals    mydrive/{owner_id}/{public_id}      (unchanged, written by the client)
 *   thumbnails   mydrive/{owner_id}/thumbnails/{media_assets.id}
 *
 * The original's location is deliberately left exactly where the existing
 * implementation puts it (the signed upload folder is `mydrive/{user-id}`), so
 * `finalize-media`'s ownership check and every existing upload keep working.
 * The thumbnail gets its own folder segment, which is what makes the two
 * lifecycles independently addressable.
 *
 * The thumbnail public ID is derived from the media id, so it is DETERMINISTIC:
 * a retried finalize, a concurrent finalize, or a later backfill all address
 * the same Cloudinary asset and can never create a duplicate.
 *
 * ── Materialization ─────────────────────────────────────────────────────────
 * Server-side, with no image library and no second storage provider: Cloudinary
 * is asked to ingest its own (transformed) delivery URL as a new upload —
 * `file=<delivery url>` is the documented remote-ingestion form of the Upload
 * API. The source URL is the ORIGINAL with an inline transformation that yields
 * a small still image:
 *
 *   image/*   w_{size},h_{size},c_fill,q_auto                         -> stored .jpg
 *   video/*   so_0,w_{size},h_{size},c_fill,q_auto (first frame, .jpg) -> stored .jpg
 *   raw/*     not transformable into a bitmap -> no thumbnail is created,
 *             and the existing behaviour (no thumbnail_url) is preserved
 *
 * Security: the API secret never leaves this process. It is used only to compute
 * the request signature, is never logged, never returned, and never persisted.
 * Provider messages are credential-redacted before they reach a log or a row.
 *
 * Idempotency: before uploading, the deterministic delivery URL is probed. If it
 * already resolves, the existing asset is reused and no upload happens. A
 * concurrent upload that loses the race is detected the same way.
 */

import {
  CLOUDINARY_API_BASE,
  CLOUDINARY_DELIVERY_BASE,
  getCloudinaryCredentials,
  getCloudinaryCloudName,
  probeCloudinaryDelivery,
  resourceTypeForMime,
  sanitizeProviderMessage,
  signParams,
  type CloudinaryResourceType,
} from "./cloudinary.ts";
import type { getSupabaseAdmin } from "./auth.ts";

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

/** Folder segment that separates persistent thumbnails from originals. */
export const THUMBNAIL_FOLDER_SEGMENT = "thumbnails";

/** Root prefix every asset of this application lives under. */
const APP_ROOT = "mydrive";

/** Default longest edge of a stored thumbnail, in pixels. */
export const DEFAULT_THUMBNAIL_SIZE_PX = 512;

/** Cloudinary resource type every persistent thumbnail is stored as. */
export const THUMBNAIL_RESOURCE_TYPE: CloudinaryResourceType = "image";

/** Format every persistent thumbnail is stored in (decodable everywhere). */
const THUMBNAIL_STORAGE_FORMAT = "jpg";

/** Delivery type used by every asset this application stores. */
const DELIVERY_TYPE = "upload";

// ─── Identity ───────────────────────────────────────────────────────────────

/** `mydrive/{userId}/thumbnails` — the thumbnail folder of one user. */
export function thumbnailFolder(userId: string): string {
  return `${APP_ROOT}/${userId}/${THUMBNAIL_FOLDER_SEGMENT}`;
}

/**
 * The deterministic public ID of a media asset's persistent thumbnail.
 *
 * Derived only from verified server-side values (the JWT owner id and the
 * media_assets row id), never from client input, so a client can neither place
 * a thumbnail in another user's folder nor collide with an existing asset.
 */
export function thumbnailPublicId(userId: string, mediaId: string): string {
  return `${thumbnailFolder(userId)}/${mediaId}`;
}

/**
 * True when `publicId` addresses a persistent THUMBNAIL rather than an original.
 *
 * Strict by construction: exactly `mydrive/{owner}/thumbnails/{id}`. A match is
 * what stops the original-cleanup worker from ever deleting a thumbnail.
 */
export function isThumbnailPublicId(publicId: string | null | undefined): boolean {
  const parts = (publicId ?? "").trim().split("/").filter(Boolean);
  return parts.length === 4 &&
    parts[0] === APP_ROOT &&
    parts[2] === THUMBNAIL_FOLDER_SEGMENT;
}

/**
 * The public ID behind a Cloudinary delivery URL, or `null` when the URL is not
 * a Cloudinary delivery URL this application can address.
 *
 * Needed because `media_assets.thumbnail_url` is stored as a delivery URL,
 * while a Cloudinary destroy/upload operation is addressed by public ID.
 */
export function publicIdFromDeliveryUrl(url: string | null | undefined): string | null {
  const raw = (url ?? "").trim();
  if (!raw) return null;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (parsed.hostname !== "res.cloudinary.com") return null;

  const segments = parsed.pathname.split("/").filter(Boolean);
  // <cloud>/<resource_type>/<delivery_type>/[transformations/][version/]<public id>
  if (segments.length < 4) return null;
  const kept: string[] = [];
  for (const [index, segment] of segments.slice(3).entries()) {
    // The first segment after the delivery type is a transformation when it
    // carries transformation syntax (`w_512,h_512`, `so_0`, …).
    if (index === 0 && segment.includes("_")) continue;
    if (/^v\d+$/.test(segment)) continue;
    kept.push(segment);
  }
  if (!kept.length) return null;

  const last = kept[kept.length - 1];
  const match = /^(.+)\.([A-Za-z0-9]{1,5})$/.exec(last);
  if (match) kept[kept.length - 1] = match[1];
  return kept.join("/");
}

/**
 * The stored format of an asset, read from the file extension of its delivery
 * URL (`…/originals/IMG_1234.heic` -> `heic`).
 *
 * Read from the URL directly rather than from [publicIdFromDeliveryUrl], which
 * deliberately strips the extension.
 */
export function formatFromDeliveryUrl(url: string | null | undefined): string | null {
  const raw = (url ?? "").trim();
  if (!raw) return null;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (parsed.hostname !== "res.cloudinary.com") return null;
  const segments = parsed.pathname.split("/").filter(Boolean);
  if (segments.length < 4) return null;
  const match = /^(.+)\.([A-Za-z0-9]{1,5})$/.exec(segments[segments.length - 1]);
  return match ? match[2].toLowerCase() : null;
}

/**
 * The canonical, version-less delivery URL of a persistent thumbnail.
 *
 * Deterministic on purpose: every retry computes the same URL, so the stored
 * `thumbnail_url` is stable and the Android cache identity never splits. The
 * original's delivery URLs already keep their Cloudinary host public, so this
 * stays inside the project's existing delivery model.
 */
export function thumbnailDeliveryUrl(cloudName: string, publicId: string): string {
  return `${CLOUDINARY_DELIVERY_BASE}/${cloudName}/${THUMBNAIL_RESOURCE_TYPE}/${DELIVERY_TYPE}/${publicId}.${THUMBNAIL_STORAGE_FORMAT}`;
}

// ─── Source URL of the thumbnail ────────────────────────────────────────────

export interface ThumbnailSource {
  /** Delivery URL of the ORIGINAL, carrying the thumbnail transformation. */
  url: string;
  /** The Cloudinary resource type the source is delivered from. */
  resourceType: CloudinaryResourceType;
  /** The transformation applied to the source. */
  transformation: string;
}

/**
 * Builds the Cloudinary URL that yields a small still image of `publicId`.
 *
 * Returns `null` when no bitmap can be derived (a `raw` asset), in which case
 * the caller keeps today's behaviour instead of inventing an asset.
 *
 * The URL is constructed from the verified cloud name, resource type and public
 * ID rather than reusing the stored `secure_url` verbatim: the stored URL may
 * already carry a transformation, and a second one would either be ignored or
 * conflict with it.
 */
export function buildThumbnailSource(params: {
  cloudName: string;
  publicId: string;
  mimeType?: string | null;
  format?: string | null;
  sizePx?: number;
}): ThumbnailSource | null {
  const publicId = (params.publicId ?? "").trim();
  if (!publicId) return null;

  const resourceType = resourceTypeForMime(params.mimeType);
  // A raw asset is delivered byte-for-byte: it supports no transformation, so
  // there is nothing a thumbnail could be derived from.
  if (resourceType === "raw") return null;

  const size = clampThumbnailSize(params.sizePx);

  // A video is turned into its first frame and delivered as a JPEG — the only
  // lightweight bitmap a video has. `so_0` must precede the sizing parameters.
  const transformation = resourceType === "video"
    ? `so_0,w_${size},h_${size},c_fill,q_auto`
    : `w_${size},h_${size},c_fill,q_auto`;

  // Videos are always requested as `.jpg`; images keep their own format so the
  // source is never re-encoded twice on the way in.
  const sourceFormat = resourceType === "video"
    ? THUMBNAIL_STORAGE_FORMAT
    : (normalizeFormat(params.format) ?? inferFormatFromMime(params.mimeType) ??
      THUMBNAIL_STORAGE_FORMAT);

  const url =
    `${CLOUDINARY_DELIVERY_BASE}/${params.cloudName}/${resourceType}/${DELIVERY_TYPE}` +
    `/${transformation}/${publicId}.${sourceFormat}`;

  return { url, resourceType, transformation };
}

export function clampThumbnailSize(sizePx?: number | null): number {
  const size = Number.isFinite(sizePx) && (sizePx as number) > 0
    ? Math.trunc(sizePx as number)
    : DEFAULT_THUMBNAIL_SIZE_PX;
  return Math.min(Math.max(size, 64), 1024);
}

function normalizeFormat(format: string | null | undefined): string | null {
  const value = (format ?? "").trim().toLowerCase().replace(/^\./, "");
  return /^[a-z0-9]{1,5}$/.test(value) ? value : null;
}

/** Common image formats keyed by the mime subtype, used when none is known. */
const MIME_FORMATS: Record<string, string> = {
  "image/jpeg": "jpg",
  "image/jpg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "image/bmp": "bmp",
  "image/heic": "heic",
  "image/heif": "heif",
  "image/tiff": "tiff",
  "image/avif": "avif",
};

function inferFormatFromMime(mimeType: string | null | undefined): string | null {
  const mime = (mimeType ?? "").trim().toLowerCase();
  if (!mime.startsWith("image/")) return null;
  return MIME_FORMATS[mime] ?? normalizeFormat(mime.slice("image/".length));
}

// ─── Materialization ────────────────────────────────────────────────────────

export interface MaterializedThumbnail {
  /** The media id this thumbnail belongs to. */
  mediaId: string;
  /** Cloudinary public ID of the persistent thumbnail. */
  publicId: string;
  /** Cloudinary delivery URL persisted as `media_assets.thumbnail_url`. */
  url: string;
  /** True when this call uploaded the asset (false when it was reused). */
  uploaded: boolean;
}

/** Raised when the persistent thumbnail could not be materialized. */
export class ThumbnailMaterializationError extends Error {
  /** A stable, non-secret reason code for logs and callers. */
  readonly reason: string;
  /** true when retrying later could plausibly succeed. */
  readonly retryable: boolean;

  constructor(reason: string, message: string, retryable = true) {
    super(message);
    this.name = "ThumbnailMaterializationError";
    this.reason = reason;
    this.retryable = retryable;
  }
}

/**
 * True when `url` already resolves on the delivery tier.
 *
 * Absence is a definitive answer only on HTTP 404; any inconclusive answer
 * (423 still-processing, 5xx, 429) is surfaced as a failure so an existing
 * thumbnail is never overwritten and a missing one is not silently skipped.
 */
async function thumbnailExists(params: {
  cloudName: string;
  publicId: string;
  fetchImpl?: typeof fetch;
}): Promise<boolean> {
  const probe = await probeCloudinaryDelivery({
    deliveryUrl: thumbnailDeliveryUrl(params.cloudName, params.publicId),
    publicId: params.publicId,
    resourceType: THUMBNAIL_RESOURCE_TYPE,
    fetchImpl: params.fetchImpl,
  });
  return probe.state === "present";
}

interface CloudinaryUploadBody {
  assetId: string | null;
  publicId: string | null;
  secureUrl: string | null;
  width: number | null;
  height: number | null;
  bytes: number | null;
  format: string | null;
  errorMessage: string | null;
}

async function readUploadBody(res: Response): Promise<CloudinaryUploadBody> {
  try {
    const body = await res.json() as Record<string, unknown>;
    const error = body.error as { message?: unknown } | undefined;
    return {
      assetId: typeof body.asset_id === "string" ? body.asset_id : null,
      publicId: typeof body.public_id === "string" ? body.public_id : null,
      secureUrl: typeof body.secure_url === "string" ? body.secure_url : null,
      width: typeof body.width === "number" ? body.width : null,
      height: typeof body.height === "number" ? body.height : null,
      bytes: typeof body.bytes === "number" ? body.bytes : null,
      format: typeof body.format === "string" ? body.format : null,
      errorMessage: typeof error?.message === "string" ? error.message : null,
    };
  } catch {
    return {
      assetId: null,
      publicId: null,
      secureUrl: null,
      width: null,
      height: null,
      bytes: null,
      format: null,
      errorMessage: null,
    };
  }
}

/** True when a provider error means a same-public-ID asset already exists. */
function isAlreadyExistsError(message: string | null): boolean {
  if (!message) return false;
  const normalized = message.toLowerCase();
  return normalized.includes("already exists") || normalized.includes("already in use");
}

/**
 * Uploads the thumbnail as an INDEPENDENT Cloudinary asset.
 *
 *   POST {api}/v1_1/{cloud}/{resource_type}/upload
 *     file=<original delivery URL + thumbnail transformation>
 *     public_id=mydrive/{owner}/thumbnails/{media_id}
 *     use_filename=false, unique_filename=false, overwrite=false,
 *     format=jpg, invalidate=true, timestamp, api_key, signature
 *
 * `file` is excluded from the signature by Cloudinary's documented rule; every
 * other parameter is signed. `overwrite=false` guarantees an existing
 * thumbnail can never be silently replaced.
 */
async function uploadThumbnailAsset(params: {
  cloudName: string;
  apiKey: string;
  apiSecret: string;
  publicId: string;
  sourceUrl: string;
  fetchImpl?: typeof fetch;
}): Promise<CloudinaryUploadBody> {
  const fetchImpl = params.fetchImpl ?? fetch;
  const timestamp = Math.floor(Date.now() / 1000);

  // Signed parameters must match the posted parameters exactly.
  const signed: Record<string, string> = {
    format: THUMBNAIL_STORAGE_FORMAT,
    invalidate: "true",
    overwrite: "false",
    public_id: params.publicId,
    timestamp: String(timestamp),
    type: DELIVERY_TYPE,
    unique_filename: "false",
    use_filename: "false",
  };
  const signature = await signParams(signed, params.apiSecret);

  const form = new URLSearchParams(signed);
  form.set("file", params.sourceUrl);
  form.set("api_key", params.apiKey);
  form.set("signature", signature);

  const res = await fetchImpl(
    `${CLOUDINARY_API_BASE}/${params.cloudName}/${THUMBNAIL_RESOURCE_TYPE}/upload`,
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form.toString(),
      signal: AbortSignal.timeout(45_000),
    },
  );

  const body = await readUploadBody(res);
  if (!res.ok) {
    const credentials = { cloudName: params.cloudName, apiKey: params.apiKey, apiSecret: params.apiSecret };
    const detail = sanitizeProviderMessage(body.errorMessage, credentials);
    // A lost race (another finalize uploaded the same deterministic id first)
    // is not a failure: the caller re-probes and reuses the existing asset.
    if (body.errorMessage && isAlreadyExistsError(body.errorMessage)) {
      throw new ThumbnailMaterializationError(
        "ALREADY_EXISTS",
        "A thumbnail already exists for this media id",
        false,
      );
    }
    throw new ThumbnailMaterializationError(
      res.status === 401 || res.status === 403 ? "AUTH_REJECTED" : "UPLOAD_FAILED",
      `Cloudinary thumbnail upload failed (HTTP ${res.status})${detail ? `: ${detail}` : ""}`,
      res.status === 429 || res.status >= 500,
    );
  }
  if (!body.publicId || !body.secureUrl) {
    throw new ThumbnailMaterializationError(
      "UNCLASSIFIED_RESPONSE",
      "Cloudinary thumbnail upload returned no public_id/secure_url",
      false,
    );
  }
  return body;
}

/**
 * Ensures the persistent thumbnail asset exists in Cloudinary and returns its
 * identity. Reuses an already-existing asset instead of re-uploading.
 */
export async function materializeThumbnail(params: {
  ownerId: string;
  mediaId: string;
  /** `media_assets.storage_path` — the ORIGINAL's public ID. */
  originalPublicId: string;
  mimeType?: string | null;
  format?: string | null;
  sizePx?: number;
  fetchImpl?: typeof fetch;
}): Promise<MaterializedThumbnail> {
  if (isThumbnailPublicId(params.originalPublicId)) {
    // Defensive: never derive a thumbnail from a thumbnail. This would also
    // mean the row's storage_path was written incorrectly upstream.
    throw new ThumbnailMaterializationError(
      "ORIGINAL_IS_A_THUMBNAIL",
      "Refusing to derive a thumbnail from a thumbnail public ID",
      false,
    );
  }

  const cloudName = getCloudinaryCloudName();
  if (!cloudName) {
    throw new ThumbnailMaterializationError(
      "MISSING_CLOUD_NAME",
      "CLOUDINARY_CLOUD_NAME is not configured",
      false,
    );
  }

  const publicId = thumbnailPublicId(params.ownerId, params.mediaId);
  const url = thumbnailDeliveryUrl(cloudName, publicId);

  const source = buildThumbnailSource({
    cloudName,
    publicId: params.originalPublicId.trim(),
    mimeType: params.mimeType,
    format: params.format,
    sizePx: params.sizePx,
  });
  if (!source) {
    throw new ThumbnailMaterializationError(
      "UNSUPPORTED_RESOURCE_TYPE",
      `No bitmap can be derived from a ${resourceTypeForMime(params.mimeType)} asset`,
      false,
    );
  }

  // Reuse path: the deterministic asset is already delivered.
  if (await thumbnailExists({ cloudName, publicId, fetchImpl: params.fetchImpl })) {
    return { mediaId: params.mediaId, publicId, url, uploaded: false };
  }

  const credentials = getCloudinaryCredentials();
  try {
    await uploadThumbnailAsset({
      cloudName: credentials.cloudName,
      apiKey: credentials.apiKey,
      apiSecret: credentials.apiSecret,
      publicId,
      sourceUrl: source.url,
      fetchImpl: params.fetchImpl,
    });
    return { mediaId: params.mediaId, publicId, url, uploaded: true };
  } catch (error) {
    // Lost a concurrent race: the other writer's asset is the one to use.
    if (
      error instanceof ThumbnailMaterializationError && error.reason === "ALREADY_EXISTS"
    ) {
      if (await thumbnailExists({ cloudName, publicId, fetchImpl: params.fetchImpl })) {
        return { mediaId: params.mediaId, publicId, url, uploaded: false };
      }
    }
    throw error;
  }
}

// ─── Persistence ────────────────────────────────────────────────────────────

/** The subset of a media_assets row this module needs. */
export interface ThumbnailMediaRow {
  id: string;
  owner_id: string;
  mime_type: string | null;
  storage_path: string | null;
  storage_url: string | null;
  thumbnail_url: string | null;
  status: string | null;
  primary_cleanup_status: string | null;
  primary_deleted_at: string | null;
}

export interface EnsureThumbnailOutcome {
  ok: boolean;
  /** Present when a persistent thumbnail is (now) stored for this media. */
  thumbnailUrl?: string;
  /** True when this call created the Cloudinary asset. */
  uploaded?: boolean;
  /** True when this call wrote new metadata (media_assets / media_variants). */
  persisted?: boolean;
  /** A stable reason code when `ok` is false, or an informational one when true. */
  reason: string;
  /** Detail for a failure; never contains a credential. */
  detail?: string;
}

/**
 * Persists the persistent thumbnail reference.
 *
 * Two records are written, both idempotent:
 *   - `media_assets.thumbnail_url`  — the media-level reference the gallery reads.
 *   - `media_variants` (variant_type = 'thumbnail') — the independently
 *     addressable variant record: media_id -> thumbnail variant -> Cloudinary
 *     asset/public ID/delivery URL. Its `storage_path` is the thumbnail's own
 *     public ID, which is what the permanent-delete lifecycle deletes without
 *     needing the original.
 *
 * `media_assets` stays the source of truth for the media metadata; the variant
 * row is a pointer, never a duplicate of the media record.
 */
async function persistThumbnailReference(
  admin: AdminClient,
  media: ThumbnailMediaRow,
  thumbnail: MaterializedThumbnail,
): Promise<boolean> {
  let wrote = false;

  if ((media.thumbnail_url ?? "").trim() !== thumbnail.url) {
    const { error } = await admin
      .from("media_assets")
      .update({ thumbnail_url: thumbnail.url, updated_at: new Date().toISOString() })
      .eq("id", media.id);
    if (error) {
      throw new Error(`Failed to store thumbnail_url: ${error.message}`);
    }
    wrote = true;
  }

  // One thumbnail variant per media. A retried finalize must not add a second.
  const { data: existing, error: lookupError } = await admin
    .from("media_variants")
    .select("id, storage_provider, storage_asset_id, storage_path, storage_url")
    .eq("media_id", media.id)
    .eq("variant_type", "thumbnail")
    .maybeSingle();
  if (lookupError) {
    throw new Error(`Failed to read the thumbnail variant: ${lookupError.message}`);
  }

  const variantRow = {
    media_id: media.id,
    variant_type: "thumbnail",
    file_name: `${media.id}.jpg`,
    mime_type: "image/jpeg",
    file_size: null as number | null,
    width: null as number | null,
    height: null as number | null,
    duration_ms: null as number | null,
    storage_provider: "cloudinary",
    storage_asset_id: thumbnail.publicId,
    storage_path: thumbnail.publicId,
    storage_url: thumbnail.url,
  };

  if (existing) {
    const changed = existing.storage_path !== variantRow.storage_path ||
      existing.storage_url !== variantRow.storage_url ||
      existing.storage_provider !== variantRow.storage_provider;
    if (changed) {
      const { error } = await admin.from("media_variants").update(variantRow).eq(
        "id",
        existing.id,
      );
      if (error) throw new Error(`Failed to update the thumbnail variant: ${error.message}`);
      wrote = true;
    }
    return wrote;
  }

  const { error: insertError } = await admin.from("media_variants").insert(variantRow);
  if (insertError) {
    // 23505 = unique_violation: a concurrent finalize inserted it first, which
    // is exactly the desired outcome, not a failure.
    if (insertError.code === "23505") return wrote;
    throw new Error(`Failed to create the thumbnail variant: ${insertError.message}`);
  }
  return true;
}

/**
 * Ensures a media asset has a persistent Cloudinary thumbnail.
 *
 * Safe to call repeatedly and concurrently: the Cloudinary asset is addressed by
 * a deterministic public ID and the variant row is unique per media.
 *
 * Returns a structured outcome instead of throwing for the expected "cannot do
 * this" cases (a raw asset, an already-cleaned original, a deleted media), so a
 * caller can log a precise reason without failing its own operation.
 */
export async function ensurePersistentThumbnail(
  admin: AdminClient,
  media: ThumbnailMediaRow,
  options: { sizePx?: number; fetchImpl?: typeof fetch } = {},
): Promise<EnsureThumbnailOutcome> {
  try {
    if (media.status === "DELETED") {
      return { ok: false, reason: "MEDIA_DELETED" };
    }

    const existingPublicId = publicIdFromDeliveryUrl(media.thumbnail_url);
    if (existingPublicId && isThumbnailPublicId(existingPublicId)) {
      // Already persistent. Self-heal the variant pointer (a partial earlier
      // write may have stored the URL but not the variant row).
      const thumbnail: MaterializedThumbnail = {
        mediaId: media.id,
        publicId: existingPublicId,
        url: (media.thumbnail_url ?? "").trim(),
        uploaded: false,
      };
      const persisted = await persistThumbnailReference(admin, media, thumbnail);
      return {
        ok: true,
        thumbnailUrl: thumbnail.url,
        uploaded: false,
        persisted,
        reason: "ALREADY_PERSISTENT",
      };
    }

    const originalPublicId = (media.storage_path ?? "").trim();
    if (!originalPublicId) {
      return { ok: false, reason: "NO_CLOUDINARY_ORIGINAL" };
    }

    // The original is gone: no thumbnail can be derived any more. Existing
    // behaviour (Drive thumbnail fallback) is preserved for these rows.
    const cleaned = media.primary_deleted_at != null ||
      (media.primary_cleanup_status ?? "").toLowerCase() === "cleanup_success";
    if (cleaned) {
      return { ok: false, reason: "ORIGINAL_ALREADY_CLEANED" };
    }

    const thumbnail = await materializeThumbnail({
      ownerId: media.owner_id,
      mediaId: media.id,
      originalPublicId,
      mimeType: media.mime_type,
      format: formatFromDeliveryUrl(media.storage_url),
      sizePx: options.sizePx,
      fetchImpl: options.fetchImpl,
    });

    const persisted = await persistThumbnailReference(admin, media, thumbnail);
    return {
      ok: true,
      thumbnailUrl: thumbnail.url,
      uploaded: thumbnail.uploaded,
      persisted,
      reason: thumbnail.uploaded ? "CREATED" : "REUSED_EXISTING_ASSET",
    };
  } catch (error) {
    if (error instanceof ThumbnailMaterializationError) {
      return { ok: false, reason: error.reason, detail: error.message };
    }
    return {
      ok: false,
      reason: "PERSISTENCE_FAILED",
      detail: (error as Error).message,
    };
  }
}

// ─── Final deletion lifecycle ───────────────────────────────────────────────

export interface ThumbnailDeleteOutcome {
  /** What the deletion attempt decided. */
  action: "DELETED" | "ALREADY_ABSENT" | "KEPT" | "NONE";
  /** Public ID the attempt addressed, when one was resolved. */
  publicId: string | null;
  /** Stable reason code for logs. */
  reason: string;
}

/**
 * Resolves the public ID of a media asset's persistent thumbnail.
 *
 * The `media_variants` record is authoritative because it is written by the same
 * transaction that materializes the asset; `media_assets.thumbnail_url` is the
 * fallback for rows whose variant row is missing.
 */
export async function resolveThumbnailPublicId(
  admin: AdminClient,
  mediaId: string,
  thumbnailUrl: string | null | undefined,
): Promise<string | null> {
  const { data } = await admin
    .from("media_variants")
    .select("storage_path")
    .eq("media_id", mediaId)
    .eq("variant_type", "thumbnail")
    .maybeSingle();
  const stored = typeof (data as { storage_path?: string } | null)?.storage_path === "string"
    ? (data as { storage_path: string }).storage_path.trim()
    : "";
  if (stored && isThumbnailPublicId(stored)) return stored;

  const derived = publicIdFromDeliveryUrl(thumbnailUrl);
  return derived && isThumbnailPublicId(derived) ? derived : null;
}

/**
 * Clears the thumbnail reference. Called only by the permanent-deletion
 * lifecycle, after the Cloudinary asset has been removed.
 *
 * The variant row is deleted (it points at an asset that no longer exists) and
 * `media_assets.thumbnail_url` is cleared, while `media_assets` itself — the
 * media record — is preserved for the tombstone/audit trail.
 */
export async function clearThumbnailReference(
  admin: AdminClient,
  mediaId: string,
): Promise<void> {
  const { error: variantError } = await admin
    .from("media_variants")
    .delete()
    .eq("media_id", mediaId)
    .eq("variant_type", "thumbnail");
  if (variantError) {
    console.error(`Failed to delete the thumbnail variant for ${mediaId}: ${variantError.message}`);
  }

  const { error: mediaError } = await admin
    .from("media_assets")
    .update({ thumbnail_url: null, updated_at: new Date().toISOString() })
    .eq("id", mediaId);
  if (mediaError) {
    console.error(`Failed to clear thumbnail_url for ${mediaId}: ${mediaError.message}`);
  }
}
