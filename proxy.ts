import { createCspProxy } from "./packages/auth/src/server/csp";

/**
 * Per-request CSP with a nonce, http mode only. In mock mode (static export for GitHub Pages) this file is inert:
 * a static host cannot send headers, so that build relies on the sandboxed preview and escaped rendering alone.
 */
export const proxy = createCspProxy({ enabled: process.env.NEXT_PUBLIC_API_MODE === "http" });

export const config = {
  matcher: [{ source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "next-router-prefetch" }] }]
};
