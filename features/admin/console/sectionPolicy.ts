/**
 * What each console lists, owns and shows to whom: PURE functions over ONE section table (console/sections.tsx). No React, no fetch: unit-tested with a small table here and exercised on the real table by the
 * admin harness route snapshot (tests/browser/admin-routes.snapshot.json). Display only: the server authorises every call.
 *
 * Adding a page = one entry in the table: `key`, `label`, which consoles own it, who may open it (`access`), where it is listed, and how it is routed (`surface`).
 */
import type { AdminScope } from "../adminModel";
import type { AdminPortal } from "../base";

export type SectionAccessKind = "open" | "system" | "company" | "tenant" | "workspace" | "data" | "orgStructure" | "employeeView";
export type SectionAccess = "ok" | "needs-platform" | "needs-scope";
/**
 * how the section is routed:
 *  standard      the same screen in every console that owns it (and in the legacy "all" console)
 *  platform-only the Platform's company screens (`tenants`)
 *  scoped        Admin-console screens for tenant / workspace admins (they run on the tenant / workspace APIs); reached before the ownership check
 *  people        like scoped, but a SYSTEM_ADMIN is sent to the Platform (it is not owned by any console for them)
 *  coming        a section whose backend does not exist yet: the screen says so plainly
 */
export type SectionSurface = "standard" | "platform-only" | "scoped" | "people" | "coming";
/** main = every console's base list; platform-main = the Platform list only; scoped = Admin list for tenant / workspace admins (by `navWhen`); coming = placeholders; hidden = routable alias, never listed */
export type SectionListing = "main" | "platform-main" | "scoped" | "coming" | "hidden";

export type SectionMeta = {
  key: string;
  label: string;
  /** dedicated consoles that own it (the legacy "all" console owns everything) */
  portals: readonly ("platform" | "admin")[];
  access: SectionAccessKind;
  surface: SectionSurface;
  listed: SectionListing;
  /** `listed: "scoped"`: who sees the entry in the Admin sidebar */
  navWhen?: (scope: AdminScope) => boolean;
  /** NeedsScope wording ("Bạn chưa quản trị {denied} nào") when `access` fails */
  denied?: string;
  /** a full title for the refusal when "Bạn chưa quản trị {denied} nào" does not read right (the organization screens: a missing code, not a missing company) */
  deniedTitle?: string;
};

const find = <T extends SectionMeta>(table: readonly T[], key: string): T | undefined => table.find((s) => s.key === key);

export function owns(table: readonly SectionMeta[], portal: AdminPortal, key: string): boolean {
  if (portal === "all") return true;
  return !!find(table, key)?.portals.includes(portal);
}

export function sectionAccess(table: readonly SectionMeta[], key: string, scope: AdminScope): SectionAccess {
  switch (find(table, key)?.access ?? "open") {
    case "open": return "ok";
    case "company": return scope.platform || scope.tenants.length ? "ok" : "needs-scope";
    case "tenant": return scope.tenants.length ? "ok" : "needs-scope";
    case "workspace": return scope.workspaces.length ? "ok" : "needs-scope";
    case "data": return scope.dataWorkspaces.length ? "ok" : "needs-scope";
    case "orgStructure": return scope.org.structureView ? "ok" : "needs-scope";      // the CODE ORG_STRUCTURE_VIEW, never TENANT_MEMBERS / platformScope / a role
    case "employeeView": return scope.org.employeeView ? "ok" : "needs-scope";
    case "system": return scope.platform ? "ok" : "needs-platform";
  }
}

/** What each console lists for THIS person, in table order. A section the person cannot use is simply not offered. */
export function navSections<T extends SectionMeta>(table: readonly T[], portal: AdminPortal, scope: AdminScope): T[] {
  const mains = table.filter((s) => s.listed === "main");
  if (portal === "all") return mains;
  if (portal === "platform") return table.filter((s) => (s.listed === "main" || s.listed === "platform-main") && owns(table, portal, s.key));
  // Admin console: a SYSTEM_ADMIN sees the system sections this console owns, everybody else only the overview plus what the server lists for them
  const base = scope.platform ? mains.filter((s) => owns(table, portal, s.key)) : mains.filter((s) => s.key === "");
  return [...base, ...table.filter((s) => s.listed === "scoped" && s.navWhen?.(scope)), ...table.filter((s) => s.listed === "coming" && owns(table, portal, s.key))];
}

const needsScope = (sec: SectionMeta): { kind: "needs-scope"; what: string; title?: string } => (sec.deniedTitle ? { kind: "needs-scope", what: sec.denied ?? "", title: sec.deniedTitle } : { kind: "needs-scope", what: sec.denied ?? "" });

export type Resolution<T extends SectionMeta> =
  | { kind: "page"; section: T }
  | { kind: "coming"; section: T }
  | { kind: "needs-platform" }
  | { kind: "needs-scope"; what: string; title?: string }
  | { kind: "scoped-home" }
  | { kind: "elsewhere" }
  | { kind: "notfound" };

/** whether the OTHER console would really show this section to this person (so "it is in the other console" is true, and not a loop) */
function servedByOther(sec: SectionMeta, portal: AdminPortal, scope: AdminScope): boolean {
  if (portal === "platform") return sec.portals.includes("admin") || sec.surface === "scoped" || (sec.surface === "people" && !scope.platform);
  return sec.portals.includes("platform");
}

/** Which screen answers a first path segment in this console for this person. */
export function resolveSection<T extends SectionMeta>(table: readonly T[], portal: AdminPortal, key: string, scope: AdminScope): Resolution<T> {
  const sec = find(table, key);
  if (portal === "admin" && sec?.surface === "coming") return { kind: "coming", section: sec };
  if (portal === "platform" && sec?.surface === "platform-only") return { kind: "page", section: sec };
  if (portal === "admin") {
    if (sec?.surface === "people" && !scope.platform) return scope.tenants.length || scope.workspaces.length ? { kind: "page", section: sec } : needsScope(sec);
    if (sec?.surface === "scoped") return sectionAccess(table, key, scope) === "ok" ? { kind: "page", section: sec } : needsScope(sec);
    if (!scope.platform && key === "") return { kind: "scoped-home" };
    if (!scope.platform && sectionAccess(table, key, scope) === "needs-platform") return { kind: "needs-platform" };
  }
  if (!sec) return { kind: "notfound" };                 // no console has such a section: a 404 (it used to be "it is in the other console", which answered the same: a loop)
  if (!owns(table, portal, key)) return servedByOther(sec, portal, scope) ? { kind: "elsewhere" } : { kind: "notfound" };
  return sec.surface === "standard" ? { kind: "page", section: sec } : { kind: "notfound" };
}
