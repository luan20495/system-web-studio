import type { Me } from "@xweb/types";

/**
 * Which of the three Xweb web apps a person may open, derived from what the server says about them (`/auth/me`).
 * This only chooses a screen and which links to show. Every API call is authorised again by the server, so a wrong answer here can
 * at worst show a screen whose data calls fail with 403 (see docs/contracts/permission-model.md: "UI chỉ ẩn nút").
 */
export type PortalId = "platform" | "admin" | "studio";
export const PORTAL_IDS: readonly PortalId[] = ["platform", "admin", "studio"];

/** What a person can do at the portal level. Kept coarse on purpose: finer permissions come from the server (`ApiProject.permissions`). */
export type Capability = "platform.operate" | "tenant.administer" | "studio.build";

export const PORTAL_REQUIRES: Record<PortalId, Capability> = {
  platform: "platform.operate",
  admin: "tenant.administer",
  studio: "studio.build"
};
export const PORTAL_PREFIX: Record<PortalId, string> = { platform: "/platform", admin: "/admin", studio: "/studio" };
export const PORTAL_LABEL: Record<PortalId, string> = { platform: "Xweb Platform", admin: "Quản trị công ty", studio: "Xweb Studio" };

/**
 * Interim mapping, to be replaced when tenancy lands (T2/T3):
 *  - there is no tenant yet, and `/admin/*` APIs are guarded server-side by `users.system_admin`, so both "platform.operate" and
 *    "tenant.administer" follow `systemAdmin`. When C1 adds TENANT_ADMIN, only this function changes.
 *  - "studio.build" = member of at least one workspace.
 */
export function capabilitiesOf(me: Me | null | undefined): ReadonlySet<Capability> {
  const out = new Set<Capability>();
  if (!me) return out;
  if (me.systemAdmin === true) { out.add("platform.operate"); out.add("tenant.administer"); }
  if (me.workspaces.length > 0) out.add("studio.build");
  return out;
}
export const canAccessPortal = (me: Me | null | undefined, portal: PortalId) => capabilitiesOf(me).has(PORTAL_REQUIRES[portal]);
export const accessiblePortals = (me: Me | null | undefined): PortalId[] => PORTAL_IDS.filter((p) => canAccessPortal(me, p));

/** A navigation entry that is shown only to people holding `requires` (no `requires` = everyone who reached the portal). */
export type NavEntry = { key: string; label: string; icon?: string; requires?: Capability };
export function visibleNav<T extends { requires?: Capability }>(items: readonly T[], me: Me | null | undefined): T[] {
  const caps = capabilitiesOf(me);
  return items.filter((i) => !i.requires || caps.has(i.requires));
}

/**
 * Absolute URL of another portal. With one origin per portal (platform.xweb.vn / admin.xweb.vn / app.xweb.vn) set
 * NEXT_PUBLIC_PORTAL_URL_PLATFORM / _ADMIN / _STUDIO at build time; unset = same origin (single-host development and the legacy root app).
 * The literals below must stay as written: Next only inlines `process.env.NEXT_PUBLIC_*` it can see in the source.
 */
const PORTAL_ORIGIN: Record<PortalId, string> = {
  platform: process.env.NEXT_PUBLIC_PORTAL_URL_PLATFORM ?? "",
  admin: process.env.NEXT_PUBLIC_PORTAL_URL_ADMIN ?? "",
  studio: process.env.NEXT_PUBLIC_PORTAL_URL_STUDIO ?? ""
};
export function portalHref(portal: PortalId, path = ""): string {
  const origin = PORTAL_ORIGIN[portal].replace(/\/+$/, "");
  return `${origin}${PORTAL_PREFIX[portal]}${path}`;
}
/** Origin configured for a portal ("" = same origin). */
export const portalOrigin = (portal: PortalId): string => PORTAL_ORIGIN[portal].replace(/\/+$/, "");
/**
 * In-app path inside a portal (no origin): portalPath("studio", "/projects/1") -> "/studio/projects/1".
 * Use it for navigation INSIDE the current web app (router.push, <Link>); use portalHref for links that leave to another portal.
 */
export const portalPath = (portal: PortalId, path = ""): string => `${PORTAL_PREFIX[portal]}${path}`;
/** Which portal an in-app path belongs to, from its first segment ("/studio/x" -> "studio"); null when it is not a portal path. */
export function portalOfPath(path: string | null | undefined): PortalId | null {
  const first = (path ?? "").split(/[?#]/)[0].split("/").filter(Boolean)[0];
  return PORTAL_IDS.find((p) => PORTAL_PREFIX[p] === `/${first}`) ?? null;
}

// ---- legacy single-app helpers (the combined root app still uses them) -------------------------------------------------------
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
  return portalOfPath(next) && /^\/[a-z]+(\/|$|\?|#)/.test(next) ? next : null;
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
    const target = portalOfPath(next);
    if (target === "platform") return canAccessPortal(me, "platform") ? next : "/auth/no-access";
    if (target === "admin") return isAdmin(me) ? next : "/auth/no-access";
    return hasWorkspace(me) ? next : "/auth/no-workspace";
  }
  if (portal === "admin") return isAdmin(me) ? portalPath("admin") : "/auth/no-access";
  if (portal === "builder") return hasWorkspace(me) ? portalPath("studio") : "/auth/no-workspace";
  // no explicit choice: employees go to the Studio; an admin without any workspace goes to the console
  if (hasWorkspace(me)) return portalPath("studio");
  return isAdmin(me) ? portalPath("admin") : "/auth/no-workspace";
}

/**
 * Post-login destination inside ONE portal's own web app (platform / admin / studio each have their own login).
 * Someone who cannot use this portal goes to the "no access" page, which then offers the portals they can use.
 */
export function resolvePortalPostLogin(input: { me: Me | null; disabled?: boolean; portal: PortalId; next?: string | null }): string {
  const { me, disabled, portal } = input;
  const next = safeNext(input.next);
  if (disabled) return "/auth/no-access?reason=disabled";
  if (!me) return "/login" + (next ? `?next=${encodeURIComponent(next)}` : "");
  if (!canAccessPortal(me, portal)) return `/auth/no-access?portal=${portal}`;
  const prefix = PORTAL_PREFIX[portal];
  return next && (next === prefix || next.startsWith(`${prefix}/`) || next.startsWith(`${prefix}?`)) ? next : prefix;
}

/** Splits "/studio/projects/abc/design" into ["studio","projects","abc","design"]. */
export function segments(pathname: string): string[] { return pathname.split("?")[0].split("/").filter(Boolean); }
