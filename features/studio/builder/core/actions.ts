/**
 * Action editor model (canonical C4 ActionType list, nine types). An action is a DECLARATION: type + typed references + typed input sources.
 * There is no field that can hold code, SQL, a URL or a header, and the editor never offers one. `REFRESH_QUERY` is the only way to re-run a
 * READ query (`RUN_QUERY` and the other old names are rejected). `trigger` is OPTIONAL: a UI-bound action has one; a child action (workflow step,
 * onSuccess/onError chain) does not.
 */
import type { ActionDef, ActionType, AppDefinitionV2, ComponentMetadataV2, DefinitionOperation, EventType, PermissionCode } from "@xweb/types";
import { ACTION_TYPES, CLIENT_ONLY_ACTION_TYPES, EVENT_TYPES, REJECTED_ACTION_ALIASES } from "./contract";
import { defOps, validateDefinition, type RefIssue } from "./definition";
import { uniqueId } from "./dataFlow";
import { typeLabel } from "./library";
import { pageExists } from "./pages";

export const ACTION_LABEL: Readonly<Record<ActionType, string>> = {
  NAVIGATE: "Chuyển trang", REFRESH_QUERY: "Làm mới dữ liệu", SUBMIT_FORM: "Gửi biểu mẫu", CREATE_RECORD: "Tạo bản ghi", UPDATE_RECORD: "Cập nhật bản ghi",
  DELETE_RECORD: "Xóa bản ghi", CALL_API: "Gọi thao tác đã duyệt", NOTIFY: "Gửi thông báo", START_WORKFLOW: "Chạy workflow",
};
export const ACTION_HELP: Readonly<Record<ActionType, string>> = {
  NAVIGATE: "Đưa người dùng tới một trang của ứng dụng. Không có tác dụng phụ trên máy chủ.",
  REFRESH_QUERY: "Chạy lại một truy vấn đọc để làm mới dữ liệu đang hiển thị.",
  SUBMIT_FORM: "Gửi dữ liệu biểu mẫu qua một truy vấn ghi đã khai báo.",
  CREATE_RECORD: "Tạo một bản ghi mới qua truy vấn ghi.", UPDATE_RECORD: "Sửa một bản ghi qua truy vấn ghi.", DELETE_RECORD: "Xóa một bản ghi qua truy vấn ghi.",
  CALL_API: "Gọi một thao tác đã được quản trị viên duyệt của nguồn dữ liệu.",
  NOTIFY: "Gửi thông báo theo mẫu đã duyệt (trong ứng dụng, email, webhook đã đăng ký, SMS).",
  START_WORKFLOW: "Bắt đầu một workflow đã khai báo.",
};
export const EVENT_LABEL: Readonly<Record<EventType, string>> = {
  onLoad: "Khi trang mở", onClick: "Khi nhấn", onChange: "Khi giá trị đổi", onSubmit: "Khi gửi biểu mẫu", onSuccess: "Khi thành công", onError: "Khi có lỗi",
};
export const isClientOnly = (t: ActionType): boolean => (CLIENT_ONLY_ACTION_TYPES as readonly string[]).includes(t);

export const isRejectedAlias = (name: string): boolean => (REJECTED_ACTION_ALIASES as readonly string[]).includes(name);
export const isActionType = (name: string): name is ActionType => (ACTION_TYPES as readonly string[]).includes(name);

/** events a component really emits and the action types each supports, from component-metadata. Nothing is guessed without it. */
export function eventsFor(meta: ComponentMetadataV2 | undefined): { name: EventType; label: string; supportedActions: ActionType[] }[] {
  return (meta?.events ?? []).filter((e) => (EVENT_TYPES as readonly string[]).includes(e.name)).map((e) => ({ ...e, label: EVENT_LABEL[e.name] ?? e.label }));
}
export function actionTypesFor(meta: ComponentMetadataV2 | undefined, event: EventType): ActionType[] {
  return meta?.events.find((e) => e.name === event)?.supportedActions.filter(isActionType) ?? [];
}

export type ActionRole = "UI_BOUND" | "CHILD" | "UNATTACHED";
export function roleOf(a: ActionDef, doc: AppDefinitionV2): ActionRole {
  if (a.trigger) return "UI_BOUND";
  const child = (doc.workflows ?? []).some((w) => w.steps.some((s) => s.actionRef === a.id || s.compensationActionRef === a.id))
    || (doc.actions ?? []).some((o) => (o.onSuccess ?? []).includes(a.id) || (o.onError ?? []).includes(a.id));
  return child ? "CHILD" : "UNATTACHED";
}
export const ROLE_LABEL: Readonly<Record<ActionRole, string>> = { UI_BOUND: "Gắn vào thành phần", CHILD: "Chạy trong workflow/chuỗi hành động", UNATTACHED: "Chưa gắn vào đâu" };

export function actionsOfSection(doc: AppDefinitionV2, sectionId: string): ActionDef[] { return (doc.actions ?? []).filter((a) => a.trigger?.sectionId === sectionId); }

export function newAction(type: ActionType, doc: AppDefinitionV2, trigger?: { sectionId: string; event: EventType }): ActionDef {
  const id = uniqueId("a", (doc.actions ?? []).map((a) => a.id));
  return { id, name: ACTION_LABEL[type], type, ...(trigger ? { trigger } : {}) };
}

/** keys no declaration may ever carry (C4: forbidden anywhere in config). Defence in depth: the editor has no such inputs, a pasted/AI object is refused here too */
export const FORBIDDEN_KEYS: readonly string[] = ["sql", "script", "url", "headers", "token", "password", "secret", "code", "js", "javascript"];
export function forbiddenKeys(value: unknown, path = ""): string[] {
  if (!value || typeof value !== "object") return [];
  const out: string[] = [];
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (FORBIDDEN_KEYS.includes(k.toLowerCase())) out.push(path ? `${path}.${k}` : k);
    out.push(...forbiddenKeys(v, path ? `${path}.${k}` : k));
  }
  return out;
}

/** reference problems of ONE action (others are not this editor's business) + forbidden keys + the alias rule */
export function checkAction(a: ActionDef, doc: AppDefinitionV2): RefIssue[] {
  const raw = (a as { type: string }).type;
  if (isRejectedAlias(raw)) return [{ path: "type", message: `“${raw}” không phải loại hành động hợp lệ. Dùng “Làm mới dữ liệu” (REFRESH_QUERY) để chạy lại truy vấn đọc.` }];
  const probe: AppDefinitionV2 = { ...doc, actions: [...(doc.actions ?? []).filter((x) => x.id !== a.id), a] };
  const idx = probe.actions!.length - 1;
  const issues = validateDefinition(probe).filter((i) => i.path.startsWith(`actions[${idx}]`));
  for (const k of forbiddenKeys(a)) issues.push({ path: k, message: `Trường “${k}” không được phép trong hành động (không SQL, địa chỉ, khóa hay mã).` });
  return issues;
}

export function describeAction(a: ActionDef, doc: AppDefinitionV2): string {
  const when = a.trigger ? `${EVENT_LABEL[a.trigger.event]} ở ${typeLabel((doc.sections.find((s) => s.id === a.trigger!.sectionId) ?? (doc.pages ?? []).flatMap((p) => p.sections).find((s) => s.id === a.trigger!.sectionId))?.type ?? a.trigger.sectionId)}` : "Chạy khi được gọi";
  const queryName = (id?: string) => (doc.queries ?? []).find((q) => q.id === id)?.name ?? id ?? "?";
  const what = a.type === "NAVIGATE" ? `chuyển tới ${a.pageRef === undefined ? "?" : a.pageRef === "home" ? "Trang chủ" : (doc.pages ?? []).find((p) => p.id === a.pageRef)?.title ?? `trang đã xoá (${a.pageRef})`}`
    : a.type === "REFRESH_QUERY" ? `làm mới “${queryName(a.queryRef)}”`
    : a.type === "START_WORKFLOW" ? `chạy workflow “${(doc.workflows ?? []).find((w) => w.id === a.workflowRef)?.name ?? a.workflowRef ?? "?"}”`
    : a.type === "NOTIFY" ? `gửi thông báo${a.channel ? ` (${a.channel})` : ""}`
    : a.type === "CALL_API" ? `gọi thao tác “${a.operationKey ?? "?"}”`
    : `${ACTION_LABEL[a.type].toLowerCase()} qua “${queryName(a.queryRef)}”`;
  return `${when} → ${what}`;
}

export function pageOptions(doc: AppDefinitionV2): { id: string; title: string }[] {
  return [{ id: "home", title: doc.site?.home?.title || "Trang chủ" }, ...(doc.pages ?? []).map((p) => ({ id: p.id, title: p.title }))].filter((p) => pageExists(doc, p.id));
}

export const actionOps = {
  add: (a: ActionDef): DefinitionOperation => defOps.add("actions", a),
  update: (id: string, patch: Partial<Record<keyof ActionDef, unknown>>): DefinitionOperation => defOps.update("actions", id, patch),
  remove: (id: string): DefinitionOperation => defOps.remove("actions", id),
};
