-- 1. sync_logs: event/audit log for replication activity (Telegram + Drive
--    workers write here; schema documented in MYDRIVE_SCHEMA.md). Idempotent.
CREATE TABLE IF NOT EXISTS public.sync_logs (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    media_id            uuid REFERENCES public.media_assets(id) ON DELETE SET NULL,
    replication_job_id  uuid REFERENCES public.replication_jobs(id) ON DELETE SET NULL,
    event_type          text NOT NULL,
    status              text,
    message             text,
    metadata            jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS sync_logs_media_idx
    ON public.sync_logs (media_id);

CREATE INDEX IF NOT EXISTS sync_logs_job_idx
    ON public.sync_logs (replication_job_id);

ALTER TABLE public.sync_logs ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'sync_logs'
          AND policyname = 'sync_logs_select_own_media'
    ) THEN
        CREATE POLICY sync_logs_select_own_media ON public.sync_logs
            FOR SELECT
            USING (private.is_admin() OR (
                EXISTS (
                    SELECT 1 FROM public.media_assets ma
                    WHERE ma.id = sync_logs.media_id
                      AND ma.owner_id = auth.uid()
                )
            ));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'sync_logs'
          AND policyname = 'sync_logs_admin_all'
    ) THEN
        CREATE POLICY sync_logs_admin_all ON public.sync_logs
            FOR ALL
            USING (private.is_admin())
            WITH CHECK (private.is_admin());
    END IF;
END;
$$;

-- Service role bypasses RLS by default; no extra grants needed for workers.