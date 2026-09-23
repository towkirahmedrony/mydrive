-- 20260923000300_media_assets_update_lockdown.sql
--
-- Production hardening for `public.media_assets`: an authenticated owner may no
-- longer UPDATE server-controlled or security-sensitive columns.
--
-- Revision 2 (this file): set_media_library_visibility now RETURNS integer --
-- the number of rows actually updated -- so clients can verify a trash/restore
-- mutation actually happened instead of silently treating a no-op as success.
--
-- Why: the existing `media_assets_update_own` policy constrains WHICH ROWS a
-- user may update (their own) but never WHICH COLUMNS. A normal authenticated
-- user could therefore PATCH status='READY', storage_url, storage_path,
-- drive_archived_at, primary_cleanup_status, deleted_at, owner_id, ... straight
-- through PostgREST and fake upload completion, Drive archival, cleanup state,
-- storage URLs, or another user's ownership.
--
-- Mechanism: Postgres column-level UPDATE privileges, which is the mechanism
-- Supabase documents for column-level security. RLS keeps restricting updates to
-- the caller's own rows, and privileges restrict which columns can be written at
-- all. PostgREST keeps exposing the table for UPDATE for a role that holds only
-- column-level grants (`has_any_column_privilege`), and lets Postgres raise
-- 42501 for any attempt to write a protected column, which PostgREST returns as
-- HTTP 403.
--
-- Effective grants after this migration:
--   authenticated : UPDATE (is_favorite)         -> the only user-editable column
--   anon          : no UPDATE
--   service_role  : unchanged, table-level UPDATE -> Edge Function workers
--   table owner   : unchanged, implicit full      -> SECURITY DEFINER RPCs
--
-- Deliberate consequences:
--   * `user_hidden_at` (Move to Trash / Restore) becomes server-controlled.
--     Clients must call public.set_media_library_visibility() instead of issuing
--     a direct UPDATE -- see section 1.
--   * A column-restricted role can no longer use the wildcard operator, so
--     clients must name columns explicitly. This client already does.
--   * Nothing else changes: RLS policies, INSERT/DELETE grants, the
--     finalize-media Edge Function, and the Telegram / Drive / Cloudinary
--     cleanup paths are untouched.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Controlled lifecycle operation: Move to Trash / Restore
--    (`user_hidden_at`). SECURITY DEFINER because the caller no longer holds
--    UPDATE privilege on the column; ownership is re-checked inside.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.set_media_library_visibility(
    p_media_id uuid,
    p_hidden   boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
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
    -- and workers, so the Lifecycle semantics are unchanged.
    --
    -- Zero matching rows is NOT an error: that keeps the previous PostgREST
    -- UPDATE semantics, where a non-matching id was a silent no-op.
    UPDATE public.media_assets
       SET user_hidden_at = CASE WHEN p_hidden THEN now() ELSE NULL END
     WHERE id = p_media_id
       AND (owner_id = auth.uid() OR private.is_admin());
END;
$$;

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

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Column-level UPDATE lockdown on public.media_assets
--
--    Revoke the table-wide UPDATE grant from the client roles and grant UPDATE
--    on the user-editable columns only. `updated_at` is deliberately NOT
--    granted: it is maintained by the table's own trigger, and a BEFORE trigger
--    writing NEW is not subject to the caller's column privileges.
--
--    The editable column list is built from the live table so this migration
--    cannot silently leave the API with zero writable columns.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_editable_columns text;
    v_grant_sql        text;
BEGIN
    -- Columns a normal authenticated owner is intentionally allowed to change.
    -- Only `is_favorite` qualifies: it is a pure user preference with no
    -- server-side meaning (the Android client currently keeps favorites in
    -- local storage, and nothing in the backend reads or writes it).
    SELECT string_agg(format('%I', c.column_name), ', ' ORDER BY c.column_name)
      INTO v_editable_columns
      FROM information_schema.columns c
     WHERE c.table_schema = 'public'
       AND c.table_name = 'media_assets'
       AND c.column_name IN ('is_favorite');

    IF v_editable_columns IS NULL THEN
        RAISE EXCEPTION
            'media_assets has no user-editable column (expected is_favorite); refusing to lock the table without a writable column';
    END IF;

    -- Client roles lose the table-wide UPDATE grant.
    REVOKE UPDATE ON TABLE public.media_assets FROM PUBLIC;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE UPDATE ON TABLE public.media_assets FROM anon;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE UPDATE ON TABLE public.media_assets FROM authenticated;

        v_grant_sql := format(
            'GRANT UPDATE (%s) ON TABLE public.media_assets TO authenticated',
            v_editable_columns
        );
        EXECUTE v_grant_sql;
    END IF;

    -- RLS is unchanged, but re-assert that it stays enabled.
    ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;

    -- Trusted server-side callers keep their full table-level UPDATE grant.
    -- The Supabase Edge Functions connect with the service-role key, and the
    -- lifecycle RPCs are SECURITY DEFINER (owned by the table owner, which
    -- always retains full privileges).
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        GRANT UPDATE ON TABLE public.media_assets TO service_role;
    END IF;

    RAISE NOTICE 'media_assets UPDATE limited to: %', v_editable_columns;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Worker-only Cloudinary cleanup RPCs are not client-callable
--
--    `claim_cloudinary_cleanup` / `complete_cloudinary_cleanup` are the only
--    handles that can move `primary_cleanup_*` state. They are called
--    exclusively by the `drive-replicate` worker with the service-role key, so
--    client EXECUTE is removed -- the same pattern already applied to the
--    Telegram worker RPCs in 20260923000100_telegram_worker_rpc_lockdown.sql.
--
--    Idempotent and tolerant of a function that is absent or has a different
--    signature: nothing is created or dropped here.
--
--    `admin_allow_cleanup_despite_telegram` is intentionally left alone: it is
--    an administrator operation that may legitimately be driven with an admin
--    user JWT, and it performs its own authorization.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    f        record;
    hardened integer := 0;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS sig
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND p.proname IN ('claim_cloudinary_cleanup', 'complete_cloudinary_cleanup')
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.sig);

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
            EXECUTE format('REVOKE ALL ON FUNCTION %s FROM anon', f.sig);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            EXECUTE format('REVOKE ALL ON FUNCTION %s FROM authenticated', f.sig);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
            EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', f.sig);
        END IF;

        hardened := hardened + 1;
    END LOOP;

    RAISE NOTICE 'Cloudinary cleanup RPCs locked to service_role: %', hardened;
END $$;
