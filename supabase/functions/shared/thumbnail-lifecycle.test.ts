/**
 * Tests for the persistent-thumbnail lifecycle.
 *
 * The behaviour that matters is that a thumbnail is INDEPENDENTLY addressable
 * from the original: it must be addressed by its own public ID, it must never be
 * re-uploaded for the same media, and a replay of any step must converge instead
 * of duplicating an asset or a variant record.
 *
 * Every Cloudinary call is intercepted, so these tests assert the exact request
 * that would be sent (including the signature, recomputed independently here
 * from Cloudinary's documented rule) without touching the network.
 */

import {
  assert,
  assertEquals,
  assertRejects,
  assertStringIncludes,
} from "jsr:@std/assert@1";
import type { getSupabaseAdmin } from "./auth.ts";
import {
  buildThumbnailSource,
  clearThumbnailReference,
  ensurePersistentThumbnail,
  formatFromDeliveryUrl,
  isThumbnailPublicId,
  materializeThumbnail,
  publicIdFromDeliveryUrl,
  resolveThumbnailPublicId,
  thumbnailDeliveryUrl,
  thumbnailFolder,
  thumbnailPublicId,
  ThumbnailMaterializationError,
  type ThumbnailMediaRow,
} from "./thumbnail-lifecycle.ts";

const CLOUD = "test-cloud";
const API_KEY = "123456789012345";
const API_SECRET = "abcSECRETabc";

Deno.env.set("CLOUDINARY_CLOUD_NAME", CLOUD);
Deno.env.set("CLOUDINARY_API_KEY", API_KEY);
Deno.env.set("CLOUDINARY_API_SECRET", API_SECRET);

const OWNER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
const MEDIA = "11111111-2222-3333-4444-555555555555";

// ─── Cloudinary request interception ────────────────────────────────────────

interface Call {
  method: string;
  url: string;
  body: URLSearchParams | null;
}

/** Independent SHA-1 of Cloudinary's documented signature string. */
async function expectedSignature(params: Record<string, string>): Promise<string> {
  const toSign = Object.keys(params).sort().map((key) => `${key}=${params[key]}`).join("&") +
    API_SECRET;
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(toSign));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function makeFetch(options: {
  /** Delivery state of the deterministic thumbnail asset. */
  thumbnailPresent?: boolean;
  /** Force an "already exists" answer from the upload endpoint. */
  uploadAlreadyExists?: boolean;
  /** Force a hard upload failure. */
  uploadHttpStatus?: number;
  uploadPublicId?: string;
}) {
  const calls: Call[] = [];
  const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? new URLSearchParams(init.body) : null;
    calls.push({ method, url, body });

    if (url.startsWith("https://api.cloudinary.com/")) {
      const status = options.uploadHttpStatus ?? 200;
      if (options.uploadAlreadyExists) {
        return new Response(
          JSON.stringify({ error: { message: "Public ID is already in use" } }),
          { status: 400 },
        );
      }
      if (status !== 200) {
        return new Response(JSON.stringify({ error: { message: "boom" } }), { status });
      }
      return new Response(
        JSON.stringify({
          asset_id: "asset-1",
          public_id: options.uploadPublicId ?? thumbnailPublicId(OWNER, MEDIA),
          secure_url: "https://res.cloudinary.com/x/y/z.jpg",
          width: 512,
          height: 512,
          bytes: 4242,
          format: "jpg",
        }),
        { status: 200 },
      );
    }

    // Delivery probe of the thumbnail.
    return options.thumbnailPresent
      ? new Response("binary", { status: 200 })
      : new Response("", { status: 404 });
  }) as typeof fetch;

  const uploads = () => calls.filter((call) => call.url.includes("api.cloudinary.com"));
  return { fetchImpl, calls, uploads };
}

// ─── Minimal fake Supabase admin client ─────────────────────────────────────

interface VariantRow {
  id: string;
  media_id: string;
  variant_type: string;
  storage_provider: string;
  storage_asset_id: string;
  storage_path: string;
  storage_url: string;
}

class FakeAdmin {
  mediaUpdates: Array<Record<string, unknown>> = [];
  variantInserts: Array<Record<string, unknown>> = [];
  variantUpdates: Array<{ id: string; payload: Record<string, unknown> }> = [];
  variantDeletes = 0;
  variantRow: VariantRow | null = null;
  insertError: { code?: string; message: string } | null = null;
  mediaUpdateError: { message: string } | null = null;

  from(table: string) {
    return new FakeQuery(this, table);
  }
}

class FakeQuery {
  private mode: "select" | "update" | "insert" | "delete" = "select";
  private payload: Record<string, unknown> | null = null;

  constructor(private readonly admin: FakeAdmin, private readonly table: string) {}

  select(_columns?: string) {
    this.mode = "select";
    return this;
  }
  update(payload: Record<string, unknown>) {
    this.mode = "update";
    this.payload = payload;
    return this;
  }
  insert(payload: Record<string, unknown>) {
    this.mode = "insert";
    this.payload = payload;
    return this;
  }
  delete() {
    this.mode = "delete";
    return this;
  }
  eq(_column: string, _value: unknown) {
    return this;
  }

  async maybeSingle() {
    if (this.table === "media_variants" && this.mode === "select") {
      return { data: this.admin.variantRow, error: null };
    }
    return { data: null, error: null };
  }

  private exec(): { error: { code?: string; message: string } | null } {
    if (this.table === "media_assets" && this.mode === "update") {
      this.admin.mediaUpdates.push(this.payload ?? {});
      return { error: this.admin.mediaUpdateError };
    }
    if (this.table === "media_variants") {
      if (this.mode === "insert") {
        if (this.admin.insertError) return { error: this.admin.insertError };
        this.admin.variantInserts.push(this.payload ?? {});
        return { error: null };
      }
      if (this.mode === "update") {
        this.admin.variantUpdates.push({ id: "v1", payload: this.payload ?? {} });
        return { error: null };
      }
      if (this.mode === "delete") {
        this.admin.variantDeletes += 1;
        return { error: null };
      }
    }
    return { error: null };
  }

  then<TResult1 = unknown, TResult2 = never>(
    onfulfilled?: ((value: unknown) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return Promise.resolve(this.exec()).then(onfulfilled, onrejected);
  }
}

type AdminClient = ReturnType<typeof getSupabaseAdmin>;

/**
 * Adapts the in-memory double to the real client type.
 *
 * The double implements exactly the PostgREST surface this module uses
 * (`from(...).select/update/insert/delete().eq()` + `maybeSingle()`), which the
 * concrete SupabaseClient generic cannot express structurally. The cast is
 * confined here so every assertion still runs against the real signatures.
 */
function adminClient(fake: FakeAdmin): AdminClient {
  return fake as unknown as AdminClient;
}

function mediaRow(overrides: Partial<ThumbnailMediaRow> = {}): ThumbnailMediaRow {
  return {
    id: MEDIA,
    owner_id: OWNER,
    mime_type: "image/jpeg",
    storage_path: `mydrive/${OWNER}/IMG_1234`,
    storage_url: `https://res.cloudinary.com/${CLOUD}/image/upload/v1726/mydrive/${OWNER}/IMG_1234.jpg`,
    thumbnail_url: null,
    status: "READY",
    primary_cleanup_status: "none",
    primary_deleted_at: null,
    ...overrides,
  };
}

// ─── Identity ───────────────────────────────────────────────────────────────

Deno.test("identity: the thumbnail lives in its own folder, keyed by media id", () => {
  assertEquals(thumbnailFolder(OWNER), `mydrive/${OWNER}/thumbnails`);
  assertEquals(thumbnailPublicId(OWNER, MEDIA), `mydrive/${OWNER}/thumbnails/${MEDIA}`);
  assertEquals(
    thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
    `https://res.cloudinary.com/${CLOUD}/image/upload/mydrive/${OWNER}/thumbnails/${MEDIA}.jpg`,
  );
});

Deno.test("identity: isThumbnailPublicId only matches a real thumbnail id", () => {
  assert(isThumbnailPublicId(thumbnailPublicId(OWNER, MEDIA)));
  // Originals — the exact shape the client uploads — must never match.
  assert(!isThumbnailPublicId(`mydrive/${OWNER}/IMG_1234`));
  assert(!isThumbnailPublicId(`mydrive/${OWNER}/thumbnails.jpg`));
  assert(!isThumbnailPublicId(`mydrive/${OWNER}/thumbnails/${MEDIA}/extra`));
  assert(!isThumbnailPublicId(`other/${OWNER}/thumbnails/${MEDIA}`));
  assert(!isThumbnailPublicId(""));
  assert(!isThumbnailPublicId(null));
});

Deno.test("identity: a delivery URL yields its own public id, not the original's", () => {
  assertEquals(
    publicIdFromDeliveryUrl(
      `https://res.cloudinary.com/${CLOUD}/image/upload/mydrive/${OWNER}/thumbnails/${MEDIA}.jpg`,
    ),
    `mydrive/${OWNER}/thumbnails/${MEDIA}`,
  );
  // With a transformation segment and an asset version.
  assertEquals(
    publicIdFromDeliveryUrl(
      `https://res.cloudinary.com/${CLOUD}/image/upload/w_256,h_256,c_fill,q_auto/v1726/mydrive/${OWNER}/IMG_1234.jpg`,
    ),
    `mydrive/${OWNER}/IMG_1234`,
  );
  // A non-Cloudinary or unparseable URL is never addressable.
  assertEquals(publicIdFromDeliveryUrl("https://example.com/a/b/c.jpg"), null);
  assertEquals(publicIdFromDeliveryUrl("not a url"), null);
  assertEquals(publicIdFromDeliveryUrl(null), null);
});

Deno.test("identity: the stored format comes from the URL extension", () => {
  assertEquals(
    formatFromDeliveryUrl(
      `https://res.cloudinary.com/${CLOUD}/image/upload/mydrive/${OWNER}/IMG.HEIC`,
    ),
    "heic",
  );
  assertEquals(formatFromDeliveryUrl(`https://res.cloudinary.com/${CLOUD}/image/upload/x`), null);
});

// ─── Source URL ─────────────────────────────────────────────────────────────

Deno.test("source: an image is transformed with c_fill and keeps its own format", () => {
  const source = buildThumbnailSource({
    cloudName: CLOUD,
    publicId: `mydrive/${OWNER}/IMG_1234`,
    mimeType: "image/jpeg",
    format: "jpg",
    sizePx: 512,
  });
  assert(source);
  assertEquals(source.resourceType, "image");
  assertEquals(
    source.url,
    `https://res.cloudinary.com/${CLOUD}/image/upload/w_512,h_512,c_fill,q_auto/mydrive/${OWNER}/IMG_1234.jpg`,
  );
});

Deno.test("source: a video becomes its first frame, delivered as jpg", () => {
  const source = buildThumbnailSource({
    cloudName: CLOUD,
    publicId: `mydrive/${OWNER}/clip`,
    mimeType: "video/mp4",
    sizePx: 256,
  });
  assert(source);
  assertEquals(source.resourceType, "video");
  // so_0 must come first: it is what turns the video into a still image.
  assertStringIncludes(source.url, "/video/upload/so_0,w_256,h_256,c_fill,q_auto/");
  assert(source.url.endsWith("clip.jpg"));
});

Deno.test("source: a raw asset has no derivable bitmap, and a size is clamped", () => {
  assertEquals(
    buildThumbnailSource({
      cloudName: CLOUD,
      publicId: `mydrive/${OWNER}/notes`,
      mimeType: "application/pdf",
    }),
    null,
  );
  // Absurd sizes are clamped into a sane band instead of being trusted.
  const tiny = buildThumbnailSource({
    cloudName: CLOUD,
    publicId: "a/b",
    mimeType: "image/png",
    sizePx: 1,
  });
  assert(tiny?.url.includes("w_64,h_64"));
  const huge = buildThumbnailSource({
    cloudName: CLOUD,
    publicId: "a/b",
    mimeType: "image/png",
    sizePx: 99_999,
  });
  assert(huge?.url.includes("w_1024,h_1024"));
});

// ─── Materialization ────────────────────────────────────────────────────────

Deno.test("materialize: uploads a separate asset, signed with Cloudinary's rule", async () => {
  const { fetchImpl, uploads } = makeFetch({});
  const result = await materializeThumbnail({
    ownerId: OWNER,
    mediaId: MEDIA,
    originalPublicId: `mydrive/${OWNER}/IMG_1234`,
    mimeType: "image/jpeg",
    format: "jpg",
    fetchImpl,
  });

  assertEquals(result.uploaded, true);
  assertEquals(result.publicId, thumbnailPublicId(OWNER, MEDIA));
  assertEquals(result.url, thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)));

  assertEquals(uploads().length, 1);
  const upload = uploads()[0];
  assertEquals(upload.method, "POST");
  assertStringIncludes(upload.url, `/v1_1/${CLOUD}/image/upload`);

  const body = upload.body!;
  // The thumbnail is stored at its OWN public id under the thumbnails folder.
  assertEquals(body.get("public_id"), `mydrive/${OWNER}/thumbnails/${MEDIA}`);
  // Never overwrite an existing thumbnail, never let Cloudinary rename it.
  assertEquals(body.get("overwrite"), "false");
  assertEquals(body.get("unique_filename"), "false");
  assertEquals(body.get("use_filename"), "false");
  assertEquals(body.get("format"), "jpg");
  assertEquals(body.get("invalidate"), "true");
  assertEquals(body.get("type"), "upload");
  assertEquals(body.get("api_key"), API_KEY);
  // `file` carries the transformed ORIGINAL url, and is not part of the
  // signature — matching Cloudinary's documented exclusion.
  assertStringIncludes(
    body.get("file")!,
    `/image/upload/w_512,h_512,c_fill,q_auto/mydrive/${OWNER}/IMG_1234.jpg`,
  );

  const signed = {
    format: "jpg",
    invalidate: "true",
    overwrite: "false",
    public_id: `mydrive/${OWNER}/thumbnails/${MEDIA}`,
    timestamp: body.get("timestamp")!,
    type: "upload",
    unique_filename: "false",
    use_filename: "false",
  };
  assertEquals(body.get("signature"), await expectedSignature(signed));
});

Deno.test("materialize: an existing thumbnail asset is REUSED, never re-uploaded", async () => {
  const { fetchImpl, uploads, calls } = makeFetch({ thumbnailPresent: true });
  const result = await materializeThumbnail({
    ownerId: OWNER,
    mediaId: MEDIA,
    originalPublicId: `mydrive/${OWNER}/IMG_1234`,
    mimeType: "image/jpeg",
    fetchImpl,
  });
  assertEquals(result.uploaded, false);
  assertEquals(uploads().length, 0);
  // The delivery tier was probed first — the check the reuse decision rests on.
  assert(calls.some((call) => call.url.includes("res.cloudinary.com")));
});

Deno.test("materialize: losing an upload race reuses the winner's asset", async () => {
  const { fetchImpl, uploads } = makeFetch({ thumbnailPresent: true, uploadAlreadyExists: true });
  const result = await materializeThumbnail({
    ownerId: OWNER,
    mediaId: MEDIA,
    originalPublicId: `mydrive/${OWNER}/IMG_1234`,
    mimeType: "image/jpeg",
    fetchImpl,
  });
  assertEquals(result.uploaded, false);
  assertEquals(uploads().length, 0);
});

Deno.test("materialize: refuses to derive a thumbnail from a thumbnail", async () => {
  const { fetchImpl } = makeFetch({});
  await assertRejects(
    () =>
      materializeThumbnail({
        ownerId: OWNER,
        mediaId: MEDIA,
        originalPublicId: thumbnailPublicId(OWNER, MEDIA),
        mimeType: "image/jpeg",
        fetchImpl,
      }),
    ThumbnailMaterializationError,
    "thumbnail",
  );
});

Deno.test("materialize: an inconclusive upload failure is retryable, not silent", async () => {
  const { fetchImpl } = makeFetch({ uploadHttpStatus: 503 });
  const error = await assertRejects(() =>
    materializeThumbnail({
      ownerId: OWNER,
      mediaId: MEDIA,
      originalPublicId: `mydrive/${OWNER}/IMG_1234`,
      mimeType: "image/jpeg",
      fetchImpl,
    })
  ) as ThumbnailMaterializationError;
  assertEquals(error.reason, "UPLOAD_FAILED");
  assertEquals(error.retryable, true);
});

// ─── ensurePersistentThumbnail ──────────────────────────────────────────────

Deno.test("ensure: records media_assets.thumbnail_url AND the media_variants row", async () => {
  const admin = new FakeAdmin();
  const { fetchImpl, uploads } = makeFetch({});

  const outcome = await ensurePersistentThumbnail(adminClient(admin), mediaRow(), { fetchImpl });

  assert(outcome.ok);
  assertEquals(outcome.reason, "CREATED");
  assertEquals(outcome.uploaded, true);
  assertEquals(outcome.thumbnailUrl, thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)));

  assertEquals(admin.mediaUpdates.length, 1);
  assertEquals(
    admin.mediaUpdates[0].thumbnail_url,
    thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
  );

  assertEquals(admin.variantInserts.length, 1);
  const variant = admin.variantInserts[0] as unknown as VariantRow;
  assertEquals(variant.media_id, MEDIA);
  assertEquals(variant.variant_type, "thumbnail");
  assertEquals(variant.storage_provider, "cloudinary");
  // The variant's own public id — this is what the permanent-delete path
  // deletes, with no reference to the original.
  assertEquals(variant.storage_path, thumbnailPublicId(OWNER, MEDIA));
  assertEquals(uploads().length, 1);
});

Deno.test("ensure: a replayed finalize neither re-uploads nor duplicates the variant", async () => {
  const admin = new FakeAdmin();
  // The thumbnail is already recorded on the media row...
  const existing = mediaRow({
    thumbnail_url: thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
  });
  // ...and its variant row already exists.
  admin.variantRow = {
    id: "v1",
    media_id: MEDIA,
    variant_type: "thumbnail",
    storage_provider: "cloudinary",
    storage_asset_id: thumbnailPublicId(OWNER, MEDIA),
    storage_path: thumbnailPublicId(OWNER, MEDIA),
    storage_url: existing.thumbnail_url!,
  };
  const { fetchImpl, uploads } = makeFetch({ thumbnailPresent: true });

  const outcome = await ensurePersistentThumbnail(adminClient(admin), existing, { fetchImpl });

  assert(outcome.ok);
  assertEquals(outcome.reason, "ALREADY_PERSISTENT");
  assertEquals(outcome.persisted, false);
  assertEquals(admin.mediaUpdates.length, 0);
  assertEquals(admin.variantInserts.length, 0);
  assertEquals(admin.variantUpdates.length, 0);
  assertEquals(uploads().length, 0);
});

Deno.test("ensure: a partial earlier write is self-healed (URL present, variant missing)", async () => {
  const admin = new FakeAdmin();
  admin.variantRow = null; // the variant row never got written
  const existing = mediaRow({
    thumbnail_url: thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
  });
  const { fetchImpl, uploads } = makeFetch({ thumbnailPresent: true });

  const outcome = await ensurePersistentThumbnail(adminClient(admin), existing, { fetchImpl });

  assert(outcome.ok);
  assertEquals(outcome.persisted, true);
  assertEquals(admin.variantInserts.length, 1);
  assertEquals(uploads().length, 0);
});

Deno.test("ensure: a concurrent variant insert (23505) is success, not failure", async () => {
  const admin = new FakeAdmin();
  admin.insertError = { code: "23505", message: "duplicate key value" };
  const { fetchImpl } = makeFetch({});

  const outcome = await ensurePersistentThumbnail(adminClient(admin), mediaRow(), { fetchImpl });

  assert(outcome.ok);
  assertEquals(outcome.persisted, true);
});

Deno.test("ensure: skips the cases that cannot have a thumbnail, with precise reasons", async () => {
  const admin = new FakeAdmin();

  const deleted = await ensurePersistentThumbnail(adminClient(admin), mediaRow({ status: "DELETED" }));
  assertEquals(deleted.reason, "MEDIA_DELETED");

  const cleaned = await ensurePersistentThumbnail(
    adminClient(admin),
    mediaRow({ primary_cleanup_status: "cleanup_success", primary_deleted_at: "2026-01-01T00:00:00Z" }),
  );
  assertEquals(cleaned.reason, "ORIGINAL_ALREADY_CLEANED");

  const noOriginal = await ensurePersistentThumbnail(adminClient(admin), mediaRow({ storage_path: null }));
  assertEquals(noOriginal.reason, "NO_CLOUDINARY_ORIGINAL");

  const raw = await ensurePersistentThumbnail(
    adminClient(admin),
    mediaRow({ mime_type: "application/pdf", storage_path: `mydrive/${OWNER}/notes` }),
  );
  assertEquals(raw.reason, "UNSUPPORTED_RESOURCE_TYPE");

  // A skipped row must not have written anything.
  assertEquals(admin.mediaUpdates.length, 0);
  assertEquals(admin.variantInserts.length, 0);
});

Deno.test("ensure: a persistence failure is reported, never thrown at the caller", async () => {
  const admin = new FakeAdmin();
  admin.mediaUpdateError = { message: "permission denied for table media_assets" };
  const { fetchImpl } = makeFetch({});

  const outcome = await ensurePersistentThumbnail(adminClient(admin), mediaRow(), { fetchImpl });

  assertEquals(outcome.ok, false);
  assertEquals(outcome.reason, "PERSISTENCE_FAILED");
  assert(outcome.detail?.includes("permission denied"));
  // The Cloudinary asset exists even though the reference could not be stored;
  // the next call converges because the asset is addressed deterministically.
});

// ─── Final deletion lifecycle ───────────────────────────────────────────────

Deno.test("final delete: the thumbnail public id resolves from the variant, then the row", async () => {
  const admin = new FakeAdmin();
  admin.variantRow = {
    id: "v1",
    media_id: MEDIA,
    variant_type: "thumbnail",
    storage_provider: "cloudinary",
    storage_asset_id: thumbnailPublicId(OWNER, MEDIA),
    storage_path: thumbnailPublicId(OWNER, MEDIA),
    storage_url: thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
  };
  assertEquals(
    await resolveThumbnailPublicId(adminClient(admin), MEDIA, null),
    thumbnailPublicId(OWNER, MEDIA),
  );

  // Fallback: no variant row, but the media row still carries the URL.
  const noVariant = new FakeAdmin();
  assertEquals(
    await resolveThumbnailPublicId(
      adminClient(noVariant),
      MEDIA,
      thumbnailDeliveryUrl(CLOUD, thumbnailPublicId(OWNER, MEDIA)),
    ),
    thumbnailPublicId(OWNER, MEDIA),
  );

  // An ORIGINAL's URL must never be mistaken for a thumbnail.
  assertEquals(
    await resolveThumbnailPublicId(
      adminClient(new FakeAdmin()),
      MEDIA,
      `https://res.cloudinary.com/${CLOUD}/image/upload/mydrive/${OWNER}/IMG_1234.jpg`,
    ),
    null,
  );
});

Deno.test("final delete: clearing the reference removes the variant and the URL", async () => {
  const admin = new FakeAdmin();
  await clearThumbnailReference(adminClient(admin), MEDIA);
  assertEquals(admin.variantDeletes, 1);
  assertEquals(admin.mediaUpdates.length, 1);
  assertEquals(admin.mediaUpdates[0].thumbnail_url, null);
});
