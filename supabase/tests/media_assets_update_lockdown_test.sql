-- media_assets_update_lockdown_test.sql
--
-- Security tests for 20260923000300_media_assets_update_lockdown.sql.
--
-- What it proves
--   1.  An owner can still update is_favorite (the only user-editable column).
--   2.  An owner cannot change owner_id.
--   3.  An owner cannot change storage_provider / storage_path / storage_url /
--       thumbnail_url / storage_asset_id / sha256_hash.
--   4.  An owner cannot mark media READY (status) or set uploaded_at.
--   5.  An owner cannot set drive_archived_at.
--   6.  An owner cannot touch primary_cleanup_* / cleanup_telegram_override.
--   7.  An owner cannot set deleted_at / user_hidden_at directly (Trash/Restore
--       must go through set_media_library_visibility).
--   8.  A user cannot update another user's media.
--   9.  Admin capability and service_role (Edge Function workers / admin
--       workflows) keep working.
--   10. The service-role INSERT path used by finalize-media still works.
--   11. Static privilege inspection: only is_favorite is UPDATE-able by
--       authenticated, anon has no UPDATE, and the table stays UPDATE-exposed
--       for PostgREST (has_any_column_privilege).
--   12. Trash / Restore still work through the controlled RPC.
--
-- How to run
--   * Supabase Dashboard -> SQL Editor (runs as `postgres`), or
--     `psql "$SUPABASE_DB_URL" -f supabase/tests/media_assets_update_lockdown_test.sql`
--   * The whole script is ONE transaction and ends with ROLLBACK, so it never
--     leaves fixtures or mutations behind.
--   * Needs INSERT on auth.users (postgres / service_role only) to build two
--     throwaway users.

begin;

-- ── Fixtures ────────────────────────────────────────────────────────────────
-- Inserting into auth.users fires public.handle_new_user(), which creates the
-- matching profiles row, so media_assets.owner_id -> profiles.id is satisfied.
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('00000000-0000-4000-a000-00000000000a', 'lockdown-owner@example.test', now(), '{}'::jsonb, '{}'::jsonb, now(), now()),
  ('00000000-0000-4000-a000-00000000000b', 'lockdown-other@example.test', now(), '{}'::jsonb, '{}'::jsonb, now(), now()),
  ('00000000-0000-4000-a000-00000000000c', 'lockdown-admin@example.test', now(), '{}'::jsonb, '{}'::jsonb, now(), now())
on conflict (id) do nothing;

insert into public.profiles (id, email, role, status)
values
  ('00000000-0000-4000-a000-00000000000a', 'lockdown-owner@example.test', 'user', 'active'),
  ('00000000-0000-4000-a000-00000000000b', 'lockdown-other@example.test', 'user', 'active'),
  ('00000000-0000-4000-a000-00000000000c', 'lockdown-admin@example.test', 'user', 'active')
on conflict (id) do nothing;

-- handle_new_user() creates the profile with the default role, so promote the
-- administrator explicitly.
update public.profiles set role = 'admin' where id = '00000000-0000-4000-a000-00000000000c';

insert into public.devices (id, user_id, device_uid, device_name, status)
values
  ('00000000-0000-4000-b000-00000000000a', '00000000-0000-4000-a000-00000000000a', 'lockdown-device-a', 'Lockdown Test Device', 'active')
on conflict (id) do nothing;

insert into public.media_assets (
  id, owner_id, device_id, local_media_id, file_name, mime_type, file_size,
  storage_provider, storage_asset_id, storage_path, storage_url, thumbnail_url,
  sha256_hash, client_upload_id, status, uploaded_at,
  drive_archived_at, primary_cleanup_status, primary_cleanup_attempts,
  primary_cleanup_error, primary_deleted_at, cleanup_telegram_override
)
values
  ('00000000-0000-4000-c000-00000000000a',
   '00000000-0000-4000-a000-00000000000a',
   '00000000-0000-4000-b000-00000000000a',
   1001, 'lockdown-ready.jpg', 'image/jpeg', 2048,
   'cloudinary', 'asset-lockdown-a', 'mydrive/owner/lockdown-ready', 'https://example.test/lockdown-ready.jpg', 'https://example.test/lockdown-ready-thumb.jpg',
   'deadbeef', '00000000-0000-4000-d000-00000000000a', 'READY', now(),
   now(), 'cleanup_success', 2, 'previous failure', now(), true),
  ('00000000-0000-4000-c000-00000000000b',
   '00000000-0000-4000-a000-00000000000a',
   '00000000-0000-4000-b000-00000000000a',
   1002, 'lockdown-uploading.jpg', 'image/jpeg', 1024,
   'cloudinary', 'asset-lockdown-b', 'mydrive/owner/lockdown-uploading', 'https://example.test/lockdown-uploading.jpg', null,
   null, '00000000-0000-4000-d000-00000000000b', 'UPLOADING', null,
   null, 'none', 0, null, null, false)
on conflict (id) do nothing;

-- ── Test 11: static privilege inspection ────────────────────────────────────
do $test$
begin
  if has_column_privilege('authenticated', 'public.media_assets', 'is_favorite', 'UPDATE') is not true then
    raise exception 'FAIL 11a: authenticated cannot UPDATE is_favorite';
  end if;
  raise notice 'PASS 11a: authenticated can UPDATE is_favorite';

  if has_column_privilege('authenticated', 'public.media_assets', 'status', 'UPDATE') then
    raise exception 'FAIL 11b: authenticated still has UPDATE on status';
  end if;
  raise notice 'PASS 11b: authenticated has no UPDATE on status';

  if has_column_privilege('authenticated', 'public.media_assets', 'user_hidden_at', 'UPDATE') then
    raise exception 'FAIL 11c: authenticated still has UPDATE on user_hidden_at';
  end if;
  raise notice 'PASS 11c: authenticated has no UPDATE on user_hidden_at';

  -- PostgREST exposes a table for UPDATE when the role holds table- or
  -- column-level UPDATE, so is_favorite stays reachable through the API.
  if has_any_column_privilege('authenticated', 'public.media_assets', 'UPDATE') is not true then
    raise exception 'FAIL 11d: authenticated lost all UPDATE exposure (PostgREST would hide PATCH)';
  end if;
  raise notice 'PASS 11d: authenticated keeps column-level UPDATE exposure for PostgREST';

  if exists (select 1 from pg_roles where rolname = 'anon') then
    if has_any_column_privilege('anon', 'public.media_assets', 'UPDATE') then
      raise exception 'FAIL 11e: anon still has UPDATE on media_assets';
    end if;
    raise notice 'PASS 11e: anon has no UPDATE on media_assets';
  end if;

  if exists (select 1 from pg_roles where rolname = 'service_role') then
    if has_column_privilege('service_role', 'public.media_assets', 'status', 'UPDATE') is not true then
      raise exception 'FAIL 11f: service_role lost UPDATE on status (workers would break)';
    end if;
    raise notice 'PASS 11f: service_role keeps full UPDATE on status';
  end if;

  if not exists (
    select 1 from pg_policies
     where schemaname = 'public' and tablename = 'media_assets'
       and policyname = 'media_assets_update_own'
  ) then
    raise exception 'FAIL 11g: media_assets_update_own policy is missing';
  end if;
  raise notice 'PASS 11g: media_assets_update_own policy still present (row scoping unchanged)';

  if not exists (
    select 1 from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'set_media_library_visibility'
  ) then
    raise exception 'FAIL 11h: set_media_library_visibility RPC is missing';
  end if;
  raise notice 'PASS 11h: set_media_library_visibility RPC exists';
end;
$test$;

-- ── Tests 2-7: every column except is_favorite is rejected ──────────────────
do $test$
declare
  v_media    constant uuid := '00000000-0000-4000-c000-00000000000b';
  v_column   text;
  v_rejected boolean;
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', '00000000-0000-4000-a000-00000000000a', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  -- Driven off the live table so a column added later is covered automatically.
  for v_column in
    select c.column_name
      from information_schema.columns c
     where c.table_schema = 'public'
       and c.table_name = 'media_assets'
       and c.column_name <> 'is_favorite'
     order by c.column_name
  loop
    v_rejected := false;
    begin
      -- Assigning a column to itself is still an UPDATE on that column, so the
      -- privilege check (not the value) decides the outcome.
      execute format('update public.media_assets set %I = %I where id = %L',
                     v_column, v_column, v_media);
    exception
      when insufficient_privilege then
        v_rejected := true;
      when others then
        raise exception 'FAIL 2-7: UPDATE of % raised % (%) instead of 42501',
          v_column, sqlstate, sqlerrm;
    end;

    if not v_rejected then
      raise exception 'FAIL 2-7: UPDATE of protected column % was accepted', v_column;
    end if;
  end loop;
  raise notice 'PASS 2-7: every protected column rejected with 42501';

  -- Named checks so the intent for each field group is explicit.
  v_rejected := false;
  begin
    update public.media_assets set owner_id = '00000000-0000-4000-a000-00000000000b' where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 2: cannot change owner_id';
  else raise exception 'FAIL 2: owner_id could be changed'; end if;

  v_rejected := false;
  begin
    update public.media_assets set storage_provider = 'imagekit', storage_path = 'x', storage_url = 'https://evil.test/x' where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 3: cannot rewrite storage provider/path/url';
  else raise exception 'FAIL 3: storage location could be rewritten'; end if;

  v_rejected := false;
  begin
    update public.media_assets set status = 'READY', uploaded_at = now() where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 4: cannot fake READY status / upload completion';
  else raise exception 'FAIL 4: media could be marked READY'; end if;

  v_rejected := false;
  begin
    update public.media_assets set drive_archived_at = now() where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 5: cannot forge drive_archived_at';
  else raise exception 'FAIL 5: drive_archived_at could be forged'; end if;

  v_rejected := false;
  begin
    update public.media_assets
       set primary_cleanup_status = 'cleanup_success',
           primary_cleanup_completed_at = now(),
           primary_deleted_at = now(),
           cleanup_telegram_override = true
     where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 6: cannot forge primary_cleanup_* / cleanup_telegram_override';
  else raise exception 'FAIL 6: cleanup state could be forged'; end if;

  v_rejected := false;
  begin
    update public.media_assets set deleted_at = now() where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 7a: cannot set deleted_at directly';
  else raise exception 'FAIL 7a: deleted_at could be set directly'; end if;

  v_rejected := false;
  begin
    update public.media_assets set user_hidden_at = now() where id = v_media;
  exception when insufficient_privilege then v_rejected := true;
  end;
  if v_rejected then raise notice 'PASS 7b: cannot set user_hidden_at directly (use the RPC)';
  else raise exception 'FAIL 7b: user_hidden_at could be set directly'; end if;

  execute 'reset role';
end;
$test$;

-- ── Test 1: owner CAN change is_favorite ────────────────────────────────────
do $test$
declare
  v_media constant uuid := '00000000-0000-4000-c000-00000000000b';
  v_fav   boolean;
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', '00000000-0000-4000-a000-00000000000a', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  update public.media_assets set is_favorite = true where id = v_media;
  select is_favorite into v_fav from public.media_assets where id = v_media;
  if v_fav is not true then
    raise exception 'FAIL 1: is_favorite was not set to true';
  end if;

  update public.media_assets set is_favorite = false where id = v_media;
  select is_favorite into v_fav from public.media_assets where id = v_media;
  if v_fav is not false then
    raise exception 'FAIL 1: is_favorite was not set back to false';
  end if;

  raise notice 'PASS 1: owner can update is_favorite and nothing else';
  execute 'reset role';
end;
$test$;

-- ── Test 8: cannot touch another user's media ───────────────────────────────
do $test$
declare
  v_media constant uuid := '00000000-0000-4000-c000-00000000000b';
  v_rows  integer;
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', '00000000-0000-4000-a000-00000000000b', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  -- A writable column, but a row this user does not own: RLS must match 0 rows.
  update public.media_assets set is_favorite = true where id = v_media;
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'FAIL 8: another user updated % row(s) of foreign media', v_rows;
  end if;

  -- The controlled RPC must also be a silent no-op for a foreign row.
  perform public.set_media_library_visibility(v_media, true);

  execute 'reset role';

  if exists (select 1 from public.media_assets where id = v_media and is_favorite = true) then
    raise exception 'FAIL 8: foreign media is_favorite was modified';
  end if;
  if exists (select 1 from public.media_assets where id = v_media and user_hidden_at is not null) then
    raise exception 'FAIL 8: RPC hid another user''s media';
  end if;
  raise notice 'PASS 8: another user''s media is untouchable (RLS + RPC ownership check)';
end;
$test$;

-- ── Test 9: admin capability through the RPC still works ───────────────────
do $test$
declare
  v_media  constant uuid := '00000000-0000-4000-c000-00000000000a';
  v_hidden timestamptz;
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', '00000000-0000-4000-a000-00000000000c', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  perform public.set_media_library_visibility(v_media, true);
  select user_hidden_at into v_hidden from public.media_assets where id = v_media;
  if v_hidden is null then
    raise exception 'FAIL 9: admin could not hide another user''s media through the RPC';
  end if;

  perform public.set_media_library_visibility(v_media, false);
  raise notice 'PASS 9: admin capability preserved through the RPC';

  execute 'reset role';
end;
$test$;

-- ── Test 12: Trash / Restore still work through the RPC ─────────────────────
do $test$
declare
  v_media  constant uuid := '00000000-0000-4000-c000-00000000000a';
  v_hidden timestamptz;
  v_refused boolean;
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', '00000000-0000-4000-a000-00000000000a', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';

  -- Move to Trash
  perform public.set_media_library_visibility(v_media, true);
  select user_hidden_at into v_hidden from public.media_assets where id = v_media;
  if v_hidden is null then
    raise exception 'FAIL 12: Move to Trash did not set user_hidden_at';
  end if;
  raise notice 'PASS 12: Move to Trash works through the RPC';

  -- Restore
  perform public.set_media_library_visibility(v_media, false);
  select user_hidden_at into v_hidden from public.media_assets where id = v_media;
  if v_hidden is not null then
    raise exception 'FAIL 12: Restore did not clear user_hidden_at';
  end if;
  raise notice 'PASS 12: Restore works through the RPC';

  execute 'reset role';

  -- A caller with no authenticated user id is refused.
  perform set_config('request.jwt.claims', '{}'::text, true);
  execute 'set local role authenticated';
  v_refused := false;
  begin
    perform public.set_media_library_visibility(v_media, true);
  exception
    when sqlstate '28000' then v_refused := true;
  end;
  if not v_refused then
    raise exception 'FAIL 12: RPC accepted a caller without a user id';
  end if;
  raise notice 'PASS 12: RPC rejects a caller with no authenticated user';

  execute 'reset role';
end;
$test$;

-- ── Tests 9-10: service_role keeps every server-side write path ─────────────
do $test$
declare
  v_media constant uuid := '00000000-0000-4000-c000-00000000000a';
begin
  execute 'set local role service_role';

  -- 9: worker / admin lifecycle updates.
  update public.media_assets
     set drive_archived_at = now(),
         primary_cleanup_status = 'cleanup_pending',
         primary_cleanup_attempts = 3,
         updated_at = now()
   where id = v_media;
  raise notice 'PASS 9: service_role can record Drive archival / cleanup state';

  update public.media_assets set status = 'DELETED', deleted_at = now() where id = v_media;
  raise notice 'PASS 9: service_role can run the deletion lifecycle';

  -- 10: the finalize-media INSERT path (status READY, identity from the JWT).
  insert into public.media_assets (
    owner_id, device_id, local_media_id, file_name, mime_type, file_size,
    storage_provider, storage_asset_id, storage_path, storage_url,
    client_upload_id, status, uploaded_at
  ) values (
    '00000000-0000-4000-a000-00000000000a', '00000000-0000-4000-b000-00000000000a',
    1003, 'finalized.jpg', 'image/jpeg', 4096,
    'cloudinary', 'asset-finalized', 'mydrive/owner/finalized', 'https://example.test/finalized.jpg',
    '00000000-0000-4000-d000-00000000000c', 'READY', now()
  );
  raise notice 'PASS 10: service_role INSERT of a READY media row (finalize-media) still works';

  execute 'reset role';
end;
$test$;

-- ── Worker cleanup RPCs are not client-callable ─────────────────────────────
do $test$
declare
  v_sig text;
begin
  for v_sig in
    select p.oid::regprocedure::text
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname in ('claim_cloudinary_cleanup', 'complete_cloudinary_cleanup')
  loop
    if has_function_privilege('authenticated', v_sig, 'EXECUTE') then
      raise exception 'FAIL: authenticated can EXECUTE %', v_sig;
    end if;
    if has_function_privilege('anon', v_sig, 'EXECUTE') then
      raise exception 'FAIL: anon can EXECUTE %', v_sig;
    end if;
    raise notice 'PASS: client roles cannot EXECUTE %', v_sig;
  end loop;
end;
$test$;

-- Nothing above is kept: fixtures and mutations are discarded.
rollback;
