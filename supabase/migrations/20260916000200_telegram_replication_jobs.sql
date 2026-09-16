-- 20260916000200_telegram_replication_jobs.sql
-- Foundation for server-side Telegram replication jobs.
--
-- Reuses the existing `replication_jobs` queue and `telegram_configs`
-- destination tables documented in MYDRIVE_SCHEMA.md — nothing here uploads
-- media to Telegram. This migration only guarantees:
--   1. the queue tables exist (no-op when the live schema already has them)
--   2. a unique (media, destination, configuration) constraint so finalize
--      retries / concurrent requests can never create duplicate Telegram jobs
--   3. the documented RLS policies are in place (owners see their own media's
--      jobs; only admins can write jobs — clients can never create jobs)
--   4. worker lookup indexes for the future Telegram worker

-- 1. Destination configuration (Telegram). No-op if already present.
--    The bot token is never stored here; bot_token_secret_id references a
--    server-side secret store so tokens can never leak to Android.
CREATE TABLE IF NOT EXISTS public.telegram_configs (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL UNIQUE REFERENCES public.profiles(id) ON DELETE CASCADE,
    chat_id             text NOT NULL,
    bot_token_secret_id uuid,
    enabled             boolean NOT NULL DEFAULT true,
    status              text NOT NULL DEFAULT 'unverified'
                        CHECK (status IN ('unverified', 'active', 'invalid', 'disabled')),
    last_tested_at      timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 2. Replication job queue (Telegram now, Google Drive later). No-op if present.
CREATE TABLE IF NOT EXISTS public.replication_jobs (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_id             uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE CASCADE,
    destination_type     text NOT NULL CHECK (destination_type IN ('telegram', 'google_drive')),
    telegram_config_id   uuid REFERENCES public.telegram_configs(id) ON DELETE SET NULL,
    drive_account_id     uuid REFERENCES public.drive_accounts(id) ON DELETE SET NULL,
    drive_folder_id      uuid REFERENCES public.drive_folders(id) ON DELETE SET NULL,
    variant_id           uuid REFERENCES public.media_variants(id) ON DELETE SET NULL,
    status               text NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'RETRYING', 'SKIPPED')),
    attempt_count        integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error           text,
    next_retry_at        timestamptz,
    telegram_message_id  bigint,
    telegram_file_id     text,
    google_drive_file_id text,
    started_at           timestamptz,
    completed_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

-- 3. Job idempotency: at most one job per (media, destination, config).
--    finalize-media targets this constraint via
--    ON CONFLICT (media_id, destination_type, telegram_config_id), so
--    retried or concurrent finalize requests can never pile up duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS replication_jobs_media_destination_config_key
    ON public.replication_jobs (media_id, destination_type, telegram_config_id);

-- 4. Worker lookup support.
CREATE INDEX IF NOT EXISTS replication_jobs_queue_idx
    ON public.replication_jobs (destination_type, status, created_at);
CREATE INDEX IF NOT EXISTS replication_jobs_media_idx
    ON public.replication_jobs (media_id);

-- 5. Row Level Security (documented conventions).
ALTER TABLE public.telegram_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.replication_jobs ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    -- telegram_configs: owner can manage their own destination; admins bypass.
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'telegram_configs'
          AND policyname = 'telegram_configs_select_own'
    ) THEN
        CREATE POLICY telegram_configs_select_own ON public.telegram_configs
            FOR SELECT
            USING (user_id = auth.uid() OR private.is_admin());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'telegram_configs'
          AND policyname = 'telegram_configs_insert_own'
    ) THEN
        CREATE POLICY telegram_configs_insert_own ON public.telegram_configs
            FOR INSERT
            WITH CHECK (user_id = auth.uid() OR private.is_admin());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'telegram_configs'
          AND policyname = 'telegram_configs_update_own'
    ) THEN
        CREATE POLICY telegram_configs_update_own ON public.telegram_configs
            FOR UPDATE
            USING (user_id = auth.uid() OR private.is_admin())
            WITH CHECK (user_id = auth.uid() OR private.is_admin());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'telegram_configs'
          AND policyname = 'telegram_configs_delete_own'
    ) THEN
        CREATE POLICY telegram_configs_delete_own ON public.telegram_configs
            FOR DELETE
            USING (user_id = auth.uid() OR private.is_admin());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'telegram_configs'
          AND policyname = 'telegram_configs_admin_all'
    ) THEN
        CREATE POLICY telegram_configs_admin_all ON public.telegram_configs
            FOR ALL
            USING (private.is_admin())
            WITH CHECK (private.is_admin());
    END IF;

    -- replication_jobs: owners can SELECT their own media's jobs; admins all.
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'replication_jobs'
          AND policyname = 'replication_jobs_select_own'
    ) THEN
        CREATE POLICY replication_jobs_select_own ON public.replication_jobs
            FOR SELECT
            USING (
                private.is_admin()
                OR EXISTS (
                    SELECT 1 FROM public.media_assets ma
                    WHERE ma.id = replication_jobs.media_id
                      AND ma.owner_id = auth.uid()
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'replication_jobs'
          AND policyname = 'replication_jobs_admin_all'
    ) THEN
        CREATE POLICY replication_jobs_admin_all ON public.replication_jobs
            FOR ALL
            USING (private.is_admin())
            WITH CHECK (private.is_admin());
    END IF;
END $$;