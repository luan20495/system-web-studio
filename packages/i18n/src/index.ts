// Vietnamese display strings shared by the three web apps. Users never see raw enums or technical ids (docs: UI language rule).
// The text library: brand.ts (names), roles.ts (the ONE role table), labels.ts (every enum the UI shows, typed so a missing label fails the typecheck), text.ts (labelOf).
// Wording rules and the glossary: docs/parallel/c5/audit/S3-glossary.md. Guards that only ratchet: tests/builder/text-guard.test.ts.
import { BRAND } from "./brand";
export * from "./brand";
export * from "./text";
export * from "./roles";
export * from "./labels";

export const ACTION_LABEL: Record<string, string> = {
  LOGIN_SUCCESS: "Đăng nhập", LOGIN_FAILURE: "Đăng nhập thất bại", LOGOUT: "Đăng xuất", CREATE_PROJECT: "Tạo ứng dụng", UPDATE_PROJECT: "Sửa cài đặt", DELETE_PROJECT: "Xóa ứng dụng",
  RUN_PROMPT: "Chạy prompt", CREATE_VERSION: "Tạo phiên bản", RESTORE_VERSION: "Khôi phục phiên bản", UPLOAD_ASSET: "Tải tệp lên", DELETE_ASSET: "Xóa tệp", PUBLISH: "Xuất bản",
  DEPLOY_STATUS_CHANGE: "Trạng thái triển khai", ADD_MEMBER: "Thêm thành viên", CHANGE_PERMISSION: "Đổi quyền", REMOVE_MEMBER: "Gỡ thành viên", REGISTER: "Đăng ký",
  PROVISION_USER: "Tạo tài khoản SSO", USER_DISABLED: "Khóa tài khoản", USER_ENABLED: "Mở khóa tài khoản", REVOKE_SESSIONS: "Thu hồi phiên", TRANSFER_OWNERSHIP: "Chuyển chủ sở hữu", CLEANUP: "Dọn dữ liệu",
  CREATE_TEMPLATE: "Tạo mẫu", UPDATE_TEMPLATE: "Sửa mẫu", ARCHIVE_TEMPLATE: "Lưu trữ mẫu", RESTORE_TEMPLATE: "Khôi phục mẫu", TEMPLATE_VISIBILITY: "Đổi phạm vi mẫu",
  CREATE_BLOCK: "Tạo khối", UPDATE_BLOCK: "Sửa khối", SUBMIT_BLOCK: "Gửi duyệt khối", WITHDRAW_BLOCK: "Rút khối", DELETE_BLOCK: "Xóa khối",
  APPROVE_BLOCK: "Duyệt khối", REJECT_BLOCK: "Từ chối khối", DEPRECATE_BLOCK: "Ngừng dùng khối", RESTORE_BLOCK: "Khôi phục khối"
};
export const actionLabel = (a: string) => ACTION_LABEL[a] ?? a;

/** Portal-level words. The names come from BRAND (one source). */
export const PORTAL_TEXT = {
  platform: { title: BRAND.portal.platform, sub: "Quản trị nền tảng" },
  admin: { title: BRAND.portal.admin, sub: "Tổ chức, người dùng và dữ liệu của công ty" },
  studio: { title: BRAND.portal.studio, sub: "Xây dựng ứng dụng" }
} as const;
