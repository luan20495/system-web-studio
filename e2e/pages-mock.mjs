// GitHub Pages regression: the mock-mode static export, served under the Pages base path, must work with no backend.
import { chromium } from "playwright-core";
import { execSync } from "node:child_process";
import { createServer } from "node:http";
import { readFileSync, existsSync, statSync } from "node:fs";
import { join, extname } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname, DIST = join(ROOT, ".next-pages"), BASEPATH = "/system-web-studio";
execSync("npx next build", { cwd: ROOT, stdio: "ignore", env: { ...process.env, NEXT_PUBLIC_API_MODE: "mock", NEXT_DIST_DIR: ".next-pages", STUDIO_BASE_PATH: BASEPATH } });
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".txt": "text/plain", ".svg": "image/svg+xml", ".woff2": "font/woff2" };
const server = createServer((req, res) => {
  const path = decodeURIComponent((req.url ?? "/").split("?")[0]);
  if (!path.startsWith(BASEPATH)) { res.writeHead(404).end("outside base path"); return; }   // like Pages: nothing is served outside it
  let file = join(DIST, path.slice(BASEPATH.length) || "/");
  if (existsSync(file) && statSync(file).isDirectory()) file = join(file, "index.html");
  if (!existsSync(file)) { res.writeHead(404).end("nf"); return; }
  res.writeHead(200, { "Content-Type": types[extname(file)] ?? "application/octet-stream" }).end(readFileSync(file));
}).listen(0);
const port = server.address().port;

const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const apiCalls = [], errors = [];
page.on("request", (r) => { if (r.url().includes("/api/")) apiCalls.push(r.url()); });
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
const checks = [];
const check = async (name, fn) => { try { await fn(); checks.push([name, true]); console.log("PASS", name); } catch (e) { checks.push([name, false]); console.log("FAIL", name, "-", e.message.split("\n")[0]); } };

await page.goto(`http://127.0.0.1:${port}${BASEPATH}/`);
await check("demo loads under the Pages base path", async () => { await page.getByText("Water Purifier Website").first().waitFor({ timeout: 10000 }); });
await check("demo shows its demo/unsaved labelling", async () => { await page.getByText(/Demo/).first().waitFor(); });
await check("mock prompt updates the preview in memory", async () => {
  await page.getByPlaceholder(/Ví dụ/).fill("Thêm bảng so sánh 3 sản phẩm");
  await page.getByRole("button", { name: /Gửi/ }).click();
  const frame = await (await page.waitForSelector("iframe.previewFrame")).contentFrame();
  await frame.waitForSelector("#comparison", { timeout: 10000 });
});
await check("publish stays a demo (no URL, no release)", async () => {
  await page.getByRole("button", { name: "Xuất bản" }).first().click();
  await page.getByRole("button", { name: "Xuất bản" }).last().click();
  await page.getByText(/Demo: chưa có website nào được deploy/).waitFor({ timeout: 10000 });
});
await check("no backend request was made and no page error occurred", async () => {
  if (apiCalls.length) throw new Error("API calls: " + apiCalls.join(", "));
  const real = errors.filter((e) => !/favicon|Failed to load resource/.test(e));
  if (real.length) throw new Error(real[0]);
});
await check("no http-mode UI leaked into the static build", async () => {
  if ((await page.content()).includes("Đăng nhập để tiếp tục")) throw new Error("login screen present in mock build");
});
await browser.close(); server.close();
console.log(`${checks.filter((c) => c[1]).length}/${checks.length} passed`);
process.exit(checks.every((c) => c[1]) ? 0 : 1);
