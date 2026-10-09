// C6 user-guide QA: unauthenticated render probe of the public surfaces (read-only). Records visible text, final URL, API calls (method path status), a screenshot.
import { chromium } from "playwright-core";
import { mkdirSync, writeFileSync } from "node:fs";
const OUT = new URL("../evidence/user-guide-20261007/", import.meta.url).pathname; mkdirSync(OUT, { recursive: true });
const BASE = process.env.UG_BASE ?? "https://studio.toolsmcp.uk";
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const res = [];
for (const path of ["/login", "/studio", "/admin", "/platform"]) {
  const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage();
  const calls = []; p.on("response", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/") || u.pathname.startsWith("/oauth2")) calls.push(`${r.request().method()} ${u.pathname} ${r.status()}`); });
  await p.goto(BASE + path, { waitUntil: "networkidle", timeout: 45000 }).catch((e) => calls.push("goto error " + e.message.slice(0, 80)));
  await p.waitForTimeout(1500);
  const text = (await p.locator("body").innerText()).replace(/\s+\n/g, "\n").slice(0, 1500);
  const radios = await p.getByRole("radio").allInnerTexts().catch(() => []);
  const buttons = await p.getByRole("button").allInnerTexts().catch(() => []);
  const labels = await p.locator("label").allInnerTexts().catch(() => []);
  await p.screenshot({ path: OUT + `probe${path.replace(/\//g, "_")}.png` });
  res.push({ path, finalUrl: p.url().replace(BASE, ""), title: await p.title(), radios, buttons: buttons.slice(0, 12), labels: labels.slice(0, 8), calls: [...new Set(calls)], text });
  await c.close();
}
await browser.close();
writeFileSync(OUT + "probe.json", JSON.stringify(res, null, 1));
for (const r of res) console.log(`\n### ${r.path} -> ${r.finalUrl} | title="${r.title}"\nradios=${JSON.stringify(r.radios)} buttons=${JSON.stringify(r.buttons)} labels=${JSON.stringify(r.labels)}\ncalls=${JSON.stringify(r.calls)}\n${r.text.slice(0, 500)}`);
