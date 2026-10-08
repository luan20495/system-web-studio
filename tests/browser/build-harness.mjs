// Bundles tests/browser/harness.tsx for the browser. esbuild is NOT a repo dependency: `npm i --prefix /tmp/esb esbuild` and pass ESBUILD_DIR.
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
const root = resolve(new URL("../..", import.meta.url).pathname);
const esbuild = createRequire(join(process.env.ESBUILD_DIR ?? "/tmp/esb", "package.json"))("esbuild");
// HARNESS_NODE_ENV=production builds the production React bundle into .test-build/browser-prod (the sanity spec runs against it: the development build keeps debug references to removed DOM)
const nodeEnv = process.env.HARNESS_NODE_ENV === "production" ? "production" : "development";
const out = join(root, ".test-build", nodeEnv === "production" ? "browser-prod" : "browser");
mkdirSync(out, { recursive: true });
const alias = { "@": root, "@xweb/types": join(root, "packages/types/src/index.ts"), "@xweb/permissions": join(root, "packages/permissions/src/index.ts"),
  "@xweb/ui": join(root, "packages/ui/src/index.ts"), "@xweb/i18n": join(root, "packages/i18n/src/index.ts"), "@xweb/api-client": join(root, "packages/api-client/src/index.ts") };
await esbuild.build({ entryPoints: [join(root, "tests/browser/harness.tsx"), join(root, "tests/browser/ds-harness.tsx"), join(root, "tests/browser/release-harness.tsx"), join(root, "tests/browser/public-harness.tsx"), join(root, "tests/browser/prov-harness.tsx"), join(root, "tests/browser/org-harness.tsx"), join(root, "tests/browser/ai-harness.tsx")], bundle: true, outdir: out, format: "iife", jsx: "automatic", platform: "browser",
  define: { "process.env": "{}", "process.env.NODE_ENV": JSON.stringify(nodeEnv), "process.env.NEXT_PUBLIC_PORTAL_URL_PLATFORM": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_ADMIN": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_STUDIO": "undefined" },
  alias, loader: { ".css": "css" }, logLevel: "warning", minify: nodeEnv === "production" });
writeFileSync(join(out, "index.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Builder harness</title><link rel="stylesheet" href="harness.css"></head><body><div id="root"></div><script src="harness.js"></script></body></html>`);
writeFileSync(join(out, "ds.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Data sources harness</title><link rel="stylesheet" href="ds-harness.css"></head><body><div id="root"></div><script src="ds-harness.js"></script></body></html>`);
writeFileSync(join(out, "release.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Release harness</title><link rel="stylesheet" href="release-harness.css"></head><body><div id="root"></div><script src="release-harness.js"></script></body></html>`);
writeFileSync(join(out, "public.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Public data harness</title><link rel="stylesheet" href="public-harness.css"></head><body><div id="root"></div><script src="public-harness.js"></script></body></html>`);
writeFileSync(join(out, "prov.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Provisioning harness</title><link rel="stylesheet" href="prov-harness.css"></head><body><div id="root"></div><script src="prov-harness.js"></script></body></html>`);
writeFileSync(join(out, "ai.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AI providers harness</title><link rel="stylesheet" href="ai-harness.css"></head><body><div id="root"></div><script src="ai-harness.js"></script></body></html>`);
writeFileSync(join(out, "org.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Organization harness</title><link rel="stylesheet" href="org-harness.css"></head><body><div id="root"></div><script src="org-harness.js"></script></body></html>`);
// Studio-app harness: the real StudioApp behind the portal entry, next/navigation + next/link replaced by stubs (tests/browser/studio-app/). Same React mode as the other pages.
await esbuild.build({ entryPoints: [join(root, "tests/browser/studio-app/entry.tsx")], bundle: true, outfile: join(out, "studio-app.js"), format: "iife", jsx: "automatic", platform: "browser", nodePaths: [join(root, "node_modules")],
  define: { "process.env": "{}", "process.env.NODE_ENV": JSON.stringify(nodeEnv), "process.env.NEXT_PUBLIC_PORTAL_URL_PLATFORM": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_ADMIN": "undefined", "process.env.NEXT_PUBLIC_PORTAL_URL_STUDIO": "undefined" },
  alias: { ...alias, "@xweb/auth": join(root, "packages/auth/src/index.ts"), "next/navigation": join(root, "tests/browser/studio-app/nav-stub.ts"), "next/link": join(root, "tests/browser/studio-app/link-stub.tsx") },
  loader: { ".css": "css" }, logLevel: "warning", minify: nodeEnv === "production" });
writeFileSync(join(out, "studio.html"), `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Studio app harness</title><link rel="stylesheet" href="studio-app.css"></head><body><div id="root"></div><script src="studio-app.js"></script></body></html>`);
await (await import("./build-c2-site.mjs")).buildC2Site(root, out, esbuild);
console.log("built", out);
