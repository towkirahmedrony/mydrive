/**
 * Server-controlled Cloudinary upload folder for an authenticated user.
 *
 * Ownership of media is still enforced by `media_assets.owner_id` and RLS.
 * This folder is an additional isolation layer so private user media cannot
 * be directed into another user's Cloudinary path by a client.
 *
 * Any client-supplied `folder` / path value is ignored.
 */
export function cloudinaryUploadFolder(authenticatedUserId: string): string {
  return `mydrive/${authenticatedUserId}`;
}

/**
 * Resolves the folder that will be signed for Cloudinary.
 *
 * `clientBody` is accepted so existing clients can keep sending a `folder`
 * field; the value is never read.
 */
export function selectUploadFolder(
  authenticatedUserId: string,
  _clientBody?: Record<string, unknown>,
): string {
  return cloudinaryUploadFolder(authenticatedUserId);
}
