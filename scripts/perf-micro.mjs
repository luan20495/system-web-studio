// @class: tooling (S4 audit). Node micro-benchmarks of the PURE functions behind the hot screens (no React, no browser, no backend): complexity growth with n.
// esbuild (outside the repo: npm i --prefix /tmp/esb esbuild) bundles each module for Node. Numbers are CPU time on this machine; use them for GROWTH (n -> 4n), not as absolute UI latency.
//   node scripts/perf-micro.mjs
import { createRequire } from "node:module";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";

const root = resolve(new URL("..", import.meta.url).pathname);
const esbuild = createRequire(join(process.env.ESBUILD_DIR ?? "/tmp/esb", "package.json"))("esbuild");
const out = mkdtempSync(join(tmpdir(), "xweb-micro-"));
const alias = { "@": root, "@xweb/types": join(root, "packages/types/src/index.ts"), "@xweb/permissions": join(root, "packages/permissions/src/index.ts"), "@xweb/ui": join(root, "packages/ui/src/index.ts"), "@xweb/i18n": join(root, "packages/i18n/src/index.ts"), "@xweb/api-client": join(root, "packages/api-client/src/index.ts") };
async function load(entry, name) {
  const outfile = join(out, `${name}.mjs`);
  await esbuild.build({ entryPoints: [join(root, entry)], bundle: true, outfile, format: "esm", platform: "node", alias, external: ["react", "react-dom", "next/*", "lucide-react", "@dnd-kit/*"], loader: { ".css": "empty" }, logLevel: "error" });
  return import(pathToFileURL(outfile).href);
}
const time = (f, reps = 5) => { f(); const ts = []; for (let i = 0; i < reps; i++) { const t = performance.now(); f(); ts.push(performance.now() - t); } ts.sort((a, b) => a - b); return ts[Math.floor(ts.length / 2)]; };
const rows = [];
const add = (what, n, ms, note = "") => rows.push({ what, n, ms, note });

try {
  const dnd = await load("features/studio/builder/core/dnd.ts", "dnd");
  const prev = await load("lib/schema-preview.ts", "prev");
  const pf = await load("features/studio/builder/core/preflight.ts", "pf");
  const org = await load("features/admin/organizationModel.ts", "org");
  const types = ["Hero", "Testimonials", "ContactForm"];
  const sectionsOf = (n) => Array.from({ length: n }, (_, i) => ({ id: `s${i}`, type: types[i % 3], props: { title: `Khối ${i}`, heading: `Khối ${i}`, subtitle: "Mô tả" } }));
  for (const n of [50, 100, 400, 1000, 2000]) {   // 50 = the contract maximum (PageSchemaValidator.MAX_SECTIONS), the rest is stress
    const sections = sectionsOf(n);
    // what PagesPanel does on every render: canStep(up) + canStep(down) for EVERY section (planStep -> planMove -> findIndex + filter copy)
    add("PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63)", n, time(() => { for (const s of sections) { dnd.canStep(sections, s.id, -1); dnd.canStep(sections, s.id, 1); } }));
    const doc = { page: "Home", sections, pages: [], site: { navigation: [] } };
    add("renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163)", n, time(() => prev.renderSchemaDocument(doc, { selectedId: `s${n >> 1}`, interactive: true, nonce: "", assets: {}, pageId: "home" })));
    add("preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change)", n, time(() => pf.preflight(doc)));
  }
  for (const n of [500, 2000, 8000, 20000]) {
    const units = Array.from({ length: n }, (_, i) => ({ id: `g${i}`, parentId: i === 0 ? null : `g${Math.floor((i - 1) / 4)}`, typeId: "t", name: `Đơn vị ${String(i).padStart(5, "0")} phòng ban ${i}`, enabled: true, version: 1 }));
    add("buildTree (sort with localeCompare('vi') + wire)", n, time(() => org.buildTree(units), 3));
    const tree = org.buildTree(units); const open = new Set(units.map((u) => u.id));
    add("flattenTree all open", n, time(() => org.flattenTree(tree, open)));
    add("moveTargets (opens the move dialog: buildTree + flatten + per-row rules)", n, time(() => org.moveTargets(units, [], "g5"), 3));
    add("unitPath (builds a Map of ALL units per call; EmployeesScreens.tsx:88 calls it per row when the server gives no unit name)", n, time(() => { for (let i = 0; i < 20; i++) org.unitPath(units, `g${n - 1 - i}`); }), "20 rows");
    const members = Array.from({ length: n }, (_, i) => ({ tenantId: "t", userId: `u${i}`, role: "MEMBER", active: i % 7 !== 0, username: `user${i}`, displayName: `Nhân viên ${i}`, email: `u${i}@acme.vn` }));
    add("employeesFromMembers: first call (sort + fold keys of the whole member list)", n, time(() => org.employeesFromMembers(members.map((m) => ({ ...m })), { q: "nhan vien 9", page: 0, size: 20 }), 3));
    org.employeesFromMembers(members, { q: "", page: 0, size: 20 });
    add("employeesFromMembers: later call on the same list (prepared cache)", n, time(() => org.employeesFromMembers(members, { q: "nhan vien 9", page: 1, size: 20 })));
  }
} finally { rmSync(out, { recursive: true, force: true }); }
console.log("Node micro-benchmarks (median of 5 after 1 warm-up; ms). Pure functions only; HARNESS-class evidence, not a backend measure.\n| what | n | ms | note |\n|---|---:|---:|---|");
for (const r of rows) console.log(`| ${r.what} | ${r.n} | ${r.ms.toFixed(2)} | ${r.note} |`);
