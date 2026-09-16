-- 20260916000600_drive_replication_jobs.sql
-- Drive replication job model + admin aggregation views.
--
-- Reuses the existing `replication_jobs` queue (destination_type =
-- 'google_drive'). No media is uploaded here. This migration only guarantees:
--   1. one Drive job per media, regardless of which Drive account ends up
--      storing it (so failover to another account updates the SAME job)
--   2. a record of Drive accounts already tried, so a retry never loops back
--      to a full/unavailable account
--   3. worker lookup indexes
--   4. admin-only aggregation views across users AND Drive accounts without
--      duplicating media_assets rows

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. Extra job columns
-- ═══════════════════════════════════════════════════════════════════════════

-- Accounts already tried for this media's Drive replication. The router
-- excludes these on the next attempt (failover), so a media can move from
-- Drive Account A -> B -> C without ever creating a second job row.
ALTER TABLE public.replication_jobs
    ADD COLUMN IF NOT EXISTS failed_drive_account_ids uuid[] NOT NULL DEFAULT '{}';

-- One Drive job per media. account/folder may change over the job's lifetime,
-- so they are intentionally NOT part of the key.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.replication_jobs
        WHERE destination_type = 'google_drive'
        GROUP BY media_id
        HAVING count(*) > 1
    ) THEN
        RAISE NOTICE 'replication_jobs has duplicate google_drive jobs per media; skipping replication_jobs_media_drive_key. Resolve duplicates and re-run to enforce.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS replication_jobs_media_drive_key
            ON public.replication_jobs (media_id)
            WHERE destination_type = 'google_drive';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS replication_jobs_drive_queue_idx
    ON public.replication_jobs (status, next_retry_at, created_at)
    WHERE destination_type = 'google_drive';

CREATE INDEX IF NOT EXISTS replication_jobs_drive_account_idx
    ON public.replication_jobs (drive_account_id)
    WHERE destination_type = 'google_drive';

CREATE INDEX IF NOT EXISTS replication_jobs_drive_folder_idx
    ON public.replication_jobs (drive_folder_id)
    WHERE destination_type = 'google_drive';

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. Job lifecycle helpers (state transitions only — no uploads)
-- ═══════════════════════════════════════════════════════════════════════════

-- 2a. enqueue_drive_replication_job()
--     Idempotently creates a PENDING Drive job for a media item. The drive
--     account is selected later by the router, so the job starts unassigned.
CREATE OR REPLACE FUNCTION public.enqueue_drive_replication_job(
    p_media_id uuid
)
RETURNS public.replication_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_row public.replication_jobs%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('drive_job:' || p_media_id::text, 0));

    SELECT rj.*
    INTO v_row
    FROM public.replication_jobs rj
    WHERE rj.media_id = p_media_id
      AND rj.destination_type = 'google_drive'
    LIMIT 1
    FOR UPDATE;

    IF FOUND THEN
        RETURN v_row;
    END IF;

    INSERT INTO public.replication_jobs (
        media_id, destination_type, status, attempt_count, created_at, updated_at
    )
    VALUES (p_media_id, 'google_drive', 'PENDING', 0, now(), now())
    RETURNING * INTO v_row;

    RETURN v_row;
END;
$$;

-- 2b. assign_drive_replication_job()
--     Stores the router's selected account + resolved folder on the job, and
--     resets it to PENDING. Re-callable on failover (overwrites the previous
--     assignment for the same media's single job row).
CREATE OR REPLACE FUNCTION public.assign_drive_replication_job(
    p_job_id           uuid,
    p_drive_account_id uuid,
    p_drive_folder_id  uuid DEFAULT NULL
)
RETURNS public.replication_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_row public.replication_jobs%ROWTYPE;
BEGIN
    UPDATE public.replication_jobs
    SET drive_account_id = p_drive_account_id,
        drive_folder_id  = p_drive_folder_id,
        status           = 'PENDING',
        next_retry_at    = NULL,
        updated_at       = now()
    WHERE id = p_job_id
      AND destination_type = 'google_drive'
    RETURNING * INTO v_row;

    RETURN v_row;
END;
$$;

-- 2c. failover_drive_replication_job()
--     Records that the current Drive account failed / is unsuitable, clears
--     the assignment and marks the job RETRYING. The next router call passes
--     failed_drive_account_ids as the exclusion list, so the job lands on a
--     different eligible account without any hardcoded account order.
CREATE OR REPLACE FUNCTION public.failover_drive_replication_job(
    p_job_id             uuid,
    p_failed_account_id  uuid,
    p_error              text DEFAULT NULL,
    p_next_retry_at      timestamptz DEFAULT NULL
)
RETURNS public.replication_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_row public.replication_jobs%ROWTYPE;
BEGIN
    UPDATE public.replication_jobs
    SET failed_drive_account_ids = (
            SELECT ARRAY(
                SELECT DISTINCT unnest(
                    COALESCE(failed_drive_account_ids, '{}'::uuid[])
                    || ARRAY[p_failed_account_id]
                )
            )
        ),
        drive_account_id = NULL,
        drive_folder_id  = NULL,
        status           = 'RETRYING',
        attempt_count    = attempt_count + 1,
        last_error       = p_error,
        next_retry_at    = COALESCE(p_next_retry_at, now()),
        updated_at       = now()
    WHERE id = p_job_id
      AND destination_type = 'google_drive'
    RETURNING * INTO v_row;

    RETURN v_row;
END;
$$;

-- 2d. Look up all Drive jobs for one media (failover diagnostics / admin).
CREATE OR REPLACE FUNCTION public.list_media_drive_jobs(
    p_media_id uuid
)
RETURNS SETOF public.replication_jobs
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT rj.*
    FROM public.replication_jobs rj
    WHERE rj.media_id = p_media_id
      AND rj.destination_type = 'google_drive'
    ORDER BY rj.created_at ASC;
$$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. Admin aggregation views
-- ═══════════════════════════════════════════════════════════════════════════
--
-- security_invoker = true means the underlying table RLS applies to the
-- QUERYING user, so a normal user sees only their own aggregated row and an
-- admin (private.is_admin()) sees every user. Drive account columns resolve
-- to zero rows for normal users because drive_accounts has no user policy.

-- 3a. Per-user summary: media counts/size + Telegram & Drive replication state.
CREATE OR REPLACE VIEW public.admin_user_storage_summary
WITH (security_invoker = true) AS
SELECT
    p.id                                          AS user_id,
    p.email,
    COALESCE(ma.media_count, 0)                   AS media_count,
    COALESCE(ma.storage_used_bytes, 0)            AS storage_used_bytes,
    COALESCE(tg.total, 0)                         AS telegram_jobs,
    COALESCE(tg.completed, 0)                     AS telegram_completed,
    COALESCE(tg.failed, 0)                        AS telegram_failed,
    COALESCE(dr.total, 0)                         AS drive_jobs,
    COALESCE(dr.completed, 0)                     AS drive_completed,
    COALESCE(dr.pending, 0)                       AS drive_pending,
    COALESCE(dr.failed, 0)                        AS drive_failed,
    COALESCE(dr.account_count, 0)                 AS drive_account_count,
    COALESCE(dr.stored_bytes, 0)                  AS drive_stored_bytes
FROM public.profiles p
LEFT JOIN LATERAL (
    SELECT
        count(*)                         AS media_count,
        COALESCE(sum(x.file_size), 0)    AS storage_used_bytes
    FROM public.media_assets x
    WHERE x.owner_id = p.id
      AND x.status <> 'DELETED'
) ma ON true
LEFT JOIN LATERAL (
    SELECT
        count(*)                                                           AS total,
        count(*) FILTER (WHERE rj.status = 'COMPLETED')                    AS completed,
        count(*) FILTER (WHERE rj.status = 'FAILED')                       AS failed
    FROM public.replication_jobs rj
    JOIN public.media_assets x ON x.id = rj.media_id
    WHERE x.owner_id = p.id
      AND rj.destination_type = 'telegram'
) tg ON true
LEFT JOIN LATERAL (
    SELECT
        count(*)                                                           AS total,
        count(*) FILTER (WHERE rj.status = 'COMPLETED')                    AS completed,
        count(*) FILTER (WHERE rj.status IN ('PENDING', 'PROCESSING', 'RETRYING')) AS pending,
        count(*) FILTER (WHERE rj.status = 'FAILED')                       AS failed,
        count(DISTINCT rj.drive_account_id)                                AS account_count,
        COALESCE(sum(x.file_size) FILTER (WHERE rj.status = 'COMPLETED'), 0) AS stored_bytes
    FROM public.replication_jobs rj
    JOIN public.media_assets x ON x.id = rj.media_id
    WHERE x.owner_id = p.id
      AND rj.destination_type = 'google_drive'
) dr ON true;

-- 3b. Physical distribution of a user's Drive media across Drive accounts.
--     media_id stays authoritative on media_assets; this view never duplicates
--     media records, it only aggregates the single job row per media.
CREATE OR REPLACE VIEW public.admin_user_drive_distribution
WITH (security_invoker = true) AS
SELECT
    ma.owner_id            AS user_id,
    rj.drive_account_id,
    da.display_name,
    da.google_email,
    da.priority,
    da.status              AS drive_status,
    count(*)               AS media_count,
    COALESCE(sum(ma.file_size), 0) AS stored_bytes
FROM public.replication_jobs rj
JOIN public.media_assets ma ON ma.id = rj.media_id
LEFT JOIN public.drive_accounts da ON da.id = rj.drive_account_id
WHERE rj.destination_type = 'google_drive'
  AND rj.status = 'COMPLETED'
GROUP BY
    ma.owner_id, rj.drive_account_id, da.display_name, da.google_email,
    da.priority, da.status;

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
          AND p.proname IN (
              'enqueue_drive_replication_job',
              'assign_drive_replication_job',
              'failover_drive_replication_job',
              'list_media_drive_jobs'
          )
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.sig);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', f.sig);
    END LOOP;
END $$;
