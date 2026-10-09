import { launch, recorder, BASE, OUT, secretFromDemoFile } from "./ug-lib.mjs";
import { writeFileSync, readFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const proj = JSON.parse(readFileSync(OUT + "explore3.json", "utf8")).url;
const browser = await launch(); const c = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await c.newPage(); const r = recorder(p);
await p.goto(BASE + "/login", { waitUntil: "networkidle" });
await p.getByLabel("Tên đăng nhập").fill("demo01"); await p.getByLabel("Mật khẩu").fill(pw); await p.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await p.waitForLoadState("networkidle"); await p.waitForTimeout(1000);
await p.goto(BASE + proj, { waitUntil: "networkidle" }); await p.waitForTimeout(1500);
const out = {};
let i = r.mark(); await p.getByRole("button", { name: "Design", exact: true }).click(); await p.waitForLoadState("networkidle"); await p.waitForTimeout(2000);
out.designUrl = p.url().replace(BASE, ""); out.calls = r.since(i);
out.text = (await p.locator("body").innerText()).slice(0, 3000); out.buttons = (await p.getByRole("button").allInnerTexts()).slice(0, 80);
await p.screenshot({ path: OUT + "explore-design.png" });
// guide labels
const want = ["Chỉnh sửa", "Dùng thử", "Công cụ", "Trang", "Thành phần", "Dữ liệu", "Biểu mẫu", "Hành động", "Workflow", "Giao diện", "Nội dung", "Thiết kế", "Quyền", "Nâng cao", "Chia sẻ", "Xuất bản", "Phiên bản"];
out.present = {}; for (const w of want) out.present[w] = await p.getByText(w, { exact: true }).count();
writeFileSync(OUT + "explore4.json", JSON.stringify(out, null, 1));
console.log(out.designUrl, JSON.stringify(out.calls)); console.log("present:", JSON.stringify(out.present)); console.log("buttons", JSON.stringify(out.buttons)); console.log(out.text.slice(0, 1500));
await browser.close();
