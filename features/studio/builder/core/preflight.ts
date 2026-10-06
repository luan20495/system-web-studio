/**
 * Preflight before publishing: what must be fixed (BLOCK) and what the author should know (WARN). Pure.
 * Covers the three things the product spec asks of publish:
 *  - broken routes (menu links, NAVIGATE actions, slugs),
 *  - broken references between data / actions / workflows,
 *  - static data safety: a PUBLIC static artifact is a file anybody can download, so data bound to it must not be tenant-private or personal.
 * Advisory only: the server re-checks at publish (and `public_data_approved` is a server-side approval the client cannot see).
 */
import type { AppDefinitionV2, PublishConfigDef } from "@xweb/types";
import { anchorsOnPage, checkSlug, HOME_ID, listPages, pageExists, sectionsOf, MAX_NAV } from "./pages";
import { allSections, validateDefinition } from "./definition";

export type PreflightIssue = {
  severity: "BLOCK" | "WARN";
  code: "ROUTE_NAV_PAGE_MISSING" | "ROUTE_NAV_URL_INSECURE" | "ROUTE_NAV_ANCHOR_MISSING" | "ROUTE_ACTION_PAGE_MISSING" | "ROUTE_SLUG_INVALID" | "PAGE_EMPTY"
    | "REF_BROKEN" | "STATIC_PUBLIC_DATA" | "PUBLIC_DATA_NEEDS_APPROVAL" | "ACTION_UNATTACHED" | "NAV_TOO_MANY";
  message: string;
  path?: string;
  pageId?: string;
};

/** audience the author is about to publish for; defaults to the draft in the document, then to PRIVATE */
export type PublishAudience = "PRIVATE" | "TENANT" | "PUBLIC";

export function effectivePublish(doc: AppDefinitionV2, audience?: PublishAudience): Required<Pick<PublishConfigDef, "mode" | "visibility">> {
  return {
    mode: doc.publishConfig?.mode ?? "STATIC",
    visibility: audience ?? (doc.publishConfig?.visibility === "PUBLIC" ? "PUBLIC" : doc.publishConfig?.visibility === "TENANT" ? "TENANT" : "PRIVATE"),
  };
}

export function preflight(doc: AppDefinitionV2, opts: { audience?: PublishAudience } = {}): PreflightIssue[] {
  const out: PreflightIssue[] = [];
  const pages = listPages(doc);
  const nav = doc.site?.navigation ?? [];

  // routes: slugs of every extra page
  for (const p of doc.pages ?? []) {
    const c = checkSlug({ ...doc, pages: (doc.pages ?? []).filter((x) => x.id !== p.id) }, p.slug);
    if (!c.ok) out.push({ severity: "BLOCK", code: "ROUTE_SLUG_INVALID", message: `Trang “${p.title}”: ${c.reason}`, pageId: p.id, path: `pages[${p.id}].slug` });
  }
  if (nav.length > MAX_NAV) out.push({ severity: "BLOCK", code: "NAV_TOO_MANY", message: `Menu có ${nav.length} liên kết, tối đa ${MAX_NAV}.`, path: "site.navigation" });

  // routes: menu links
  const anchorsPerPage = pages.map((p) => ({ p, anchors: anchorsOnPage(sectionsOf(doc, p.id)) }));
  nav.forEach((l, i) => {
    const at = `site.navigation[${i}]`;
    if (l.pageId !== undefined && !pageExists(doc, l.pageId)) out.push({ severity: "BLOCK", code: "ROUTE_NAV_PAGE_MISSING", message: `Liên kết menu “${l.label}” trỏ tới một trang không còn tồn tại.`, path: at });
    else if (l.url !== undefined && !/^https:\/\/[^\s"'<>\\]+$/.test(l.url)) out.push({ severity: "BLOCK", code: "ROUTE_NAV_URL_INSECURE", message: `Liên kết menu “${l.label}” phải là địa chỉ https hợp lệ.`, path: at });
    else if (l.anchor !== undefined) {
      const missing = anchorsPerPage.filter(({ anchors }) => !anchors.has(l.anchor!)).map(({ p }) => p.title);
      if (missing.length) out.push({ severity: "WARN", code: "ROUTE_NAV_ANCHOR_MISSING", message: `Liên kết menu “${l.label}” (${l.anchor}) không có đích ở: ${missing.join(", ")}. Menu hiện ở mọi trang nên nhấp ở đó sẽ không đi đâu.`, path: at });
    }
  });

  // routes: NAVIGATE actions
  (doc.actions ?? []).forEach((a, i) => {
    if (a.type === "NAVIGATE" && (a.pageRef === undefined || !pageExists(doc, a.pageRef)))
      out.push({ severity: "BLOCK", code: "ROUTE_ACTION_PAGE_MISSING", message: `Hành động “${a.name || a.id}” chuyển tới một trang không tồn tại.`, path: `actions[${i}].pageRef` });
  });

  // empty pages are legal but almost never intended
  for (const p of pages) if (p.sectionCount === 0) out.push({ severity: "WARN", code: "PAGE_EMPTY", message: `Trang “${p.title}” chưa có nội dung.`, pageId: p.id });

  // references (already reported for routes above: do not repeat NAVIGATE page problems)
  for (const v of validateDefinition(doc)) {
    if (/\.pageRef$/.test(v.path)) continue;
    out.push({ severity: "BLOCK", code: "REF_BROKEN", message: v.message, path: v.path });
  }

  // an action that nothing can start is dead weight (a UI-bound action has a trigger; a child is referenced by a workflow step or a chain)
  const referenced = new Set<string>();
  for (const w of doc.workflows ?? []) for (const s of w.steps) { if (s.actionRef) referenced.add(s.actionRef); if (s.compensationActionRef) referenced.add(s.compensationActionRef); }
  for (const a of doc.actions ?? []) { for (const r of [...(a.onSuccess ?? []), ...(a.onError ?? [])]) referenced.add(r); }
  for (const a of doc.actions ?? []) if (!a.trigger && !referenced.has(a.id) && a.enabled !== false)
    out.push({ severity: "WARN", code: "ACTION_UNATTACHED", message: `Hành động “${a.name || a.id}” chưa gắn vào sự kiện nào và không thuộc workflow nào.`, path: `actions[${a.id}]` });

  // static data safety
  const { mode, visibility } = effectivePublish(doc, opts.audience);
  const bound = (doc.dataBindings ?? []);
  if (bound.length && visibility === "PUBLIC") {
    const where = bound.slice(0, 3).map((b) => `${allSections(doc).find((s) => s.id === b.sectionId)?.type ?? b.sectionId}.${b.prop}`).join(", ");
    if (mode === "STATIC") out.push({ severity: "BLOCK", code: "STATIC_PUBLIC_DATA",
      message: `Trang tĩnh công khai là tệp ai cũng tải được, nên không được nhúng dữ liệu của công ty (${where}${bound.length > 3 ? "…" : ""}). Hãy chọn đối tượng Nội bộ/Riêng tư, hoặc bỏ gắn dữ liệu, hoặc xuất bản dạng động.`, path: "dataBindings" });
    else out.push({ severity: "WARN", code: "PUBLIC_DATA_NEEDS_APPROVAL",
      message: "Ứng dụng công khai có gắn dữ liệu: cần quản trị viên phê duyệt cho phép dữ liệu công khai. Máy chủ sẽ kiểm tra lại khi xuất bản.", path: "dataBindings" });
  }
  return out;
}

export const blockers = (issues: PreflightIssue[]): PreflightIssue[] => issues.filter((i) => i.severity === "BLOCK");
export { HOME_ID };
