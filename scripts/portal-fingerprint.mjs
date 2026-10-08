#!/usr/bin/env node
// @class: unit
// Deterministic BUILD FINGERPRINT of one portal (D-C0-45): what `next build` of apps/<app> reads, hashed by CONTENT, so that "was this build made from this source?" has an answer.
//   node scripts/portal-fingerprint.mjs --app admin [--root DIR] [--meta | --list | --explain] [-- KEY=VALUE ...]      (the KEY=VALUE words after `--` are the build environment)
// Inputs (existing files only; a deleted file therefore changes the hash):
//   root      package.json  package-lock.json  tsconfig*.json  next.config.*  .npmrc  .nvmrc  .node-version
//   shared    packages/**  features/**  components/**  lib/**  app/**  public/**
//   the app   apps/<app>/**           (NOT the other two apps: a change in admin does not rebuild platform)
//   build env the KEY=VALUE words (NEXT_PUBLIC_*, API_PROXY_TARGET, ...), sorted;  the Node major version
// Never hashed: node_modules, every .next* directory, *.tsbuildinfo, next-env.d.ts (generated), logs, .DS_Store, .git, .run.
// A dirty working tree is fine: contents are hashed, not commits (git is only used to LIST files, tracked + untracked-not-ignored; without git the tree is walked).
// Output: 16 hex characters.  --meta: "<short commit> <dirty 0|1>" of the inputs.  --list: the hashed files.  --explain: counts and per-section digests.
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const argv = process.argv.slice(2); const dd = argv.indexOf("--"); const flags = dd >= 0 ? argv.slice(0, dd) : argv; const envWords = dd >= 0 ? argv.slice(dd + 1) : [];
const opt = (n, d) => { const i = flags.indexOf(n); return i >= 0 ? flags[i + 1] : d; };
const ROOT = opt("--root", new URL("..", import.meta.url).pathname.replace(/\/$/, "")); const APP = opt("--app");
if (!APP) { console.error("usage: portal-fingerprint.mjs --app <platform|admin|studio> [--root DIR] [--meta|--list|--explain] [-- KEY=VAL ...]"); process.exit(64); }

const ROOT_FILES = /^(package\.json|package-lock\.json|tsconfig[^/]*\.json|next\.config\.[^/]+|\.npmrc|\.nvmrc|\.node-version)$/;
const SHARED = ["packages", "features", "components", "lib", "app", "public"];
const EXCLUDE = /(^|\/)(node_modules|\.git|\.run|\.test-build)(\/|$)|(^|\/)\.next[^/]*(\/|$)|\.tsbuildinfo$|(^|\/)next-env\.d\.ts$|\.log$|(^|\/)\.DS_Store$/;

function walk(dir, acc) { let names; try { names = readdirSync(dir); } catch { return acc; } for (const n of names) { const p = join(dir, n); const rel = relative(ROOT, p).split(sep).join("/"); if (EXCLUDE.test(rel)) continue; let s; try { s = statSync(p); } catch { continue; } if (s.isDirectory()) walk(p, acc); else acc.push(rel); } return acc; }
function listCandidates() {
  const specs = [":(top)package.json", ":(top)package-lock.json", ":(top,glob)tsconfig*.json", ":(top,glob)next.config.*", ":(top).npmrc", ":(top).nvmrc", ":(top)node-version", ":(top).node-version", ...SHARED, `apps/${APP}`];
  try { const out = execFileSync("git", ["ls-files", "-z", "-co", "--exclude-standard", "--", ...specs], { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], maxBuffer: 64 * 1024 * 1024 }); return out.split("\0").filter(Boolean); }
  catch { return [...readdirSync(ROOT).filter((n) => ROOT_FILES.test(n)), ...SHARED.flatMap((d) => walk(join(ROOT, d), [])), ...walk(join(ROOT, "apps", APP), [])]; }
}
// only what this portal's build reads: root config files (no slash), the shared directories, and ITS OWN app directory
const wanted = (f) => ROOT_FILES.test(f) || SHARED.some((d) => f.startsWith(d + "/")) || f.startsWith(`apps/${APP}/`);
const files = [...new Set(listCandidates())].filter((f) => wanted(f) && !EXCLUDE.test(f) && existsSync(join(ROOT, f)) && statSync(join(ROOT, f)).isFile()).sort();
const h = (b) => createHash("sha256").update(b).digest("hex");
const lines = files.map((f) => `${f}\0${h(readFileSync(join(ROOT, f)))}`);
const section = (re) => h(lines.filter((l) => re.test(l)).join("\n")).slice(0, 12);
const envLines = [...envWords].sort();
const digest = h([`APP:${APP}`, `NODE:${process.versions.node.split(".")[0]}`, ...envLines.map((e) => `ENV:${e}`), ...lines].join("\n")).slice(0, 16);

if (flags.includes("--list")) console.log(files.join("\n"));
else if (flags.includes("--meta")) {
  let commit = "nogit", dirty = 0;
  try {
    commit = execFileSync("git", ["rev-parse", "--short=12", "HEAD"], { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    dirty = execFileSync("git", ["status", "--porcelain", "--", "package.json", "package-lock.json", ...SHARED, `apps/${APP}`], { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).split("\n").some((l) => l.trim() && !EXCLUDE.test(l.slice(3))) ? 1 : 0;
  } catch { /* no git: the fingerprint is still exact */ }
  console.log(`${commit} ${dirty}`);
} else if (flags.includes("--explain")) {
  console.log(`app=${APP} files=${files.length} node=${process.versions.node.split(".")[0]} env=${envLines.length}`);
  console.log(`  shared packages ${section(/^packages\//)}  features ${section(/^features\//)}  components ${section(/^components\//)}  lib ${section(/^lib\//)}  app ${section(/^app\//)}  apps/${APP} ${section(new RegExp(`^apps/${APP}/`))}`);
  console.log(`  fingerprint=${digest}`);
} else console.log(digest);
