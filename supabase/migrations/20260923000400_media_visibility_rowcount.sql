-- 20260923000400_media_visibility_rowcount.sql
--
-- Production hardening follow-up to 20260923000300: make Move to Trash / Restore
-- (hide / unhide from the library) deterministic and verifiable.
--
-- The previous revision of `set_media_library_visibility` returned `void` and
-- deliberately treated a zero-row update as a silent no-op, so a stale or
-- foreign media id would still produce an HTTP 200 and the client reported
-- "Success" without the intended row being changed.
--
-- This migration redefines the function to:
--   * RETURN the number of rows actually updated (integer, one of 0 or 1),
--   * raise a real exception when the media id does not exist at all
--     (`media_not_found`, SQLSTATE P0002-class P0001 text) so "row not found"
--     can never be reported as success, and
--   * keep the identical security model: SECURITY DEFINER with an explicit
--     ownership / admin re-check, and the same grants.
--
-- The client (MediaAssetsRepository) decodes this integer and maps:
--   1 -> Success, P0001 "media_not_found" -> NotFound, other errors -> Failed.
--
-- Note on foreign ids: a media id owned by another user raises media_not_found
-- too. That is deliberate - the existence of another user's row must not be
-- disclosed to the caller, and the mutation is equally refused.

CREATE OR REPLACE FUNCTION public.set_media_library_visibility(
    p_media_id uuid,
    p_hidden   boolean
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_updated integer;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    -- SECURITY DEFINER bypasses RLS, so ownership is enforced here.
    -- `private.is_admin()` preserves the admin capability the previous
    -- owner-or-admin RLS policy allowed.
    --
    -- Only `user_hidden_at` is written. `status`, `deleted_at`, `updated_at`,
    -- and every replication/cleanup column are left to the existing triggers
    -- and workers, so the cloud-first lifecycle semantics are unchanged.
    UPDATE public.media_assets
       SET user_hidden_at = CASE WHEN p_hidden THEN now() ELSE NULL END
     WHERE id = p_media_id
       AND (owner_id = auth.uid() OR private.is_admin());

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated = 0 THEN
        -- Either the id does not exist, or it belongs to another user. Both
        -- are refused with the same error so no information is leaked.
        RAISE EXCEPTION 'media_not_found'
            USING ERRCODE = 'P0001';
    END IF;

    RETURN v_updated;
END;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Grants: identical to 20260923000300 (the signature is unchanged, but the
-- REVOKE/GRANT statements are re-asserted defensively after the replacement).
-- ─────────────────────────────────────────────────────────────────────────────
REVOKE ALL ON FUNCTION public.set_media_library_visibility(uuid, boolean) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON FUNCTION public.set_media_library_visibility(uuid, boolean) FROM anon;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        GRANT EXECUTE ON FUNCTION public.set_media_library_visibility(uuid, boolean) TO authenticated;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        GRANT EXECUTE ON FUNCTION public.set_media_library_visibility(uuid, boolean) TO service_role;
    END IF;
END $$;
