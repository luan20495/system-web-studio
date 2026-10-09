import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync, writeFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state;
const browser = await launch(); const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage();
await login(p, "demo01", pw); await p.goto(BASE + `/studio/projects/${st.projectId}/design`, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(2500);
const dlg = p.getByRole("dialog").first(); const out = { text: await dlg.innerText(), buttons: await dlg.getByRole("button").allInnerTexts(), links: await dlg.getByRole("link").allInnerTexts() };
await p.screenshot({ path: OUT + "explore-history.png" }); writeFileSync(OUT + "explore7.json", JSON.stringify(out, null, 1)); console.log(out.text); console.log("BUTTONS", JSON.stringify(out.buttons));
await browser.close();
