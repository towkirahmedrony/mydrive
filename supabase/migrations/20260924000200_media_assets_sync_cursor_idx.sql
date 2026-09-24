-- Incremental catalog synchronization (client "updated_at" cursor):
--   WHERE owner_id = ?
--     AND (updated_at > ? OR (updated_at = ? AND id > ?))
--   ORDER BY updated_at ASC, id ASC
--   LIMIT ?
--
-- The library page index (20260923000200_media_assets_library_page_idx) serves
-- the visible page, which is ordered by created_at DESC. It cannot serve this
-- keyset, which walks the change timeline instead, so without this index a
-- refresh has to scan every row the owner ever wrote just to find the handful
-- that changed since the last successful synchronization.
--
-- Deliberately not partial: the incremental query must also return rows that
-- left the library (trashed, or moved to the DELETED lifecycle state) so the
-- client can apply the unchanged visibility rule locally and advance its
-- cursor. An index restricted to status = 'READY' AND user_hidden_at IS NULL
-- would hide exactly those rows from the plan.
--
-- Additive and non-destructive: one index. No data, RLS, grant or lifecycle
-- change.

CREATE INDEX IF NOT EXISTS media_assets_owner_updated_idx
  ON public.media_assets (owner_id, updated_at, id);
