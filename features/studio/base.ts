import { portalHref, portalPath } from "@xweb/permissions";
import type { AppKind } from "@/lib/http-types";

/**
 * Studio navigation helpers. The portal prefix lives in ONE place (@xweb/permissions PORTAL_PREFIX); screens never spell "/studio".
 *  - S(path):         in-app path inside Studio, for <Link>/router.push in the same web app: S("/projects/1") -> "/studio/projects/1".
 *  - consoleHref():   link that LEAVES Studio for the Admin console (another origin in production; path-only when no origin is configured).
 */
export const S = (path = ""): string => portalPath("studio", path);
export const projectBase = (projectId: string): string => S(`/projects/${projectId}`);
export const consoleHref = (path = ""): string => portalHref("admin", path);

/** What kind of app a project is, in the words of the product. A project without `appKind` is a Website (the V1 default). */
const APP_KIND_LABEL: Record<AppKind, string> = {
  WEBSITE_STATIC: "Website", SOURCE_WEB_APP: "Ứng dụng web (mã nguồn)", DASHBOARD: "Dashboard", INTERNAL_TOOL: "Công cụ nội bộ", WORKFLOW: "Quy trình", SERVER_APP: "Ứng dụng có máy chủ",
};
export const appKindLabel = (kind?: AppKind | null): string => APP_KIND_LABEL[kind ?? "WEBSITE_STATIC"] ?? "Ứng dụng";
