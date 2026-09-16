-- 20260916000300_telegram_worker.sql
-- Server-side Telegram replication worker support.
--
-- Creates atomic SQL functions for safe job claiming and lifecycle updates
-- so concurrent workers cannot double-process the same job.
-- Reads bot tokens from the Supabase vault (vault.decrypted_secrets) or
-- from environment-variable secrets configured in the Edge Function settings.

-- 1. claim_telegram_job() — atomically claim a PENDING or RETRYING Telegram job.
--
--    SELECT FOR UPDATE prevents concurrent claims on the same row.
--    Only jobs whose next_retry_at is NULL or in the past are eligible.
--    Returns the claimed job row (status updated to PROCESSING, attempt_count incremented).
--
--    Security: uses SECURITY DEFINER so the Supabase service-role key can
--    call this function to update admin-only rows without triggering RLS.
CREATE OR REPLACE FUNCTION public.claim_telegram_job()
RETURNS public.replication_jobs
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  claimed_job public.replication_jobs%ROWTYPE;
BEGIN
  SELECT rj.*
  INTO claimed_job
  FROM public.replication_jobs rj
  WHERE rj.destination_type = 'telegram'
    AND rj.status IN ('PENDING', 'RETRYING')
    AND (rj.next_retry_at IS NULL OR rj.next_retry_at <= now())
  ORDER BY rj.created_at ASC
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
  WHERE id = claimed_job.id;

  claimed_job.status        := 'PROCESSING';
  claimed_job.attempt_count := claimed_job.attempt_count + 1;
  claimed_job.started_at    := now();
  claimed_job.updated_at    := now();

  RETURN claimed_job;
END;
$$;


-- 2. complete_telegram_job() — mark a job COMPLETED or FAILED/RETRYING with result metadata.
--
--    The worker calls this with all outcome data in a single atomic UPDATE.
--    When RETRYING, the caller must set next_retry_at so the worker skips
--    the job until the delay has elapsed.
CREATE OR REPLACE FUNCTION public.complete_telegram_job(
  p_job_id        uuid,
  p_status        text,
  p_last_error    text DEFAULT NULL,
  p_message_id    bigint DEFAULT NULL,
  p_telegram_file_id text DEFAULT NULL,
  p_next_retry_at timestamptz DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  UPDATE public.replication_jobs
  SET status           = p_status,
      last_error       = p_last_error,
      telegram_message_id  = COALESCE(p_message_id, telegram_message_id),
      telegram_file_id     = COALESCE(p_telegram_file_id, telegram_file_id),
      next_retry_at    = p_next_retry_at,
      completed_at     = CASE WHEN p_status IN ('COMPLETED', 'FAILED') THEN now() ELSE completed_at END,
      updated_at       = now()
  WHERE id = p_job_id;
END;
$$;


-- 3. worker_lookup_telegram_token() — retrieve a bot token from the vault or env-secrets.
--
--    Preference order:
--      1. Supabase vault  (vault.decrypted_secrets, if the extension is installed)
--      2. Supabase secrets table  (secrets.value column, if it exists)
--      3. Edge Function environment variable  TELEGRAM_BOT_TOKEN_USER_<user_id>
--
--    SECURITY DEFINER allows bypassing RLS on vault/secrets tables.
--    The returned value is the plaintext token; the caller MUST NOT log it.
CREATE OR REPLACE FUNCTION public.worker_lookup_telegram_token(
  p_secret_id  uuid,
  p_user_id    uuid
)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  token text;
BEGIN
  -- 1. Supabase vault
  BEGIN
    SELECT secret INTO token
    FROM vault.decrypted_secrets
    WHERE id = p_secret_id
    LIMIT 1;

    IF token IS NOT NULL AND length(token) > 0 THEN
      RETURN token;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    NULL;  -- vault extension not installed; continue
  END;

  -- 2. Fallback: secrets table (some Supabase installs use this schema)
  BEGIN
    SELECT value INTO token
    FROM secrets
    WHERE id = p_secret_id
    LIMIT 1;

    IF token IS NOT NULL AND length(token) > 0 THEN
      RETURN token;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    NULL;  -- table doesn't exist; continue
  END;

  -- 3. Fallback: environment variable secret stored as a Supabase secret
  --    Set via: supabase secrets set TELEGRAM_BOT_TOKEN_USER_<uuid>=<token>
  BEGIN
    token := current_setting('app.settings.telegram_bot_token_user_' || p_user_id::text, true);

    IF token IS NOT NULL AND length(token) > 0 THEN
      RETURN token;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    NULL;
  END;

  RETURN NULL;
END;
$$;


-- 4. Worker lookup indexes (only add if they don't already exist).
CREATE INDEX IF NOT EXISTS replication_jobs_pending_retry_idx
  ON public.replication_jobs (next_retry_at, created_at)
  WHERE destination_type = 'telegram' AND status IN ('PENDING', 'RETRYING');

CREATE INDEX IF NOT EXISTS replication_jobs_user_idx
  ON public.replication_jobs (telegram_config_id)
  WHERE destination_type = 'telegram';
