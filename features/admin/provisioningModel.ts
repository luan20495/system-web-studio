/**
 * Rules of the "create account" flows (Platform and Admin portals). Pure: unit-tested. What is OFFERED comes from the scope the server gave (adminScope) and
 * from the capability table (provisioning.ts); what is ALLOWED is decided by the server on every call. No role name decides anything here: the account TYPES below
 * are choices for the form, never a permission check. SYSTEM_ADMIN is not an account type: granting it is a separate, confirmed action in the user's own page.
 */
import type { AdminScope } from "./adminModel";
import type { CapabilityId, CapabilityState, WorkspaceRoleId } from "./provisioning";

export type Surface = "platform" | "admin";
export type AccountTypeId = "TENANT_ADMIN" | "WORKSPACE_ADMIN" | "USER";
export type AccountType = { id: AccountTypeId; label: string; hint: string; roles: { id: WorkspaceRoleId; label: string }[] };
const R = (id: WorkspaceRoleId, label: string) => ({ id, label });
export const ACCOUNT_TYPES: Readonly<Record<AccountTypeId, AccountType>> = {
  TENANT_ADMIN: { id: "TENANT_ADMIN", label: "Quản trị công ty", hint: "Quản lý thành viên của một công ty. Được tạo cùng một workspace; vai trò công ty gán sau khi người đó kích hoạt tài khoản.", roles: [R("WORKSPACE_ADMIN", "Quản trị không gian làm việc")] },
  WORKSPACE_ADMIN: { id: "WORKSPACE_ADMIN", label: "Quản trị workspace", hint: "Quản lý thành viên và nguồn dữ liệu của một workspace.", roles: [R("WORKSPACE_ADMIN", "Quản trị không gian làm việc")] },
  USER: { id: "USER", label: "Người dùng", hint: "Dùng Studio theo vai trò trong workspace.", roles: [R("EDITOR", "Biên tập viên"), R("PUBLISHER", "Người xuất bản"), R("VIEWER", "Người xem")] },
};

export type CreateState =
  | { state: "ready" }
  | { state: "not-ready"; reason: string; owner: string }
  | { state: "forbidden"; reason: string };
export type ProvisioningPlan = {
  surface: Surface;
  create: CreateState;
  /** account types the form offers: never SYSTEM_ADMIN, never a type the caller's scope cannot grant */
  accountTypes: AccountType[];
  /** the tenant is the caller's own: shown, never typed (Admin portal, non-SYSTEM_ADMIN) */
  fixedTenant: { id: string; name: string } | null;
  /** may choose any tenant (SYSTEM_ADMIN) */
  tenantChoice: boolean;
  /** add an account that already exists to a workspace (MEMBER_MANAGE) */
  addExisting: { available: boolean; reason?: string };
};

export function provisioningPlan(scope: AdminScope, surface: Surface, state: (id: CapabilityId) => CapabilityState): ProvisioningPlan {
  const add = state("addWorkspaceMember");
  const addExisting = scope.workspaces.length > 0 && add.status === "READY" ? { available: true } : { available: false, reason: scope.workspaces.length === 0 ? "Máy chủ không liệt kê quyền quản lý thành viên workspace cho bạn." : add.status === "NOT_READY" ? add.reason : undefined };
  if (scope.platform) {
    const c = state("createPlatformUser");
    return { surface, create: c.status === "READY" ? { state: "ready" } : { state: "not-ready", reason: c.reason, owner: c.owner }, accountTypes: [ACCOUNT_TYPES.TENANT_ADMIN, ACCOUNT_TYPES.WORKSPACE_ADMIN, ACCOUNT_TYPES.USER], fixedTenant: null, tenantChoice: true, addExisting };
  }
  if (scope.tenants.length > 0) {
    const c = state("createTenantUser");
    const t = scope.tenants[0];
    return { surface, create: c.status === "READY" ? { state: "ready" } : { state: "not-ready", reason: c.reason, owner: c.owner }, accountTypes: [ACCOUNT_TYPES.WORKSPACE_ADMIN, ACCOUNT_TYPES.USER], fixedTenant: { id: t.id, name: t.name }, tenantChoice: false, addExisting };
  }
  return { surface, create: { state: "forbidden", reason: "Tài khoản của bạn không có quyền tạo tài khoản mới. Bạn chỉ có thể thêm người đã có tài khoản vào workspace." }, accountTypes: [], fixedTenant: null, tenantChoice: false, addExisting };
}

export type AccountForm = { username: string; displayName: string; email: string; type: AccountTypeId; tenantId: string; workspaceId: string; role: WorkspaceRoleId | "" };
export const emptyAccountForm = (type: AccountTypeId = "USER"): AccountForm => ({ username: "", displayName: "", email: "", type, tenantId: "", workspaceId: "", role: ACCOUNT_TYPES[type].roles[0].id });
export const USERNAME_RE = /^[a-z0-9][a-z0-9._-]{2,39}$/;
export const EMAIL_RE = /^[^\s@<>"]{1,64}@[^\s@<>"]{1,190}\.[A-Za-z]{2,24}$/;
export type FormProblems = Partial<Record<"username" | "displayName" | "email" | "tenant" | "workspace" | "role" | "type", string>>;

/** mirrors `AccountService.create` (username, email) + the form's own requirements; the server answers 400/409 for anything this does not see */
export function validateAccountForm(f: AccountForm, plan: ProvisioningPlan): FormProblems {
  const out: FormProblems = {};
  const u = f.username.trim().toLowerCase();
  if (!USERNAME_RE.test(u) || u.startsWith("oidc-")) out.username = "Tên đăng nhập 3–40 ký tự: chữ thường, số, dấu . _ -";
  if (!f.displayName.trim() || f.displayName.trim().length > 160) out.displayName = "Hãy nhập tên hiển thị (tối đa 160 ký tự).";
  const e = f.email.trim();
  if (e && (e.length > 254 || !EMAIL_RE.test(e))) out.email = "Email không hợp lệ.";
  const type = plan.accountTypes.find((t) => t.id === f.type);
  if (!type) out.type = "Loại tài khoản này không được phép với quyền của bạn.";
  if (f.type === "TENANT_ADMIN" && plan.tenantChoice && !f.tenantId) out.tenant = "Hãy chọn công ty.";
  if (!f.workspaceId) out.workspace = "Hãy chọn workspace.";
  if (type && !type.roles.some((r) => r.id === f.role)) out.role = "Vai trò không hợp lệ cho loại tài khoản này.";
  return out;
}

export type ProvisioningProblem = { kind: "validation" | "duplicate" | "forbidden" | "mismatch" | "protection" | "disabled" | "unavailable" | "not-ready" | "unknown"; field?: "username" | "email" | "workspace"; text: string };
/** the server's refusals by CODE; a specific code never becomes a generic message */
export function provisioningProblem(e: { code?: string; status?: number; message?: string; reason?: string } | null | undefined): ProvisioningProblem {
  const c = e?.code ?? "", st = e?.status ?? 0;
  const by: Record<string, ProvisioningProblem> = {
    PROVISIONING_NOT_READY: { kind: "not-ready", text: e?.reason ?? "Backend provisioning chưa sẵn sàng." },
    INVALID_USERNAME: { kind: "validation", field: "username", text: "Tên đăng nhập 3–40 ký tự: chữ thường, số, dấu . _ -" },
    INVALID_EMAIL: { kind: "validation", field: "email", text: "Email không hợp lệ." },
    USERNAME_TAKEN: { kind: "duplicate", field: "username", text: "Tên đăng nhập này đã tồn tại." },
    EMAIL_TAKEN: { kind: "duplicate", field: "email", text: "Email này đã được dùng." },
    ALREADY_MEMBER: { kind: "duplicate", text: "Người này đã là thành viên." },
    WORKSPACE_NOT_FOUND: { kind: "mismatch", field: "workspace", text: "Không tìm thấy workspace này (hoặc không thuộc công ty của bạn)." },
    USER_NOT_FOUND: { kind: "mismatch", text: "Không tìm thấy người dùng này, hoặc người này chưa đủ điều kiện cho công ty này (chưa kích hoạt, bị khóa, hoặc không thuộc workspace của công ty)." },
    MEMBER_NOT_FOUND: { kind: "mismatch", text: "Không tìm thấy thành viên." },
    TENANT_NOT_FOUND: { kind: "mismatch", text: "Không tìm thấy công ty này." },
    ADMIN_REQUIRED: { kind: "forbidden", text: "Thao tác này chỉ dành cho quản trị hệ thống." },
    PERMISSION_DENIED: { kind: "forbidden", text: "Bạn không có quyền thực hiện thao tác này." },
    SELF_GRANT_FORBIDDEN: { kind: "protection", text: "Bạn không thể tự cấp quyền hoặc tự đổi vai trò của chính mình." },
    CANNOT_CHANGE_SELF: { kind: "protection", text: "Bạn không thể tự đổi quyền của chính mình." },
    LAST_TENANT_ADMIN: { kind: "protection", text: "Công ty phải còn ít nhất một quản trị viên." },
    LAST_ADMIN: { kind: "protection", text: "Workspace phải còn ít nhất một quản trị viên." },
    LAST_SYSTEM_ADMIN: { kind: "protection", text: "Không thể gỡ quyền của quản trị hệ thống cuối cùng." },
    ACCOUNT_DISABLED: { kind: "disabled", text: "Tài khoản đang bị khóa: hãy mở khóa trước." },
    NOT_LOCAL_ACCOUNT: { kind: "validation", text: "Tài khoản này đăng nhập bằng tài khoản công ty (SSO): không có mật khẩu để đặt lại." },
    NOT_ACTIVE: { kind: "disabled", text: "Chỉ áp dụng cho tài khoản đã kích hoạt và đang hoạt động." },
    QUERY_TOO_SHORT: { kind: "validation", text: "Gõ ít nhất 2 ký tự để tìm." },
  };
  if (by[c]) return by[c];
  if (st === 0 || c === "NETWORK" || c === "TIMEOUT") return { kind: "unavailable", text: "Không kết nối được máy chủ. Chưa rõ thao tác đã được ghi hay chưa: hãy tải lại danh sách trước khi thử lại." };
  if (st === 503 || st === 502 || st === 504 || st >= 500) return { kind: "unavailable", text: "Máy chủ chưa sẵn sàng. Hãy thử lại sau ít phút." };
  if (st === 403) return { kind: "forbidden", text: "Bạn không có quyền thực hiện thao tác này." };
  if (st === 404) return { kind: "mismatch", text: "Không tìm thấy (hoặc không thuộc phạm vi của bạn)." };
  if (st === 409) return { kind: "duplicate", text: e?.message ?? "Xung đột dữ liệu." };
  if (st === 400) return { kind: "validation", text: e?.message ?? "Dữ liệu chưa hợp lệ." };
  return { kind: "unknown", text: e?.message ?? "Chưa thực hiện được." };
}
