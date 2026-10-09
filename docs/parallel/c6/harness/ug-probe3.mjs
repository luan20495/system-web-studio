// C6 re-probe: after "Gỡ trang xuống", does "Phục vụ lại bản này" bring the site back (guide §6)? Uses the live QA project.
import { launch, recorder, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync, writeFileSync } from "node:fs";
const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state; const pw = secretFromDemoFile("demo01"); const T = (s) => String(s ?? "").replace(/\s+/g, " ").trim(); const out = {};
const browser = await launch(); const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage(); const rc = recorder(p); const dialogs = [];
p.on("dialog", async (d) => { dialogs.push(T(d.message())); await d.accept(); });
await login(p, "demo01", pw); await p.goto(BASE + `/studio/projects/${st.projectId}/design`, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(1800); const d = p.getByRole("dialog").first();
await d.getByText(/Các lần xuất bản/).click().catch(() => {}); await p.waitForTimeout(900);
out.dialogText = T(await d.innerText()).slice(0, 700); const b = d.getByRole("button", { name: /Phục vụ lại bản này/ }); out.reserveButtons = await b.count();
const m = rc.mark(); if (out.reserveButtons) { await b.first().click(); await p.waitForTimeout(7000); }
out.calls = rc.since(m); out.publicAfter = await fetch(st.url + "?c6=" + Date.now()).then((r) => r.status);
out.site = await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/site`)).json().catch(() => null); out.nativeDialogs = dialogs;
writeFileSync(OUT + "probe3.json", JSON.stringify(out, null, 1)); await browser.close(); console.log(JSON.stringify(out, null, 1));
