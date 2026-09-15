import { serve } from "jsr:@std/http/server";
import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAuth } from "../shared/auth.ts";

/**
 * Process Backup - Processes backup metadata after a user uploads files.
 * Updates the sync state and records backup progress.
 *
 * Expects a JSON body:
 *   {
 *     "backup_id": "string (optional, auto-generated if not provided)",
 *     "files": [
 *       {
 *         "file_path": "string",
 *         "file_name": "string",
 *         "file_size": number,
 *         "mime_type": "string",
 *         "thumbnail_path": "string (optional)"
 *       }
 *     ],
 *     "device_id": "string",
 *     "backup_type": "full" | "incremental"
 *   }
 *
 * Usage:
 *   POST https://<project-ref>.supabase.co/functions/v1/process-backup
 *   Headers:
 *     apikey: <anon-key>
 *     Authorization: Bearer <user-jwt>
 *     Content-Type: application/json
 *
 * Response: { success: true, backup: { id, status, file_count, total_size } }
 */
serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  try {
    const { supabase, user } = await getSupabaseAuth(req);
    const body = await req.json();

    const { files, device_id, backup_type = "incremental" } = body;

    if (!files || !Array.isArray(files) || files.length === 0) {
      return new Response(
        JSON.stringify({ error: "files array is required and must not be empty" }),
        {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
          status: 400,
        }
      );
    }

    if (!device_id) {
      return new Response(
        JSON.stringify({ error: "device_id is required" }),
        {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
          status: 400,
        }
      );
    }

    // Calculate totals
    const totalSize = files.reduce(
      (sum: number, f: { file_size: number }) => sum + (f.file_size || 0),
      0
    );
    const backupId =
      body.backup_id || crypto.randomUUID();

    // Insert backup record
    const { data: backup, error: backupError } = await supabase
      .from("backups")
      .insert({
        id: backupId,
        user_id: user.id,
        device_id,
        backup_type,
        file_count: files.length,
        total_size_bytes: totalSize,
        status: "processing",
        started_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (backupError) {
      throw new Error(`Failed to create backup record: ${backupError.message}`);
    }

    // Insert individual file records
    const fileRecords = files.map(
      (f: {
        file_path: string;
        file_name: string;
        file_size: number;
        mime_type: string;
        thumbnail_path?: string;
      }) => ({
        backup_id: backupId,
        user_id: user.id,
        device_id,
        file_path: f.file_path,
        file_name: f.file_name,
        file_size: f.file_size,
        mime_type: f.mime_type || "application/octet-stream",
        thumbnail_path: f.thumbnail_path || null,
        status: "pending",
      })
    );

    const { error: filesError } = await supabase
      .from("backup_files")
      .insert(fileRecords);

    if (filesError) {
      throw new Error(`Failed to insert file records: ${filesError.message}`);
    }

    return new Response(
      JSON.stringify({
        success: true,
        backup: {
          id: backupId,
          status: "processing",
          file_count: files.length,
          total_size_bytes: totalSize,
          backup_type,
        },
      }),
      {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
        status: 200,
      }
    );
  } catch (error) {
    return new Response(
      JSON.stringify({ error: (error as Error).message }),
      {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
        status: 400,
      }
    );
  }
});
