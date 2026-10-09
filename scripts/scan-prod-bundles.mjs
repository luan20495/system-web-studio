#!/usr/bin/env node
// Production bundle scan (D-C0-44, guard 4): what the BROWSER receives from Platform / Admin / Studio must not name this machine or its containers.
//   node scripts/scan-prod-bundles.mjs [--root DIR] [--apps platform,admin,studio] [--dist .next] [--json]
// Scanned (browser-visible): <dist>/static/**  (client JS / CSS / chunks)  and  <dist>/server/app/** *.html *.rsc *.segment.rsc *.meta (prerendered output sent to the browser).
// NOT scanned: <dist>/*.json manifests and server-only chunks: routes-manifest / required-server-files legitimately hold the server-side proxy target (API_PROXY_TARGET) that never reaches a browser.
// Findings: localhost · 127.0.0.1 / 0.0.0.0 / ::1 · host.docker.internal · a docker compose service name used as a host (read from compose*.yml) · hbl-/hblpub- container names · a private IPv4 with a port.
// Documented harmless library strings are allowed through scripts/prod-bundle-allowlist.json (kind + context regex + reason).
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative, sep } from "node:path";

const argv = process.argv.slice(2); const arg = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const ROOT = arg("--root", new URL("..", import.meta.url).pathname.replace(/\/$/, ""));
const APPS = arg("--apps", "platform,admin,studio").split(",").filter(Boolean); const DIST = arg("--dist", ".next"); const JSON_OUT = argv.includes("--json");
const ALLOW = (() => { try { return JSON.parse(readFileSync(arg("--allowlist", join(new URL("..", import.meta.url).pathname, "scripts/prod-bundle-allowlist.json")), "utf8")).allow ?? []; } catch { return []; } })();

/** docker compose service names of this repo (they resolve only inside the compose network) */
export function composeServices(root) {
  const names = new Set();
  for (const f of ["compose.yml", "compose.public.yml"]) {
    let t; try { t = readFileSync(join(root, f), "utf8"); } catch { continue; }
    const m = /^services:\s*\n([\s\S]*?)(?:^\S|(?![\s\S]))/m.exec(t); if (!m) continue;
    for (const k of m[1].matchAll(/^  ([a-z][a-z0-9_-]+):\s*(?:#.*)?$/gm)) names.add(k[1]);
  }
  return [...names];
}
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

export function buildPatterns(root) {
  const svc = composeServices(root).filter((n) => n.length >= 4);
  const list = [
    { kind: "localhost", re: /\blocalhost\b/gi },
    { kind: "loopback", re: /\b127\.\d{1,3}\.\d{1,3}\.\d{1,3}\b|\b0\.0\.0\.0\b|\[::1\]/g },
    { kind: "docker-host", re: /\bhost\.docker\.internal\b|\bgateway\.docker\.internal\b/g },
    { kind: "container-name", re: /\bhbl(?:pub)?-[a-z0-9-]+-\d\b|\bhbl(?:pub)?_[a-z0-9_]+_\d\b/g },
    { kind: "private-ip", re: /\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}):\d{2,5}\b/g },
  ];
  if (svc.length) list.push({ kind: "compose-service", re: new RegExp(`(?:[a-z][a-z0-9+.-]*:\\/\\/|["'\`@])(?:${svc.map(esc).join("|")})(?::\\d{2,5}|\\/)`, "g") });
  return list;
}

function* files(dir) {
  let names; try { names = readdirSync(dir); } catch { return; }
  for (const n of names) { const p = join(dir, n); const s = statSync(p); if (s.isDirectory()) yield* files(p); else yield p; }
}
const browserFiles = (appDir) => {
  const out = []; const d = join(appDir, DIST);
  for (const f of files(join(d, "static"))) if (/\.(js|css|mjs|html|json|txt|map)$/.test(f) && !/\.map$/.test(f)) out.push(f);
  for (const f of files(join(d, "server", "app"))) if (/\.(html|rsc|meta|txt)$/.test(f)) out.push(f);
  return out;
};

export function scanApp(root, app) {
  const appDir = join(root, "apps", app); const findings = []; const matched = new Set(); const pats = buildPatterns(root); let scanned = 0;
  if (!existsSync(join(appDir, DIST, "static"))) return { app, scanned: 0, findings: [{ app, kind: "no-build", file: `apps/${app}/${DIST}`, message: "no production build to scan (run the portal build first)" }], matched };
  for (const f of browserFiles(appDir)) {
    const t = readFileSync(f, "utf8"); scanned++;
    for (const p of pats) {
      const re = new RegExp(p.re.source, p.re.flags); let m;
      while ((m = re.exec(t))) {
        const ctx = t.slice(Math.max(0, m.index - 60), m.index + m[0].length + 60);
        const al = ALLOW.findIndex((a) => a.kind === p.kind && new RegExp(a.context).test(ctx));
        if (al >= 0) { matched.add(al); continue; }
        findings.push({ app, kind: p.kind, file: relative(root, f).split(sep).join("/"), message: `'${m[0]}' in a browser-visible production file`, excerpt: ctx.replace(/\s+/g, " ") });
      }
    }
  }
  return { app, scanned, findings, matched };
}

export function scanAll(root = ROOT, apps = APPS) {
  const runs = apps.map((a) => scanApp(root, a)); const stale = ALLOW.map((a, i) => ({ a, i })).filter(({ i }) => !runs.some((r) => r.matched.has(i))).map(({ a }) => `${a.kind} / ${a.context.slice(0, 40)}`);
  return { runs, findings: runs.flatMap((r) => r.findings), stale };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { runs, findings, stale } = scanAll();
  if (JSON_OUT) { console.log(JSON.stringify({ findings })); process.exit(findings.length ? 1 : 0); }
  for (const r of runs) console.log(`${r.findings.length ? "FAIL" : "PASS"} ${r.app}: ${r.scanned} browser-visible file(s) scanned, ${r.findings.length} finding(s)`);
  for (const f of findings.slice(0, 40)) console.log(`  [${f.kind}] ${f.file}\n      ${f.message}${f.excerpt ? " ... " + f.excerpt.slice(0, 140) : ""}`);
  if (stale.length) console.log(`note: allow-list entries that matched nothing in this scan: ${stale.join(" ; ")}`);
  process.exit(findings.length ? 1 : 0);
}
