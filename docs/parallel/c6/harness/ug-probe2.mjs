// C6 targeted re-probe of steps whose first result may have been a harness error (unpublish/restore confirm, AI progress, rail tabs in Edit mode).
import { launch, recorder, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync, writeFileSync } from "node:fs";
const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state; const pw = secretFromDemoFile("demo01");
const T = (s) => String(s ?? "").replace(/\s+/g, " ").trim(); const out = {};
const browser = await launch(); const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage(); const rc = recorder(p);
await login(p, "demo01", pw); await p.goto(BASE + `/studio/projects/${st.projectId}/design`, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
const dialogs = []; p.on("dialog", async (d) => { dialogs.push({ type: d.type(), message: T(d.message()) }); await d.accept(); });
const body = async () => T(await p.locator("body").innerText());
// 1. Khôi phục: what happens on click
await p.getByRole("button", { name: "Phiên bản", exact: true }).click(); await p.waitForTimeout(1200);
const v0 = (await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/versions`)).json()).length;
let m = rc.mark(); await p.getByRole("button", { name: /Khôi phục/ }).nth(1).click(); await p.waitForTimeout(4000);
out.restoreAfterClick = { text: (await body()).slice(0, 600), calls: rc.since(m) }; await p.screenshot({ path: OUT + "ug-restore-click.png" });
const dlg = p.getByRole("dialog"); out.restoreDialogs = await dlg.count();
for (const nm of [/Xác nhận/, /Khôi phục phiên bản/, /^Khôi phục$/]) { const b = p.getByRole("dialog").getByRole("button", { name: nm }); if (await b.count()) { await b.last().click(); await p.waitForTimeout(3000); out.restoreConfirmed = String(nm); break; } }
const v1 = (await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/versions`)).json()).length; out.versions = [v0, v1]; out.restoreCalls = rc.since(m);
await p.keyboard.press("Escape"); await p.waitForTimeout(500);
// 2. unpublish
await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(1800); await p.getByText(/Các lần xuất bản/).click().catch(() => {}); await p.waitForTimeout(800);
m = rc.mark(); const dn = p.getByRole("button", { name: "Gỡ trang xuống" }); out.unpublishBtn = await dn.count(); await dn.click(); await p.waitForTimeout(1500);
out.unpublishAfterClick = (await body()).slice(0, 700); await p.screenshot({ path: OUT + "ug-unpublish-click.png" });
const site1 = await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/site`)).json().catch(() => null); out.siteAfterClick = site1;
out.unpublishCalls = rc.since(m);
for (const nm of [/Xác nhận/, /^Gỡ/, /Đồng ý/]) { const b = p.locator(".modalOverlay button, [role=dialog] button").filter({ hasText: nm }); if (await b.count()) { out.unpublishConfirmBtns = await b.allInnerTexts(); await b.last().click(); await p.waitForTimeout(4000); break; } }
const g = await fetch(st.url + "?c6=" + Date.now()).then((r) => r.status); out.publicAfterUnpublish = g; out.siteAfter = await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/site`)).json().catch(() => null);
out.unpublishCalls2 = rc.since(m);
await p.keyboard.press("Escape"); await p.waitForTimeout(500);
// 3. rail tabs in Edit mode
for (const tab of ["Hành động", "Workflow"]) { await p.getByText(tab, { exact: true }).first().click().catch(() => {}); await p.waitForTimeout(900); out["rail_" + tab] = (await body()).slice(0, 900); await p.screenshot({ path: OUT + `ug-rail-${tab}.png` }); }
// 4. AI simulator progress
await p.getByRole("button", { name: "✦ AI", exact: true }).click().catch(() => p.getByText("AI", { exact: true }).first().click()); await p.waitForTimeout(1200);
out.aiPlaceholders = await p.locator("textarea, input[type=text]").evaluateAll((els) => els.map((e) => ({ ph: e.placeholder, al: e.getAttribute("aria-label") }))); out.aiScreen = (await body()).slice(0, 600); await p.screenshot({ path: OUT + "ug-ai-screen.png" });
out.nativeDialogs = dialogs; out.finalVersions = (await (await ctx.request.get(`${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}/versions`)).json()).length;
writeFileSync(OUT + "probe2.json", JSON.stringify(out, null, 1)); await browser.close(); console.log(JSON.stringify({ nativeDialogs: out.nativeDialogs, versions: out.versions, restoreCalls: out.restoreCalls, unpublishCalls2: out.unpublishCalls2, publicAfterUnpublish: out.publicAfterUnpublish, siteAfter: out.siteAfter, aiPlaceholders: out.aiPlaceholders, hd: out["rail_Hành động"]?.slice(300, 900), wf: out.rail_Workflow?.slice(300, 900) }, null, 1));
