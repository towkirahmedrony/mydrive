import { corsHeaders, handleCors } from "../shared/cors.ts";
import { getSupabaseAuth } from "../shared/auth.ts";
import { selectUploadFolder } from "./upload-folder.ts";

/**
 * Cloudinary Upload Auth - Generates signed upload authorization for Android.
 *
 * The API Secret never leaves this server. Android receives only the values it
 * needs to perform a direct signed upload to Cloudinary's upload endpoint.
 *
 * Required server-side secrets (Supabase Edge Function secrets):
 *   CLOUDINARY_CLOUD_NAME   – your Cloudinary cloud name
 *   CLOUDINARY_API_KEY      – your Cloudinary API key
 *   CLOUDINARY_API_SECRET   – your Cloudinary API secret (NEVER returned)
 *
 * Usage:
 *   POST https://<project-ref>.supabase.co/functions/v1/cloudinary-upload-auth
 *   Headers:
 *     apikey: <anon-key>
 *     Authorization: Bearer <user-jwt>
 *     Content-Type: application/json
 *
 * Request body (all optional; `folder` from the client is always ignored):
 *   {
 *     "folder": "<ignored>"            // never trusted; server sets mydrive/<user-id>
 *     "resource_type": "image"         // image | video | raw | auto
 *     "allowed_formats": ["jpg","png","mp4"]
 *     "max_file_size": 104857600       // bytes, e.g. 100 MB
 *   }
 *
 * Response:
 *   {
 *     "success": true,
 *     "cloud_name": "...",
 *     "api_key": "...",
 *     "timestamp": 1234567890,
 *     "signature": "abc123...",
 *     "folder": "mydrive/<user-id>",
 *     "params": { ... }          // all params the client must POST
 *   }
 *
 * Security:
 *   - CLOUDINARY_API_SECRET is read but never included in the response.
 *   - CLOUDINARY_SERVICE_ROLE_KEY (if present) is never returned.
 *   - The signature is HMAC-SHA1 of the params+timestamp+secret –
 *     it proves the request was authorised by the server.
 *   - Each request uses a fresh timestamp so authorizations are short-lived.
 *   - The Cloudinary folder is derived only from the verified JWT user id
 *     (`mydrive/<user.id>`). Any client-supplied folder/public path is ignored.
 */
Deno.serve(async (req: Request) => {
  const corsResponse = handleCors(req);
  if (corsResponse) return corsResponse;

  // Only accept POST
  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ error: "Method not allowed" }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" }, status: 405 },
    );
  }

  try {
    // ── 1. Authenticate the caller ────────────────────────────────────
    const { user } = await getSupabaseAuth(req);

    // ── 2. Read Cloudinary credentials from server-side secrets ───────
    const cloudName = Deno.env.get("CLOUDINARY_CLOUD_NAME");
    const apiKey = Deno.env.get("CLOUDINARY_API_KEY");
    const apiSecret = Deno.env.get("CLOUDINARY_API_SECRET");

    if (!cloudName || !apiKey || !apiSecret) {
      console.error(
        "cloudinary-upload-auth: missing Cloudinary env vars. " +
          "Set CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, and CLOUDINARY_API_SECRET " +
          "as Edge Function secrets.",
      );
      return new Response(
        JSON.stringify({ error: "Server misconfiguration – Cloudinary secrets not set" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" }, status: 500 },
      );
    }

    // ── 3. Parse optional request body ─────────────────────────────────
    let body: Record<string, unknown> = {};
    try {
      body = await req.json();
    } catch {
      // Empty body is fine – all fields are optional
    }

    // Folder is server-controlled from the verified JWT user. Any client
    // folder / path override (another user, traversal, empty, etc.) is ignored.
    const folder = selectUploadFolder(user.id, body);

    const resourceType: string =
      typeof body.resource_type === "string" &&
      ["image", "video", "raw", "auto"].includes(body.resource_type)
        ? body.resource_type
        : "auto";

    const allowedFormats: string[] | undefined = Array.isArray(body.allowed_formats)
      ? body.allowed_formats.map((f: unknown) => String(f))
      : undefined;

    const maxFileSize: number | undefined =
      typeof body.max_file_size === "number" && body.max_file_size > 0
        ? body.max_file_size
        : undefined;

    // ── 4. Build Cloudinary upload parameters (secret never included) ──
    const timestamp = Math.floor(Date.now() / 1000);

    const paramsToSign: Record<string, string | number> = {
      folder,
      timestamp,
    };

    if (allowedFormats) {
      paramsToSign.allowed_formats = allowedFormats.join(",");
    }
    if (maxFileSize) {
      paramsToSign.max_file_size = maxFileSize;
    }

    // ── 5. Generate SHA-1 signature ────────────────────────────────────
    // Cloudinary signs by concatenating key=value pairs sorted alphabetically,
    // appended with the API secret, then SHA-1 hashing.
    const signString =
      Object.keys(paramsToSign)
        .sort()
        .map((k) => `${k}=${paramsToSign[k]}`)
        .join("&") + apiSecret;

    const encoder = new TextEncoder();
    const data = encoder.encode(signString);
    const hashBuffer = await crypto.subtle.digest("SHA-1", data);
    const hashArray = new Uint8Array(hashBuffer);
    const signature = Array.from(hashArray)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");

    // ── 6. Return only what Android needs ──────────────────────────────
    const responseParams: Record<string, string | number> = {
      folder,
      timestamp,
      ...(allowedFormats ? { allowed_formats: allowedFormats.join(",") } : {}),
      ...(maxFileSize ? { max_file_size: maxFileSize } : {}),
    };

    return new Response(
      JSON.stringify({
        success: true,
        cloud_name: cloudName,
        api_key: apiKey,
        timestamp,
        signature,
        folder,
        resource_type: resourceType,
        params: responseParams,
      }),
      {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
        status: 200,
      },
    );
  } catch (error) {
    // Distinguish auth errors (401) from other errors (400)
    const message = (error as Error).message;
    const isAuthError =
      message.includes("Missing Authorization") ||
      message.includes("Invalid or expired token");

    return new Response(
      JSON.stringify({ error: message }),
      {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
        status: isAuthError ? 401 : 400,
      },
    );
  }
});
