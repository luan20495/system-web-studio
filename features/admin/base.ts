import { portalHref, PORTAL_PREFIX } from "@xweb/permissions";

/**
 * Which console the shared admin screens are rendered for.
 *  - "platform": platform.xweb.vn (super admin: operate the platform)
 *  - "admin":    admin.xweb.vn    (tenant admin: run one company)
 *  - "all":      the legacy combined console at /admin (root app), everything in one place
 * Each dedicated web app has exactly one portal, so a module-level value set at render start is safe.
 */
export type AdminPortal = "all" | "platform" | "admin";

let portal: AdminPortal = "all";
let base = "/admin";

export function setAdminPortal(next: AdminPortal) { portal = next; base = next === "platform" ? PORTAL_PREFIX.platform : PORTAL_PREFIX.admin; }
export function adminPortal(): AdminPortal { return portal; }
/** Absolute in-app path of an admin screen of the CURRENT console: A("/users/1") -> "/admin/users/1" (or "/platform/..."). */
export function A(path = ""): string { return base + path; }
/** Link to a section that lives in the OTHER console (cross-origin in production, path-only when no portal URL is configured). */
export function otherConsoleHref(to: "platform" | "admin", path = ""): string { return portalHref(to) + path; }

/** Section keys (first path segment) each console owns. `null` = everything (legacy). */
export const OWNED: Record<AdminPortal, ReadonlySet<string> | null> = {
  all: null,
  platform: new Set(["", "tenants", "users", "workspaces", "ai", "components", "templates", "builds", "packages", "system", "backups", "costs", "alerts", "security", "settings", "audit", "connectors"]),
  admin: new Set(["", "users", "workspaces", "applications", "departments", "identity", "ai-governance", "templates", "audit", "sharing", "data-sources", "groups", "byok"]),
};
export function owns(key: string, p: AdminPortal = portal): boolean { const set = OWNED[p]; return set === null || set.has(key); }
