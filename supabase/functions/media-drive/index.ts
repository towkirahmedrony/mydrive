import { getSupabaseAdmin, getSupabaseAuth } from "../shared/auth.ts";
import { assertAdmin, handleMediaDriveRequest } from "./handler.ts";

/**
 * media-drive — authenticated read path for owned media, plus admin access,
 * for media that lives in the Google Drive
 * archive.
 *
 * Why this exists
 * ---------------
 * The production lifecycle is:
 *
 *   Android -> Cloudinary -> media_assets -> Google Drive archive
 *                                              |
 *                              primary Cloudinary asset deleted
 *                              only AFTER the Drive copy is verified
 *
 * So a historical `media_assets` row can be perfectly valid while its
 * Cloudinary original no longer exists. Every admin surface that resolved media
 * from the Cloudinary delivery URL alone therefore reported a normal, healthy
 * archived file as "no longer present in storage". This function is the missing
 * read direction: it serves the verified Drive copy.
 *
 * What it is NOT
 * --------------
 *   - it does not upload, copy, delete, move or restore anything, and it makes
 *     no change to the archive, to `media_assets` or to `replication_jobs`;
 *   - it does not re-create a deleted Cloudinary asset;
 *   - it does not introduce a second Google OAuth/token system: it reuses the
 *     production credential helper (`accessTokenForAccount`), which reads the
 *     refresh token from Supabase Vault through the same backend-only RPC the
 *     replication worker uses.
 *
 * Contract
 * --------
 *   POST /functions/v1/media-drive
 *   Headers: Authorization: Bearer <admin user JWT>, apikey: <anon key>
 *            Range: bytes=... (optional, forwarded to Drive for seeking)
 *   Body:    { "media_id": "<uuid>", "variant": "thumb" | "original",
 *              "owner_id": "<uuid>" (optional, must match the row when sent) }
 *
 *   Success: the media bytes are streamed back (200, or 206 for a Range
 *            request) with Content-Type / Content-Length / Content-Range /
 *            Accept-Ranges / ETag forwarded from Drive.
 *   Failure: JSON `{ success:false, error, reason, retryable }`.
 *
 * Account selection
 * -----------------
 * The Drive account is NEVER hardcoded and is never chosen by the caller. It is
 * read from the media's own completed replication job
 * (`replication_jobs.drive_account_id`), so a file is always read through the
 * account that actually owns it, and the path works unchanged as more accounts
 * are added to the pool.
 *
 * Security
 * --------
 *   - the platform verifies the JWT before this code runs; non-admin callers
 *     are restricted to media rows they own;
 *   - the caller supplies an internal `media_id` only. A Google Drive file id
 *     is never accepted from the client: it is looked up from the database, so
 *     an admin cannot turn this endpoint into a Drive-wide file reader;
 *   - when `owner_id` is supplied it must match the row, so a request cannot be
 *     repointed at another employee's media;
 *   - no refresh token, access token, Vault reference, service-role key or
 *     provider secret is ever returned to the caller or written to a log.
 *
 * The request path itself lives in `./handler.ts`; this file only supplies the
 * runtime dependencies (platform JWT verification, admin lookup, service-role
 * client) to it.
 */

Deno.serve((req: Request) =>
  handleMediaDriveRequest(req, {
    authenticate: async (request) => {
      const { user } = await getSupabaseAuth(request);
      return { userId: user.id };
    },
    adminClient: () => getSupabaseAdmin(),
    isAdmin: (userId, admin) => assertAdmin(admin, userId),
    isOwner: async (userId, mediaId, admin) => {
      const { data, error } = await admin
        .from("media_assets")
        .select("id")
        .eq("id", mediaId)
        .eq("owner_id", userId)
        .maybeSingle();
      if (error) throw error;
      return data !== null;
    },
  })
);
