/**
 * Unit tests for the `media-drive` request path that a media player uses.
 *
 * These cover the two things streaming depends on and nothing that needs a
 * Google credential: the GET shape a player can open, and the `Range` header
 * normalization that decides whether a seek turns into a byte range or into a
 * full download. Run with:
 *
 *   deno test --allow-import supabase/functions/media-drive/handler.test.ts
 */

import { assertEquals } from "jsr:@std/assert@1";
import {
  handleMediaDriveRequest,
  type MediaDriveDependencies,
  normalizeRange,
} from "./handler.ts";

const OWNED_MEDIA = "8a1a2ca0-0b8e-4f1a-9f2e-1c3d4e5f6a7b";
const OTHER_MEDIA = "0c9b7f31-1111-4222-8333-444455556666";
const FUNCTION_URL = "https://project.supabase.co/functions/v1/media-drive";

function deps(overrides: Partial<MediaDriveDependencies> = {}): MediaDriveDependencies {
  return {
    authenticate: () => Promise.resolve({ userId: "user-1" }),
    adminClient: () => ({} as ReturnType<MediaDriveDependencies["adminClient"]>),
    isAdmin: () => Promise.resolve(false),
    isOwner: (_userId, mediaId) => Promise.resolve(mediaId === OWNED_MEDIA),
    ...overrides,
  };
}

/* ─────────────────────────────── range ───────────────────────────────── */

Deno.test("range: a normal range is forwarded unchanged", () => {
  assertEquals(normalizeRange("bytes=1000000-1999999"), "bytes=1000000-1999999");
});

Deno.test("range: open-ended and suffix ranges survive", () => {
  assertEquals(normalizeRange("bytes=1000000-"), "bytes=1000000-");
  assertEquals(normalizeRange("bytes=-500"), "bytes=-500");
});

Deno.test("range: invalid headers are ignored rather than rejected", () => {
  for (
    const header of [
      "",
      "bytes=",
      "bytes=abc-def",
      "bytes=2000-1000",
      "items=0-100",
      "bytes=0-99, 200-299",
    ]
  ) {
    assertEquals(normalizeRange(header), null, header);
  }
});

/* ──────────────────────────────── GET ────────────────────────────────── */

Deno.test("GET: a media id the caller does not own is refused", async () => {
  const response = await handleMediaDriveRequest(
    new Request(`${FUNCTION_URL}?media_id=${OTHER_MEDIA}&variant=original`),
    deps(),
  );

  assertEquals(response.status, 403);
  assertEquals((await response.json()).reason, "forbidden");
});

Deno.test("GET: a non-uuid media id is rejected before any lookup", async () => {
  let ownershipChecked = false;
  const response = await handleMediaDriveRequest(
    new Request(`${FUNCTION_URL}?media_id=../../etc/passwd&variant=original`),
    deps({
      isOwner: () => {
        ownershipChecked = true;
        return Promise.resolve(true);
      },
    }),
  );

  assertEquals(response.status, 400);
  assertEquals(ownershipChecked, false);
});

Deno.test("GET: the owner's media id reaches the archive resolver", async () => {
  let resolved = false;
  const response = await handleMediaDriveRequest(
    new Request(`${FUNCTION_URL}?media_id=${OWNED_MEDIA}&variant=original`),
    deps({
      adminClient: () => {
        resolved = true;
        throw new Error("Media lookup failed: no database in this test");
      },
    }),
  );

  assertEquals(resolved, true);
  assertEquals(response.status, 502);
});

Deno.test("methods other than GET/POST are still refused", async () => {
  const response = await handleMediaDriveRequest(
    new Request(FUNCTION_URL, { method: "DELETE" }),
    deps(),
  );

  assertEquals(response.status, 405);
});
