// @class: unit — GUARD (M-053): the Platform / Admin console chunk stays small. The system / governance sections are fetched on first visit (`lazy` in console/sections.tsx); only the landing page and the
// company / user / people / organization screens are imported statically. Measured 2026-10-09 (Next 16 production build, apps/admin): the console chunk 345.9 -> 178.8 KB raw (93.3 -> 48.5 KB gzip), first load unchanged.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// __dirname = <root>/.test-build/tests/builder when compiled
const src = readFileSync(join(__dirname, "..", "..", "..", "features/admin/console/sections.tsx"), "utf8");
const EAGER_OK = new Set(["../pages/Overview", "../pages/UsersPages", "../TenantScreens", "../OrganizationLive", "../ProvisioningLive", "../shared/peopleSections", "./sectionPolicy"]);

test("GUARD: sections.tsx imports a page statically only for the landing page and the company / user / people / organization screens", () => {
  const staticFrom = src.split("\n").filter((l) => l.startsWith("import ") && !l.startsWith("import type")).map((l) => l.slice(l.lastIndexOf(" from \"") + 7, l.lastIndexOf("\"")));
  const pages = staticFrom.filter((f) => f.startsWith("../") || f.startsWith("./"));
  const heavy = pages.filter((f) => !EAGER_OK.has(f));
  assert.deepEqual(heavy, [], "make it `lazy(() => import(...))` like the other sections");
});

test("GUARD: the lazy sections really are lazy imports, and the page wrapper is a Suspense boundary", () => {
  const lazies = src.split("lazy(() => import(\"").slice(1).map((chunk) => chunk.slice(0, chunk.indexOf("\"")));
  for (const f of ["./AiSection", "../pages/AuditPage", "../pages/ApplicationsPages", "../pages/OperationsPages", "../pages/RegistryPages", "../pages/SystemPages", "../pages/IdentityPages", "../pages/AiGovernancePage"]) assert.ok(lazies.includes(f), f);
  const routes = readFileSync(join(__dirname, "..", "..", "..", "features/admin/console/routes.tsx"), "utf8");
  assert.match(routes, /<Suspense fallback=\{<PageLoading\/>\}>/);
  assert.doesNotMatch(routes.slice(routes.indexOf("function PageLoading"), routes.indexOf("function PageLoading") + 160), /level=\{1\}/, "a loading fallback must not render an h1: useMain moves focus to the page's h1");
});

test("GUARD (M-053): the Studio's code-project workspace is its own chunk (a page project never loads it), behind a Suspense boundary", () => {
  const pw = readFileSync(join(__dirname, "..", "..", "..", "features/studio/ProjectWorkspace.tsx"), "utf8");
  assert.match(pw, /lazy\(\(\) => import\("\.\/CodeWorkspace"\)/);
  assert.doesNotMatch(pw, /^import \{ CodeWorkspace \} from/m, "a static import would put it back in the Studio shell chunk");
  assert.match(pw, /<Suspense fallback=[^>]*>[\s\S]{0,160}<CodeWorkspace /);
});
