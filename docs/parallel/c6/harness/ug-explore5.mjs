import { launch, recorder, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { writeFileSync, readFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const proj = JSON.parse(readFileSync(OUT + "explore3.json", "utf8")).url.replace(/\/ai$/, "/design");
const browser = await launch(); const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage(); const r = recorder(p);
await login(p, "demo01", pw);
await p.goto(BASE + proj, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
const out = {};
out.frames = p.frames().map((f) => f.url().replace(BASE, "").slice(0, 60));
// find the preview canvas text
const heroTitle = "Nước sạch mỗi ngày, sống khỏe mỗi ngày.";
let target = null; for (const f of p.frames()) { const l = f.getByText(heroTitle, { exact: false }).first(); if (await l.count()) { target = l; out.inFrame = f.url().replace(BASE, "").slice(0, 60) || "main"; break; } }
out.heroCount = target ? 1 : 0;
let i = r.mark();
if (target) { await target.click(); await p.waitForTimeout(1500); }
out.afterClick = { calls: r.since(i), text: (await p.locator("body").innerText()).split("Chưa chọn")[0].slice(-1800), tabs: await p.getByRole("tab").allInnerTexts().catch(() => []), buttons: (await p.getByRole("button").allInnerTexts()).filter((b) => b.length < 30).slice(0, 70), inputs: await p.locator("input:visible,textarea:visible").evaluateAll((els) => els.map((e) => ({ t: e.type, v: (e.value || "").slice(0, 40), l: e.labels?.[0]?.innerText?.slice(0, 30), a: e.getAttribute("aria-label") }))) };
await p.screenshot({ path: OUT + "explore-selected.png" });
writeFileSync(OUT + "explore5.json", JSON.stringify(out, null, 1));
console.log(JSON.stringify({ frames: out.frames, inFrame: out.inFrame, heroCount: out.heroCount, calls: out.afterClick.calls, tabs: out.afterClick.tabs, inputs: out.afterClick.inputs }, null, 1)); console.log("buttons", JSON.stringify(out.afterClick.buttons));
await browser.close();
