// Bundles tests/browser/harness.tsx for the browser. esbuild is NOT a repo dependency: `npm i --prefix /tmp/esb esbuild` and pass ESBUILD_DIR.
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
const root = resolve(new URL("../..", import.meta.url).pathname);
const esbuild = createRequire(join(process.env.ESBUILD_DIR ?? "/tmp/esb", "package.json"))("esbuild");
const out = join(root, ".test-build", "browser");
mkdirSync(out, { recursive: true });
const alias = { "@": root, "@xweb/types": join(root, "packages/types/src/index.ts"), "@xweb/permissions": join(root, "packages/permissions/src/index.ts"),
  "@xweb/ui": join(root, "packages/ui/src/index.ts"), "@xweb/i18n": join(root, "packages/i18n/src/index.ts"), "@xweb/api-client": join(root, "packages/api-client/src/index.ts") };
await esbuild.build({ entryPoints: [join(root, "tests/browser/harness.tsx")], bundle: true, outdir: out, format: "iife", jsx: "automatic", platform: "browser",
  define: { "process.env.NODE_ENV": '"development"', "process.env.NEXT_PUBLIC_PORTAL_URL_PLATFORM": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_ADMIN": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_STUDIO": "undefined" },
  alias, loader: { ".css": "css" }, logLevel: "warning" });
writeFileSync(join(out, "index.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Builder harness</title><link rel="stylesheet" href="harness.css"></head><body><div id="root"></div><script src="harness.js"></script></body></html>`);
console.log("built", out);
