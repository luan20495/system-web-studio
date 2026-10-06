import { portalHref, portalPath } from "@xweb/permissions";

/**
 * Studio navigation helpers. The portal prefix lives in ONE place (@xweb/permissions PORTAL_PREFIX); screens never spell "/studio".
 *  - S(path):         in-app path inside Studio, for <Link>/router.push in the same web app: S("/projects/1") -> "/studio/projects/1".
 *  - consoleHref():   link that LEAVES Studio for the Admin console (another origin in production; path-only when no origin is configured).
 */
export const S = (path = ""): string => portalPath("studio", path);
export const projectBase = (projectId: string): string => S(`/projects/${projectId}`);
export const consoleHref = (path = ""): string => portalHref("admin", path);
