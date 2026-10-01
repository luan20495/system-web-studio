import type { NextConfig } from "next";

const configuredBasePath = process.env.STUDIO_BASE_PATH ?? "";
const basePath = configuredBasePath === "/" ? "" : configuredBasePath.replace(/\/+$/, "");

if (basePath && (!basePath.startsWith("/") || basePath.startsWith("//") || /[?#]/.test(basePath))) {
  throw new Error("STUDIO_BASE_PATH must be an absolute URL path without query or fragment.");
}

const httpMode = process.env.NEXT_PUBLIC_API_MODE === "http";
const apiTarget = (process.env.API_PROXY_TARGET ?? "http://127.0.0.1:8080").replace(/\/+$/, "");

const nextConfig: NextConfig = {
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
  turbopack: { root: process.cwd() },
  // Static export (GitHub Pages) cannot set headers; the server-backed http mode can.
  ...(httpMode ? { async headers() {
    return [{ source: "/:path*", headers: [
      { key: "X-Content-Type-Options", value: "nosniff" },
      { key: "X-Frame-Options", value: "DENY" },
      { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
      { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" }
    ] }];
  } } : {}),
  ...(httpMode ? { async rewrites() { return [{ source: "/api/:path*", destination: `${apiTarget}/api/:path*` }]; } } : {}),
};

export default nextConfig;
