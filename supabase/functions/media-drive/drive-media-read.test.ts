/**
 * Unit tests for the archived-media read path (`shared/drive-media-read.ts`).
 *
 * Every case injects a fake `fetch`, so the module is exercised without a
 * Google credential and without touching production. Run with:
 *
 *   deno test --allow-import supabase/functions/media-drive/drive-media-read.test.ts
 *
 * The assertions that matter most are the failure classifications: a timeout, a
 * revoked credential and a deleted file must never collapse into the same
 * answer, because the Admin Panel shows a different message for each.
 */

import {
  assert,
  assertEquals,
  assertRejects,
  assertStringIncludes,
} from "jsr:@std/assert@1";
import {
  classifyDriveStatus,
  DriveMediaError,
  isGoogleThumbnailUrl,
  openDriveFileContent,
  openDriveThumbnail,
  readDriveFileMetadata,
  withThumbnailSize,
} from "../shared/drive-media-read.ts";

const FILE_ID = "1ku83hGUPPoa47w37Nuav0oYx9e7ePj8h";
const TOKEN = "ya29.SECRET-ACCESS-TOKEN";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Records the calls it receives so assertions can inspect URL + headers. */
function recordingFetch(
  handler: (url: string, init?: RequestInit) => Response | Promise<Response>,
) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const impl = ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    calls.push({ url, init });
    return Promise.resolve(handler(url, init));
  }) as typeof fetch;
  return { impl, calls };
}

function headerOf(init: RequestInit | undefined, name: string): string | null {
  const headers = new Headers(init?.headers);
  return headers.get(name);
}

/* ─────────────────────────────── metadata ─────────────────────────────── */

Deno.test("metadata: reads the safe fields and normalizes the byte count", async () => {
  const { impl, calls } = recordingFetch(() =>
    jsonResponse({
      id: FILE_ID,
      name: "Screenshot_20260921_005026.jpg",
      mimeType: "image/jpeg",
      size: "232144",
      thumbnailLink: "https://lh3.googleusercontent.com/drive-viewer/abc=s220",
      parents: ["1ArvJXGFcBC1iOQSUPVPveVyU1npML77o"],
      trashed: false,
      md5Checksum: "d41d8cd98f00b204e9800998ecf8427e",
    })
  );

  const meta = await readDriveFileMetadata({
    accessToken: TOKEN,
    fileId: FILE_ID,
    fetchImpl: impl,
  });

  assertEquals(meta.id, FILE_ID);
  assertEquals(meta.sizeBytes, 232144);
  assertEquals(meta.trashed, false);
  assertEquals(meta.parents, ["1ArvJXGFcBC1iOQSUPVPveVyU1npML77o"]);
  assert(meta.thumbnailLink?.startsWith("https://lh3.googleusercontent.com"));

  // The access token is sent to the API and nowhere else.
  assertEquals(headerOf(calls[0].init, "authorization"), `Bearer ${TOKEN}`);
  assertStringIncludes(calls[0].url, "/drive/v3/files/");
  assertStringIncludes(calls[0].url, "supportsAllDrives=true");
  assertStringIncludes(calls[0].url, "thumbnailLink");
});

Deno.test("metadata: 404 means the archived file is gone, and is not retryable", async () => {
  const { impl } = recordingFetch(() => jsonResponse({}, 404));

  const error = await assertRejects(
    () => readDriveFileMetadata({ accessToken: TOKEN, fileId: FILE_ID, fetchImpl: impl }),
    DriveMediaError,
  );
  assertEquals(error.reason, "archive_missing");
  assertEquals(error.retryable, false);
  assertEquals(error.status, 404);
});

Deno.test("metadata: 401/403 is a credential problem, never a deleted file", async () => {
  for (const status of [401, 403]) {
    const { impl } = recordingFetch(() => jsonResponse({}, status));
    const error = await assertRejects(
      () => readDriveFileMetadata({ accessToken: TOKEN, fileId: FILE_ID, fetchImpl: impl }),
      DriveMediaError,
    );
    assertEquals(error.reason, "credential_error");
    assertEquals(error.retryable, false);
  }
});

Deno.test("metadata: transport failure is retryable and does not leak the token", async () => {
  const impl = (() => {
    throw new DOMException("connection reset", "NetworkError");
  }) as unknown as typeof fetch;

  const error = await assertRejects(
    () => readDriveFileMetadata({ accessToken: TOKEN, fileId: FILE_ID, fetchImpl: impl }),
    DriveMediaError,
  );
  assertEquals(error.reason, "provider_unavailable");
  assertEquals(error.retryable, true);
  assert(!error.message.includes(TOKEN));
  assert(!error.message.includes("SECRET"));
});

Deno.test("classify: 429 and 5xx are retryable, 416 is not, a thumbnail 404 has its own reason", () => {
  for (const status of [429, 500, 502, 503]) {
    const result = classifyDriveStatus(status, "content");
    assertEquals(result.reason, "provider_unavailable");
    assertEquals(result.retryable, true);
  }
  assertEquals(classifyDriveStatus(416, "content").reason, "range_not_satisfiable");
  assertEquals(classifyDriveStatus(404, "metadata").reason, "archive_missing");
  // A missing thumbnail is a "no poster" answer for a file that exists.
  assertEquals(classifyDriveStatus(404, "thumbnail").reason, "no_preview");
});

/* ─────────────────────────────── content ──────────────────────────────── */

Deno.test("content: a byte range is forwarded and the 206 answer is passed through", async () => {
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const { impl, calls } = recordingFetch(
    (_url, init) => {
      assertEquals(headerOf(init, "range"), "bytes=0-3");
      return new Response(bytes, {
        status: 206,
        headers: {
          "Content-Type": "video/mp4",
          "Content-Range": "bytes 0-3/71046969",
          "Content-Length": "4",
          "Accept-Ranges": "bytes",
        },
      });
    },
  );

  const response = await openDriveFileContent({
    accessToken: TOKEN,
    fileId: FILE_ID,
    range: "bytes=0-3",
    fetchImpl: impl,
  });

  assertEquals(response.status, 206);
  assertEquals(response.headers.get("content-range"), "bytes 0-3/71046969");
  assertEquals(new Uint8Array(await response.arrayBuffer()).length, 4);
  assertStringIncludes(calls[0].url, "alt=media");
  assertEquals(headerOf(calls[0].init, "authorization"), `Bearer ${TOKEN}`);
});

Deno.test("content: no range header is sent when the caller does not ask for one", async () => {
  const { impl, calls } = recordingFetch(() =>
    new Response(new Uint8Array([9]), {
      status: 200,
      headers: { "Content-Type": "image/jpeg" },
    })
  );

  const response = await openDriveFileContent({
    accessToken: TOKEN,
    fileId: FILE_ID,
    fetchImpl: impl,
  });

  assertEquals(response.status, 200);
  assertEquals(headerOf(calls[0].init, "range"), null);
});

Deno.test("content: a 500 is retryable, a 404 is the archived file being gone", async () => {
  const server = recordingFetch(() => jsonResponse({}, 500));
  const serverError = await assertRejects(
    () =>
      openDriveFileContent({
        accessToken: TOKEN,
        fileId: FILE_ID,
        fetchImpl: server.impl,
      }),
    DriveMediaError,
  );
  assertEquals(serverError.reason, "provider_unavailable");

  const missing = recordingFetch(() => jsonResponse({}, 404));
  const missingError = await assertRejects(
    () =>
      openDriveFileContent({
        accessToken: TOKEN,
        fileId: FILE_ID,
        fetchImpl: missing.impl,
      }),
    DriveMediaError,
  );
  assertEquals(missingError.reason, "archive_missing");
});

/* ────────────────────────────── thumbnails ────────────────────────────── */

Deno.test("thumbnail: asks for a larger edge, then streams the image", async () => {
  const { impl, calls } = recordingFetch(() =>
    new Response(new Uint8Array([1, 2]), {
      status: 200,
      headers: { "Content-Type": "image/jpeg" },
    })
  );

  const response = await openDriveThumbnail({
    accessToken: TOKEN,
    thumbnailLink: "https://lh3.googleusercontent.com/drive-viewer/abc=s220",
    size: 480,
    fetchImpl: impl,
  });

  assertEquals(response.status, 200);
  assertEquals(response.headers.get("content-type"), "image/jpeg");
  assertEquals(calls[0].url, "https://lh3.googleusercontent.com/drive-viewer/abc=s480");
});

Deno.test("thumbnail: a non-Google host is refused before any fetch happens", async () => {
  const { impl, calls } = recordingFetch(() => jsonResponse({}, 200));

  const error = await assertRejects(
    () =>
      openDriveThumbnail({
        accessToken: TOKEN,
        thumbnailLink: "https://evil.example.com/steal",
        fetchImpl: impl,
      }),
    DriveMediaError,
  );

  assertEquals(error.reason, "no_preview");
  assertEquals(calls.length, 0);
  assertEquals(isGoogleThumbnailUrl("https://evil.example.com/x"), false);
  assertEquals(
    isGoogleThumbnailUrl("https://lh3.googleusercontent.com/drive-viewer/x"),
    true,
  );
});

Deno.test("withThumbnailSize replaces known directives and leaves others intact", () => {
  assertEquals(
    withThumbnailSize("https://lh3.googleusercontent.com/x=s220", 480),
    "https://lh3.googleusercontent.com/x=s480",
  );
  assertEquals(
    withThumbnailSize("https://lh3.googleusercontent.com/x=w220-h220", 480),
    "https://lh3.googleusercontent.com/x=w480-h480",
  );
  assertEquals(
    withThumbnailSize("https://lh3.googleusercontent.com/x", 480),
    "https://lh3.googleusercontent.com/x=s480",
  );
  // An unfamiliar directive shape is preserved rather than broken.
  assertEquals(
    withThumbnailSize("https://lh3.googleusercontent.com/x?auth=1", 480),
    "https://lh3.googleusercontent.com/x?auth=1",
  );
});
