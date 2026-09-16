-- 20260916000100_finalize_media.sql
-- Support for the finalize-media Edge Function:
--   * idempotency on media_assets.client_upload_id (one record per upload key)
--   * query support for upcoming replication jobs
--   * guaranteed user-scoped RLS on media_assets

-- 1. Idempotency key: retried/replayed finalize requests must never create a
--    duplicate media record, even when two requests race. client_upload_id is
--    a client-generated UUID v4 and is globally unique.
CREATE UNIQUE INDEX IF NOT EXISTS media_assets_client_upload_id_key
  ON public.media_assets (client_upload_id);

-- 2. Helpful for later Telegram/Drive replication jobs: find a user's ready
--    media in upload order.
CREATE INDEX IF NOT EXISTS media_assets_owner_uploaded_idx
  ON public.media_assets (owner_id, uploaded_at)
  WHERE status = 'READY';

-- 3. Row Level Security: owners can SELECT/UPDATE/DELETE their own rows and
--    INSERT rows they own; admins bypass via private.is_admin().
ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'media_assets'
      AND policyname = 'media_assets_select_own'
  ) THEN
    CREATE POLICY media_assets_select_own ON public.media_assets
      FOR SELECT
      USING (owner_id = auth.uid() OR private.is_admin());
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'media_assets'
      AND policyname = 'media_assets_insert_own'
  ) THEN
    CREATE POLICY media_assets_insert_own ON public.media_assets
      FOR INSERT
      WITH CHECK (owner_id = auth.uid() OR private.is_admin());
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'media_assets'
      AND policyname = 'media_assets_update_own'
  ) THEN
    CREATE POLICY media_assets_update_own ON public.media_assets
      FOR UPDATE
      USING (owner_id = auth.uid() OR private.is_admin())
      WITH CHECK (owner_id = auth.uid() OR private.is_admin());
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'media_assets'
      AND policyname = 'media_assets_delete_own'
  ) THEN
    CREATE POLICY media_assets_delete_own ON public.media_assets
      FOR DELETE
      USING (owner_id = auth.uid() OR private.is_admin());
  END IF;
END $$;