-- Minimal actor-aware audit trail for privileged admin operations.
-- This is intentionally separate from sync_logs, which records worker events
-- and has no actor identity.
CREATE TABLE IF NOT EXISTS public.admin_audit_logs (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
  action text NOT NULL,
  media_id uuid REFERENCES public.media_assets(id) ON DELETE SET NULL,
  target_user_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  success boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS admin_audit_logs_media_idx ON public.admin_audit_logs (media_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_actor_idx ON public.admin_audit_logs (actor_id, created_at DESC);
ALTER TABLE public.admin_audit_logs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS admin_audit_logs_admin_only ON public.admin_audit_logs;
CREATE POLICY admin_audit_logs_admin_only ON public.admin_audit_logs
  FOR ALL USING (private.is_admin()) WITH CHECK (private.is_admin());
