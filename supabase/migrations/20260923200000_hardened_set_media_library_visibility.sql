-- 20260923200000_media_visibility_guard_trigger.sql
--
-- Production hardening follow-up to 20260923000300 / 20260923000400.
--
-- 20260923000400 already hardened `set_media_library_visibility` to return the
-- number of rows actually updated and to raise `media_not_found` on a missing
-- or foreign id. The Android client (MediaAssetsRepository) decodes that
-- contract, so the function signature and return type must NOT change.
--
-- This migration adds defense in depth on top of the column-level privileges:
-- a BEFORE UPDATE trigger on public.media_assets that rejects any direct
-- `UPDATE ... SET user_hidden_at` issued by a non-definer role, even if the
-- column-level grant is ever accidentally widened. Library visibility changes
-- must go through `set_media_library_visibility()` (SECURITY DEFINER, where
-- current_user is the function owner and the trigger permits the write).
--
-- No lifecycle behavior changes: status, deleted_at, replication, cleanup, and
-- the Cloudinary/Drive/Telegram paths are untouched.

-- ---------------------------------------------------------------------------
-- Guard: block direct writes to media_assets.user_hidden_at by client roles.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.guard_media_assets_user_hidden_at()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Trusted server-side contexts: the table owner (postgres /
    -- supabase_admin / platform owner) and service_role. SECURITY DEFINER
    -- functions such as set_media_library_visibility() run as the function
    -- owner, so they pass this check and are allowed through.
    IF current_user IN ('service_role', 'postgres', 'supabase_admin')
       OR pg_has_role(current_user, 'pg_database_owner', 'member') THEN
        RETURN NEW;
    END IF;

    -- Normal authenticated users must never write user_hidden_at directly.
    IF NEW.user_hidden_at IS DISTINCT FROM OLD.user_hidden_at THEN
        RAISE EXCEPTION
            'Direct modification of media_assets.user_hidden_at is not permitted; use set_media_library_visibility()'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_media_assets_guard_user_hidden_at ON public.media_assets;
CREATE TRIGGER trg_media_assets_guard_user_hidden_at
    BEFORE UPDATE OF user_hidden_at ON public.media_assets
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_media_assets_user_hidden_at();

-- The trigger function is never called by clients.
REVOKE ALL ON FUNCTION public.guard_media_assets_user_hidden_at() FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON FUNCTION public.guard_media_assets_user_hidden_at() FROM anon;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON FUNCTION public.guard_media_assets_user_hidden_at() FROM authenticated;
    END IF;
END $$;
