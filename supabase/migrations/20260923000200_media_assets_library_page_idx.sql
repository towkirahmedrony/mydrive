-- Keyset pagination for the user-facing library:
--   WHERE owner_id = ? AND status = 'READY' AND user_hidden_at IS NULL
--   ORDER BY created_at DESC, id DESC
-- Existing media_assets_owner_uploaded_idx is (owner_id, uploaded_at) WHERE READY
-- and does not cover this cursor. Partial index stays non-destructive.

CREATE INDEX IF NOT EXISTS media_assets_library_page_idx
  ON public.media_assets (owner_id, created_at DESC, id DESC)
  WHERE status = 'READY' AND user_hidden_at IS NULL;
