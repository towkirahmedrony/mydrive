import { serve } from "jsr:@std/http/server";
import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAuth } from "../shared/auth.ts";

/**
 * Sync Device - Syncs device metadata with the Supabase database.
 *
 * Expects a JSON body:
 *   {
 *     "device_id": "string",
 *     "device_name": "string",
 *     "platform": "android" | "ios" | "web",
 *     "os_version": "string",
 *     "app_version": "string",
 *     "storage_total_gb": number,
 *     "storage_used_gb": number
 *   }
 *
 * Usage:
 *   POST https://<project-ref>.supabase.co/functions/v1/sync-device
 *   Headers:
 *     apikey: <anon-key>
 *     Authorization: Bearer <user-jwt>
 *     Content-Type: application/json
 *
 * Response: { success: true, device: { ... } }
 */
serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  try {
    const { supabase, user } = await getSupabaseAuth(req);
    const body = await req.json();

    const {
      device_id,
      device_name,
      platform,
      os_version,
      app_version,
      storage_total_gb,
      storage_used_gb,
    } = body;

    if (!device_id || !device_name) {
      return new Response(
        JSON.stringify({ error: "device_id and device_name are required" }),
        {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
          status: 400,
        }
      );
    }

    // Upsert device record - update if exists, insert if not
    const { data, error } = await supabase
      .from("devices")
      .upsert(
        {
          id: device_id,
          user_id: user.id,
          device_name,
          platform: platform || "android",
          os_version: os_version || "",
          app_version: app_version || "",
          storage_total_gb: storage_total_gb || 0,
          storage_used_gb: storage_used_gb || 0,
          last_synced_at: new Date().toISOString(),
        },
        { onConflict: "id" }
      )
      .select()
      .single();

    if (error) {
      throw new Error(`Database error: ${error.message}`);
    }

    return new Response(
      JSON.stringify({ success: true, device: data }),
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
