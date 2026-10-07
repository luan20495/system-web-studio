import type { NextConfig } from "next";

/**
 * Shared Next config for the root (legacy) app and the platform / admin / studio apps.
 * `http: false` is the legacy mock build (static export for GitHub Pages); the three apps always run against the real backend.
 */
export function createNextConfig(options: { http: boolean; root?: string }): NextConfig {
  const configuredBasePath = process.env.STUDIO_BASE_PATH ?? "";
  const basePath = configuredBasePath === "/" ? "" : configuredBasePath.replace(/\/+$/, "");
  if (basePath && (!basePath.startsWith("/") || basePath.startsWith("//") || /[?#]/.test(basePath))) {
    throw new Error("STUDIO_BASE_PATH must be an absolute URL path without query or fragment.");
  }
  const httpMode = options.http;
  // D-C0-34: the backend address is stated, never inherited. `next dev` may fall back to the local API; a production build / start (what every deployment and
  // scripts/portals.sh run) needs API_PROXY_TARGET, or HBL_ENV=local to say "this is a local machine".
  const stated = process.env.API_PROXY_TARGET;
  if (httpMode && !stated && process.env.NODE_ENV === "production" && process.env.HBL_ENV !== "local")
    throw new Error("API_PROXY_TARGET is required for a production build / start (set it, or HBL_ENV=local on a development machine).");
  const apiTarget = (stated ?? "http://127.0.0.1:8080").replace(/\/+$/, "");

  return {
    // lets a second dev server (http mode) run next to an existing one without sharing .next
    ...(process.env.NEXT_DIST_DIR ? { distDir: process.env.NEXT_DIST_DIR } : {}),
    reactStrictMode: true,
    poweredByHeader: false,
    // mock mode is a static export (GitHub Pages); http mode needs a server for the same-origin /api proxy
    ...(httpMode ? {} : { output: "export" as const }),
    trailingSlash: !httpMode,
    images: { unoptimized: true },
    agentRules: false,
    basePath,
    assetPrefix: basePath ? `${basePath}/` : undefined,
    turbopack: { root: options.root ?? process.cwd() },
    // Static export (GitHub Pages) cannot set headers; the server-backed http mode can.
    ...(httpMode ? { async headers() {
      return [{ source: "/:path*", headers: [
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" }
      ] }];
    } } : {}),
    // beforeFiles: these must win over the app's catch-all route (e.g. /login/oauth2/code/oidc is the OIDC callback, not a page)
    ...(httpMode ? { async rewrites() { return { beforeFiles: [
        { source: "/api/:path*", destination: `${apiTarget}/api/:path*` },
        // OIDC: the browser is redirected to these backend endpoints, so they must be reachable on the UI origin too
        { source: "/oauth2/:path*", destination: `${apiTarget}/oauth2/:path*` },
        { source: "/login/oauth2/:path*", destination: `${apiTarget}/login/oauth2/:path*` }
      ], afterFiles: [], fallback: [] }; } } : {}),
  };
}
