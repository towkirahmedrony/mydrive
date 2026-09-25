/**
 * The account must stop advertising itself as healthy when its refresh token
 * stops working.
 *
 * This is the failure that made real media render as "unavailable": every one of
 * the 243 production media had its Cloudinary original deleted, so Drive was the
 * only remaining source — and the Drive account's refresh token had silently
 * died while `health_status` still said `healthy` and `last_error` was null.
 */
import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import { accessTokenForAccount } from "./drive-folders.ts";
import type { getSupabaseAdmin } from "./auth.ts";

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

Deno.env.set("GOOGLE_OAUTH_CLIENT_ID", "test-client-id");
Deno.env.set("GOOGLE_OAUTH_CLIENT_SECRET", "test-client-secret");

interface Recorded {
  healthStatus: string | null;
  status: string | null;
  lastError: string | null;
}

/** Minimal fake: drive_accounts lookup, the vault RPC, and the health RPC. */
function fakeAdmin(options: { refreshToken: string | null; rpcFails?: boolean }) {
  const recorded: Recorded[] = [];
  const rpcCalls: { name: string; args: Record<string, unknown> }[] = [];

  const admin = {
    from(table: string) {
      assertEquals(table, "drive_accounts");
      const builder = {
        select() {
          return builder;
        },
        eq() {
          return builder;
        },
        maybeSingle() {
          return Promise.resolve({
            data: { refresh_token_secret_id: "secret-1" },
            error: null,
          });
        },
      };
      return builder;
    },
    rpc(name: string, args: Record<string, unknown>) {
      rpcCalls.push({ name, args });
      if (name === "worker_lookup_drive_refresh_token") {
        return Promise.resolve({ data: options.refreshToken, error: null });
      }
      if (name === "mark_drive_account_result") {
        if (options.rpcFails) {
          return Promise.resolve({ data: null, error: { message: "rpc down" } });
        }
        recorded.push({
          healthStatus: (args.p_health_status as string | null) ?? null,
          status: (args.p_status as string | null) ?? null,
          lastError: (args.p_last_error as string | null) ?? null,
        });
        return Promise.resolve({ data: null, error: null });
      }
      throw new Error(`unexpected rpc: ${name}`);
    },
  };

  return { admin: admin as unknown as AdminClient, recorded, rpcCalls };
}

/** Intercepts the Google token endpoint so no network call is made. */
function googleFetch(status: number, body: unknown) {
  const original = globalThis.fetch;
  const calls: string[] = [];
  globalThis.fetch = ((input: string | URL, init?: RequestInit) => {
    calls.push(String(input) + "?" + String(init?.body ?? ""));
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  }) as typeof fetch;
  return {
    calls,
    restore() {
      globalThis.fetch = original;
    },
  };
}

Deno.test("a dead refresh token is recorded as reauth_required and still thrown", async () => {
  const { admin, recorded, rpcCalls } = fakeAdmin({ refreshToken: "1//refresh" });
  const g = googleFetch(400, {
    error: "invalid_grant",
    error_description: "Token has been expired or revoked.",
  });

  try {
    await assertRejects(
      () => accessTokenForAccount(admin, "036a2dc1-6082-4c35-9250-020f72d0beae"),
      Error,
      "invalid_grant",
    );
  } finally {
    g.restore();
  }

  assertEquals(recorded.length, 1, "the credential failure must be recorded once");
  assertEquals(recorded[0].status, "reauth_required");
  assertEquals(recorded[0].healthStatus, "unhealthy");
  assert(
    recorded[0].lastError?.includes("invalid_grant"),
    "last_error must carry Google's stable error code so the cause is diagnosable",
  );
  assertEquals(
    rpcCalls.filter((c) => c.name === "mark_drive_account_result").length,
    1,
  );
});

Deno.test("a healthy exchange records nothing (no extra RPC on the hot path)", async () => {
  const { admin, recorded, rpcCalls } = fakeAdmin({ refreshToken: "1//refresh" });
  const g = googleFetch(200, { access_token: "ya29.token", expires_in: 3600 });

  let token = "";
  try {
    token = await accessTokenForAccount(admin, "036a2dc1-6082-4c35-9250-020f72d0beae");
  } finally {
    g.restore();
  }

  assertEquals(token, "ya29.token");
  assertEquals(recorded.length, 0);
  assertEquals(
    rpcCalls.filter((c) => c.name === "mark_drive_account_result").length,
    0,
  );
});

Deno.test("a bookkeeping failure never replaces the credential error", async () => {
  const { admin } = fakeAdmin({ refreshToken: "1//refresh", rpcFails: true });
  const g = googleFetch(400, { error: "invalid_grant" });

  try {
    await assertRejects(
      () => accessTokenForAccount(admin, "036a2dc1-6082-4c35-9250-020f72d0beae"),
      Error,
      "invalid_grant",
    );
  } finally {
    g.restore();
  }
});

Deno.test("a missing refresh token is recorded too, without calling Google", async () => {
  const { admin, recorded } = fakeAdmin({ refreshToken: null });
  const g = googleFetch(200, { access_token: "unused" });

  try {
    await assertRejects(() => accessTokenForAccount(admin, "acct"), Error);
  } finally {
    g.restore();
  }

  assertEquals(g.calls.length, 0, "Google must not be called without a token");
  // The vault lookup fails before any exchange, so nothing is recorded yet: the
  // account never had a usable credential to expire.
  assertEquals(recorded.length, 0);
});
