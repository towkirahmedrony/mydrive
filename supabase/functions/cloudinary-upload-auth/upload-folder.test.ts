/**
 * Security tests for Cloudinary upload-folder isolation.
 *
 * Run with:
 *   deno test supabase/functions/cloudinary-upload-auth/upload-folder.test.ts
 */

import { assertEquals, assertNotEquals } from "jsr:@std/assert@1";
import {
  cloudinaryUploadFolder,
  selectUploadFolder,
} from "./upload-folder.ts";

const USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

Deno.test("Test 1 — Normal upload uses authenticated user folder", () => {
  assertEquals(cloudinaryUploadFolder(USER_A), `mydrive/${USER_A}`);
  assertEquals(selectUploadFolder(USER_A, {}), `mydrive/${USER_A}`);
});

Deno.test("Test 2 — Malicious folder override is ignored", () => {
  const folder = selectUploadFolder(USER_A, {
    folder: `mydrive/${USER_B}`,
  });
  assertEquals(folder, `mydrive/${USER_A}`);
  assertNotEquals(folder, `mydrive/${USER_B}`);
});

Deno.test("Test 3 — Arbitrary / traversal folder is ignored", () => {
  assertEquals(
    selectUploadFolder(USER_A, { folder: "../../something" }),
    `mydrive/${USER_A}`,
  );
  assertEquals(
    selectUploadFolder(USER_A, { folder: "another-user" }),
    `mydrive/${USER_A}`,
  );
  assertEquals(
    selectUploadFolder(USER_A, { folder: "/etc/passwd" }),
    `mydrive/${USER_A}`,
  );
  assertEquals(
    selectUploadFolder(USER_A, { folder: "" }),
    `mydrive/${USER_A}`,
  );
});

Deno.test("Client folder field is never mixed into the signed folder", () => {
  const tampered = selectUploadFolder(USER_A, {
    folder: `mydrive/${USER_B}`,
    resource_type: "image",
  });
  assertEquals(tampered, cloudinaryUploadFolder(USER_A));
});
