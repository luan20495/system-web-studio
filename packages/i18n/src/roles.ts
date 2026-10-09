/**
 * The ONE role-label table (M-062). Four tables named the same role three different ways (adminModel.ts, UserDialogs.tsx, provisioningModel.ts, drawers.tsx, and this package's own unused one):
 * EDITOR was "Biên tập viên" / "Biên tập" / "Người chỉnh sửa", WORKSPACE_ADMIN "Quản trị không gian làm việc" / "Quản trị workspace".
 *
 * DECISION FOR THE PRODUCT OWNER (S3-glossary.md 2.2): the names below are the proposal (the form this package already had, minus the long "không gian làm việc").
 * Typed `satisfies Record<RoleCode, string>`: adding a role code without a label fails the typecheck.
 */
import { labelOf } from "./text";

export const ROLE_CODES = ["OWNER", "WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER", "TENANT_ADMIN", "MEMBER", "SYSTEM_ADMIN"] as const;
export type RoleCode = (typeof ROLE_CODES)[number];

export const ROLE_LABEL = {
  OWNER: "Chủ sở hữu",
  WORKSPACE_ADMIN: "Quản trị workspace",
  EDITOR: "Người chỉnh sửa",
  PUBLISHER: "Người xuất bản",
  VIEWER: "Người xem",
  TENANT_ADMIN: "Quản trị công ty",
  MEMBER: "Thành viên",
  SYSTEM_ADMIN: "Quản trị hệ thống"
} as const satisfies Record<RoleCode, string>;

/** one-line meaning of each role, for pickers and tooltips (what the role MAY do; the server still decides) */
export const ROLE_HINT = {
  OWNER: "Toàn quyền với ứng dụng, kể cả chuyển quyền sở hữu và xóa.",
  WORKSPACE_ADMIN: "Quản lý thành viên và nguồn dữ liệu của workspace.",
  EDITOR: "Sửa nội dung và cấu hình ứng dụng.",
  PUBLISHER: "Xuất bản ứng dụng lên website.",
  VIEWER: "Chỉ xem, không sửa.",
  TENANT_ADMIN: "Quản lý người dùng, workspace và cài đặt của công ty.",
  MEMBER: "Người dùng thường của công ty.",
  SYSTEM_ADMIN: "Quản trị toàn nền tảng, mọi công ty."
} as const satisfies Record<RoleCode, string>;

/** the roles a person can be given inside a workspace, in the order a picker shows them (OWNER is an app-level role and is not granted here) */
export const WORKSPACE_ROLE_ORDER = ["WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER"] as const satisfies readonly RoleCode[];

/** the label of a role code; an unknown role is shown as "Khác" (and reported), never as the raw code */
export const roleName = (r: string | null | undefined): string => labelOf(ROLE_LABEL, r, undefined, "role");
