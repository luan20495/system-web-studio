import { portalHref, PORTAL_PREFIX } from "@xweb/permissions";

/**
 * Which console the shared admin screens are rendered for.
 *  - "platform": platform.xweb.vn (super admin: operate the platform)
 *  - "admin":    admin.xweb.vn    (tenant admin: run one company)
 *  - "all":      the legacy combined console at /admin (root app), everything in one place
 * The console is carried by `AdminConsoleContext` (console/context.tsx); nothing here is module state.
 */
export type AdminPortal = "all" | "platform" | "admin";

/** In-app path prefix of a console ("all" lives under /admin like the Admin console). */
export const adminBase = (portal: AdminPortal): string => (portal === "platform" ? PORTAL_PREFIX.platform : PORTAL_PREFIX.admin);
/** Link to a section that lives in the OTHER console (cross-origin in production, path-only when no portal URL is configured). */
export function otherConsoleHref(to: "platform" | "admin", path = ""): string { return portalHref(to) + path; }
