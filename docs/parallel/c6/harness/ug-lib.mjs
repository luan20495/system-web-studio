// C6 user-guide QA helpers: a step recorder that never stores secrets, API call capture (method path status), screenshots.
import { chromium } from "playwright-core";
import { mkdirSync, writeFileSync, readFileSync } from "node:fs";
export const OUT = new URL("../evidence/user-guide-20261007/", import.meta.url).pathname; mkdirSync(OUT, { recursive: true });
export const BASE = process.env.UG_BASE ?? "https://studio.toolsmcp.uk";
export function secretFromDemoFile(user) {            // returns the password of <user> from the public demo-accounts file; never printed or stored
  const t = readFileSync("/Users/hoangluan/code/HBL/.run/public/demo-accounts.txt", "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p[0] === user);
  return t ? t[t.length - 1] : null;
}
export async function launch() { return chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true }); }
export function recorder(page) {
  const calls = [];
  page.on("response", (r) => { try { const u = new URL(r.url()); if (u.pathname.startsWith("/api/") || u.pathname.startsWith("/oauth2")) calls.push({ m: r.request().method(), p: u.pathname.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, "{id}"), s: r.status() }); } catch {} });
  return { calls, mark: () => calls.length, since: (i) => [...new Set(calls.slice(i).map((c) => `${c.m} ${c.p} ${c.s}`))] };
}
export const results = [];
export function rec(step, status, expected, actual, extra = {}) { results.push({ step, status, expected, actual, ...extra }); console.log(`${status.padEnd(7)} ${step}  | expected: ${expected} | actual: ${actual}`); }
export function save(name) { writeFileSync(OUT + name, JSON.stringify(results, null, 1)); }

export async function login(page, user, pw, portalRadio = null) {   // literal guide step: Tên đăng nhập / Mật khẩu / Đăng nhập (no radio unless asked)
  await page.goto(BASE + "/login", { waitUntil: "networkidle" });
  if (portalRadio) await page.getByRole("radio", { name: portalRadio }).check();
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pw);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 20000 });
  await page.waitForLoadState("networkidle"); await page.waitForTimeout(800);
}
