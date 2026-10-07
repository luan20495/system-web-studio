// @class: harness — build step of the C2-runtime fixture page used by tests/browser/publicdata.spec.mjs (no backend)
// Builds the fixture of a data-bound published page USING C2's OWN CODE (read from git, default ref c1e0df5, override C2_REF): `resolveBindings` + `renderSitePages` + the runtime script.
// Nothing of C2's is copied into the repo; the output goes to .test-build/browser/c2/. If the ref is not available the fixture is NOT built and the spec reports its checks as SKIPPED (never as passed).
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { createRequire } from "node:module";
export async function buildC2Site(root, outDir, esbuild) {
  const ref = process.env.C2_REF || "c1e0df5";
  const require = createRequire(join(root, "package.json"));
  const ts = require("typescript");
  const show = (p) => execFileSync("git", ["show", `${ref}:${p}`], { cwd: root, encoding: "utf8", maxBuffer: 1 << 24, stdio: ["ignore", "pipe", "ignore"] });
  let files;
  try { files = { "lib/schema-preview.ts": show("lib/schema-preview.ts"), "workers/render/page-runtime.ts": show("workers/render/page-runtime.ts") }; }
  catch { console.log(`c2 fixture: ref ${ref} not available, NOT built`); return false; }
  files["lib/preview-document.ts"] = readFileSync(join(root, "lib/preview-document.ts"), "utf8");
  const tmp = mkdtempSync(join(tmpdir(), "c2-site-"));
  for (const [p, src] of Object.entries(files)) {
    const out = join(tmp, p.replace(/\.ts$/, ".cjs")); mkdirSync(dirname(out), { recursive: true });
    // the modules import each other as "./x" / "../../lib/x": point them at the .cjs files
    const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/require\("(\.[^"]+)"\)/g, 'require("$1.cjs")');
    writeFileSync(out, js);
  }
  const { resolveBindings, PAGE_RUNTIME_JS, PAGE_RUNTIME_PATH } = require(join(tmp, "workers/render/page-runtime.cjs"));
  const { renderSitePages } = require(join(tmp, "lib/schema-preview.cjs"));
  const schema = {
    page: "Trang dữ liệu", pages: [],
    sections: [
      { id: "hero", type: "Hero", props: { eyebrow: "Giới thiệu", title: "Tiêu đề viết tay", description: "Mô tả viết tay", ctaLabel: "Mua", ctaHref: "#" } },
      { id: "grid", type: "ProductGrid", props: { heading: "Sản phẩm", items: [{ id: "i1", name: "Mẫu viết tay", description: "Mô tả mẫu", image: "" }] } },
      { id: "foot", type: "Footer", props: { text: "© viết tay" } },
    ],
    queries: [{ id: "q-title", dataSourceRef: "orders", mode: "READ", public: true }, { id: "q-items", dataSourceRef: "orders", mode: "READ", public: true }],
    dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-title" }, { id: "b2", sectionId: "grid", prop: "items", queryRef: "q-items" }, { id: "b3", sectionId: "foot", prop: "text", queryRef: "q-title" }],
  };
  const bindings = resolveBindings(schema);
  const pages = renderSitePages(schema, {}, bindings);
  const site = join(outDir, "c2", "site"); mkdirSync(join(site, "_runtime"), { recursive: true });
  // C5's passive observer (reads C2's attributes through C5's vocabulary); loaded after C2's own runtime script
  await esbuild.build({ entryPoints: [join(root, "tests/browser/c2-observer.ts")], bundle: true, outfile: join(outDir, "c2", "observer.js"), format: "iife", platform: "browser", logLevel: "warning" });
  writeFileSync(join(site, "index.html"), pages["index.html"].replace("</body>", '<script src="../observer.js"></script></body>'));
  writeFileSync(join(site, PAGE_RUNTIME_PATH), PAGE_RUNTIME_JS);
  writeFileSync(join(outDir, "c2", "bindings.json"), JSON.stringify({ ref, bindings }, null, 1));
  console.log(`c2 fixture: built from ${ref} (${bindings.length} bindings resolved by C2's resolveBindings)`);
  return true;
}
