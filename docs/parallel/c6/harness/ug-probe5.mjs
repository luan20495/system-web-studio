// C6 probe: where do slots ("Thêm khe"), "Khe dữ liệu" and "Dữ liệu công khai" live in the public Studio? Throwaway project, removed by ug-cleanup.mjs.
import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { writeFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const T = (s) => String(s ?? "").replace(/\s+/g, " ").trim(); const out = {};
const browser = await launch(); const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage(); await login(p, "demo01", pw);
await p.goto(BASE + "/studio/new", { waitUntil: "networkidle" }); await p.getByPlaceholder("Tên ứng dụng").fill("C6-UG-QA-probe5-" + Date.now().toString(36)); await p.getByRole("button", { name: "Tạo website" }).click();
await p.waitForURL(/\/studio\/projects\//, { timeout: 30000 }); await p.waitForLoadState("networkidle"); await p.waitForTimeout(2500);
await p.getByRole("button", { name: "Design", exact: true }).click(); await p.waitForTimeout(2000);
await p.getByRole("tab", { name: "Dữ liệu", exact: true }).first().click(); await p.waitForTimeout(1200);
await p.getByText(/Đã khai báo/).first().click().catch(() => {}); await p.waitForTimeout(800);
const rail = T(await p.locator("aside, .bx-left").first().innerText().catch(() => "")); out.railData = rail;
out.dataTexts = { "Khe dữ liệu": /Khe dữ liệu/.test(rail), "Thêm khe": /Thêm khe/.test(rail), "Thêm nguồn dữ liệu": /Thêm nguồn dữ liệu/.test(rail), "Liên kết khe dữ liệu": /Liên kết khe dữ liệu/.test(rail), "Dữ liệu công khai": /Dữ liệu công khai/.test(rail) };
await p.getByRole("tab", { name: "Trang", exact: true }).first().click(); await p.waitForTimeout(600); await p.getByText(/Đầu trang \(Hero\)/).first().click(); await p.waitForTimeout(800);
await p.getByRole("tab", { name: /^Dữ liệu/ }).last().click().catch(() => {}); await p.waitForTimeout(1000); out.inspectorData = T(await p.locator("body").innerText()).slice(-900); await p.screenshot({ path: OUT + "ug-inspector-data.png" });
out.allText = { "Khe dữ liệu": /Khe dữ liệu/.test(out.inspectorData), "Thêm khe": /Thêm khe/.test(out.inspectorData), "Dữ liệu công khai": /Dữ liệu công khai/.test(out.inspectorData) };
// accessible name of the rail
out.railAria = await p.locator('[aria-label="Công cụ"]').count(); out.railAsideAria = await p.locator('[aria-label="Công cụ dựng ứng dụng"]').count();
// problems badge on Xuất bản
out.publishBtn = T(await p.getByRole("button", { name: /Xuất bản/ }).first().innerText());
writeFileSync(OUT + "probe5.json", JSON.stringify(out, null, 1)); await browser.close(); console.log(JSON.stringify({ dataTexts: out.dataTexts, allText: out.allText, railAria: out.railAria, railAsideAria: out.railAsideAria, publishBtn: out.publishBtn, inspector: out.inspectorData.slice(-500) }, null, 1));
