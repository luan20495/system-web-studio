// Private-site access (ADR 0009), client half of M-051 (pure, unit-tested). The server is the authority: it validates the path
// (SiteService.safePath) and builds the redirect from its configured sites origin. The client only refuses to SEND an odd path and to
// FOLLOW a redirect that is not a plain https ticket link, as defence in depth. The sites origin is not known to the client, so the host is
// not pinned (and must NOT be "same origin": the redirect goes to the sites host).

/** the site path sent with the ticket request: same rule as the server's safePath (anything else becomes "/") */
export function safeSitePath(path: string | null | undefined): string {
  const p = path && path.trim() ? path : "/";
  // eslint-disable-next-line no-control-regex
  if (!p.startsWith("/") || p.startsWith("//") || p.length > 512 || /[\\\u0000-\u001f\u007f-\u009f]/.test(p) || p.includes("..")) return "/";
  return p;
}

/**
 * The server's redirect, or null when it must not be followed: https only (http only while THIS page is itself served over http, i.e. a local stack: no host names are listed here because a
 * production bundle must not carry any loopback literal, C0 bundle scan), no user:password,
 * the ticket endpoint `/_access` with a `ticket` and nothing else that could carry script (javascript:, data:, a protocol-relative URL).
 */
export function safeTicketRedirect(redirect: unknown, pageProtocol: string = typeof location === "undefined" ? "https:" : location.protocol): string | null {
  if (typeof redirect !== "string" || !redirect || redirect.length > 2048) return null;
  let u: URL;
  try { u = new URL(redirect); } catch { return null; }
  if (!(u.protocol === "https:" || (u.protocol === "http:" && pageProtocol === "http:"))) return null;
  if (u.username || u.password || !u.hostname) return null;
  if (!/(^|\/)_access$/.test(u.pathname) || !u.searchParams.get("ticket")) return null;
  return u.href;
}

/**
 * M-051: a server-given URL put into href/src (site URL, deployment URL, preview URL, asset download URL): absolute http(s) without user:password,
 * or a same-origin path ("/…", not "//…"). Anything else (javascript:, data:, blob:, vbscript:) gives undefined, so no link / no frame source is rendered.
 */
export function webUrl(url: string | null | undefined): string | undefined {
  if (typeof url !== "string" || !url) return undefined;
  // the URL parser DROPS tab / newline, so "/\t/evil.example" would become a protocol-relative URL: control characters and backslashes are never part of an accepted value
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f\\]/.test(url)) return undefined;
  if (url.startsWith("/")) return url.startsWith("//") ? undefined : url;
  try { const u = new URL(url); return (u.protocol === "https:" || u.protocol === "http:") && !u.username && !u.password ? url : undefined; } catch { return undefined; }
}
