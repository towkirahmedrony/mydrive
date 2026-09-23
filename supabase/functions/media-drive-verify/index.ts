/**
 * NEUTRALISED VERIFICATION ENDPOINT — no longer functional.
 *
 * This function existed only as a temporary harness to verify the archived
 * media read path against real production media. It is deployed with
 * verify_jwt=true (so the platform rejects unauthenticated calls before this
 * code runs) and returns 410 Gone with no media access, no database access and
 * no credential handling of any kind.
 *
 * The real read path lives in `media-drive` (admin-only, verify_jwt=true).
 */
Deno.serve((_req: Request) =>
  new Response(
    JSON.stringify({
      success: false,
      error: "This verification endpoint has been retired and performs no work.",
      reason: "gone",
      retryable: false,
    }),
    { status: 410, headers: { "Content-Type": "application/json" } },
  )
);
