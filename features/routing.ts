import type { Me } from "@/lib/http-types";

export type Portal = "admin" | "builder";
const PORTAL_KEY = "factory-portal";

/** Portal choice is navigation intent only. It is remembered per browser tab and never sent to the server as a permission. */
export function rememberPortal(p: Portal) { try { sessionStorage.setItem(PORTAL_KEY, p); } catch { /* private mode: fall back to defaults */ } }
export function rememberedPortal(): Portal | null {
  try { const v = sessionStorage.getItem(PORTAL_KEY); return v === "admin" || v === "builder" ? v : null; } catch { return null; }
}

/** Only same-app paths are accepted as a post-login destination (no open redirects, no protocol-relative URLs). */
export function safeNext(next: string | null | undefined): string | null {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.includes("\\")) return null;
  return /^\/(admin|studio)(\/|$|\?)/.test(next) ? next : null;
}

export const isAdmin = (me: Me) => me.systemAdmin === true;
export const hasWorkspace = (me: Me) => me.workspaces.length > 0;

/**
 * Where a signed-in (or not) user goes. Pure function: the backend still authorises every request, so a wrong answer here can
 * at worst show a screen whose data calls fail with 403.
 */
export function resolvePostLogin(input: { me: Me | null; disabled?: boolean; portal: Portal | null; next?: string | null }): string {
  const { me, disabled, portal } = input;
  const next = safeNext(input.next);
  if (disabled) return "/auth/no-access?reason=disabled";
  if (!me) return "/login" + (next ? `?next=${encodeURIComponent(next)}` : "");
  if (next) {
    if (next.startsWith("/admin")) return isAdmin(me) ? next : "/auth/no-access";
    return hasWorkspace(me) ? next : "/auth/no-workspace";
  }
  if (portal === "admin") return isAdmin(me) ? "/admin" : "/auth/no-access";
  if (portal === "builder") return hasWorkspace(me) ? "/studio" : "/auth/no-workspace";
  // no explicit choice: employees go to the Studio; an admin without any workspace goes to the console
  if (hasWorkspace(me)) return "/studio";
  return isAdmin(me) ? "/admin" : "/auth/no-workspace";
}

/** Splits "/studio/projects/abc/design" into ["studio","projects","abc","design"]. */
export function segments(pathname: string): string[] { return pathname.split("?")[0].split("/").filter(Boolean); }
