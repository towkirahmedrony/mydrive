-- 20260916000700_drive_worker_rows.sql
-- Server-side Drive replication worker support.
--
-- Mirrors the Telegram worker pattern (claim_telegram_job /
-- complete_telegram_job) for Google Drive:
--   1. claim_drive_job()       — atomically claim the next PENDING/RETRYING
--                                Drive job. SELECT ... FOR UPDATE SKIP LOCKED
--                                prevents concurrent workers from double
--                                processing the same row.
--   2. complete_drive_job()    — terminal/retry outcome in ONE atomic UPDATE.
--   3. Native resumable-upload columns on replication_jobs so a worker that
--      dies mid-upload can resume via Google's resumable session URI instead
--      of restarting from byte zero.
--
-- This migration only manages job state. Media is not uploaded here.

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. Resumable upload state on the single Drive job per media
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE public.replication_jobs
    ADD COLUMN IF NOT EXISTS google_drive_upload_url       text,
    ADD COLUMN IF NOT EXISTS google_drive_upload_chunk     integer DEFAULT 0,
    ADD COLUMN IF NOT EXISTS google_drive_upload_attempts  integer DEFAULT 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. claim_drive_job() — atomic claim of the next eligible Drive job
-- ═══════════════════════════════════════════════════════════════════════════

-- Claims one eligible google_drive job:
--   * PENDING / RETRYING whose retry delay has elapsed, or
--   * a PROCESSING row whose started_at went stale (worker crashed mid-upload;
--     kept fresh by the worker's per-chunk heartbeat). FOR UPDATE SKIP LOCKED
--     means a row claimed by another worker is skipped, never blocked.
--
-- Resumable state (google_drive_upload_url / google_drive_upload_chunk) is
-- intentionally preserved so a retried upload resumes where it stopped.
-- Returns the claimed row with status = PROCESSING and attempt_count
-- incremented (first attempt = 1).
CREATE OR REPLACE FUNCTION public.claim_drive_job()
RETURNS public.replication_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_job public.replication_jobs%ROWTYPE;
BEGIN
    SELECT rj.*
    INTO v_job
    FROM public.replication_jobs rj
    WHERE rj.destination_type = 'google_drive'
      AND (
          (rj.status IN ('PENDING', 'RETRYING')
           AND (rj.next_retry_at IS NULL OR rj.next_retry_at <= now()))
          OR
          (rj.status = 'PROCESSING'
           AND rj.started_at IS NOT NULL
           AND rj.started_at < now() - interval '90 seconds')
      )
    ORDER BY rj.created_at ASC, rj.id ASC
    LIMIT 1
    FOR UPDATE SKIP LOCKED;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    UPDATE public.replication_jobs
    SET status        = 'PROCESSING',
        attempt_count = attempt_count + 1,
        started_at    = now(),
        updated_at    = now()
    WHERE id = v_job.id;

    v_job.status        := 'PROCESSING';
    v_job.attempt_count := v_job.attempt_count + 1;
    v_job.started_at    := now();
    v_job.updated_at    := now();

    RETURN v_job;
END;
$$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. complete_drive_job() — single-atomic-UPDATE job outcome
-- ═══════════════════════════════════════════════════════════════════════════

-- The worker calls this exactly once per outcome.
--
--   * COMPLETED    -> persists drive_file_id + final account/folder, clears
--                     upload state. Ignores repeat calls (idempotent).
--   * RETRYING     -> keeps the SAME media/job row, sets next_retry_at.
--                     Used for transient failures (no account change).
--   * FAILED       -> permanent terminal state.
--   * SKIPPED      -> permanent terminal state (e.g. too large for every
--                     eligible account).
--
-- p_upload_url / p_upload_chunk allow a mid-upload worker crash to leave its
-- resumable session URI and progress behind so the next attempt resumes.
-- COALESCE semantics keep previously stored upload progress intact unless the
-- caller explicitly overrides it (pass NULL to leave untouched).
CREATE OR REPLACE FUNCTION public.complete_drive_job(
    p_job_id             uuid,
    p_status             text,
    p_last_error         text DEFAULT NULL,
    p_drive_account_id   uuid DEFAULT NULL,
    p_drive_folder_id    uuid DEFAULT NULL,
    p_google_drive_file_id text DEFAULT NULL,
    p_next_retry_at      timestamptz DEFAULT NULL,
    p_upload_url         text DEFAULT NULL,
    p_upload_chunk       integer DEFAULT NULL,
    p_upload_attempts    integer DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Idempotency: a COMPLETED Drive job that a retried/duplicate worker
    -- touches must never go backwards or produce a second Drive file. When
    -- the row is already COMPLETED, re-calling with COMPLETED + the SAME file
    -- id simply refreshes timestamps (no observable duplicate work).
    IF p_status IN ('COMPLETED') THEN
        UPDATE public.replication_jobs
        SET status                      = p_status,
            drive_account_id            = COALESCE(p_drive_account_id, drive_account_id),
            drive_folder_id             = COALESCE(p_drive_folder_id, drive_folder_id),
            google_drive_file_id        = COALESCE(p_google_drive_file_id, google_drive_file_id),
            google_drive_upload_url     = NULL,
            google_drive_upload_chunk   = 0,
            google_drive_upload_attempts = 0,
            last_error                  = NULL,
            next_retry_at               = NULL,
            started_at                  = NULL,
            completed_at                = now(),
            updated_at                  = now()
        WHERE id = p_job_id;
    ELSIF p_status = 'SKIPPED' THEN
        UPDATE public.replication_jobs
        SET status              = p_status,
            drive_account_id    = p_drive_account_id,
            drive_folder_id     = p_drive_folder_id,
            last_error          = p_last_error,
            next_retry_at       = NULL,
            started_at          = NULL,
            completed_at        = now(),
            updated_at          = now()
        WHERE id = p_job_id;
    ELSE
        UPDATE public.replication_jobs
        SET status                      = p_status,
            drive_account_id            = p_drive_account_id,
            drive_folder_id             = p_drive_folder_id,
            google_drive_file_id        = p_google_drive_file_id,
            last_error                  = p_last_error,
            next_retry_at               = p_next_retry_at,
            completed_at                = CASE WHEN p_status IN ('FAILED') THEN now() ELSE completed_at END,
            google_drive_upload_url     = COALESCE(p_upload_url, google_drive_upload_url),
            google_drive_upload_chunk   = COALESCE(p_upload_chunk, google_drive_upload_chunk, 0),
            google_drive_upload_attempts = COALESCE(p_upload_attempts, google_drive_upload_attempts, 0),
            updated_at                  = now()
        WHERE id = p_job_id;
    END IF;
END;
$$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. Lock down EXECUTE: backend (service_role) only
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    f record;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS sig
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.proname IN ('claim_drive_job', 'complete_drive_job')
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.sig);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', f.sig);
    END LOOP;
END $$;