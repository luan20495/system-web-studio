/**
 * Permission-driven UI helpers. The API still returns the legacy PROJECT_* names in `project.permissions`; the contract's canonical vocabulary
 * (14 codes) is what the Builder reasons in. Hiding a control is a convenience, never authorisation: the server checks every call
 * (tenant-permission.md §5 rule 4).
 */
import { LEGACY_PERMISSION_ALIAS, PERMISSION_CODES, type PermissionCode } from "./contract";

const CANONICAL: ReadonlySet<string> = new Set(PERMISSION_CODES);

/** legacy → canonical; canonical stays; anything else is dropped (the frontend never invents a code) */
export function canonicalPermissions(raw: readonly string[] | undefined | null): Set<PermissionCode> {
  const out = new Set<PermissionCode>();
  for (const p of raw ?? []) {
    if (CANONICAL.has(p)) out.add(p as PermissionCode);
    else if (p in LEGACY_PERMISSION_ALIAS) out.add(LEGACY_PERMISSION_ALIAS[p as keyof typeof LEGACY_PERMISSION_ALIAS]);
  }
  return out;
}

export type BuilderCapabilities = {
  canView: boolean; canEdit: boolean; canPublish: boolean; canShare: boolean;
  /** Test mode runs real queries/actions with the user's own rights, so each kind needs its own code */
  canRunQueries: boolean; canRunActions: boolean; canStartWorkflows: boolean; canManageWorkflows: boolean; canManageDataSources: boolean;
};

export function capabilitiesFor(raw: readonly string[] | undefined | null): BuilderCapabilities {
  const p = canonicalPermissions(raw);
  return {
    canView: p.has("APP_VIEW") || p.has("APP_EDIT"), canEdit: p.has("APP_EDIT"), canPublish: p.has("APP_PUBLISH"), canShare: p.has("APP_SHARE"),
    canRunQueries: p.has("QUERY_EXECUTE"), canRunActions: p.has("ACTION_EXECUTE"), canStartWorkflows: p.has("WORKFLOW_EXECUTE"),
    canManageWorkflows: p.has("WORKFLOW_MANAGE"), canManageDataSources: p.has("DATA_SOURCE_MANAGE"),
  };
}

/** short Vietnamese reason shown on a disabled control */
export function whyNot(cap: keyof BuilderCapabilities): string {
  switch (cap) {
    case "canEdit": return "Bạn không có quyền chỉnh sửa ứng dụng này.";
    case "canPublish": return "Bạn không có quyền xuất bản.";
    case "canShare": return "Bạn không có quyền chia sẻ.";
    case "canRunQueries": return "Bạn chưa được cấp quyền chạy truy vấn.";
    case "canRunActions": return "Bạn chưa được cấp quyền chạy hành động.";
    case "canStartWorkflows": return "Bạn chưa được cấp quyền chạy workflow.";
    case "canManageWorkflows": return "Bạn chưa được cấp quyền quản lý workflow.";
    case "canManageDataSources": return "Bạn chưa được cấp quyền quản lý nguồn dữ liệu.";
    default: return "Bạn không có quyền.";
  }
}
