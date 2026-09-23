-- 20260923000100_telegram_worker_rpc_lockdown.sql
-- Harden Telegram worker SECURITY DEFINER RPCs without changing queue semantics.
--
-- Functions:
--   public.claim_telegram_job()
--   public.complete_telegram_job(...)
--   public.worker_lookup_telegram_token(...)
--
-- Protections:
--   1. Pin search_path = public, pg_temp so a caller cannot shadow objects.
--   2. Revoke EXECUTE from PUBLIC / anon / authenticated.
--   3. Grant EXECUTE only to service_role (telegram-replicate uses the
--      service-role key via getSupabaseAdmin()).
--
-- Function bodies, signatures, and job-claim/complete behavior are unchanged.
-- Telegram bot tokens remain server-side; this closes the client RPC path to
-- worker_lookup_telegram_token.

DO $$
DECLARE
    f record;
    hardened integer := 0;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS sig
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.proname IN (
              'claim_telegram_job',
              'complete_telegram_job',
              'worker_lookup_telegram_token'
          )
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %s SET search_path = public, pg_temp',
            f.sig
        );

        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f.sig);

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
            EXECUTE format('REVOKE ALL ON FUNCTION %s FROM anon', f.sig);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            EXECUTE format('REVOKE ALL ON FUNCTION %s FROM authenticated', f.sig);
        END IF;

        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', f.sig);

        hardened := hardened + 1;
    END LOOP;

    IF hardened = 0 THEN
        RAISE EXCEPTION
            'Telegram worker RPCs not found (claim_telegram_job, complete_telegram_job, worker_lookup_telegram_token)';
    END IF;
END $$;
