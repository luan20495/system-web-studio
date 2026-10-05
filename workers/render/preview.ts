// Safe-render previews (stage F): screenshots the renderer's own static HTML (no scripts are ever emitted for published markup) in a
// headless browser with JavaScript DISABLED and every network request blocked, so nothing in a template or block can execute or call out.
// Needs a Chrome/Chromium binary (PREVIEW_CHROME_PATH); without one the endpoint answers 501 and the API marks previews UNAVAILABLE.
import type { Browser } from "playwright-core";

const CHROME = process.env.PREVIEW_CHROME_PATH ?? "";
let browser: Promise<Browser> | null = null;

export const previewAvailable = () => CHROME !== "";

async function getBrowser(): Promise<Browser> {
  if (!browser) {
    browser = import("playwright-core").then(({ chromium }) => chromium.launch({
      executablePath: CHROME, headless: true,
      args: ["--disable-gpu", "--no-first-run", "--disable-extensions", "--disable-background-networking", "--disable-sync", "--mute-audio"]
    }));
    browser.catch(() => { browser = null; });
  }
  return browser;
}

/** PNG of `html` at 1200×800 (viewport), downscaled by the browser to `scale`. */
export async function screenshot(html: string, scale = 0.5): Promise<Buffer> {
  const b = await getBrowser();
  const context = await b.newContext({ javaScriptEnabled: false, viewport: { width: 1200, height: 800 }, deviceScaleFactor: scale, offline: true });
  try {
    await context.route("**/*", (route) => route.abort());            // nothing leaves the sandboxed page (images render as placeholders)
    const page = await context.newPage();
    await page.setContent(html, { waitUntil: "domcontentloaded", timeout: 10_000 });
    return await page.screenshot({ type: "png", timeout: 10_000 });
  } finally {
    await context.close();
  }
}
