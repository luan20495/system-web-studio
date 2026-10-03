// Bootstrap config for the package mirror before the API is running: the scaffold lockfile + infra/verdaccio/extra-packages.txt.
// At runtime the build runner keeps the config in sync with the approved package catalog (ADR 0013).
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { verdaccioConfig } from "../workers/runner/verdaccio-config.mjs";
const root = new URL("..", import.meta.url).pathname;
const names = new Set();
for (const k of Object.keys(JSON.parse(readFileSync(root + "backend/src/main/resources/scaffolds/react-vite/package-lock.json", "utf8")).packages ?? {})) if (k) names.add(k.slice(k.lastIndexOf("node_modules/") + 13));
const extra = root + "infra/verdaccio/extra-packages.txt";
if (existsSync(extra)) for (const l of readFileSync(extra, "utf8").split("\n")) { const n = l.replace(/#.*/, "").trim(); if (n) names.add(n); }
writeFileSync(root + "infra/verdaccio/config.yaml", verdaccioConfig([...names]));
console.log(`allowlist: ${names.size} packages -> infra/verdaccio/config.yaml`);
