-- Private Vault / hidden media — cloud-side metadata only.
--
-- DESIGN NOTE (important): the vault's *content* never reaches Supabase. The
-- encrypted copy of a hidden photo/video lives in app-private storage on the
-- device, protected by an Android Keystore AES-GCM key. These tables only record
-- WHICH media is in the vault, on WHICH device, and the state of the transition.
-- No key material, PIN, biometric data, decrypted media or file path outside the
-- app sandbox is stored here.
--
-- CLOUD-FIRST: hiding media does NOT delete or duplicate the cloud asset.
-- `media_vault_items.media_id` references the EXISTING `media_assets` row, so the
-- same logical media keeps its single Cloudinary asset and Drive archive.
--
-- Conventions follow the existing schema: uuid PKs, `timestamptz` + the existing
-- `set_updated_at()` trigger, FKs to media_assets/devices/profiles with ON DELETE
-- CASCADE, and RLS using `(owner_id = auth.uid()) OR private.is_admin()`.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. media_vault_items — one row per media asset currently in a device's vault
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.media_vault_items (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The SAME cloud asset the normal gallery uses: no second Cloudinary asset.
    media_id              uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE CASCADE,
    owner_id              uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    -- The vault copy is device-local, so the vault record is device-scoped.
    device_id             uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    vault_status          text NOT NULL DEFAULT 'ENCRYPTING'
        CONSTRAINT media_vault_items_status_check
        CHECK (vault_status IN ('ENCRYPTING', 'READY', 'RESTORING', 'FAILED', 'DELETED')),
    vault_version         integer NOT NULL DEFAULT 1
        CONSTRAINT media_vault_items_version_positive CHECK (vault_version >= 1),
    -- Device-relative file name inside the app sandbox (NOT a public path).
    encrypted_storage_path text,
    encrypted_file_size   bigint,
    encrypted_sha256      text,
    original_mime_type    text NOT NULL,
    original_file_name    text NOT NULL,
    original_file_size    bigint NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    hidden_at             timestamptz,
    restored_at           timestamptz,
    -- An ENCRYPTING/READY/RESTORING record must always name its ciphertext file,
    -- so a client cannot mark media hidden without a stored vault copy.
    CONSTRAINT media_vault_items_ciphertext_present
        CHECK (vault_status IN ('FAILED', 'DELETED') OR encrypted_storage_path IS NOT NULL)
);

-- One media asset cannot have two ACTIVE vault records (re-hiding after a
-- permanent delete is allowed, which is why DELETED rows are excluded).
CREATE UNIQUE INDEX IF NOT EXISTS uq_media_vault_items_active_media
    ON public.media_vault_items (media_id)
    WHERE vault_status <> 'DELETED';

CREATE INDEX IF NOT EXISTS idx_media_vault_items_owner
    ON public.media_vault_items (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_vault_items_device
    ON public.media_vault_items (device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_vault_items_status
    ON public.media_vault_items (vault_status);

DROP TRIGGER IF EXISTS media_vault_items_set_updated_at ON public.media_vault_items;
CREATE TRIGGER media_vault_items_set_updated_at
    BEFORE UPDATE ON public.media_vault_items
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. media_vault_settings — per-device vault configuration (NON-sensitive)
--    The PIN verifier and salt are NOT stored here: they stay on the device in
--    Keystore-protected private preferences.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.media_vault_settings (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id              uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id             uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    biometric_enabled     boolean NOT NULL DEFAULT false,
    pin_enabled           boolean NOT NULL DEFAULT false,
    lock_timeout_seconds  integer NOT NULL DEFAULT 60
        CONSTRAINT media_vault_settings_timeout_range
        CHECK (lock_timeout_seconds >= 0 AND lock_timeout_seconds <= 3600),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT media_vault_settings_device_key UNIQUE (device_id)
);

CREATE INDEX IF NOT EXISTS idx_media_vault_settings_owner
    ON public.media_vault_settings (owner_id);

DROP TRIGGER IF EXISTS media_vault_settings_set_updated_at ON public.media_vault_settings;
CREATE TRIGGER media_vault_settings_set_updated_at
    BEFORE UPDATE ON public.media_vault_settings
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. media_vault_events — audit trail of high-level vault actions
--    Deliberately records only WHAT happened, not WHY an unlock failed: no PIN,
--    no biometric result detail, no keys, no media content.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.media_vault_events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id     uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    vault_item_id uuid REFERENCES public.media_vault_items(id) ON DELETE SET NULL,
    event_type    text NOT NULL
        CONSTRAINT media_vault_events_type_check
        CHECK (event_type IN ('HIDE', 'UNHIDE', 'VAULT_UNLOCK_SUCCESS',
                              'VAULT_UNLOCK_FAILURE', 'PERMANENT_DELETE', 'RESTORE')),
    event_at      timestamptz NOT NULL DEFAULT now(),
    -- Small, safe, non-sensitive context only (e.g. {"source":"gallery"}).
    metadata      jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_media_vault_events_owner_time
    ON public.media_vault_events (owner_id, event_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_vault_events_device_time
    ON public.media_vault_events (device_id, event_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_vault_events_item
    ON public.media_vault_events (vault_item_id);

-- `media_vault_devices` is intentionally NOT created: `public.devices` already
-- models the device, and media_vault_items.device_id references it directly, so a
-- second device mapping table would be redundant.

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. RLS — owner-only, plus the project's existing admin model
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.media_vault_items    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media_vault_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media_vault_events   ENABLE ROW LEVEL SECURITY;

-- Vault items: the owning device maintains its own records; admins may read.
DROP POLICY IF EXISTS "Users can view own vault items" ON public.media_vault_items;
CREATE POLICY "Users can view own vault items"
    ON public.media_vault_items FOR SELECT TO authenticated
    USING ((owner_id = auth.uid()) OR private.is_admin());

DROP POLICY IF EXISTS "Users can insert own vault items" ON public.media_vault_items;
CREATE POLICY "Users can insert own vault items"
    ON public.media_vault_items FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS "Users can update own vault items" ON public.media_vault_items;
CREATE POLICY "Users can update own vault items"
    ON public.media_vault_items FOR UPDATE TO authenticated
    USING (owner_id = auth.uid())
    WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS "Admins can delete vault items" ON public.media_vault_items;
CREATE POLICY "Admins can delete vault items"
    ON public.media_vault_items FOR DELETE TO authenticated
    USING (private.is_admin());

-- Settings: the device configures its own row; admins may read/change.
DROP POLICY IF EXISTS "Users can view own vault settings" ON public.media_vault_settings;
CREATE POLICY "Users can view own vault settings"
    ON public.media_vault_settings FOR SELECT TO authenticated
    USING ((owner_id = auth.uid()) OR private.is_admin());

DROP POLICY IF EXISTS "Users can insert own vault settings" ON public.media_vault_settings;
CREATE POLICY "Users can insert own vault settings"
    ON public.media_vault_settings FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS "Users can update own vault settings" ON public.media_vault_settings;
CREATE POLICY "Users can update own vault settings"
    ON public.media_vault_settings FOR UPDATE TO authenticated
    USING ((owner_id = auth.uid()) OR private.is_admin())
    WITH CHECK ((owner_id = auth.uid()) OR private.is_admin());

-- Events: append-only for the device (no UPDATE policy, so history cannot be
-- rewritten), readable by its owner and by admins.
DROP POLICY IF EXISTS "Users can view own vault events" ON public.media_vault_events;
CREATE POLICY "Users can view own vault events"
    ON public.media_vault_events FOR SELECT TO authenticated
    USING ((owner_id = auth.uid()) OR private.is_admin());

DROP POLICY IF EXISTS "Users can insert own vault events" ON public.media_vault_events;
CREATE POLICY "Users can insert own vault events"
    ON public.media_vault_events FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS "Admins can delete vault events" ON public.media_vault_events;
CREATE POLICY "Admins can delete vault events"
    ON public.media_vault_events FOR DELETE TO authenticated
    USING (private.is_admin());
