#!/usr/bin/env node
// Builds a UI fixture for the PUBLIC hosts from .run/public/* (operator → super admin; demo01 → workspace admin). No ids are known there, so detail routes are skipped by ui-ux.mjs.
// Usage: node ui-public-fixture.mjs > $FIXTURE  (mode 600, outside the repo). Nothing is printed except the JSON written to stdout.
import { readFileSync } from "node:fs";
const R = "/Users/hoangluan/code/HBL/.run/public/"; const env = Object.fromEntries(readFileSync(R + "public.env", "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const demo = readFileSync(R + "demo-accounts.txt", "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p.length >= 2 && /^demo/.test(p[0]));
const su = { username: env.BOOTSTRAP_ADMIN_USERNAME, password: env.BOOTSTRAP_ADMIN_PASSWORD }, wa = { username: demo[0], password: demo[demo.length - 1] };
process.stdout.write(JSON.stringify({ tag: "public", accounts: { superAdmin: su, tenantAdmin: su, workspaceAdmin: wa, appCreator: wa }, ids: { projects: [{}, {}, {}, {}, {}, {}, {}], workspaceIds: [], main: undefined } }));
