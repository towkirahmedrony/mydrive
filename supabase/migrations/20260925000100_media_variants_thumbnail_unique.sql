-- Persistent Cloudinary thumbnails — one thumbnail variant per media.
--
-- WHY THIS IS NEEDED
-- The persistent thumbnail is recorded twice, on purpose:
--   1. media_assets.thumbnail_url            -> the media-level reference the
--                                               gallery reads (source of truth
--                                               for media metadata stays here);
--   2. media_variants (variant_type='thumbnail') -> the independently
--                                               addressable variant record:
--                                               media_id -> thumbnail variant ->
--                                               Cloudinary asset / public ID /
--                                               delivery URL.
-- The variant record is what lets the FINAL deletion lifecycle delete the
-- thumbnail without depending on the original, and what lets a thumbnail be
-- resolved by media id alone.
--
-- `finalize-media` is idempotent on client_upload_id, but two CONCURRENT
-- finalize requests for the same upload can both pass the "no thumbnail yet"
-- check and both insert a variant row. This partial unique index makes that
-- impossible: the loser of the race gets SQLSTATE 23505, which the writer
-- treats as "the thumbnail already exists" (the desired outcome), not a
-- failure. No column is added and no existing row is modified.
--
-- Scope note: `variant_type = 'telegram'` legitimately has MORE than one row
-- per media (one per Telegram destination), so the uniqueness is partial and
-- applies to 'thumbnail' only.
--
-- Non-destructive and idempotent: additive index, IF NOT EXISTS, and a guard so
-- a database where `media_variants` is not present yet is left untouched rather
-- than failing the migration run.

DO $$
BEGIN
  IF to_regclass('public.media_variants') IS NULL THEN
    RAISE NOTICE 'media_variants is not present; skipping thumbnail unique index';
    RETURN;
  END IF;

  -- DDL must go through EXECUTE inside a PL/pgSQL block.
  EXECUTE
    'CREATE UNIQUE INDEX IF NOT EXISTS media_variants_thumbnail_unique '
    'ON public.media_variants (media_id) '
    'WHERE variant_type = ''thumbnail''';
END
$$;
