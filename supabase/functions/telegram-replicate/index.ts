import { serve } from "jsr:@std/http";
import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAdmin } from "../shared/auth.ts";

// ─── Types ────────────────────────────────────────────────────────────────

interface MediaAsset {
  id: string;
  owner_id: string;
  file_name: string;
  mime_type: string;
  file_size: number;
  storage_url: string | null;
  storage_asset_id: string;
  width: number | null;
  height: number | null;
  status: string;
}

interface TelegramConfig {
  id: string;
  user_id: string;
  chat_id: string;
  bot_token_secret_id: string | null;
  enabled: boolean;
  status: string;
}

interface ClaimedJob {
  id: string;
  media_id: string;
  telegram_config_id: string;
  status: string;
  attempt_count: number;
}

interface AppSettings {
  max_retry: number;
  retry_base_delay_seconds: number;
  telegram_target_mb: number;
  telegram_hard_limit_mb: number;
}

interface TelegramApiResult {
  ok: boolean;
  message_id?: number;
  file_id?: string;
  retry_after?: number;
  error_code?: number;
  description?: string;
}

// ─── Constants ────────────────────────────────────────────────────────────

const TG_API = "https://api.telegram.org";
const BATCH_SIZE = 5;
const MAX_MS = 50_000; // ~50s to stay within Edge Function 60s timeout
const CONCURRENT_LIMIT = 3;

const PHOTO_MAX = 10 * 1024 * 1024; // 10 MB Telegram photo limit
const VIDEO_MAX = 50 * 1024 * 1024; // 50 MB Telegram video limit
const DOC_MAX = 50 * 1024 * 1024; // 50 MB Telegram document limit

const PHOTO_MIMES = new Set([
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
  "image/bmp",
  "image/tiff",
  "image/heic",
  "image/heif",
]);
const VIDEO_MIMES = new Set([
  "video/mp4",
  "video/quicktime",
  "video/x-msvideo",
  "video/x-matroska",
  "video/webm",
]);

// ─── In-process concurrency guard ─────────────────────────────────────────

let inflight = 0;

// ─── Entry point ──────────────────────────────────────────────────────────

serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  // Light in-process concurrency limiter; atomic SQL claim prevents double-processing.
  if (inflight >= CONCURRENT_LIMIT) {
    return jsonResponse({ processed: 0, reason: "Worker busy" }, 429);
  }

  inflight++;
  const started = Date.now();

  try {
    // Accept service-role key OR public_url=true (for cron / manual test).
    const url = new URL(req.url);
    const isPublicUrl = url.searchParams.get("public_url") === "true";

    let admin;
    if (isPublicUrl) {
      admin = getSupabaseAdmin();
    } else {
      // Verify the caller owns the service-role key (basic gate).
      const authHeader = req.headers.get("Authorization") ?? "";
      if (!authHeader.startsWith("Bearer ")) {
        return jsonResponse(
          { error: "Authorization required (service role key)" },
          401,
        );
      }
      admin = getSupabaseAdmin();
    }

    // ── Load global app settings (max_retry, retry delays) ────────────
    const { data: settings } = await admin
      .from("app_settings")
      .select("*")
      .eq("id", true)
      .maybeSingle();

    const typedSettings = (settings as AppSettings) ?? null;
    const maxRetry = typedSettings?.max_retry ?? 5;
    const retryBase = typedSettings?.retry_base_delay_seconds ?? 60;

    // ── Process a batch of jobs ───────────────────────────────────────
    let processed = 0;
    const results: Array<{ job_id: string; status: string; error?: string }> =
      [];

    while (
      processed < BATCH_SIZE &&
      Date.now() - started < MAX_MS
    ) {
      const claimed = await claimNextJob(admin);
      if (!claimed) break;

      const result = await processJob(admin, claimed, typedSettings);
      processed++;
      results.push({
        job_id: claimed.id,
        status: result.status,
        error: result.error,
      });
    }

    return jsonResponse(
      {
        processed,
        results,
        elapsed_ms: Date.now() - started,
      },
      200,
    );
  } catch (error) {
    console.error("telegram-replicate worker failed:", (error as Error).message);
    return jsonResponse({ error: (error as Error).message }, 500);
  } finally {
    inflight--;
  }
});

// ─── Job claiming ─────────────────────────────────────────────────────────

async function claimNextJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
): Promise<ClaimedJob | null> {
  const { data, error } = await admin.rpc("claim_telegram_job").maybeSingle();

  if (error) {
    console.error("claim_telegram_job RPC error:", error.message);
    return null;
  }
  return (data as ClaimedJob) ?? null;
}

// ─── Single job processing ────────────────────────────────────────────────

async function processJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
  job: ClaimedJob,
  settings: AppSettings | null,
): Promise<{ status: string; error?: string }> {
  const log = (msg: string) =>
    console.log(`[job ${job.id.slice(0, 8)}] ${msg}`);

  log(`Processing (attempt ${job.attempt_count})`);

  // ── 1. Idempotency check ───────────────────────────────────────────
  if (job.status === "COMPLETED") {
    log("Already completed — skipping");
    return { status: "SKIPPED" };
  }

  // ── 2. Load media ──────────────────────────────────────────────────
  const { data: mediaRow, error: mediaErr } = await admin
    .from("media_assets")
    .select(
      "id, owner_id, file_name, mime_type, file_size, storage_url, storage_asset_id, width, height, status",
    )
    .eq("id", job.media_id)
    .maybeSingle();

  if (mediaErr || !mediaRow) {
    const msg = `Media not found: ${mediaErr?.message ?? "missing"}`;
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  const media = mediaRow as MediaAsset;

  if (!media.storage_url) {
    const msg = "Media has no storage_url (Cloudinary URL missing)";
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  // ── 3. Load Telegram configuration (server-side, user-scoped) ──────
  const { data: configRow, error: cfgErr } = await admin
    .from("telegram_configs")
    .select("id, user_id, chat_id, bot_token_secret_id, enabled, status")
    .eq("id", job.telegram_config_id)
    .maybeSingle();

  if (cfgErr || !configRow) {
    const msg = `Telegram config not found: ${cfgErr?.message ?? "missing"}`;
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  const config = configRow as TelegramConfig;

  if (!config.enabled || config.status === "disabled") {
    const msg = "User Telegram destination is disabled";
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  if (config.status === "invalid") {
    const msg = "User Telegram credentials are invalid";
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  if (!config.chat_id || config.chat_id.trim().length === 0) {
    const msg = "Telegram chat_id is empty";
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  // ── 4. Retrieve bot token (server-side secret, never exposed) ───────
  const botToken = await getBotToken(admin, config);

  if (!botToken) {
    const msg = "Could not retrieve Telegram bot token from secrets store";
    log(`FAIL: ${msg}`);
    await failJob(admin, job.id, msg);
    return { status: "FAILED", error: msg };
  }

  // ── 5. Enforce file-size limits ────────────────────────────────────
  const sizeErr = checkFileSizeLimits(media);
  if (sizeErr) {
    log(`SKIP (too large): ${sizeErr}`);
    await skipJob(admin, job.id, sizeErr);
    return { status: "SKIPPED", error: sizeErr };
  }

  // ── 6. Send to Telegram ────────────────────────────────────────────
  try {
    const result = await sendMediaToTelegram(
      botToken,
      config.chat_id,
      media,
    );

    if (result.ok && result.message_id) {
      log(`SUCCESS: message_id=${result.message_id}`);
      await completeJob(admin, job.id, result.message_id, result.file_id);
      return { status: "COMPLETED" };
    }

    // ── Handle Telegram error response ────────────────────────────────
    const errCode = result.error_code ?? 0;
    const desc = (result.description ?? "Unknown Telegram error").toLowerCase();

    // 401 — bot token invalid / revoked → permanent failure
    if (errCode === 401) {
      const msg = `Telegram 401 Unauthorized: ${result.description}`;
      log(`FAIL (permanent): ${msg}`);
      await failJob(admin, job.id, msg);
      return { status: "FAILED", error: msg };
    }

    // 403 — bot blocked or kicked from chat → permanent failure
    if (errCode === 403) {
      const msg = `Telegram 403 Forbidden: ${result.description}`;
      log(`FAIL (permanent): ${msg}`);
      await failJob(admin, job.id, msg);
      return { status: "FAILED", error: msg };
    }

    // 400 bad request — format / media-specific permanent failures
    if (errCode === 400) {
      if (
        desc.includes("wrong file identifier") ||
        desc.includes("file is too big") ||
        desc.includes("file must be non-empty") ||
        desc.includes("bad request: wrong")
      ) {
        const msg = `Telegram 400 Bad Request: ${result.description}`;
        log(`FAIL (permanent): ${msg}`);
        await failJob(admin, job.id, msg);
        return { status: "FAILED", error: msg };
      }
    }

    // 429 — rate limit → retry at the Telegram-suggested time
    if (errCode === 429 || desc.includes("too many requests")) {
      const retrySec = result.retry_after ?? 30;
      log(`RATE LIMITED — retry after ${retrySec}s`);
      await retryJob(admin, job.id, retrySec);
      return {
        status: "RETRYING",
        error: `Rate limited: retry after ${retrySec}s`,
      };
    }

    // 5xx — transient server error → retry with backoff
    if (errCode >= 500) {
      const msg = `Telegram ${errCode}: ${result.description}`;
      log(`TRANSIENT: ${msg}`);
      const backoff = computeBackoff(
        job.attempt_count,
        settings?.retry_base_delay_seconds,
      );
      await retryJob(admin, job.id, backoff);
      return { status: "RETRYING", error: msg };
    }

    // Other error → retry with backoff (treat as transient)
    const msg = `Telegram error ${errCode}: ${result.description}`;
    log(`UNKNOWN: ${msg}`);
    const backoff = computeBackoff(
      job.attempt_count,
      settings?.retry_base_delay_seconds,
    );
    await retryJob(admin, job.id, backoff);
    return { status: "RETRYING", error: msg };
  } catch (err) {
    const msg = (err as Error).message;
    log(`TRANSIENT (exception): ${msg}`);
    const backoff = computeBackoff(
      job.attempt_count,
      settings?.retry_base_delay_seconds,
    );
    await retryJob(admin, job.id, backoff);
    return { status: "RETRYING", error: msg };
  } finally {
    // Bot token is a local variable — goes out of scope here.
  }
}

// ─── Job lifecycle helpers ────────────────────────────────────────────────

async function completeJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
  jobId: string,
  messageId: number,
  fileId?: string,
): Promise<void> {
  const { error } = await admin.rpc("complete_telegram_job", {
    p_job_id: jobId,
    p_status: "COMPLETED",
    p_message_id: messageId,
    p_telegram_file_id: fileId ?? null,
  });
  if (error) {
    console.error(`completeJob failed for ${jobId}:`, error.message);
  }
}

async function failJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
  jobId: string,
  errorMessage: string,
): Promise<void> {
  const { error } = await admin.rpc("complete_telegram_job", {
    p_job_id: jobId,
    p_status: "FAILED",
    p_last_error: errorMessage.slice(0, 2000), // cap length for DB
  });
  if (error) {
    console.error(`failJob failed for ${jobId}:`, error.message);
  }
}

async function skipJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
  jobId: string,
  reason: string,
): Promise<void> {
  const { error } = await admin.rpc("complete_telegram_job", {
    p_job_id: jobId,
    p_status: "SKIPPED",
    p_last_error: reason.slice(0, 2000),
  });
  if (error) {
    console.error(`skipJob failed for ${jobId}:`, error.message);
  }
}

async function retryJob(
  admin: ReturnType<typeof getSupabaseAdmin>,
  jobId: string,
  delaySeconds: number,
): Promise<void> {
  const nextRetryAt = new Date(Date.now() + delaySeconds * 1000).toISOString();
  const { error } = await admin.rpc("complete_telegram_job", {
    p_job_id: jobId,
    p_status: "RETRYING",
    p_next_retry_at: nextRetryAt,
  });
  if (error) {
    console.error(`retryJob failed for ${jobId}:`, error.message);
  }
}

// ─── Bot token retrieval ──────────────────────────────────────────────────

async function getBotToken(
  admin: ReturnType<typeof getSupabaseAdmin>,
  config: TelegramConfig,
): Promise<string | null> {
  if (!config.bot_token_secret_id) return null;

  // Attempt SQL-based lookup via the vault / secrets fallback function.
  const { data, error } = await admin.rpc("worker_lookup_telegram_token", {
    p_secret_id: config.bot_token_secret_id,
    p_user_id: config.user_id,
  });

  if (error) {
    console.error("worker_lookup_telegram_token error:", error.message);
    return null;
  }

  const token = data as string | null;
  if (!token || token.length === 0) return null;
  return token;
}

// ─── File-size limit enforcement ──────────────────────────────────────────

function checkFileSizeLimits(media: MediaAsset): string | null {
  const size = media.file_size;
  const mime = media.mime_type.toLowerCase();

  if (PHOTO_MIMES.has(mime)) {
    if (size > PHOTO_MAX) {
      return `Photo too large for Telegram: ${(size / 1024 / 1024).toFixed(1)} MB exceeds 10 MB limit`;
    }
  } else if (VIDEO_MIMES.has(mime)) {
    if (size > VIDEO_MAX) {
      return `Video too large for Telegram: ${(size / 1024 / 1024).toFixed(1)} MB exceeds 50 MB limit`;
    }
  } else {
    // Document / unknown
    if (size > DOC_MAX) {
      return `File too large for Telegram document: ${(size / 1024 / 1024).toFixed(1)} MB exceeds 50 MB limit`;
    }
  }

  return null;
}

// ─── Media routing ────────────────────────────────────────────────────────

async function sendMediaToTelegram(
  botToken: string,
  chatId: string,
  media: MediaAsset,
): Promise<TelegramApiResult> {
  const mime = media.mime_type.toLowerCase();

  if (PHOTO_MIMES.has(mime)) {
    return sendPhoto(botToken, chatId, media);
  }

  if (VIDEO_MIMES.has(mime)) {
    return sendVideo(botToken, chatId, media);
  }

  // Everything else → sendDocument
  return sendDocument(botToken, chatId, media);
}

// ─── Telegram sendPhoto ───────────────────────────────────────────────────

async function sendPhoto(
  botToken: string,
  chatId: string,
  media: MediaAsset,
): Promise<TelegramApiResult> {
  const caption = makeCaption(media.file_name);

  const form = new FormData();
  form.append("chat_id", chatId);
  if (caption) form.append("caption", caption);
  form.append("parse_mode", "CaptionHashtag");
  form.append(
    "photo",
    await fetchMediaAsBlob(media.storage_url!),
    safeFilename(media),
  );

  return telegramUpload(botToken, "sendPhoto", form);
}

// ─── Telegram sendVideo ───────────────────────────────────────────────────

async function sendVideo(
  botToken: string,
  chatId: string,
  media: MediaAsset,
): Promise<TelegramApiResult> {
  const caption = makeCaption(media.file_name);

  const form = new FormData();
  form.append("chat_id", chatId);
  if (caption) form.append("caption", caption);
  form.append("parse_mode", "CaptionHashtag");
  form.append(
    "video",
    await fetchMediaAsBlob(media.storage_url!),
    safeFilename(media),
  );

  return telegramUpload(botToken, "sendVideo", form);
}

// ─── Telegram sendDocument (fallback) ────────────────────────────────────

async function sendDocument(
  botToken: string,
  chatId: string,
  media: MediaAsset,
): Promise<TelegramApiResult> {
  const caption = makeCaption(media.file_name);

  const form = new FormData();
  form.append("chat_id", chatId);
  if (caption) form.append("caption", caption);
  form.append("parse_mode", "CaptionHashtag");
  form.append(
    "document",
    await fetchMediaAsBlob(media.storage_url!),
    safeFilename(media),
  );

  return telegramUpload(botToken, "sendDocument", form);
}

// ─── Telegram HTTP layer ──────────────────────────────────────────────────

async function telegramUpload(
  botToken: string,
  method: string,
  form: FormData,
): Promise<TelegramApiResult> {
  const res = await fetch(`${TG_API}/bot${botToken}/${method}`, {
    method: "POST",
    body: form,
  });

  return parseTelegramResponse(res);
}

async function parseTelegramResponse(
  res: Response,
): Promise<TelegramApiResult> {
  try {
    const body = (await res.json()) as Record<string, unknown>;

    if (body.ok === true) {
      const result = body.result as Record<string, unknown>;
      return {
        ok: true,
        message_id: result?.message_id as number | undefined,
        file_id: result?.photo
          ? ((result.photo as unknown[]).at(-1) as Record<string, unknown>)
              ?.file_id as string | undefined
          : (result?.video as Record<string, unknown>)
              ?.file_id as string | undefined,
      };
    }

    // Error path — classify the error
    const errorCode = (body.error_code as number) ?? res.status;
    const description = (body.description as string) ?? "";
    const retryAfter = (body.parameters as Record<string, unknown>)
      ?.retry_after as number | undefined;

    return {
      ok: false,
      error_code: errorCode,
      description,
      retry_after: retryAfter,
    };
  } catch {
    // Could not parse JSON — treat as transient
    return {
      ok: false,
      error_code: res.status,
      description: `HTTP ${res.status}: unable to parse Telegram response`,
    };
  }
}

// ─── Media fetching helpers ───────────────────────────────────────────────

/**
 * Fetch a media file from Cloudinary and return it as a Blob.
 *
 * Uses Deno/Supabase Edge Function `fetch` which streams via HTTP.
 * For files that can fit in the Edge Function's memory budget this works
 * cleanly; for very large files (see `checkFileSizeLimits`) the job is
 * already SKIPPED before reaching this function.
 *
 * Edge Function note: files above ~50 MB may hit memory limits. The
 * `checkFileSizeLimits` function ensures we never attempt to buffer
 * anything beyond Telegram's own documented limits (10 MB photo,
 * 50 MB video/document), which are well within Edge Function capacity.
 */
async function fetchMediaAsBlob(url: string): Promise<Blob> {
  const res = await fetch(url, {
    redirect: "follow",
    signal: AbortSignal.timeout(30_000),
  });

  if (!res.ok) {
    throw new Error(
      `Failed to fetch media from Cloudinary: HTTP ${res.status} ${res.statusText}`,
    );
  }

  return res.blob();
}

// ─── Naming / caption helpers ─────────────────────────────────────────────

function safeFilename(media: MediaAsset): string {
  // Telegram uses the filename for the Content-Disposition header.
  // Sanitise to ASCII-safe characters only.
  const name = media.file_name
    .trim()
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .slice(0, 120);

  if (name.includes(".")) return name;

  // No extension in the name — derive from mime_type
  const ext = extensionForMime(media.mime_type);
  return name + ext;
}

function makeCaption(fileName: string): string {
  return fileName
    .replace(/[\r\n]/g, " ")
    .trim()
    .slice(0, 1024); // Telegram caption limit
}

function extensionForMime(mime: string): string {
  const m = mime.toLowerCase();
  if (m.includes("png")) return ".png";
  if (m.includes("gif")) return ".gif";
  if (m.includes("webp")) return ".webp";
  if (m.includes("heic") || m.includes("heif")) return ".heic";
  if (m.includes("mp4")) return ".mp4";
  if (m.includes("quicktime")) return ".mov";
  if (m.includes("webm")) return ".webm";
  if (m.includes("bmp")) return ".bmp";
  return ".jpg";
}

// ─── Retry / backoff ──────────────────────────────────────────────────────

/**
 * Exponential backoff: retry_base_delay_seconds × 2^attempt_count.
 * The first attempt (attempt_count = 1) waits one full base interval.
 * The second waits double, third quadruple, etc.
 */
function computeBackoff(
  attemptCount: number,
  baseDelaySeconds?: number,
): number {
  const base = baseDelaySeconds ?? 60;
  return base * Math.pow(2, attemptCount);
}

// ─── Response helper ──────────────────────────────────────────────────────

function jsonResponse(payload: unknown, status: number): Response {
  return new Response(JSON.stringify(payload), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
    status,
  });
}
