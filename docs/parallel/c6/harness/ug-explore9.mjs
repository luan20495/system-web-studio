import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state;
const browser = await launch(); const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage();
await login(p, "demo01", pw); await p.goto(BASE + `/studio/projects/${st.projectId}/design`, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
await p.getByText(/C6 guide QA/).first().click(); await p.waitForTimeout(800); await p.getByLabel("Tiêu đề").last().fill("C6 offline probe 2");
await c.setOffline(true); await p.getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(3500);
const html = await p.evaluate(() => { const ss = document.querySelector(".saveState"); const top = ss ? { saveState: ss.outerHTML.slice(0, 300), siblings: [...ss.parentElement.children].map((e) => e.tagName + ":" + e.textContent.trim().slice(0, 40)) } : null; return [top, ...(() => { const els = [...document.querySelectorAll("button,a,[role=button]")].filter((e) => /Failed to fetch|Thử lại/.test(e.textContent + (e.getAttribute("title") ?? "") + (e.getAttribute("aria-label") ?? ""))); return els.map((e) => ({ outer: e.outerHTML.slice(0, 200) })); })()]; });
console.log(JSON.stringify(html, null, 1)); await c.setOffline(false); await browser.close();
