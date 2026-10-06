// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * Conformance against the SINGLE shared fixture set owned by C2 (docs/contracts/v2/integration-contract.md, decision 2026-10-06):
 * integration/v2 backend/src/test/resources/app-definition/conformance/ (+ manifest.json). That directory is NOT copied into this repo (one copy, no mirror).
 * Point XWEB_CONFORMANCE_DIR at its parent `app-definition/` directory (after the C2 import, backend/src/test/resources/app-definition). Without it
 * the tests are SKIPPED and say so; they never pass vacuously.
 *
 * What the client-side validator must do: accept every VALID fixture, and report the manifest `paths` of the INVALID fixtures it is responsible for.
 * `unknown fields` are closed-world rejected by the server only (SCHEMA_INVALID 422): the Builder never writes unknown keys and keeps the ones it reads.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { validateDefinition } from "../../features/studio/builder/core/definition";

const root = process.env.XWEB_CONFORMANCE_DIR;
const dir = root ? join(root, "conformance") : "";
const available = !!root && existsSync(join(dir, "manifest.json"));
const skip = available ? false : "XWEB_CONFORMANCE_DIR not set or manifest.json missing: conformance NOT run (see docs/parallel/c5/PHASE3_AUDIT.md)";

type Entry = { file: string; expect: "VALID" | "INVALID"; paths?: string[] };
const manifest: { fixtures: Entry[] } = available ? JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8")) : { fixtures: [] };
/** the server owns these checks (closed-world JSON); the client validator does not claim them */
const SERVER_ONLY = new Set(["invalid-unknown-definition-field.json"]);

test("conformance: manifest is present and lists fixtures", { skip }, () => assert.ok(manifest.fixtures.length >= 16));

for (const f of manifest.fixtures) {
  if (f.expect === "VALID") {
    test(`conformance VALID ${f.file}: no client-side issue`, { skip }, () => {
      const issues = validateDefinition(JSON.parse(readFileSync(join(dir, f.file), "utf8")));
      assert.deepEqual(issues.map((i) => i.path), []);
    });
  } else if (!SERVER_ONLY.has(f.file)) {
    test(`conformance INVALID ${f.file}: reports every manifest path`, { skip }, () => {
      const got = new Set(validateDefinition(JSON.parse(readFileSync(join(dir, f.file), "utf8"))).map((i) => i.path));
      for (const p of f.paths ?? []) assert.ok(got.has(p), `missing ${p}; got ${[...got].join(", ")}`);
    });
  }
}
