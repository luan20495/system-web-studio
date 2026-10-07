/**
 * Permission-driven UI helpers of the Builder. The only logic lives in packages/permissions/src/canonical.ts (pure, tested); this file adapts it to the
 * shapes the Builder uses. Hiding a control is a convenience, never authorisation: the server checks every call (tenant-permission.md §5 rule 4) and refuses a
 * forged request (403 same scope without the permission, 404 out of scope). No role name is read here or anywhere in the UI.
 */
import {
  canBindDataSources, canEditProject, canManageDataSources, canManageWorkflows, canPublish, canRunAction, canRunTestQuery, canShare, canStartWorkflow, canViewDataSources, canViewProject,
  actionRequires, missingReason, resolvePermissions, workflowStartRequires, TEST_QUERY_REQUIRES,
  type ActionPermissionInput, type PermissionCode, type PermissionSet,
} from "../../../../packages/permissions/src/canonical";

/** legacy storage name → canonical (documented alias); canonical stays; anything else is dropped (the frontend never invents a code) */
export function canonicalPermissions(raw: readonly string[] | undefined | null): Set<PermissionCode> { return resolvePermissions(raw); }

export type BuilderCapabilities = {
  canView: boolean; canEdit: boolean; canPublish: boolean; canShare: boolean;
  /** coarse per-kind flags (one code each); the Test panel uses the conjunctions below, which are what the server really requires */
  canRunQueries: boolean; canRunActions: boolean; canStartWorkflows: boolean; canManageWorkflows: boolean; canManageDataSources: boolean;
  canViewDataSources: boolean; canBindDataSources: boolean;
};

export function capabilitiesFor(raw: readonly string[] | undefined | null): BuilderCapabilities {
  const p = resolvePermissions(raw);
  return {
    canView: canViewProject(p), canEdit: canEditProject(p), canPublish: canPublish(p), canShare: canShare(p),
    canRunQueries: p.has("QUERY_EXECUTE"), canRunActions: p.has("ACTION_EXECUTE"), canStartWorkflows: p.has("WORKFLOW_EXECUTE"),
    canManageWorkflows: canManageWorkflows(p), canManageDataSources: canManageDataSources(p), canViewDataSources: canViewDataSources(p), canBindDataSources: canBindDataSources(p),
  };
}

/** What the Test panel needs, with the reason when something is missing (null = allowed). The reasons name the missing permissions. */
export function testGates(raw: readonly string[] | undefined | null) {
  const p: PermissionSet = resolvePermissions(raw);
  return {
    query: (): string | null => missingReason(p, TEST_QUERY_REQUIRES),
    action: (a: ActionPermissionInput): string | null => missingReason(p, actionRequires(a, { test: true })),
    workflow: (): string | null => missingReason(p, workflowStartRequires({ test: true })),
    canRunQuery: canRunTestQuery(p),
    canRunAction: (a: ActionPermissionInput) => canRunAction(p, a, { test: true }),
    canStartWorkflow: canStartWorkflow(p, { test: true }),
  };
}

/** short Vietnamese reason shown on a disabled control */
export function whyNot(cap: keyof BuilderCapabilities): string {
  switch (cap) {
    case "canView": return "Bạn không có quyền xem ứng dụng này.";
    case "canEdit": return "Bạn chỉ có quyền xem: không có quyền chỉnh sửa ứng dụng này.";
    case "canPublish": return "Bạn không có quyền xuất bản (cũng là quyền khôi phục bản đã xuất bản).";
    case "canShare": return "Bạn không có quyền chia sẻ.";
    case "canRunQueries": return "Bạn chưa được cấp quyền chạy truy vấn.";
    case "canRunActions": return "Bạn chưa được cấp quyền chạy hành động.";
    case "canStartWorkflows": return "Bạn chưa được cấp quyền chạy workflow.";
    case "canManageWorkflows": return "Bạn chưa được cấp quyền quản lý workflow.";
    case "canViewDataSources": return "Bạn chưa được cấp quyền xem nguồn dữ liệu.";
    case "canManageDataSources": return "Bạn chưa được cấp quyền quản lý nguồn dữ liệu.";
    case "canBindDataSources": return "Liên kết nguồn dữ liệu cần quyền quản lý nguồn dữ liệu và quyền chỉnh sửa ứng dụng.";
    default: return "Bạn không có quyền.";
  }
}
