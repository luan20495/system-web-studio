import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state;
const browser = await launch(); const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage();
await login(p, "demo01", pw); await p.goto(BASE + `/studio/projects/${st.projectId}/design`, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(2000);
const dlg = p.getByRole("dialog").first(); await dlg.getByText(/Các lần xuất bản/).click(); await p.waitForTimeout(1500);
console.log(await dlg.innerText()); console.log("BUTTONS", JSON.stringify(await dlg.getByRole("button").allInnerTexts()));
await p.screenshot({ path: OUT + "explore-history-open.png" }); await browser.close();
