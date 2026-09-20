import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { getSupabaseAdmin, getSupabaseAuth } from "../shared/auth.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SORTS = {
  newest: { column: "created_at", ascending: false },
  oldest: { column: "created_at", ascending: true },
  largest: { column: "file_size", ascending: false },
  smallest: { column: "file_size", ascending: true },
  name: { column: "file_name", ascending: true },
} as const;
type AdminClient = ReturnType<typeof getSupabaseAdmin>;

type MediaRow = Record<string, unknown> & { id: string };
type JobRow = { id: string; media_id: string; destination_type: "telegram" | "google_drive"; status: string; last_error: string | null; completed_at: string | null };

function json(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}
function bad(message: string, status = 400) { return json({ success: false, error: message }, status); }
function safeError(error: unknown) { console.error("admin-media failed", error); return "The media operation could not be completed."; }
async function assertAdmin(admin: AdminClient, userId: string) {
  const { data, error } = await admin.from("profiles").select("role").eq("id", userId).maybeSingle();
  if (error) throw error;
  return (data as { role?: string } | null)?.role === "admin";
}
function parsePage(value: unknown) { const page = Number(value); return Number.isInteger(page) ? Math.min(Math.max(page, 1), 10_000) : 1; }
function parsePageSize(value: unknown) { const size = Number(value); return Number.isInteger(size) ? Math.min(Math.max(size, 1), 48) : 24; }
function publicMedia(row: MediaRow, jobs: JobRow[]) { return { ...row, storage_url: row.storage_url ?? null, thumbnail_url: row.thumbnail_url ?? null, jobs: jobs.filter((job) => job.media_id === row.id).map(({ media_id: _mediaId, ...job }) => job) }; }

async function listMedia(admin: AdminClient, body: Record<string, unknown>) {
  const page = parsePage(body.page); const pageSize = parsePageSize(body.page_size); const search = typeof body.search === "string" ? body.search.trim().slice(0, 120) : ""; const status = typeof body.status === "string" ? body.status : ""; const kind = body.kind === "IMAGE" || body.kind === "VIDEO" ? body.kind : ""; const userId = typeof body.user_id === "string" ? body.user_id.trim() : ""; const sortKey = typeof body.sort === "string" && body.sort in SORTS ? body.sort as keyof typeof SORTS : "newest";
  if (userId && !UUID_RE.test(userId)) return bad("Owner filter must be a valid user identifier.");
  if (status && !["UPLOADING", "READY", "FAILED", "DELETED"].includes(status)) return bad("Unsupported media state.");
  let query = admin.from("media_assets").select("id, owner_id, file_name, mime_type, file_size, width, height, duration_ms, storage_provider, storage_path, storage_url, thumbnail_url, status, created_at, uploaded_at, deleted_at", { count: "exact" });
  if (search) { const escaped = search.replace(/[%_]/g, "\\$&"); query = query.or(`file_name.ilike.%${escaped}%,storage_path.ilike.%${escaped}%,id.eq.${UUID_RE.test(search) ? search : "00000000-0000-0000-0000-000000000000"}`); }
  if (status) query = query.eq("status", status); if (userId) query = query.eq("owner_id", userId); if (kind === "IMAGE") query = query.ilike("mime_type", "image/%"); if (kind === "VIDEO") query = query.ilike("mime_type", "video/%");
  const ordering = SORTS[sortKey]; const from = (page - 1) * pageSize; const { data, error, count } = await query.order(ordering.column, { ascending: ordering.ascending, nullsFirst: false }).range(from, from + pageSize - 1);
  if (error) throw error;
  const rows = (data as MediaRow[] | null) ?? []; const ids = rows.map((row) => row.id); let jobs: JobRow[] = [];
  if (ids.length) { const result = await admin.from("replication_jobs").select("id, media_id, destination_type, status, last_error, completed_at").in("media_id", ids); if (result.error) throw result.error; jobs = (result.data as JobRow[] | null) ?? []; }
  return json({ success: true, media: rows.map((row) => publicMedia(row, jobs)), total: count ?? 0, total_bytes: rows.reduce((sum, row) => sum + (typeof row.file_size === "number" ? row.file_size : 0), 0), page, page_size: pageSize });
}

async function retry(admin: AdminClient, userId: string, body: Record<string, unknown>) {
  const ids = Array.isArray(body.media_ids) ? body.media_ids.filter((value): value is string => typeof value === "string" && UUID_RE.test(value)).slice(0, 100) : [];
  if (!ids.length || ids.length !== (Array.isArray(body.media_ids) ? body.media_ids.length : 0)) return bad("Select one or more valid media items.");
  const { data: jobs, error: jobError } = await admin.from("replication_jobs").select("id, media_id, destination_type, status").in("media_id", ids).eq("status", "FAILED");
  if (jobError) throw jobError;
  const eligible = (jobs as Array<{ id: string; media_id: string; destination_type: string; status: string }> | null) ?? []; if (!eligible.length) return json({ success: true, retried: 0 });
  const jobIds = eligible.map((job) => job.id); const { error: updateError } = await admin.from("replication_jobs").update({ status: "RETRYING", next_retry_at: new Date().toISOString(), last_error: null, updated_at: new Date().toISOString() }).in("id", jobIds).eq("status", "FAILED"); if (updateError) throw updateError;
  const auditRows = eligible.map((job) => ({ actor_id: userId, action: "retry_replication", media_id: job.media_id, details: { destination_type: job.destination_type, job_id: job.id }, success: true })); const { error: auditError } = await admin.from("admin_audit_logs").insert(auditRows); if (auditError) throw auditError;
  return json({ success: true, retried: eligible.length });
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return bad("Method not allowed.", 405);
  try { const { user } = await getSupabaseAuth(req); const admin = getSupabaseAdmin(); if (!(await assertAdmin(admin, user.id))) return bad("Admin privileges required.", 403); let body: Record<string, unknown>; try { body = await req.json(); } catch { return bad("Invalid JSON body."); } const action = body.action; if (action === "list") return await listMedia(admin, body); if (action === "retry") return await retry(admin, user.id, body); return bad("Unsupported media action."); } catch (error) { return json({ success: false, error: safeError(error) }, 500); }
});
