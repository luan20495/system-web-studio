import { NextResponse, type NextRequest } from "next/server";

/**
 * Per-request CSP with a nonce, http mode only. In mock mode (static export for GitHub Pages) this file is inert:
 * a static host cannot send headers, so that build relies on the sandboxed preview and escaped rendering alone.
 *
 * style-src needs 'unsafe-inline' because the sandboxed preview iframe (srcdoc) inherits this policy and carries its own
 * <style> block. Scripts are what matter for XSS, and those are locked to the nonce ('strict-dynamic', no unsafe-inline).
 */
const HTTP_MODE = process.env.NEXT_PUBLIC_API_MODE === "http";

function origin(value: string | undefined): string {
  try { return value ? new URL(value).origin : ""; } catch { return ""; }
}

export function proxy(request: NextRequest) {
  if (!HTTP_MODE) return NextResponse.next();
  const nonce = btoa(crypto.randomUUID());
  const dev = process.env.NODE_ENV === "development";
  const storage = origin(process.env.MINIO_PUBLIC_ENDPOINT ?? process.env.MINIO_ENDPOINT);     // presigned upload/download target
  const extraConnect = (process.env.CSP_EXTRA_CONNECT_ORIGINS ?? "").split(",").map((s) => origin(s.trim())).filter(Boolean);
  const policy = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    `img-src 'self' data: blob: ${storage}`.trim(),
    `connect-src 'self' ${[storage, ...extraConnect].filter(Boolean).join(" ")}${dev ? " ws:" : ""}`.replace(/\s+$/, ""),
    "font-src 'self'",
    // previews of code apps are framed from the sites origin (sandboxed there with CSP "sandbox allow-scripts")
    `frame-src 'self' about: ${origin(process.env.SITES_ORIGIN)}`.trim(),
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'"
  ].join("; ");

  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", policy);          // Next reads the nonce from the request header
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", policy);
  if (process.env.STUDIO_HSTS === "true") response.headers.set("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
  return response;
}

export const config = {
  matcher: [{ source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "next-router-prefetch" }] }]
};
