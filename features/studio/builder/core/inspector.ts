/**
 * Inspector model: six tabs (Nội dung / Thiết kế / Dữ liệu / Hành động / Quyền / Nâng cao) and what each one can honestly do right now.
 * Pure. The tabs never show raw JSON; "Nâng cao" shows read-only identifiers and never a secret (nothing in the document is one).
 */
import type { AppDefinitionV2, ComponentMetadataV2, PropDef, RegistryComponent, SchemaOperation, Section } from "@xweb/types";
import { PERMISSION_CODES } from "./contract";
import { propsSchemaOf } from "./library";
import { canonicalPermissions } from "./permissions";
import { notReady, staticReadiness, type Readiness } from "./readiness";

export type InspectorTabId = "content" | "design" | "data" | "action" | "permission" | "advanced";
export const INSPECTOR_TABS: readonly { id: InspectorTabId; label: string }[] = [
  { id: "content", label: "Nội dung" }, { id: "design", label: "Thiết kế" }, { id: "data", label: "Dữ liệu" },
  { id: "action", label: "Hành động" }, { id: "permission", label: "Quyền" }, { id: "advanced", label: "Nâng cao" },
];

/** props that are about looks, not content. The registry declares only a few today; spacing/size/alignment/typography are NOT in any registry schema yet. */
export const DESIGN_PROP_NAMES: ReadonlySet<string> = new Set(["theme", "variant", "align", "alignment", "size", "spacing", "background", "layout", "columns", "radius", "font", "fontFamily"]);
/** a prop that shows/hides the whole section belongs to the Permission/visibility tab, not to Content */
export const VISIBILITY_PROP = "visible";

const isDesign = (name: string, def: PropDef) => DESIGN_PROP_NAMES.has(name) && def.type !== "array";

export type PropGroups = { content: [string, PropDef][]; design: [string, PropDef][]; visibility: [string, PropDef] | null };
export function groupProps(component: RegistryComponent | undefined): PropGroups {
  const defs = (propsSchemaOf(component).properties ?? {}) as Record<string, PropDef>;
  const g: PropGroups = { content: [], design: [], visibility: null };
  for (const [k, d] of Object.entries(defs)) {
    if (k === VISIBILITY_PROP) g.visibility = [k, d];
    else if (isDesign(k, d)) g.design.push([k, d]);
    else g.content.push([k, d]);
  }
  return g;
}

export type TabState = { id: InspectorTabId; label: string; readiness: Readiness; note?: string };

export type InspectorContext = {
  section: Section; component: RegistryComponent | undefined; meta: ComponentMetadataV2 | undefined;
  canEdit: boolean; definitionOps: Readiness;
};

export function tabStates(ctx: InspectorContext): TabState[] {
  const g = groupProps(ctx.component);
  const ready: Readiness = { state: "AVAILABLE" };
  return INSPECTOR_TABS.map((t): TabState => {
    switch (t.id) {
      case "content": return { ...t, readiness: ready, ...(g.content.length ? {} : { note: "Thành phần này không có nội dung chỉnh được." }) };
      case "design": return g.design.length
        ? { ...t, readiness: ready }
        : { ...t, readiness: notReady("Component này chưa khai báo thuộc tính thiết kế (khoảng cách, kích thước, căn lề, chữ) trong registry. Màu, phông và bo góc chung của cả ứng dụng nằm ở mục Giao diện bên trái.") };
      case "data": return { ...t, readiness: ctx.definitionOps.state === "AVAILABLE" ? ready : ctx.definitionOps };
      case "action": return { ...t, readiness: ctx.definitionOps.state === "AVAILABLE" ? (ctx.meta ? ready : notReady("Chưa có thông tin sự kiện của component (component-metadata).")) : ctx.definitionOps };
      case "permission": return { ...t, readiness: ready };
      case "advanced": return { ...t, readiness: ready };
    }
  });
}

/** bindable props: from component-metadata when the server has it, otherwise DERIVED from the props schema exactly like the server's derived overlay */
export type BindableProp = { prop: string; cardinality: "LIST" | "SINGLE"; itemFields: string[]; derived: boolean };
export function bindableProps(component: RegistryComponent | undefined, meta: ComponentMetadataV2 | undefined): BindableProp[] {
  if (meta) return meta.bindableProps.map((b) => ({ ...b, derived: false }));
  const defs = (propsSchemaOf(component).properties ?? {}) as Record<string, PropDef & { itemProperties?: Record<string, unknown> }>;
  return Object.entries(defs).flatMap<BindableProp>(([prop, d]) => d.type === "array" ? [{ prop, cardinality: "LIST", itemFields: Object.keys(d.itemProperties ?? {}), derived: true }]
    : d.type === "string" && !d.format && prop !== "id" ? [{ prop, cardinality: "SINGLE", itemFields: [], derived: true }] : []);
}

/** events of a component (only canonical EventType wire names come from the metadata). Without metadata: none are invented. */
export const eventsOf = (meta: ComponentMetadataV2 | undefined) => meta?.events ?? [];

// --------------------------------------------------------------------------------------------------- content → operations
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Compiles an edited draft of a section's props into page operations (the same ops the AI uses). Arrays of objects become ADD_ITEM / REMOVE_ITEM /
 * UPDATE_PROP per changed field so ids stay stable. `only` limits the diff to a group of props (Content vs Design edit different props).
 */
export function propOperations(section: Section, defs: Record<string, PropDef>, draft: Record<string, unknown>, only?: ReadonlySet<string>): SchemaOperation[] {
  const ops: SchemaOperation[] = [];
  const id = section.id;
  for (const [key, def] of Object.entries(defs)) {
    if (only && !only.has(key)) continue;
    const before = section.props[key], after = draft[key];
    if (same(before, after)) continue;
    if (def.type === "array" && def.itemProperties) {
      const oldItems = (before as Record<string, unknown>[] | undefined) ?? [], newItems = (after as Record<string, unknown>[] | undefined) ?? [];
      for (const o of oldItems) if (!newItems.some((n) => n.id === o.id)) ops.push({ type: "REMOVE_ITEM", sectionId: id, arrayPath: key, itemId: String(o.id) });
      for (const n of newItems) {
        const o = oldItems.find((x) => x.id === n.id);
        if (!o) { ops.push({ type: "ADD_ITEM", sectionId: id, arrayPath: key, item: n }); continue; }
        for (const f of Object.keys(def.itemProperties)) if (f !== "id" && !same(o[f], n[f])) ops.push({ type: "UPDATE_PROP", sectionId: id, arrayPath: key, itemId: String(n.id), path: f, value: n[f] ?? "" });
      }
    } else ops.push({ type: "UPDATE_PROP", sectionId: id, path: key, value: after ?? (def.type === "boolean" ? false : "") });
  }
  return ops;
}

// ------------------------------------------------------------------------------------------------------------- permissions
export { canonicalPermissions };

/** declared permission refs (PermissionDef) of a resource of the document, for the Permission tab */
export function permissionRefsFor(doc: AppDefinitionV2, resourceType: "QUERY" | "ACTION" | "WORKFLOW" | "VIEW_MODEL" | "DATA_SOURCE", resourceRef: string) {
  return (doc.permissions ?? []).filter((p) => p.resourceType === resourceType && p.resourceRef === resourceRef);
}
export const permissionLabel: Readonly<Record<(typeof PERMISSION_CODES)[number], string>> = {
  APP_VIEW: "Xem ứng dụng", APP_USE: "Dùng ứng dụng", APP_EDIT: "Chỉnh sửa", APP_PUBLISH: "Xuất bản", APP_SHARE: "Chia sẻ",
  DATA_SOURCE_VIEW: "Xem nguồn dữ liệu", DATA_SOURCE_MANAGE: "Quản lý nguồn dữ liệu", QUERY_EXECUTE: "Chạy truy vấn", DATA_MUTATE: "Ghi dữ liệu",
  ACTION_EXECUTE: "Chạy hành động", WORKFLOW_EXECUTE: "Chạy workflow", WORKFLOW_MANAGE: "Quản lý workflow", TENANT_MANAGE: "Quản lý công ty", TENANT_MEMBERS: "Quản lý thành viên công ty",
};
export { staticReadiness };
