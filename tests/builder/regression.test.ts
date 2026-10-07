// @class: unit — regressions of bugs the first real-backend run found: build-time API proxy target and the runbook that must not say otherwise. No backend, no network.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createNextConfig } from "../../packages/auth/src/server/nextConfig";

const root = join(__dirname, "..", "..", "..");
const withEnv = async <T,>(value: string | undefined, fn: () => Promise<T> | T): Promise<T> => {
  const before = process.env.API_PROXY_TARGET;
  if (value === undefined) delete process.env.API_PROXY_TARGET; else process.env.API_PROXY_TARGET = value;
  try { return await fn(); } finally { if (before === undefined) delete process.env.API_PROXY_TARGET; else process.env.API_PROXY_TARGET = before; }
};
type Rewrites = { beforeFiles: { source: string; destination: string }[] };
const apiDestination = async (cfg: ReturnType<typeof createNextConfig>) => ((await cfg.rewrites!()) as unknown as Rewrites).beforeFiles.find((r) => r.source === "/api/:path*")!.destination;

test("API_PROXY_TARGET is captured when the config is created (next build), not when the server starts", async () => {
  const cfg = await withEnv("http://127.0.0.1:38080/", () => createNextConfig({ http: true }));
  assert.equal(await withEnv("http://127.0.0.1:9999", () => apiDestination(cfg)), "http://127.0.0.1:38080/api/:path*", "a later environment change must not move the proxy (that is why `next start` ignores it)");
});

test("without API_PROXY_TARGET the proxy defaults to :8080; a trailing slash is removed", async () => {
  assert.equal(await withEnv(undefined, async () => apiDestination(createNextConfig({ http: true }))), "http://127.0.0.1:8080/api/:path*");
  assert.equal(await withEnv("http://api.test:1///", async () => apiDestination(createNextConfig({ http: true }))), "http://api.test:1/api/:path*");
});

test("the runbook and the Mac record never tell the operator to set API_PROXY_TARGET on `next start` alone", () => {
  for (const f of ["docs/C5_REAL_BACKEND_E2E_RUNBOOK.md", "docs/parallel/c5/MAC_RUN_2026-10-06.md"]) {
    const text = readFileSync(join(root, f), "utf8");
    assert.match(text, /build time|BAKED IN AT BUILD TIME|\*\*build time\*\*/i, `${f} must say the target is a build-time setting`);
    for (const line of text.split("\n")) {
      if (/API_PROXY_TARGET=\S+/.test(line) && /next start/.test(line)) assert.match(line, /build/, `${f}: API_PROXY_TARGET on a \`next start\` line without a build: ${line.trim()}`);
    }
  }
});
