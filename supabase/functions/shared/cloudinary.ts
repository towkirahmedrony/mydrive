/**
 * Server-side Cloudinary client for the MyDrive media lifecycle.
 *
 * Scope: authentication + DELETE of the temporary primary copy AFTER the media
 * has been replicated to Google Drive and verified, plus an independent
 * absence check. Nothing else.
 *
 * ── Why this module does not use the Admin API ───────────────────────────────
 * Cloudinary applies a rate limit to the Admin API only ("The free plan includes
 * 500 hourly requests"), and explicitly states the Upload API "isn't
 * rate-limited". The previous implementation spent 1-3 Admin operations on
 * DELETE plus 2 on the Admin lookup, so a bulk cleanup exhausted the hourly
 * budget and stalled. Both operations are therefore replaced with non-Admin
 * equivalents:
 *
 *   deletion     -> Upload API  POST /{cloud}/{resource_type}/destroy
 *                   (documented under Upload API > Asset management)
 *   verification -> delivery-side fresh-derivation probe (no API budget at all)
 *
 * Successful cleanup now costs 0 Cloudinary Admin API operations. No Admin
 * request is made anywhere in this module, deliberately: keeping the old
 * Admin DELETE or Admin GET around as a "fallback" would silently re-introduce
 * the rate-limit dependency.
 *
 * Security:
 *   - CLOUDINARY_API_SECRET / CLOUDINARY_API_KEY come from Edge Function secrets.
 *   - The secret is used only to compute the request signature. The secret, the
 *     signature, the request body and any Authorization header are NEVER logged,
 *     never returned to a caller and never persisted.
 *   - Provider messages are credential-redacted before they reach a log, a
 *     job row or sync_logs.
 *
 * Idempotency:
 *   `{"result":"not found"}` means the asset is already gone. That is the SUCCESS
 *   case for a retried cleanup and is reported as
 *   `deleted:false, alreadyAbsent:true`.
 */

const CLOUDINARY_API_BASE = "https://api.cloudinary.com/v1_1";
const CLOUDINARY_DELIVERY_BASE = "https://res.cloudinary.com";

/** Cloudinary resource types an uploaded asset can live under. */
export type CloudinaryResourceType = "image" | "video" | "raw";

/** Delivery type used by every asset this application uploads. */
const DELIVERY_TYPE = "upload";

export interface CloudinaryDeleteResult {
  /** true only when Cloudinary POSITIVELY answered `{"result":"ok"}`. */
  deleted: boolean;
  /** true when Cloudinary positively answered that the asset is absent. */
  alreadyAbsent: boolean;
  /** The resource type the asset was found under (null when absent). */
  resourceType: CloudinaryResourceType | null;
  /** HTTP status of the deciding call. */
  httpStatus: number;
  /** Safe, secret-free provider evidence for observability. */
  provider: {
    /** Which API answered. Always the Upload API — proves 0 Admin API usage. */
    endpoint: string;
    /** The id Cloudinary echoed back, if any (truncated). */
    echoedId: string | null;
    /** The provider's result wording: "ok" or a not-found variant. */
    echoedStatus: string | null;
    /** Raw response classification (status + result), credential-redacted. */
    raw: string | null;
  };
}

/** Raised when the outcome could not be decided (auth/config/transport/5xx). */
export class CloudinaryDeleteError extends Error {
  readonly status: number;
  /** true when retrying later could plausibly succeed. */
  readonly retryable: boolean;
  /**
   * Sanitized provider evidence captured at the moment of failure.
   *
   * Populated on EVERY failure path so the raw HTTP status + body survive into
   * sync_logs even when classification throws — an unexpected 2xx is precisely
   * the case where the body matters most. Never contains a credential.
   */
  provider: {
    httpStatus: number;
    classification: string;
    raw: string | null;
    publicId: string | null;
    resourceType: string | null;
  } | null = null;

  constructor(message: string, status: number, retryable = true) {
    super(message);
    this.name = "CloudinaryDeleteError";
    this.status = status;
    this.retryable = retryable;
  }
}

interface CloudinaryCredentials {
  cloudName: string;
  apiKey: string;
  apiSecret: string;
}

/**
 * Reads Cloudinary credentials from Edge Function secrets.
 * Throws a message that never contains any part of a credential.
 */
function getCredentials(): CloudinaryCredentials {
  const cloudName = Deno.env.get("CLOUDINARY_CLOUD_NAME");
  const apiKey = Deno.env.get("CLOUDINARY_API_KEY");
  const apiSecret = Deno.env.get("CLOUDINARY_API_SECRET");

  if (!cloudName || !apiKey || !apiSecret) {
    throw new CloudinaryDeleteError(
      "Cloudinary credentials missing (CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET)",
      0,
      false,
    );
  }
  return { cloudName, apiKey, apiSecret };
}

/**
 * Non-secret Cloudinary configuration, for diagnostics only.
 * Deliberately exposes the cloud name (already public in every delivery URL)
 * and NOTHING else — never the API key or secret.
 */
export function getCloudinaryCloudName(): string | null {
  return Deno.env.get("CLOUDINARY_CLOUD_NAME") ?? null;
}

// ─── Signing ────────────────────────────────────────────────────────────────

/**
 * SHA-1 hex digest, implemented with WebCrypto.
 *
 * SHA-1 is not a security choice here — it is the algorithm Cloudinary
 * specifies for API request signing. The secret never leaves this process.
 */
async function sha1Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-1",
    new TextEncoder().encode(input),
  );
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Builds the Cloudinary request signature.
 *
 * Documented rule: take every parameter except `file`, `api_key` and
 * `signature`, sort them alphabetically by key, join as `k=v&k=v`, append the
 * API secret, and SHA-1 the result. The signature is returned, never logged.
 */
async function signParams(
  params: Record<string, string>,
  apiSecret: string,
): Promise<string> {
  const toSign = Object.keys(params)
    .sort()
    .map((key) => `${key}=${params[key]}`)
    .join("&");
  return await sha1Hex(toSign + apiSecret);
}

// ─── Shared helpers ─────────────────────────────────────────────────────────

/**
 * The safe, non-secret projection of a Cloudinary response body.
 * The Upload API destroy endpoint answers `{"result":"ok"}` or
 * `{"result":"not found"}`; errors carry `{"error":{"message":"…"}}`.
 */
interface CloudinaryBody {
  result: string | null;
  errorMessage: string | null;
}

async function readCloudinaryBody(res: Response): Promise<CloudinaryBody> {
  try {
    const body = await res.json() as {
      result?: unknown;
      error?: { message?: unknown };
    };
    return {
      result: typeof body.result === "string" ? body.result : null,
      errorMessage: typeof body.error?.message === "string" ? body.error.message : null,
    };
  } catch {
    // Non-JSON body: nothing safe to surface.
    return { result: null, errorMessage: null };
  }
}

function describeStatus(status: number): string {
  if (status === 401 || status === 403) return "Cloudinary rejected the server credentials";
  if (status === 429) return "Cloudinary rate limited the request";
  if (status >= 500) return "Cloudinary is temporarily unavailable";
  return "Cloudinary request failed";
}

/**
 * Makes a provider-supplied error string safe to persist in a log or a job row.
 * Cloudinary error bodies describe the request (`Invalid public_id`,
 * `Missing required parameter - public_id`, …). They do not carry credentials,
 * but the API key and secret are defensively redacted anyway so no failure path
 * can ever leak a credential into logs, sync_logs or replication_jobs.
 */
function sanitizeProviderMessage(
  message: string | null,
  credentials: CloudinaryCredentials,
): string | null {
  if (!message) return null;
  let out = message;
  for (const secretValue of [credentials.apiSecret, credentials.apiKey]) {
    if (secretValue && out.includes(secretValue)) {
      out = out.split(secretValue).join("[redacted]");
    }
  }
  return out.slice(0, 200);
}

/**
 * True when Cloudinary's result wording means "this asset is not present".
 *
 * Observed live: `{"result":"not found"}`. The Admin batch endpoint used
 * `not_found` with an UNDERSCORE, so separator and case variants are all
 * accepted — matching only one spelling made an already-absent asset fail
 * forever, and a retried cleanup could never reach a terminal state.
 */
function isAbsenceStatus(status: string | null): boolean {
  if (!status) return false;
  const normalized = status.trim().toLowerCase().replace(/[\s_-]+/g, "");
  return normalized === "notfound" || normalized === "missing";
}

/** True when Cloudinary positively confirmed a deletion. */
function isDeletedStatus(status: string | null): boolean {
  return status !== null && status.trim().toLowerCase().replace(/[\s_-]+/g, "") === "ok";
}

/**
 * Maps a media mime type to the Cloudinary resource type used at upload time.
 * `image/*` is stored under `image`, `video/*` under `video`, everything else
 * under `raw`. `auto` never appears as a stored resource type.
 */
export function resourceTypeForMime(mimeType: string | null | undefined): CloudinaryResourceType {
  const mime = (mimeType ?? "").toLowerCase();
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "video";
  return "raw";
}

/** All resource types worth probing, the derived one first. */
function resourceTypeCandidates(
  mimeType: string | null | undefined,
  preferred?: CloudinaryResourceType | null,
): CloudinaryResourceType[] {
  const first = preferred ?? resourceTypeForMime(mimeType);
  const order: CloudinaryResourceType[] = ["image", "video", "raw"];
  return [first, ...order.filter((t) => t !== first)];
}

// ─── Deletion: Upload API destroy ───────────────────────────────────────────

interface DestroyDecision {
  outcome: "deleted" | "absent" | "unknown";
  echoedStatus: string | null;
}

/**
 * STRICT classification of a destroy response.
 *
 * A deletion is reported only for `{"result":"ok"}`. An absence is reported only
 * for a recognised not-found wording. A 2xx carrying anything else is `unknown`
 * and is treated as a FAILURE — never as success. An over-eager "success" would
 * mark a media cleaned up while its Cloudinary copy is still live.
 */
function classifyDestroy(res: Response, body: CloudinaryBody): DestroyDecision {
  if (!res.ok) {
    return { outcome: "unknown", echoedStatus: body.result };
  }
  const status = body.result;
  if (isDeletedStatus(status)) {
    return { outcome: "deleted", echoedStatus: status };
  }
  if (isAbsenceStatus(status)) {
    return { outcome: "absent", echoedStatus: status };
  }
  return { outcome: "unknown", echoedStatus: status };
}

/**
 * Deletes the Cloudinary asset identified by `publicId` via the Upload API.
 *
 *   POST https://api.cloudinary.com/v1_1/{cloud}/{resource_type}/destroy
 *     public_id, timestamp, api_key, signature, invalidate=true, type=upload
 *
 * Documented as an Asset-management method of the (rate-unlimited) Upload API —
 * not the rate-limited Admin API. `invalidate=true` asks Cloudinary to purge CDN
 * cached copies; the documented propagation is "a few seconds to a few minutes".
 *
 * Resource type is derived from the media mime type; when Cloudinary reports the
 * asset absent under the derived type the remaining types are probed, so an asset
 * uploaded under a different type is still cleaned up. (Persisting the resource
 * type would remove this probing — a separate follow-up, deliberately not done
 * here.)
 *
 * Never logs or throws with the API secret, the signature or the request body.
 */
export async function destroyCloudinaryAsset(params: {
  publicId: string;
  mimeType?: string | null;
  resourceType?: CloudinaryResourceType | null;
  fetchImpl?: typeof fetch;
}): Promise<CloudinaryDeleteResult> {
  const publicId = (params.publicId ?? "").trim();
  if (!publicId) {
    throw new CloudinaryDeleteError("Missing Cloudinary public_id", 0, false);
  }

  const credentials = getCredentials();
  const { cloudName, apiKey, apiSecret } = credentials;
  const fetchImpl = params.fetchImpl ?? fetch;

  let lastStatus = 0;
  let sawAbsence = false;
  let lastProvider: CloudinaryDeleteResult["provider"] = {
    endpoint: "upload_api_destroy",
    echoedId: null,
    echoedStatus: null,
    raw: null,
  };
  let lastBodySummary = "";

  /**
   * Builds a failure that ALWAYS carries the sanitized provider evidence.
   * The raw HTTP status and body must survive even when classification throws.
   */
  const failWith = (
    classification: string,
    message: string,
    status: number,
    retryable: boolean,
    resourceType: CloudinaryResourceType | null = null,
  ): CloudinaryDeleteError => {
    const err = new CloudinaryDeleteError(message, status, retryable);
    err.provider = {
      httpStatus: status,
      classification,
      raw: lastBodySummary || null,
      publicId,
      resourceType,
    };
    return err;
  };

  for (const resourceType of resourceTypeCandidates(params.mimeType, params.resourceType)) {
    const timestamp = Math.floor(Date.now() / 1000);
    const invalidate = "true";

    // Signed parameters must match the posted parameters exactly.
    const signed: Record<string, string> = {
      invalidate,
      public_id: publicId,
      timestamp: String(timestamp),
      type: DELIVERY_TYPE,
    };
    const signature = await signParams(signed, apiSecret);

    const form = new URLSearchParams({
      ...signed,
      api_key: apiKey,
      signature,
    });

    const url = `${CLOUDINARY_API_BASE}/${cloudName}/${resourceType}/destroy`;

    const res = await fetchImpl(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form.toString(),
      signal: AbortSignal.timeout(30_000),
    });

    lastStatus = res.status;
    const body = await readCloudinaryBody(res);
    const decision = classifyDestroy(res, body);

    lastBodySummary = JSON.stringify({
      endpoint: "upload_api_destroy",
      resource_type: resourceType,
      http: res.status,
      result: decision.echoedStatus,
      error: sanitizeProviderMessage(body.errorMessage, credentials),
    }).slice(0, 400);
    lastProvider = {
      endpoint: "upload_api_destroy",
      echoedId: null,
      echoedStatus: decision.echoedStatus,
      raw: lastBodySummary,
    };

    if (decision.outcome === "deleted") {
      return {
        deleted: true,
        alreadyAbsent: false,
        resourceType,
        httpStatus: res.status,
        provider: lastProvider,
      };
    }

    if (decision.outcome === "absent") {
      // Absent under THIS resource type: keep probing the others, because the
      // asset may live under a different one.
      sawAbsence = true;
      continue;
    }

    // A transport-level 404 is also an absence signal.
    if (res.status === 404) {
      sawAbsence = true;
      continue;
    }

    const detail = sanitizeProviderMessage(body.errorMessage, credentials);
    const suffix = detail ? `: ${detail}` : `: HTTP ${res.status}`;

    if (res.status === 401 || res.status === 403) {
      throw failWith("auth_rejected", `${describeStatus(res.status)}${suffix}`, res.status, false);
    }
    if (res.status === 429 || res.status >= 500) {
      throw failWith("transient_provider_error", `${describeStatus(res.status)}${suffix}`, res.status, true);
    }

    // Anything else — including an unexpected 2xx that named neither a deletion
    // nor an absence — is a malformed request or an unrecognised answer:
    // permanent until the request itself changes, and NEVER a success.
    throw failWith(
      res.ok ? "unclassified_2xx" : "provider_rejected",
      `${describeStatus(res.status)}${suffix}`,
      res.status,
      false,
    );
  }

  if (!sawAbsence) {
    // No resource type gave a positive or negative answer for this id: the
    // delete is NOT confirmed and must not be reported as a success.
    throw failWith(
      "unclassified_no_absence",
      `Cloudinary did not confirm deletion for this asset (last response: ${lastBodySummary || "no response"})`,
      lastStatus,
      false,
    );
  }

  // Every resource type reported the asset absent -> already deleted.
  // This is the idempotent retry case: a retried cleanup of an asset that was
  // already removed must not corrupt the media workflow.
  return {
    deleted: false,
    alreadyAbsent: true,
    resourceType: null,
    httpStatus: lastStatus || 404,
    provider: lastProvider,
  };
}

// ─── Independent verification: fresh-derivation delivery probe ───────────────

/** Authoritative delivery-side state of one Cloudinary asset. */
export type CloudinaryAssetState = "present" | "absent" | "unknown";

export interface CloudinaryDeliveryProbe {
  state: CloudinaryAssetState;
  httpStatus: number;
  resourceType: CloudinaryResourceType | null;
  /** The fresh, uncached URL that was probed (never carries a credential). */
  probeUrl: string | null;
  /** The unique transformation used to force a cache miss. */
  transformUsed: string | null;
  /** Sanitized response evidence. */
  raw: string | null;
}

/** Nonce source for deriving a unique transformation + cache key. */
function freshNonce(): number {
  const rand = new Uint32Array(1);
  crypto.getRandomValues(rand);
  return (rand[0] % 100000) + 1;
}

/**
 * Builds a delivery URL that cannot be served from an existing cache entry.
 *
 * The original delivery URL is `cache-control: immutable, max-age=2592000`, so
 * a deleted asset can keep returning HTTP 200 from the edge for up to 30 days.
 * Using it as the verification signal produced a false "still present" and is
 * therefore forbidden here.
 *
 * The safest documented way to force a genuine origin read is a transformation
 * that was never requested before: an unknown path segment is part of the CDN
 * cache key, so the request cannot match a cached response. A unique query value
 * is appended as well, but is NOT relied upon on its own (Cloudinary's CDN may
 * ignore unknown query parameters for cache-key purposes).
 *
 * `raw` assets do not support transformations, so they fall back to a
 * query-only probe; that is best-effort and can only ever over-report presence
 * (never a false absence).
 */
function buildFreshProbeUrl(params: {
  deliveryUrl: string | null;
  cloudName: string;
  publicId: string;
  resourceType: CloudinaryResourceType;
  format?: string | null;
  nonce: number;
}): { url: string; transformUsed: string | null } {
  let base: URL | null = null;
  if (params.deliveryUrl) {
    try {
      base = new URL(params.deliveryUrl);
    } catch {
      base = null;
    }
  }
  if (!base) {
    const ext = params.format ? `.${params.format.replace(/^\./, "")}` : "";
    base = new URL(
      `${CLOUDINARY_DELIVERY_BASE}/${params.cloudName}/${params.resourceType}/${DELIVERY_TYPE}/${params.publicId}${ext}`,
    );
  }

  const segments = base.pathname.split("/").filter(Boolean);
  // Segments are: [cloud, resource_type, delivery_type, …version/folder/file].
  const insertAt = 3;

  let transformUsed: string | null = null;
  if (params.resourceType === "image") {
    const w = 32 + (params.nonce % 47);
    const h = 32 + (Math.floor(params.nonce / 47) % 47);
    transformUsed = `w_${w},h_${h},c_fill`;
  } else if (params.resourceType === "video") {
    transformUsed = `w_${32 + (params.nonce % 47)}`;
  }

  if (transformUsed && segments.length >= insertAt) {
    segments.splice(insertAt, 0, transformUsed);
    base.pathname = "/" + segments.join("/");
  }

  base.searchParams.set("_pd", String(params.nonce));
  return { url: base.toString(), transformUsed };
}

/**
 * INDEPENDENT verification: asks the delivery tier whether the asset can still
 * be retrieved, using a URL that cannot hit an existing cache entry.
 *
 * This is deliberately NOT an Admin API call, so it consumes none of the 500
 * hourly Admin operations, and it is NOT the delete response, so the delete
 * cannot vouch for itself.
 *
 *   HTTP 404                        -> absent   (confirmed gone)
 *   2xx                             -> present  (still retrievable)
 *   anything else (423/5xx/…)       -> unknown  (never treated as absence)
 */
export async function probeCloudinaryDelivery(params: {
  deliveryUrl?: string | null;
  publicId: string;
  resourceType: CloudinaryResourceType;
  format?: string | null;
  fetchImpl?: typeof fetch;
}): Promise<CloudinaryDeliveryProbe> {
  const publicId = (params.publicId ?? "").trim();
  if (!publicId) {
    throw new CloudinaryDeleteError("Missing Cloudinary public_id", 0, false);
  }

  const cloudName = Deno.env.get("CLOUDINARY_CLOUD_NAME");
  if (!cloudName) {
    throw new CloudinaryDeleteError(
      "Cloudinary credentials missing (CLOUDINARY_CLOUD_NAME)",
      0,
      false,
    );
  }

  const fetchImpl = params.fetchImpl ?? fetch;
  const nonce = freshNonce();
  const { url, transformUsed } = buildFreshProbeUrl({
    deliveryUrl: params.deliveryUrl ?? null,
    cloudName,
    publicId,
    resourceType: params.resourceType,
    format: params.format ?? null,
    nonce,
  });

  const res = await fetchImpl(url, {
    method: "GET",
    headers: { "Cache-Control": "no-cache", Pragma: "no-cache" },
    cache: "no-store",
    signal: AbortSignal.timeout(25_000),
  });

  const raw = JSON.stringify({
    via: "fresh_derivation_delivery_probe",
    http: res.status,
    resource_type: params.resourceType,
    transform: transformUsed,
    nonce,
  }).slice(0, 400);

  if (res.status === 404) {
    // Drain the (empty) body so the connection can be reused.
    await res.body?.cancel().catch(() => {});
    return {
      state: "absent",
      httpStatus: res.status,
      resourceType: params.resourceType,
      probeUrl: url,
      transformUsed,
      raw,
    };
  }

  if (res.ok) {
    await res.body?.cancel().catch(() => {});
    return {
      state: "present",
      httpStatus: res.status,
      resourceType: params.resourceType,
      probeUrl: url,
      transformUsed,
      raw,
    };
  }

  // 423 (derivation still processing), 429, 5xx … are inconclusive: they must
  // never be read as absence, so the source is kept and the row is retried.
  await res.body?.cancel().catch(() => {});
  throw new CloudinaryDeleteError(
    `Cloudinary delivery probe inconclusive: HTTP ${res.status}`,
    res.status,
    res.status === 429 || res.status >= 500 || res.status === 423,
  );
}
