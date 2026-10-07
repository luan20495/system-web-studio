/**
 * The ONE place where the frontend turns "what the backend resolved for this person" into UX decisions (C1 permission contract, tenant-permission.md §5).
 *
 *  - Input is ONLY a list of permission codes as the server returned them (`ApiProject.permissions`, `Me.workspaces[].permissions`, `Me.permissions`).
 *    A ROLE NAME IS NEVER AN INPUT: nothing here (or anywhere in the UI) may say `role === "VIEWER"`. Whatever a role grants is the server's business; the
 *    server tells us the resulting codes.
 *  - The only translation is the documented storage alias (contract §5 table: the project payload still carries `PROJECT_READ` for `APP_VIEW`, …).
 *    A code that is neither canonical nor in that table is dropped; the frontend never invents a permission.
 *  - Everything below is UX only: hiding or disabling a control is not authorisation. The server re-checks actor, tenant, workspace, project/app,
 *    resource scope and permission on every call, so a forged request with a missing permission is refused (403) or out of scope (404) regardless of this file.
 */
import { LEGACY_PERMISSION_ALIAS, PERMISSION_CODES, type PermissionCode } from "../../types/src/contract/v2/permissions";

export type { PermissionCode };
export type PermissionSet = ReadonlySet<PermissionCode>;

const CANONICAL: ReadonlySet<string> = new Set(PERMISSION_CODES);

/** Canonical set of a server-provided list. Legacy storage names are translated through the contract's alias table; anything else is dropped. */
export function resolvePermissions(raw: readonly string[] | undefined | null): Set<PermissionCode> {
  const out = new Set<PermissionCode>();
  for (const p of raw ?? []) {
    if (CANONICAL.has(p)) out.add(p as PermissionCode);
    else if (p in LEGACY_PERMISSION_ALIAS) out.add(LEGACY_PERMISSION_ALIAS[p as keyof typeof LEGACY_PERMISSION_ALIAS]);
  }
  return out;
}

export const holds = (p: PermissionSet, code: PermissionCode): boolean => p.has(code);
export const holdsAll = (p: PermissionSet, codes: readonly PermissionCode[]): boolean => codes.every((c) => p.has(c));
/** the codes of `needed` that `p` does not hold (for an honest "you are missing …" message) */
export const missing = (p: PermissionSet, needed: readonly PermissionCode[]): PermissionCode[] => needed.filter((c) => !p.has(c));

/** Server storage constants that have NO canonical code (the server keeps them internal but still returns them on a project): PROJECT_DELETE. */
export const holdsStorageConstant = (raw: readonly string[] | undefined | null, name: "PROJECT_DELETE"): boolean => !!raw?.includes(name);

// ---- page level ------------------------------------------------------------------------------------------------------------------------------------------

/** Studio / project view. APP_VIEW only: APP_USE, APP_EDIT or any data code do NOT stand in for it. */
export const canViewProject = (p: PermissionSet): boolean => holds(p, "APP_VIEW");
/** APP_VIEW without APP_EDIT is a legitimate state: the project opens READ-ONLY (never a redirect). */
export const canEditProject = (p: PermissionSet): boolean => holds(p, "APP_EDIT");
export const isReadOnlyProject = (p: PermissionSet): boolean => canViewProject(p) && !canEditProject(p);

// ---- running things in the Test panel / app ----------------------------------------------------------------------------------------------------------

/** TEST query: ALL of APP_USE + QUERY_EXECUTE + APP_EDIT. */
export const TEST_QUERY_REQUIRES: readonly PermissionCode[] = ["APP_USE", "QUERY_EXECUTE", "APP_EDIT"];
export const canRunTestQuery = (p: PermissionSet): boolean => holdsAll(p, TEST_QUERY_REQUIRES);

/** Action types that change data: the server adds DATA_MUTATE for exactly these (ActionRuntime.DATA_MUTATING). */
export const DATA_MUTATING_ACTION_TYPES: readonly string[] = ["SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API"];

export type ActionPermissionInput = {
  type: string;
  /** the canonical code declared by the action (`permissionRef` → `PermissionDef.permission`), already resolved by the caller; null/undefined = none */
  declaredPermission?: string | null;
};

/**
 * Everything a person must hold to run an action:
 *   APP_USE + ACTION_EXECUTE
 *   + DATA_MUTATE            when the action mutates data
 *   + WORKFLOW_EXECUTE       when the action starts a workflow (the server checks it for START_WORKFLOW)
 *   + <declared permission>  when the action declares its own
 *   + APP_EDIT               when it runs in TEST (the draft belongs to the editor)
 */
export function actionRequires(action: ActionPermissionInput, opts: { test?: boolean } = {}): PermissionCode[] {
  const need: PermissionCode[] = ["APP_USE", "ACTION_EXECUTE"];
  if (DATA_MUTATING_ACTION_TYPES.includes(action.type)) need.push("DATA_MUTATE");
  if (action.type === "START_WORKFLOW") need.push("WORKFLOW_EXECUTE");
  if (action.declaredPermission && CANONICAL.has(action.declaredPermission)) need.push(action.declaredPermission as PermissionCode);
  if (opts.test) need.push("APP_EDIT");
  return [...new Set(need)];
}
export const canRunAction = (p: PermissionSet, action: ActionPermissionInput, opts: { test?: boolean } = {}): boolean => holdsAll(p, actionRequires(action, opts));

/** Workflow start: APP_USE + WORKFLOW_EXECUTE, plus APP_EDIT in TEST. */
export const workflowStartRequires = (opts: { test?: boolean } = {}): PermissionCode[] => (opts.test ? ["APP_USE", "WORKFLOW_EXECUTE", "APP_EDIT"] : ["APP_USE", "WORKFLOW_EXECUTE"]);
export const canStartWorkflow = (p: PermissionSet, opts: { test?: boolean } = {}): boolean => holdsAll(p, workflowStartRequires(opts));
/**
 * Status / cancel of a run: the server decides (tenant → workspace → project/app → creator or WORKFLOW_MANAGE; C4 F-1 is still OPEN for creator cross-scope).
 * The UI therefore offers NOTHING based on "I created it": it shows what the server returns and surfaces the server's 403/404. Only WORKFLOW_MANAGE is a UX hint.
 */
export const canManageWorkflows = (p: PermissionSet): boolean => holds(p, "WORKFLOW_MANAGE");

// ---- publish lifecycle ----------------------------------------------------------------------------------------------------------------------------------

/** Publish AND rollback are one lifecycle: APP_PUBLISH. APP_EDIT never stands in for it. */
export const canPublish = (p: PermissionSet): boolean => holds(p, "APP_PUBLISH");
export const canRollback = (p: PermissionSet): boolean => holds(p, "APP_PUBLISH");
export const canShare = (p: PermissionSet): boolean => holds(p, "APP_SHARE");

// ---- data sources ------------------------------------------------------------------------------------------------------------------------------------------

/** metadata only (list, descriptors). Says nothing about credentials, test connection or bindings. */
export const canViewDataSources = (p: PermissionSet): boolean => holds(p, "DATA_SOURCE_VIEW");
/** create / update / delete / credential metadata / connection test */
export const canManageDataSources = (p: PermissionSet): boolean => holds(p, "DATA_SOURCE_MANAGE");
/** a TEST / draft binding changes the application's draft: DATA_SOURCE_MANAGE + APP_EDIT */
export const canBindDataSources = (p: PermissionSet): boolean => holdsAll(p, ["DATA_SOURCE_MANAGE", "APP_EDIT"]);

// ---- portal level ------------------------------------------------------------------------------------------------------------------------------------------

/**
 * "May use Studio" from ONE resolved permission list (a workspace's `permissions`): APP_VIEW. A list that is ABSENT (an older backend) cannot say no, so it does not
 * block (UX only; the server still answers 403/404). An EMPTY list is an answer: it holds nothing.
 */
export const canViewStudioIn = (raw: readonly string[] | undefined | null): boolean => raw === undefined || raw === null || canViewProject(resolvePermissions(raw));

/** Short Vietnamese labels for "you are missing …" (the 14 canonical codes) */
export const PERMISSION_LABEL_VI: Readonly<Record<PermissionCode, string>> = {
  APP_VIEW: "Xem ứng dụng", APP_USE: "Dùng ứng dụng", APP_EDIT: "Chỉnh sửa ứng dụng", APP_PUBLISH: "Xuất bản", APP_SHARE: "Chia sẻ",
  DATA_SOURCE_VIEW: "Xem nguồn dữ liệu", DATA_SOURCE_MANAGE: "Quản lý nguồn dữ liệu", QUERY_EXECUTE: "Chạy truy vấn", DATA_MUTATE: "Ghi dữ liệu",
  ACTION_EXECUTE: "Chạy hành động", WORKFLOW_EXECUTE: "Chạy workflow", WORKFLOW_MANAGE: "Quản lý workflow", TENANT_MANAGE: "Quản trị công ty", TENANT_MEMBERS: "Quản lý thành viên công ty",
};
export const missingReason = (p: PermissionSet, needed: readonly PermissionCode[]): string | null => {
  const m = missing(p, needed);
  return m.length ? `Bạn chưa có quyền: ${m.map((c) => PERMISSION_LABEL_VI[c]).join(", ")}.` : null;
};
