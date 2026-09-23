import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin } from "../shared/auth.ts";
import {
  planNextDriveAccount,
  markDriveAccountResult,
  releaseDriveQuota,
} from "../shared/drive-router.ts";
import {
  accessTokenForAccount,
  resolveUserDriveFolder,
  type DriveFolderRow,
} from "../shared/drive-folders.ts";
import { findFileByName } from "../shared/google-drive.ts";
import {
  uploadChunked,
  uploadStreaming,
  DriveUploadError,
  DriveUploadExpiredError,
  DriveUploadUncertainError,
  type ChunkProgress,
} from "../shared/google-drive-upload.ts";
import {
  verifyDriveUpload,
  DriveVerifyError,
  type DriveVerification,
} from "../shared/drive-verify.ts";
import {
  destroyCloudinaryAsset,
  probeCloudinaryDelivery,
  resourceTypeForMime,
  getCloudinaryCloudName,
  CloudinaryDeleteError,
  type CloudinaryDeliveryProbe,
} from "../shared/cloudinary.ts";

/**
 * drive-replicate — server-side media lifecycle worker.
 *
 * Android -> Cloudinary -> finalize-media -> media_assets -> PENDING Drive job
 *   -> this worker -> Drive Router -> selected Google Drive account
 *   -> user's per-account folder -> original media file on Google Drive
 *   -> VERIFY the Drive copy -> mark the media archived
 *   -> if every required destination is complete: delete the Cloudinary copy.
 *
 * Android NEVER uploads to Google Drive and Google Drive is never exposed to
 * the app; it is a server-managed archive destination.
 *
 * Phase 1 — Drive replication:
 *   - claim_drive_job() atomically claims the next PENDING/RETRYING (or
 *     long-stale PROCESSING) Drive job; concurrent workers can never
 *     double-process the same row (FOR UPDATE SKIP LOCKED).
 *   - The Drive Router (list/reserve over drive_accounts, no hardcoded
 *     accounts) picks the best enabled+healthy account with enough quota and
 *     the configured safety margin, excluding accounts the job already failed
 *     on (failed_drive_account_ids = failover list).
 *   - resolveUserDriveFolder() reuses the stored drive_folders mapping for
 *     (user, account) or creates it idempotently under a creation lease.
 *   - Original Cloudinary media is streamed to Drive (never buffered whole in
 *     memory, never downloaded to Android). Files up to 20 MB use a single
 *     streaming resumable request; larger files use 5 MB chunked resumable
 *     uploads whose session URI + progress survive worker timeouts/crashes.
 *     No resize, no recompression, no format change: the bytes on Drive are
 *     the bytes Cloudinary stored.
 *   - Every completed upload is VERIFIED with Drive files.get before the job
 *     is recorded as COMPLETED (drive-verify.ts): file exists, is not trashed,
 *     lives in the expected user's folder, has the expected name and a size
 *     consistent with the source.
 *   - Transient failures retry with exponential backoff; account-level
 *     failures (OAuth, quota, disabled) fail over to the next eligible
 *     account on the SAME job row — the media_assets record is never
 *     duplicated and the application user stays authoritative.
 *
 * Phase 2 — Cloudinary cleanup (only for verified archives):
 *   - claim_cloudinary_cleanup() hands out media whose Drive copy is COMPLETED
 *     AND whose Telegram replication (when configured) reached a terminal
 *     success state AND app_settings.auto_delete_primary_after_replication is
 *     on. It is an atomic FOR UPDATE SKIP LOCKED claim with its own retry
 *     counter (media_assets.primary_cleanup_*) — no second job table.
 *   - The Drive copy is re-verified immediately before the delete, so a Drive
 *     asset that later became unreadable/trashed keeps its Cloudinary source.
 *   - Deletion is idempotent: Cloudinary's "not found" is a successful cleanup
 *     of an already-deleted asset.
 *   - A FAILED cleanup never touches the Drive archive and never corrupts the
 *     media record: it is retried, and the source is preserved.
 *
 * Security:
 *   - service-role key required (or public_url=true for cron). Google refresh
 *     tokens, the Cloudinary API secret and the OAuth client secret stay in
 *     the Vault / Edge Function secrets and are never logged or returned.
 *
 * Deploy:
 *   supabase functions deploy drive-replicate
 */

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

const BATCH_SIZE = 2; // Drive jobs per invocation
const CLEANUP_BATCH_SIZE = 3; // cleanup candidates per invocation
const MAX_MS = 48_000; // stay well inside the 60s Edge Function limit
const CONCURRENT_LIMIT = 3; // in-process concurrency guard
const CHUNKED_THRESHOLD_BYTES = 20 * 1024 * 1024; // >= this -> chunked resumable

// ─── Types ────────────────────────────────────────────────────────────────

interface DriveJob {
  id: string;
  media_id: string;
  drive_account_id: string | null;
  drive_folder_id: string | null;
  status: string;
  attempt_count: number;
  last_error: string | null;
  next_retry_at: string | null;
  google_drive_file_id: string | null;
  failed_drive_account_ids: string[];
  google_drive_upload_url: string | null;
  google_drive_upload_chunk: number;
  google_drive_upload_attempts: number;
  created_at: string;
}

interface MediaAsset {
  id: string;
  owner_id: string;
  file_name: string;
  mime_type: string;
  file_size: number;
  storage_url: string | null;
  storage_asset_id: string;
  status: string;
}

/**
 * A media row handed out by claim_cloudinary_cleanup(). It carries the same
 * identity fields as MediaAsset plus the cleanup lifecycle columns, so the
 * worker never needs a second lookup to decide what to delete.
 */
interface CleanupCandidate {
  id: string;
  owner_id: string;
  file_name: string;
  mime_type: string;
  file_size: number;
  storage_url: string | null;
  storage_path: string | null;
  storage_asset_id: string;
  status: string;
  primary_cleanup_status: string;
  primary_cleanup_attempts: number;
  drive_archived_at: string | null;
}

interface AppSettings {
  drive_enabled: boolean;
  max_retry: number;
  retry_base_delay_seconds: number;
  auto_delete_primary_after_replication: boolean;
}

// ─── In-process concurrency guard ─────────────────────────────────────────

let inflight = 0;

// ─── Entry point ──────────────────────────────────────────────────────────

// Deno.serve instead of `import { serve } from "jsr:@std/http"`: the deployed
// edge runtime resolves that module to a version with no `serve` export, which
// made this worker fail with a BOOT_ERROR and never run at all.
Deno.serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  if (inflight >= CONCURRENT_LIMIT) {
    return jsonResponse({ processed: 0, reason: "Worker busy" }, 429);
  }

  inflight++;
  const started = Date.now();

  try {
    const url = new URL(req.url);
    const isPublicUrl = url.searchParams.get("public_url") === "true";

    let admin: AdminClient;
    if (isPublicUrl) {
      admin = getSupabaseAdmin();
    } else {
      const authHeader = req.headers.get("Authorization") ?? "";
      if (!authHeader.startsWith("Bearer ")) {
        return jsonResponse(
          { error: "Authorization required (service role key)" },
          401,
        );
      }
      admin = getSupabaseAdmin();
    }

    const { data: settings } = await admin
      .from("app_settings")
      .select("*")
      .eq("id", true)
      .maybeSingle();

    const typedSettings = (settings as AppSettings | null) ?? null;
    const maxRetry = typedSettings?.max_retry ?? 5;
    const retryBase = typedSettings?.retry_base_delay_seconds ?? 60;

    let processed = 0;
    const results: Array<{ job_id: string; status: string; error?: string }> =
      [];

    while (processed < BATCH_SIZE && Date.now() - started < MAX_MS) {
      const claimed = await claimNextJob(admin);
      // PostgREST returns a NULL composite (claim_drive_job found nothing) as
      // an all-null object rather than null, so the id must be checked too.
      if (!claimed || !claimed.id) break;

      const result = await processJob(admin, claimed, {
        ...typedSettings,
        max_retry: maxRetry,
        retry_base_delay_seconds: retryBase,
      });
      processed++;
      results.push({
        job_id: claimed.id,
        status: result.status,
        error: result.error,
      });
    }

    // ── Phase 2: Cloudinary cleanup for already-verified archives ───────
    // Runs in the same invocation so a single cron/manual trigger advances
    // both halves of the lifecycle. claim_cloudinary_cleanup() is a no-op
    // while auto_delete_primary_after_replication is false, and it is an
    // atomic claim, so running this concurrently is safe.
    const cleanup = await runCleanupPhase(admin, started);

    return jsonResponse(
      {
        processed,
        results,
        cleanup_processed: cleanup.processed,
        cleanup_results: cleanup.results,
        elapsed_ms: Date.now() - started,
      },
      200,
    );
  } catch (error) {
    console.error(
      "drive-replicate worker failed:",
      ((error as Error).message ?? String(error)).slice(0, 500),
    );
    return jsonResponse({ error: (error as Error).message }, 500);
  } finally {
    inflight--;
  }
});

// ─── Job claiming ─────────────────────────────────────────────────────────

async function claimNextJob(admin: AdminClient): Promise<DriveJob | null> {
  const { data, error } = await admin.rpc("claim_drive_job").maybeSingle();
  if (error) {
    console.error("claim_drive_job RPC error:", error.message);
    return null;
  }
  return (data as DriveJob | null) ?? null;
}

// ─── Single job processing ────────────────────────────────────────────────

async function processJob(
  admin: AdminClient,
  job: DriveJob,
  settings: AppSettings,
): Promise<{ status: string; error?: string }> {
  const log = (msg: string) =>
    console.log(`[drive ${job.id.slice(0, 8)}] ${msg}`);

  log(
    `Processing (attempt ${job.attempt_count}) account=${job.drive_account_id ?? "none"}`,
  );

  // ── 1. Idempotency: a job that already reached a terminal state is done ──
  if (job.status === "COMPLETED") {
    log("Already completed — skipping");
    return { status: "SKIPPED" };
  }

  // ── 2. Global switch ────────────────────────────────────────────────
  if (settings.drive_enabled === false) {
    const msg = "Drive replication is disabled globally";
    log(msg);
    await completeDriveJob(admin, job.id, { status: "SKIPPED", error: msg });
    await logSyncEvent(admin, job, "JOB_SKIPPED", "SKIPPED", { reason: msg });
    return { status: "SKIPPED", error: msg };
  }

  // ── 3. Load media ───────────────────────────────────────────────────
  const media = await loadMedia(admin, job);
  if (!media) {
    const msg = "Media not found or not READY";
    log(`FAIL (permanent): ${msg}`);
    await completeDriveJob(admin, job.id, { status: "FAILED", error: msg });
    await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: msg });
    return { status: "FAILED", error: msg };
  }
  if (!media.storage_url || !media.storage_asset_id) {
    const msg = "Media has no Cloudinary origin (storage_url missing)";
    log(`FAIL (permanent): ${msg}`);
    await completeDriveJob(admin, job.id, { status: "FAILED", error: msg });
    await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: msg });
    return { status: "FAILED", error: msg };
  }

  // ── 3b. Idempotency: a job that already holds a Drive file id ─────────
  // This is the "uploaded successfully but the database write did not land"
  // case. The existing file is NOT trusted blindly and NOT duplicated: it is
  // verified against the user's folder, name and size, and only then recorded
  // COMPLETED. A file that cannot be verified is left alone (so no second copy
  // is ever created) and the job is retried.
  if (job.google_drive_file_id && job.drive_account_id) {
    const reconciled = await reconcileExistingUpload(admin, job, media, settings);
    if (reconciled) return reconciled;
  }

  // ── 4. Route: pick the best eligible account (atomic quota reserve) ──
  const routed = await planNextDriveAccount(admin, job.id, {
    requiredBytes: media.file_size,
  });
  const account = routed.account;

  if (!account) {
    const msg =
      "No eligible Drive account (all full, disconnected, disabled or excluded)";
    if (job.attempt_count >= settings.max_retry) {
      log(`FAIL (attempts exhausted): ${msg}`);
      await completeDriveJob(admin, job.id, { status: "FAILED", error: msg });
      await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: msg });
      return { status: "FAILED", error: msg };
    }
    log(`TRANSIENT (no route): ${msg}`);
    const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
    await retryDriveJobSameAccount(admin, job.id, null, null, msg, backoff);
    await logSyncEvent(admin, job, "JOB_RETRY", "RETRYING", {
      reason: msg,
      next_retry_ms: backoff * 1000,
    });
    return { status: "RETRYING", error: msg };
  }

  log(
    `Routed to account ${account.id.slice(0, 8)} (${account.google_email ?? "?"}, priority ${account.priority})`,
  );
  await logSyncEvent(admin, job, "DRIVE_ACCOUNT_SELECTED", "OK", {
    account_id: account.id,
    account_name: account.name,
    priority: account.priority,
    health_status: account.health_status,
    storage_available_bytes: account.storage_available_bytes,
    reserved_bytes: account.reserved_bytes,
    file_size: media.file_size,
  });

  // An upload session left over from a previous, different account must be
  // discarded before we start on this one.
  const resumeSameAccount =
    job.google_drive_upload_url &&
    job.google_drive_upload_chunk > 0 &&
    job.drive_account_id === account.id;

  // ── 5. Persist the account selection (keep PROCESSING) ──────────────
  await admin
    .from("replication_jobs")
    .update({ drive_account_id: account.id, updated_at: new Date().toISOString() })
    .eq("id", job.id);

  if (!resumeSameAccount && job.google_drive_upload_url) {
    await admin
      .from("replication_jobs")
      .update({
        google_drive_upload_url: null,
        google_drive_upload_chunk: 0,
        last_error: null,
        updated_at: new Date().toISOString(),
      })
      .eq("id", job.id);
    log("Discarded upload session from a previous account");
  }

  // ── 6. Resolve (or create) the user's folder on this account ────────
  let folder: DriveFolderRow | null = null;
  try {
    folder = await resolveUserDriveFolder(admin, account, media.owner_id);
  } catch (err) {
    const errMsg = (err as Error).message;
    const decision = decideFailure(err);
    if (decision.kind === "failover") {
      log(`FOLDER account failure → failover: ${errMsg}`);
      const outcome = await accountFailover(admin, job, account, settings, errMsg, decision);
      await logSyncEvent(admin, job, "JOB_FAILOVER", outcome, {
        from_account: account.id,
        error: errMsg,
      });
      return { status: outcome, error: errMsg };
    }
    if (decision.kind === "permanent") {
      log(`FAIL (folder permanent): ${errMsg}`);
      await releaseDriveQuota(admin, account.id, media.file_size).catch(() => {});
      await completeDriveJob(admin, job.id, { status: "FAILED", error: errMsg });
      await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: errMsg });
      return { status: "FAILED", error: errMsg };
    }
    // transient folder trouble (root folder lease held elsewhere, network…)
    log(`TRANSIENT (folder): ${errMsg}`);
    const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
    await retryDriveJobSameAccount(
      admin, job.id, account.id, job.drive_folder_id, errMsg, backoff,
    );
    await logSyncEvent(admin, job, "JOB_RETRY", "RETRYING", { reason: errMsg });
    return { status: "RETRYING", error: errMsg };
  }

  if (!folder || !folder.google_folder_id) {
    const msg =
      "User folder not ready yet (creation lease held elsewhere) — will retry";
    log(msg);
    const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
    await retryDriveJobSameAccount(admin, job.id, account.id, null, msg, backoff);
    await logSyncEvent(admin, job, "JOB_RETRY", "RETRYING", { reason: msg });
    return { status: "RETRYING", error: msg };
  }

  await admin
    .from("replication_jobs")
    .update({ drive_folder_id: folder.id, updated_at: new Date().toISOString() })
    .eq("id", job.id);
  log(`Folder ${folder.google_folder_id} (${folder.folder_status}) on account ${account.id.slice(0, 8)}`);
  await logSyncEvent(admin, job, "DRIVE_FOLDER_RESOLVED", "OK", {
    account_id: account.id,
    folder_id: folder.id,
    google_folder_id: folder.google_folder_id,
    folder_name: folder.folder_name,
    created: folder.folder_status === "active" && folder.create_attempts >= 1,
  });

  // ── 7. Access token (server-side secrets only) ─────────────────────
  let accessToken: string;
  try {
    accessToken = await accessTokenForAccount(admin, account.id);
  } catch (err) {
    const errMsg = (err as Error).message;
    log(`AUTH account failure → failover: ${errMsg}`);
    const decision = decideFailure(err);
    const outcome = await accountFailover(admin, job, account, settings, errMsg, decision);
    await logSyncEvent(admin, job, "JOB_FAILOVER", outcome, {
      from_account: account.id,
      error: errMsg,
    });
    return { status: outcome, error: errMsg };
  }

  // ── 8. Re-check job state right before upload (double-delivery guard) ──
  const recheck = await admin
    .from("replication_jobs")
    .select("status, google_drive_file_id")
    .eq("id", job.id)
    .maybeSingle();
  if (recheck.data) {
    const rc = recheck.data as { status: string; google_drive_file_id: string | null };
    if (rc.status === "COMPLETED" || rc.google_drive_file_id) {
      log(`Job already completed elsewhere — skipping upload`);
      return { status: "SKIPPED" };
    }
  }

  // ── 9. Duplicate guard (fresh uploads only) ────────────────────────
  // A same-named file in the user's folder is almost certainly OUR earlier
  // upload of the same media (the name carries a media-id tag). It is verified
  // before reuse so a half-written leftover is never archived, and it is never
  // replaced by a second copy while it exists.
  const fileName = driveFileName(media);
  if (!resumeSameAccount) {
    try {
      const existingId = await findFileByName(
        fileName,
        folder.google_folder_id,
        accessToken,
      );
      if (existingId) {
        const verification = await verifyDriveUpload({
          accessToken,
          fileId: existingId,
          expectedName: fileName,
          expectedParentId: folder.google_folder_id,
          expectedSize: media.file_size,
        });
        log(`File already exists on Drive (${existingId}) and verified — marking COMPLETED`);
        await logSyncEvent(admin, job, "DRIVE_UPLOAD_VERIFIED", "OK", {
          file_id: existingId,
          account_id: account.id,
          folder_id: folder.id,
          reused_existing: true,
          checks: verification.checks,
          drive_md5: verification.md5Checksum,
          drive_size_bytes: verification.sizeBytes,
        });
        await completeDriveJob(admin, job.id, {
          status: "COMPLETED",
          driveAccountId: account.id,
          driveFolderId: folder.id,
          driveFileId: existingId,
        });
        await markMediaArchived(admin, job, {
          accountId: account.id,
          folderRowId: folder.id,
          driveFileId: existingId,
          verification,
        });
        await logSyncEvent(admin, job, "JOB_COMPLETED", "COMPLETED", {
          file_id: existingId,
          account_id: account.id,
          folder_id: folder.id,
          reused: true,
        });
        return { status: "COMPLETED" };
      }
    } catch (err) {
      if (err instanceof DriveVerifyError && err.mismatch) {
        // The leftover file is not a usable archive copy. Never upload a
        // second copy over it, never record COMPLETED, never let the cleanup
        // phase see this media: leave Cloudinary untouched and retry.
        const msg = `Existing Drive file failed verification: ${err.message}`;
        log(`FAIL (existing file mismatch): ${msg}`);
        await completeDriveJob(admin, job.id, {
          status: "RETRYING",
          error: msg,
          nextRetryAt: new Date(
            Date.now() + computeBackoff(job, settings.retry_base_delay_seconds) * 1000,
          ).toISOString(),
        });
        await logSyncEvent(admin, job, "DRIVE_UPLOAD_FAILED", "RETRYING", {
          reason: msg,
          existing_file_mismatch: true,
          file_id: null,
        });
        return { status: "RETRYING", error: msg };
      }
      // A list/transport failure shouldn't abort a legitimate upload — log it.
      log(`Duplicate check skipped (${(err as Error).message})`);
    }
  }

  // ── 10. Upload the original media (streamed, no full buffering) ────
  const description =
    `MyDrive archive\nmedia_id: ${media.id}\ncloudinary: ${media.storage_asset_id}`.slice(
      0, 500,
    );

  // Live report of the newest resumable position, in case we need to resume.
  let lastChunkProgress: ChunkProgress | null = null;

  const uploadStartedAt = Date.now();
  await logSyncEvent(admin, job, "DRIVE_UPLOAD_STARTED", "PROCESSING", {
    media_id: media.id,
    account_id: account.id,
    folder_id: folder.id,
    google_folder_id: folder.google_folder_id,
    file_name: fileName,
    mime_type: media.mime_type,
    file_size: media.file_size,
    mode: media.file_size <= CHUNKED_THRESHOLD_BYTES ? "streaming" : "chunked_resumable",
    attempt_count: job.attempt_count,
    resumed: resumeSameAccount,
  });

  try {
    let fileId: string;

    if (media.file_size <= CHUNKED_THRESHOLD_BYTES) {
      log(`Uploading ${(media.file_size / 1024 / 1024).toFixed(2)} MB (streamed)`);
      fileId = await uploadStreaming({
        accessToken,
        sourceUrl: media.storage_url,
        fileName,
        mimeType: media.mime_type || "application/octet-stream",
        parentFolderId: folder.google_folder_id,
        fileSize: media.file_size,
        description,
      });
      log(`SUCCESS: file ${fileId}`);
    } else {
      const resumeAt = resumeSameAccount ? job.google_drive_upload_chunk : 0;
      log(
        `Uploading ${(media.file_size / 1024 / 1024).toFixed(1)} MB (chunked, resume @ ${resumeAt} bytes)`,
      );
      const onProgress = async (progress: ChunkProgress) => {
        lastChunkProgress = progress;
        await persistUploadProgress(admin, job.id, progress);
      };
      const { fileId: chunkedId, bytesSent } = await uploadChunked(
        {
          accessToken,
          sourceUrl: media.storage_url,
          fileName,
          mimeType: media.mime_type || "application/octet-stream",
          parentFolderId: folder.google_folder_id,
          fileSize: media.file_size,
          description,
          resumeAtBytes: resumeAt,
          uploadUrl: resumeSameAccount ? job.google_drive_upload_url : undefined,
          chunkCloseMs: 45_000,
        },
        onProgress,
      );
      fileId = chunkedId;
      log(`SUCCESS: file ${fileId} (${bytesSent} bytes)`);
    }

    await logSyncEvent(admin, job, "DRIVE_UPLOAD_SUCCESS", "OK", {
      media_id: media.id,
      account_id: account.id,
      folder_id: folder.id,
      file_id: fileId,
      file_size: media.file_size,
      duration_ms: Date.now() - uploadStartedAt,
      attempt_count: job.attempt_count,
    });

    // ── 10b. VERIFY the Drive copy before anything is recorded or deleted ──
    // Cloudinary is only ever deleted from a verified archive, so this must
    // pass first. A verification failure leaves the job retryable and the
    // Cloudinary source untouched.
    let verification: DriveVerification;
    try {
      verification = await verifyDriveUpload({
        accessToken,
        fileId,
        expectedName: fileName,
        expectedParentId: folder.google_folder_id,
        expectedSize: media.file_size,
      });
    } catch (verifyErr) {
      const msg = `Drive upload verification failed: ${(verifyErr as Error).message}`;
      log(`FAIL (verification): ${msg}`);
      await logSyncEvent(admin, job, "DRIVE_UPLOAD_FAILED", "VERIFY_FAILED", {
        media_id: media.id,
        account_id: account.id,
        folder_id: folder.id,
        file_id: fileId,
        file_size: media.file_size,
        reason: msg,
        drive_mismatch: verifyErr instanceof DriveVerifyError
          ? verifyErr.mismatch
          : false,
        cloudinary_preserved: true,
      });
      const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
      await retryDriveJobSameAccount(
        admin, job.id, account.id, folder.id, msg, backoff,
      );
      return { status: "RETRYING", error: msg };
    }

    log(`VERIFIED: file ${fileId} md5=${verification.md5Checksum ?? "n/a"}`);
    await logSyncEvent(admin, job, "DRIVE_UPLOAD_VERIFIED", "OK", {
      media_id: media.id,
      account_id: account.id,
      folder_id: folder.id,
      google_folder_id: folder.google_folder_id,
      file_id: fileId,
      checks: verification.checks,
      drive_md5: verification.md5Checksum,
      drive_size_bytes: verification.sizeBytes,
      source_size_bytes: media.file_size,
      duration_ms: Date.now() - uploadStartedAt,
      attempt_count: job.attempt_count,
    });

    await completeDriveJob(admin, job.id, {
      status: "COMPLETED",
      driveAccountId: account.id,
      driveFolderId: folder.id,
      driveFileId: fileId,
    });
    await markDriveAccountResult(admin, account.id, {
      healthStatus: "healthy",
      status: "active",
      lastError: null,
    }).catch(() => {});
    await markMediaArchived(admin, job, {
      accountId: account.id,
      folderRowId: folder.id,
      driveFileId: fileId,
      verification,
    });
    await logSyncEvent(admin, job, "JOB_COMPLETED", "COMPLETED", {
      file_id: fileId,
      account_id: account.id,
      folder_id: folder.id,
      google_folder_id: folder.google_folder_id,
      bytes: media.file_size,
      verified: true,
    });
    return { status: "COMPLETED" };
  } catch (err) {
    const decision = decideFailure(err);
    const errMsg = (err as Error).message;

    await logSyncEvent(admin, job, "DRIVE_UPLOAD_FAILED", decision.kind, {
      media_id: media.id,
      account_id: account.id,
      folder_id: folder.id,
      file_id: null,
      file_size: media.file_size,
      reason: errMsg,
      attempt_count: job.attempt_count,
      cloudinary_preserved: true,
    });

    switch (decision.kind) {
      case "failover": {
        log(`ACCOUNT FAILURE → failover: ${errMsg}`);
        const outcome = await accountFailover(admin, job, account, settings, errMsg, decision);
        await logSyncEvent(admin, job, "JOB_FAILOVER", outcome, {
          from_account: account.id,
          error: errMsg,
        });
        return { status: outcome, error: errMsg };
      }
      case "restart_session": {
        if (job.attempt_count >= settings.max_retry) {
          log(`FAIL (attempts exhausted): ${errMsg}`);
          await releaseDriveQuota(admin, account.id, media.file_size).catch(() => {});
          await completeDriveJob(admin, job.id, { status: "FAILED", error: errMsg });
          await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: errMsg });
          return { status: "FAILED", error: errMsg };
        }
        log(`SESSION EXPIRED → fresh restart: ${errMsg}`);
        await clearUploadSession(admin, job.id);
        const backoff = 30; // restart quickly, session+cold
        await retryDriveJobSameAccount(
          admin, job.id, account.id, folder.id, errMsg, backoff,
        );
        await logSyncEvent(admin, job, "JOB_RETRY", "RETRYING", { reason: errMsg });
        return { status: "RETRYING", error: errMsg };
      }
      case "permanent": {
        log(`FAIL (permanent): ${errMsg}`);
        await releaseDriveQuota(admin, account.id, media.file_size).catch(() => {});
        await completeDriveJob(admin, job.id, { status: "FAILED", error: errMsg });
        await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: errMsg });
        return { status: "FAILED", error: errMsg };
      }
      case "transient_retry":
      default: {
        // Transient: Cloudinary hiccups, Drive 5xx / 429, network, or an
        // uncertain upload outcome where we must simply resume.
        if (job.attempt_count >= settings.max_retry) {
          log(`FAIL (attempts exhausted): ${errMsg}`);
          await releaseDriveQuota(admin, account.id, media.file_size).catch(() => {});
          await completeDriveJob(admin, job.id, { status: "FAILED", error: errMsg });
          await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: errMsg });
          return { status: "FAILED", error: errMsg };
        }
        log(`TRANSIENT: ${errMsg}`);
        if (decision.retrySeconds != null && decision.health === "degraded") {
          await markDriveAccountResult(admin, account.id, {
            healthStatus: "degraded",
            lastError: errMsg.slice(0, 500),
          }).catch(() => {});
        }
        const backoff = decision.retrySeconds ??
          computeBackoff(job, settings.retry_base_delay_seconds);
        // Preserve the resumable session/progress when the outcome was
        // uncertain (retry returns on the same account and resumes).
        await retryDriveJobSameAccount(
          admin,
          job.id,
          account.id,
          folder.id,
          errMsg,
          backoff,
          decision.kind === "uncertain"
            ? (lastChunkProgress?.bytesSent ?? job.google_drive_upload_chunk)
            : null,
        );
        await logSyncEvent(admin, job, "JOB_RETRY", "RETRYING", {
          reason: errMsg,
          resume_at: decision.kind === "uncertain" ? job.google_drive_upload_chunk : 0,
          next_retry_ms: backoff * 1000,
        });
        return { status: "RETRYING", error: errMsg };
      }
    }
  }
}

// ─── Idempotent reconciliation + archive marking ──────────────────────────

/**
 * Handles a claimed job that already carries a Drive file id — the "upload
 * succeeded, database write did not" case.
 *
 * The existing file is verified (never trusted blindly, never duplicated):
 *   - verifies -> records COMPLETED with the EXISTING file id (no new file)
 *   - provably wrong (trashed / wrong folder / wrong size) -> clears the file id
 *     and retries, so the next attempt uploads a replacement
 *   - cannot be verified right now (transport, 429/5xx) -> retries and keeps
 *     the file id, so the next attempt re-verifies the SAME file
 *
 * Returns a result when the job was handled, or null when the caller should
 * fall through to a fresh upload.
 */
async function reconcileExistingUpload(
  admin: AdminClient,
  job: DriveJob,
  media: MediaAsset,
  settings: AppSettings,
): Promise<{ status: string; error?: string } | null> {
  const log = (msg: string) => console.log(`[drive ${job.id.slice(0, 8)}] ${msg}`);
  const fileId = job.google_drive_file_id!;
  const accountId = job.drive_account_id!;

  log(`Reconciling existing upload ${fileId} on account ${accountId.slice(0, 8)}`);

  const folder = await loadDriveFolder(admin, job.drive_folder_id);
  if (!folder?.google_folder_id) {
    const msg = "Cannot verify the existing Drive file: folder mapping is missing";
    log(`TRANSIENT (reconcile): ${msg}`);
    const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
    await retryDriveJobSameAccount(admin, job.id, accountId, job.drive_folder_id, msg, backoff);
    return { status: "RETRYING", error: msg };
  }

  let accessToken: string;
  try {
    accessToken = await accessTokenForAccount(admin, accountId);
  } catch (err) {
    const errMsg = (err as Error).message;
    log(`AUTH account failure during reconcile → failover: ${errMsg}`);
    const decision = decideFailure(err);
    const outcome = await accountFailover(admin, job, { id: accountId }, settings, errMsg, decision);
    await logSyncEvent(admin, job, "JOB_FAILOVER", outcome, {
      from_account: accountId,
      error: errMsg,
      stage: "reconcile",
    });
    return { status: outcome, error: errMsg };
  }

  try {
    const verification = await verifyDriveUpload({
      accessToken,
      fileId,
      expectedName: driveFileName(media),
      expectedParentId: folder.google_folder_id,
      expectedSize: media.file_size,
    });

    log(`Reconcile verified: ${fileId} md5=${verification.md5Checksum ?? "n/a"}`);
    await logSyncEvent(admin, job, "DRIVE_UPLOAD_VERIFIED", "OK", {
      media_id: media.id,
      account_id: accountId,
      folder_id: folder.id,
      google_folder_id: folder.google_folder_id,
      file_id: fileId,
      checks: verification.checks,
      drive_md5: verification.md5Checksum,
      drive_size_bytes: verification.sizeBytes,
      source_size_bytes: media.file_size,
      reconciled: true,
      new_upload_created: false,
    });

    await completeDriveJob(admin, job.id, {
      status: "COMPLETED",
      driveAccountId: accountId,
      driveFolderId: folder.id,
      driveFileId: fileId,
    });
    await markMediaArchived(admin, job, {
      accountId,
      folderRowId: folder.id,
      driveFileId: fileId,
      verification,
    });
    await logSyncEvent(admin, job, "JOB_COMPLETED", "COMPLETED", {
      file_id: fileId,
      account_id: accountId,
      folder_id: folder.id,
      reconciled: true,
      duplicate_file_created: false,
    });
    return { status: "COMPLETED" };
  } catch (err) {
    const errMsg = (err as Error).message;
    const mismatch = err instanceof DriveVerifyError && err.mismatch;
    log(`Reconcile failed (mismatch=${mismatch}): ${errMsg}`);

    await logSyncEvent(admin, job, "DRIVE_UPLOAD_FAILED", "VERIFY_FAILED", {
      media_id: media.id,
      account_id: accountId,
      folder_id: folder.id,
      file_id: mismatch ? null : fileId,
      reason: errMsg,
      drive_mismatch: mismatch,
      cloudinary_preserved: true,
      reconciled: true,
    });

    const backoff = computeBackoff(job, settings.retry_base_delay_seconds);
    if (mismatch) {
      // The old file id is unusable: drop it so the next attempt uploads a
      // replacement (the stale Drive file is left in place for an operator to
      // inspect rather than silently deleted).
      const { error } = await admin
        .from("replication_jobs")
        .update({
          google_drive_file_id: null,
          google_drive_upload_url: null,
          google_drive_upload_chunk: 0,
          status: "RETRYING",
          last_error: errMsg.slice(0, 4000),
          next_retry_at: new Date(Date.now() + backoff * 1000).toISOString(),
          updated_at: new Date().toISOString(),
        })
        .eq("id", job.id)
        .eq("destination_type", "google_drive");
      if (error) {
        console.error(`reconcile reset failed for ${job.id}: ${error.message}`);
      }
    } else {
      await retryDriveJobSameAccount(
        admin, job.id, accountId, folder.id, errMsg, backoff,
      );
    }
    return { status: "RETRYING", error: errMsg };
  }
}

/**
 * Records the verified archive on media_assets and queues the media for
 * Cloudinary cleanup.
 *
 * `drive_archived_at` is the media-level marker that a verified Drive copy
 * exists. `primary_cleanup_status` moves to 'cleanup_pending' — the actual
 * delete only happens once claim_cloudinary_cleanup() says every required
 * destination is complete AND the global auto-delete switch is on.
 *
 * Never touches an already-successful cleanup (idempotent).
 */
async function markMediaArchived(
  admin: AdminClient,
  job: DriveJob,
  params: {
    accountId: string;
    folderRowId: string;
    driveFileId: string;
    verification: { md5Checksum: string | null; sizeBytes: number | null };
  },
): Promise<void> {
  const { data: current } = await admin
    .from("media_assets")
    .select("id, primary_cleanup_status, drive_archived_at")
    .eq("id", job.media_id)
    .maybeSingle();

  const row = current as
    | { id: string; primary_cleanup_status: string | null; drive_archived_at: string | null }
    | null;
  if (!row) return;

  const patch: Record<string, unknown> = {
    updated_at: new Date().toISOString(),
  };
  if (!row.drive_archived_at) {
    patch.drive_archived_at = new Date().toISOString();
  }
  // Never move a successful cleanup backwards.
  if (row.primary_cleanup_status !== "cleanup_success") {
    patch.primary_cleanup_status = "cleanup_pending";
  }

  const { error } = await admin.from("media_assets").update(patch).eq("id", job.media_id);
  if (error) {
    // The archive itself is already verified and recorded on the job row, so a
    // failure here must not fail the job; the cleanup claim re-derives state
    // from the Drive job anyway.
    console.error(`markMediaArchived failed for media ${job.media_id}: ${error.message}`);
    return;
  }

  await admin.from("sync_logs").insert({
    media_id: job.media_id,
    replication_job_id: job.id,
    event_type: "DRIVE_MEDIA_ARCHIVED",
    status: "OK",
    message: null,
    metadata: {
      media_id: job.media_id,
      replication_job_id: job.id,
      drive_account_id: params.accountId,
      drive_folder_id: params.folderRowId,
      drive_file_id: params.driveFileId,
      drive_md5: params.verification.md5Checksum,
      drive_size_bytes: params.verification.sizeBytes,
      cleanup_status: patch.primary_cleanup_status ?? row.primary_cleanup_status,
    },
  });
}

/** Loads a drive_folders row by its row id. */
async function loadDriveFolder(
  admin: AdminClient,
  folderRowId: string | null,
): Promise<DriveFolderRow | null> {
  if (!folderRowId) return null;
  const { data } = await admin
    .from("drive_folders")
    .select("*")
    .eq("id", folderRowId)
    .maybeSingle();
  return (data as DriveFolderRow | null) ?? null;
}

// ─── Phase 2: Cloudinary cleanup ──────────────────────────────────────────

/**
 * Claims and processes cleanup candidates. A no-op while the global
 * auto-delete switch is off, or when nothing is eligible.
 */
async function runCleanupPhase(
  admin: AdminClient,
  started: number,
): Promise<{
  processed: number;
  results: Array<{ media_id: string; status: string; error?: string }>;
}> {
  const results: Array<{ media_id: string; status: string; error?: string }> = [];
  let processed = 0;

  while (processed < CLEANUP_BATCH_SIZE && Date.now() - started < MAX_MS) {
    const claimed = await claimNextCleanup(admin);
    if (!claimed) break;

    const outcome = await processCleanup(admin, claimed);
    processed++;
    results.push({
      media_id: claimed.id,
      status: outcome.status,
      error: outcome.error,
    });
  }

  return { processed, results };
}

/**
 * Claims the next batch of cleanup candidates. A SETOF-returning RPC comes back
 * as an array; an empty result (master switch off / nothing eligible) is an
 * empty array and must not be mistaken for an error.
 */
async function claimNextCleanup(
  admin: AdminClient,
): Promise<CleanupCandidate | null> {
  // Claim EXACTLY one row. The RPC moves the whole claimed batch to
  // 'cleanup_processing', and the claim predicate never re-selects that state,
  // so requesting a batch and then processing only rows[0] stranded the rest
  // permanently. One-at-a-time keeps claim and processing in lockstep.
  const { data, error } = await admin.rpc("claim_cloudinary_cleanup", {
    p_limit: 1,
  });

  if (error) {
    console.error("claim_cloudinary_cleanup RPC error:", error.message);
    return null;
  }
  const rows = (data as CleanupCandidate[] | null) ?? [];
  return rows[0] ?? null;
}

/**
 * Deletes the Cloudinary copy of ONE media after re-verifying its Drive archive.
 *
 * Re-verification is deliberate: cleanup must never run on the strength of a
 * stale COMPLETED flag alone. If the Drive file is gone/trashed at this moment,
 * the Cloudinary source is preserved and the cleanup is retried.
 */
async function processCleanup(
  admin: AdminClient,
  media: CleanupCandidate,
): Promise<{ status: string; error?: string }> {
  const log = (msg: string) => console.log(`[cleanup ${media.id.slice(0, 8)}] ${msg}`);
  const startedAt = Date.now();

  log(
    `Processing Cloudinary cleanup (attempt ${media.primary_cleanup_attempts}, status ${media.primary_cleanup_status})`,
  );

  const base = {
    media_id: media.id,
    file_size: media.file_size,
    attempt_count: media.primary_cleanup_attempts,
  };

  if (!media.storage_asset_id) {
    const msg = "Media has no Cloudinary asset id to delete";
    log(`FAIL (permanent): ${msg}`);
    await completeCleanup(admin, media.id, "cleanup_failed", msg);
    return { status: "cleanup_failed", error: msg };
  }

  // ── 1. Load the Drive job that authorises the delete ──────────────────
  const { data: jobRow, error: jobError } = await admin
    .from("replication_jobs")
    .select("id, drive_account_id, drive_folder_id, google_drive_file_id, status")
    .eq("media_id", media.id)
    .eq("destination_type", "google_drive")
    .maybeSingle();

  if (jobError) {
    const msg = `Drive job lookup failed: ${jobError.message}`;
    log(`TRANSIENT: ${msg}`);
    await completeCleanup(admin, media.id, "cleanup_failed", msg);
    return { status: "cleanup_failed", error: msg };
  }

  const driveJob = jobRow as {
    id: string;
    drive_account_id: string | null;
    drive_folder_id: string | null;
    google_drive_file_id: string | null;
    status: string;
  } | null;

  if (!driveJob || driveJob.status !== "COMPLETED" || !driveJob.google_drive_file_id) {
    const msg = "Refusing cleanup: no completed Drive job with a file id";
    log(`SKIP (unsafe): ${msg}`);
    await completeCleanup(admin, media.id, "cleanup_pending", msg);
    await logCleanupEvent(admin, media, driveJob?.id ?? null, "CLOUDINARY_CLEANUP_FAILED", {
      ...base,
      reason: msg,
      cloudinary_preserved: true,
    });
    return { status: "cleanup_pending", error: msg };
  }

  // ── 2. Re-verify the Drive archive immediately before deleting ────────
  let verification: DriveVerification;
  try {
    const folder = await loadDriveFolder(admin, driveJob.drive_folder_id);
    if (!folder?.google_folder_id || !driveJob.drive_account_id) {
      throw new DriveVerifyError("Drive folder mapping missing for cleanup", 0, {
        retryable: true,
      });
    }
    const accessToken = await accessTokenForAccount(admin, driveJob.drive_account_id);
    verification = await verifyDriveUpload({
      accessToken,
      fileId: driveJob.google_drive_file_id,
      expectedName: driveFileName(media),
      expectedParentId: folder.google_folder_id,
      expectedSize: media.file_size,
    });
    await logCleanupEvent(admin, media, driveJob.id, "DRIVE_UPLOAD_VERIFIED", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      checks: verification.checks,
      drive_md5: verification.md5Checksum,
      drive_size_bytes: verification.sizeBytes,
      pre_cleanup_recheck: true,
    });
  } catch (err) {
    const msg = `Pre-cleanup Drive verification failed: ${(err as Error).message}`;
    log(`ABORT (verification): ${msg}`);
    await completeCleanup(admin, media.id, "cleanup_failed", msg);
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_CLEANUP_FAILED", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      reason: msg,
      cloudinary_preserved: true,
    });
    return { status: "cleanup_failed", error: msg };
  }

  // ── 3. Delete the Cloudinary copy ─────────────────────────────────────
  const publicId = (media.storage_path ?? media.storage_asset_id ?? "").trim();
  const cloudName = getCloudinaryCloudName();
  const primaryType = resourceTypeForMime(media.mime_type);

  await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_STARTED", {
    ...base,
    file_id: driveJob.google_drive_file_id,
    operation_id: `cleanup:${media.id}:${media.primary_cleanup_attempts}`,
    cloud_name: cloudName,
    public_id: publicId || null,
    resource_type: primaryType,
    endpoint_kind: "upload_api_destroy",
    admin_api_calls: 0,
    drive_verified: true,
  });

  try {
    const result = await destroyCloudinaryAsset({
      publicId,
      mimeType: media.mime_type,
    });

    // Structured, credential-free classification of the provider answer.
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_RESPONSE", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      public_id: publicId || null,
      resource_type: result.resourceType,
      http_status: result.httpStatus,
      classification: result.deleted
        ? "deleted_confirmed"
        : result.alreadyAbsent
        ? "already_absent"
        : "unclassified",
      provider: result.provider,
    });

    // ── 3b. INDEPENDENT verification — the destroy response cannot vouch for
    // itself, and the ORIGINAL delivery URL is `immutable, max-age=2592000`, so
    // it can keep returning HTTP 200 for a deleted asset from the edge cache.
    // A genuinely fresh, never-requested derivation is probed instead: an
    // unknown transformation segment is part of the CDN cache key, so the
    // request cannot match a cached response and a 404 is real evidence of
    // absence. This consumes 0 Admin API operations (delivery tier, not API).
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_VERIFY_STARTED", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      public_id: publicId || null,
      resource_type: result.resourceType ?? primaryType,
      via: "fresh_derivation_delivery_probe",
      admin_api_calls: 0,
    });

    let verifiedAbsent = false;
    let probe: CloudinaryDeliveryProbe | null = null;
    try {
      probe = await probeCloudinaryDelivery({
        deliveryUrl: media.storage_url,
        publicId,
        resourceType: result.resourceType ?? primaryType,
      });
      verifiedAbsent = probe.state === "absent";
    } catch (probeErr) {
      await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_VERIFY_FAILED", {
        ...base,
        file_id: driveJob.google_drive_file_id,
        public_id: publicId || null,
        result: "inconclusive",
        reason: (probeErr as Error).message,
        cloudinary_preserved: true,
      });
      const msg = `Cloudinary deletion could not be independently verified: ${(probeErr as Error).message}`;
      log(`ABORT (verify inconclusive): ${msg}`);
      await completeCleanup(admin, media.id, "cleanup_failed", msg);
      await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_FAILED", {
        ...base,
        file_id: driveJob.google_drive_file_id,
        public_id: publicId || null,
        reason: msg,
        verification: "inconclusive",
        drive_copy_preserved: true,
      });
      return { status: "cleanup_failed", error: msg };
    }

    if (!verifiedAbsent) {
      const msg =
        `Cloudinary asset still retrievable after destroy (probe=${probe.state}, http=${probe.httpStatus})`;
      log(`FAIL (verify): ${msg}`);
      await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_VERIFY_FAILED", {
        ...base,
        file_id: driveJob.google_drive_file_id,
        public_id: publicId || null,
        probe_state: probe.state,
        probe_http_status: probe.httpStatus,
        probe_transform: probe.transformUsed,
        cloudinary_preserved: true,
      });
      await completeCleanup(admin, media.id, "cleanup_failed", msg);
      await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_FAILED", {
        ...base,
        file_id: driveJob.google_drive_file_id,
        public_id: publicId || null,
        reason: msg,
        verification: "still_present",
        drive_copy_preserved: true,
      });
      return { status: "cleanup_failed", error: msg };
    }

    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_VERIFY_SUCCESS", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      public_id: publicId || null,
      resource_type: probe.resourceType,
      probe_http_status: probe.httpStatus,
      probe_state: probe.state,
      probe_transform: probe.transformUsed,
      provider_reported: result.deleted
        ? "deleted"
        : result.alreadyAbsent
        ? "already_absent"
        : "unclassified",
      duration_ms: Date.now() - startedAt,
      admin_api_calls: 0,
    });

    // Every precondition passed AND the asset is independently confirmed gone.
    await completeCleanup(admin, media.id, "cleanup_success", null);
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_CLEANUP_SUCCESS", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      public_id: publicId || null,
      deleted: result.deleted,
      already_absent: result.alreadyAbsent,
      resource_type: result.resourceType,
      http_status: result.httpStatus,
      provider: result.provider,
      drive_md5: verification.md5Checksum,
      duration_ms: Date.now() - startedAt,
      independently_verified_absent: true,
      drive_copy_preserved: true,
    });
    log(
      `SUCCESS: Cloudinary asset independently verified absent (deleted=${result.deleted}, alreadyAbsent=${result.alreadyAbsent})`,
    );
    return { status: "cleanup_success" };
  } catch (err) {
    const msg = (err as Error).message;
    const retryable = !(err instanceof CloudinaryDeleteError) || err.retryable;
    log(`FAIL: ${msg}${retryable ? " (retryable)" : " (permanent)"}`);

    // Preserve the raw provider answer even when classification threw. An
    // unexpected 2xx carries the most diagnostic value and used to be lost.
    if (err instanceof CloudinaryDeleteError && err.provider) {
      await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_RESPONSE", {
        ...base,
        file_id: driveJob.google_drive_file_id,
        operation_id: `cleanup:${media.id}:${media.primary_cleanup_attempts}`,
        public_id: err.provider.publicId ?? (publicId || null),
        resource_type: err.provider.resourceType ?? primaryType,
        http_status: err.provider.httpStatus,
        classification: err.provider.classification,
        provider_raw: err.provider.raw,
        duration_ms: Date.now() - startedAt,
      });
    }

    await completeCleanup(admin, media.id, "cleanup_failed", msg);
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_CLEANUP_FAILED", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      reason: msg,
      retryable,
      provider_status: err instanceof CloudinaryDeleteError ? err.status : null,
      cloudinary_preserved: true,
      drive_copy_preserved: true,
    });
    await logCleanupEvent(admin, media, driveJob.id, "CLOUDINARY_DELETE_FAILED", {
      ...base,
      file_id: driveJob.google_drive_file_id,
      public_id: (media.storage_path ?? media.storage_asset_id ?? null),
      reason: msg,
      provider_status: err instanceof CloudinaryDeleteError ? err.status : null,
      retryable,
      verification: "not_attempted_after_delete_failure",
      drive_copy_preserved: true,
    });
    return { status: "cleanup_failed", error: msg };
  }
}

/** Best-effort Cloudinary resource type hint for observability only. */
function resourceTypeHint(mimeType: string | null): string {
  const mime = (mimeType ?? "").toLowerCase();
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/") || mime.startsWith("audio/")) return "video";
  return "raw";
}

/** Single atomic cleanup outcome transition. */
async function completeCleanup(
  admin: AdminClient,
  mediaId: string,
  status: "cleanup_success" | "cleanup_failed" | "cleanup_pending",
  error: string | null,
): Promise<void> {
  const { error: rpcError } = await admin.rpc("complete_cloudinary_cleanup", {
    p_media_id: mediaId,
    p_status: status,
    p_error: error ? error.slice(0, 2000) : null,
  });
  if (rpcError) {
    console.error(`complete_cloudinary_cleanup failed for ${mediaId}: ${rpcError.message}`);
  }
}

/**
 * Structured, secret-free cleanup logging into the existing sync_logs audit
 * stream. Never records a Cloudinary secret, signature or full URL.
 */
async function logCleanupEvent(
  admin: AdminClient,
  media: CleanupCandidate,
  jobId: string | null,
  eventType: string,
  metadata: Record<string, unknown>,
): Promise<void> {
  const { error } = await admin.from("sync_logs").insert({
    media_id: media.id,
    replication_job_id: jobId,
    event_type: eventType,
    status: eventType === "CLOUDINARY_CLEANUP_SUCCESS" ||
        eventType === "DRIVE_UPLOAD_VERIFIED"
      ? "OK"
      : null,
    message: null,
    metadata,
  });
  if (error) {
    console.error(`sync_logs insert failed (${eventType}):`, error.message);
  }
}

// ─── Load helpers ─────────────────────────────────────────────────────────

async function loadMedia(
  admin: AdminClient,
  job: DriveJob,
): Promise<MediaAsset | null> {
  const { data, error } = await admin
    .from("media_assets")
    .select(
      "id, owner_id, file_name, mime_type, file_size, storage_url, storage_asset_id, status",
    )
    .eq("id", job.media_id)
    .maybeSingle();

  if (error || !data) return null;
  const row = data as unknown as MediaAsset;
  if (row.status !== "READY") return null;
  if (typeof row.file_size !== "number" || row.file_size < 0) return null;
  return row;
}

// ─── Lifecycle helpers (state only, single atomic UPDATE each) ────────────

async function completeDriveJob(
  admin: AdminClient,
  jobId: string,
  params: {
    status: string;
    driveAccountId?: string | null;
    driveFolderId?: string | null;
    driveFileId?: string | null;
    error?: string | null;
    nextRetryAt?: string | null;
    uploadUrl?: string | null;
    uploadChunk?: number | null;
  },
): Promise<void> {
  const { error } = await admin.rpc("complete_drive_job", {
    p_job_id: jobId,
    p_status: params.status,
    p_last_error: (params.error ?? null)?.slice(0, 4000) ?? null,
    p_drive_account_id: params.driveAccountId ?? null,
    p_drive_folder_id: params.driveFolderId ?? null,
    p_google_drive_file_id: params.driveFileId ?? null,
    p_next_retry_at: params.nextRetryAt ?? null,
    p_upload_url: params.uploadUrl ?? null,
    p_upload_chunk: params.uploadChunk ?? null,
    p_upload_attempts: null,
  });
  if (error) {
    console.error(`complete_drive_job failed for ${jobId}: ${error.message}`);
  }
}

async function retryDriveJobSameAccount(
  admin: AdminClient,
  jobId: string,
  driveAccountId: string | null,
  driveFolderId: string | null,
  errorMessage: string,
  delaySeconds: number,
  resumeAtByte?: number | null,
): Promise<void> {
  const patch: Record<string, unknown> = {
    status: "RETRYING",
    next_retry_at: new Date(Date.now() + delaySeconds * 1000).toISOString(),
    last_error: errorMessage.slice(0, 4000),
    updated_at: new Date().toISOString(),
  };
  if (resumeAtByte != null) {
    // Keep the persisted resumable session so the next claim resumes it.
    patch.google_drive_upload_chunk = resumeAtByte;
  }
  const { error } = await admin
    .from("replication_jobs")
    .update(patch)
    .eq("id", jobId)
    .eq("destination_type", "google_drive");
  if (error) {
    console.error(`retryDriveJobSameAccount failed for ${jobId}:`, error.message);
  }
}

/** Marks the account, gives the reservation back and moves the job to another account. */
async function accountFailover(
  admin: AdminClient,
  job: DriveJob,
  account: { id: string },
  settings: AppSettings,
  errorMessage: string,
  decision: FailureDecision,
): Promise<"RETRYING" | "FAILED"> {
  await markDriveAccountResult(admin, account.id, {
    healthStatus: decision.health ?? "degraded",
    status: decision.accountStatus ?? "error",
    lastError: errorMessage.slice(0, 500),
  }).catch(() => {});

  // The reservation must be released with the media's real size. Awaiting the
  // hint matters: passing the promise itself made release_drive_quota receive
  // NaN, so a failed-over account kept the bytes reserved forever and slowly
  // dropped out of the router's eligibility window.
  const reservedBytes = await jobMediaBytesHint(admin, job);

  if (job.attempt_count >= settings.max_retry) {
    await releaseDriveQuota(admin, account.id, reservedBytes)
      .catch(() => {});
    await completeDriveJob(admin, job.id, { status: "FAILED", error: errorMessage });
    await logSyncEvent(admin, job, "JOB_FAILED", "FAILED", { reason: errorMessage });
    return "FAILED";
  }

  await releaseDriveQuota(admin, account.id, reservedBytes)
    .catch(() => {});

  const backoff = decision.retrySeconds ??
    computeBackoff(job, settings.retry_base_delay_seconds);
  const { error } = await admin.rpc("failover_drive_replication_job", {
    p_job_id: job.id,
    p_failed_account_id: account.id,
    p_error: errorMessage.slice(0, 4000),
    p_next_retry_at: new Date(Date.now() + backoff * 1000).toISOString(),
  });
  if (error) {
    console.error(`failover_drive_replication_job failed: ${error.message}`);
  }
  return "RETRYING";
}

/** Persists resumable progress + heartbeat while the job stays PROCESSING. */
async function persistUploadProgress(
  admin: AdminClient,
  jobId: string,
  progress: ChunkProgress,
): Promise<void> {
  const { error } = await admin
    .from("replication_jobs")
    .update({
      google_drive_upload_url: progress.uploadUrl,
      google_drive_upload_chunk: progress.bytesSent,
      started_at: new Date().toISOString(),
      last_error: "Upload in progress",
      updated_at: new Date().toISOString(),
    })
    .eq("id", jobId)
    .eq("destination_type", "google_drive");
  if (error) {
    console.error(`persistUploadProgress failed for ${jobId}:`, error.message);
  }
}

async function clearUploadSession(
  admin: AdminClient,
  jobId: string,
): Promise<void> {
  await admin
    .from("replication_jobs")
    .update({
      google_drive_upload_url: null,
      google_drive_upload_chunk: 0,
      updated_at: new Date().toISOString(),
    })
    .eq("id", jobId);
}

/** Best-effort media size read for quota release during failover. */
async function jobMediaBytesHint(
  admin: AdminClient,
  job: DriveJob,
): Promise<number> {
  try {
    const { data } = await admin
      .from("media_assets")
      .select("file_size")
      .eq("id", job.media_id)
      .maybeSingle();
    return typeof (data as { file_size?: number } | null)?.file_size === "number"
      ? (data as { file_size: number }).file_size
      : 0;
  } catch {
    return 0;
  }
}

// ─── Failure classification ───────────────────────────────────────────────

type FailureDecision = {
  kind: "transient_retry" | "restart_session" | "failover" | "permanent" |
    "uncertain";
  message: string;
  retrySeconds: number | null;
  health?: "healthy" | "degraded" | "unhealthy";
  accountStatus?:
    | "active" | "quota_full" | "reauth_required" | "disabled" | "error";
};

function decideFailure(err: unknown): FailureDecision {
  const msg = ((err as Error).message ?? String(err)).slice(0, 1000);

  // Post-upload verification problems: a transport/5xx failure is worth
  // retrying on the same account; an authorization failure means the account
  // needs reconnecting. Either way the Cloudinary source is preserved.
  if (err instanceof DriveVerifyError) {
    if (err.status === 401 || err.status === 403) {
      return {
        kind: "failover",
        message: msg,
        retrySeconds: null,
        health: "unhealthy",
        accountStatus: "reauth_required",
      };
    }
    if (err.status === 429) {
      return { kind: "transient_retry", message: msg, retrySeconds: 60, health: "degraded" };
    }
    return { kind: "transient_retry", message: msg, retrySeconds: null };
  }

  if (err instanceof DriveUploadUncertainError) {
    return { kind: "uncertain", message: msg, retrySeconds: 30 };
  }
  if (err instanceof DriveUploadExpiredError) {
    return { kind: "restart_session", message: msg, retrySeconds: 30 };
  }
  if (err instanceof DriveUploadError) {
    if (err.quotaExceeded) {
      return {
        kind: "failover",
        message: msg,
        retrySeconds: null,
        health: "degraded",
        accountStatus: "quota_full",
      };
    }
    if (err.status === 401 || err.status === 403) {
      return {
        kind: "failover",
        message: msg,
        retrySeconds: null,
        health: "unhealthy",
        accountStatus: "reauth_required",
      };
    }
    if (err.status === 404 || err.status === 410) {
      return { kind: "restart_session", message: msg, retrySeconds: 30 };
    }
    if (err.status === 429) {
      return {
        kind: "transient_retry",
        message: msg,
        retrySeconds: err.retryAfterSeconds ?? 60,
        health: "degraded",
      };
    }
    if (err.status >= 500) {
      return {
        kind: "transient_retry",
        message: msg,
        retrySeconds: null,
        health: "degraded",
      };
    }
    if (err.status === 400) {
      return { kind: "restart_session", message: msg, retrySeconds: 30 };
    }
    return { kind: "permanent", message: msg, retrySeconds: null };
  }

  // Non-Drive errors (Supabase lookups, folder resolution, OAuth exchange…)
  const hasHttp = /HTTP (\d{3})/.exec(msg);
  const httpStatus = hasHttp ? Number(hasHttp[1]) : 0;

  if (/refresh token|oauth|credential|no stored credentials|access token/i.test(msg)) {
    return {
      kind: "failover",
      message: msg,
      retrySeconds: null,
      health: "unhealthy",
      accountStatus: "reauth_required",
    };
  }
  if (/storage ?quota|enough storage|storageQuotaExceeded/i.test(msg)) {
    return {
      kind: "failover",
      message: msg,
      retrySeconds: null,
      health: "degraded",
      accountStatus: "quota_full",
    };
  }
  if (httpStatus === 401 || httpStatus === 403) {
    return {
      kind: "failover",
      message: msg,
      retrySeconds: null,
      health: "unhealthy",
      accountStatus: "reauth_required",
    };
  }
  if (httpStatus >= 500) {
    return { kind: "transient_retry", message: msg, retrySeconds: null, health: "degraded" };
  }
  if (httpStatus === 429) {
    return { kind: "transient_retry", message: msg, retrySeconds: 60, health: "degraded" };
  }
  if (/cloudinary|fetch failed|timeout|abort|network|socket/i.test(msg)) {
    return { kind: "transient_retry", message: msg, retrySeconds: null };
  }
  return { kind: "transient_retry", message: msg, retrySeconds: null };
}

// ─── Observability ────────────────────────────────────────────────────────

async function logSyncEvent(
  admin: AdminClient,
  job: DriveJob,
  eventType: string,
  status: string,
  metadata: Record<string, unknown>,
): Promise<void> {
  const { error } = await admin.from("sync_logs").insert({
    media_id: job.media_id,
    replication_job_id: job.id,
    event_type: eventType,
    status,
    message: null,
    metadata,
  });
  if (error) {
    console.error(`sync_logs insert failed (${eventType}):`, error.message);
  }
}

// ─── Naming / backoff helpers ─────────────────────────────────────────────

function driveFileName(media: MediaAsset): string {
  const raw = (media.file_name ?? "")
    .trim()
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/_+/g, "_")
    .slice(0, 180);
  const base = raw && !raw.startsWith(".") ? raw : `media_${media.id}`;
  const withExt = base.includes(".") ? base : base + extForMime(media.mime_type);
  const tag = `media_${media.id.slice(0, 8)}`; // avoids cross-upload collisions
  const dot = withExt.lastIndexOf(".");
  return dot > 0
    ? `${withExt.slice(0, dot)}_${tag}${withExt.slice(dot)}`
    : `${withExt}_${tag}`;
}

function extForMime(mime: string): string {
  const m = (mime ?? "").toLowerCase();
  if (m.includes("png")) return ".png";
  if (m.includes("gif")) return ".gif";
  if (m.includes("webp")) return ".webp";
  if (m.includes("heic")) return ".heic";
  if (m.includes("heif")) return ".heif";
  if (m.includes("mp4")) return ".mp4";
  if (m.includes("quicktime")) return ".mov";
  if (m.includes("webm")) return ".webm";
  if (m.includes("bmp")) return ".bmp";
  if (m.includes("jpeg") || m.includes("jpg")) return ".jpg";
  return "";
}

function computeBackoff(job: DriveJob, baseDelaySeconds: number): number {
  const base = baseDelaySeconds ?? 60;
  return base * Math.pow(2, Math.max(0, job.attempt_count - 1));
}

// ─── Response helper ──────────────────────────────────────────────────────

function jsonResponse(payload: unknown, status: number): Response {
  return new Response(JSON.stringify(payload), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
    status,
  });
}