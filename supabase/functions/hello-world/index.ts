import { serve } from "jsr:@std/http";
import { corsHeaders, handleCors } from "../shared/cors.ts";

/**
 * Hello World - Test edge function to verify Supabase edge functions are working.
 *
 * Usage:
 *   POST/GET https://<project-ref>.supabase.co/functions/v1/hello-world
 *   Headers: apikey: <anon-key>
 *
 * Response: { message: "Hello from MyDrive Edge Functions!", timestamp: "...", project: "MyDrive" }
 */
serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  try {
    return new Response(
      JSON.stringify({
        message: "Hello from MyDrive Edge Functions!",
        timestamp: new Date().toISOString(),
        project: "MyDrive",
        status: "healthy",
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
