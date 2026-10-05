// Vietnamese display strings shared by the three web apps. Users never see raw enums or technical ids (docs: UI language rule).
export const ACTION_LABEL: Record<string, string> = {
  LOGIN_SUCCESS: "Đăng nhập", LOGIN_FAILURE: "Đăng nhập thất bại", LOGOUT: "Đăng xuất", CREATE_PROJECT: "Tạo ứng dụng", UPDATE_PROJECT: "Sửa cài đặt", DELETE_PROJECT: "Xóa ứng dụng",
  RUN_PROMPT: "Chạy prompt", CREATE_VERSION: "Tạo phiên bản", RESTORE_VERSION: "Khôi phục phiên bản", UPLOAD_ASSET: "Tải tệp lên", DELETE_ASSET: "Xóa tệp", PUBLISH: "Xuất bản",
  DEPLOY_STATUS_CHANGE: "Trạng thái triển khai", ADD_MEMBER: "Thêm thành viên", CHANGE_PERMISSION: "Đổi quyền", REMOVE_MEMBER: "Gỡ thành viên", REGISTER: "Đăng ký",
  PROVISION_USER: "Tạo tài khoản SSO", USER_DISABLED: "Vô hiệu hóa người dùng", USER_ENABLED: "Kích hoạt người dùng", REVOKE_SESSIONS: "Thu hồi phiên", TRANSFER_OWNERSHIP: "Chuyển chủ sở hữu", CLEANUP: "Dọn dữ liệu",
  CREATE_TEMPLATE: "Tạo mẫu", UPDATE_TEMPLATE: "Sửa mẫu", ARCHIVE_TEMPLATE: "Lưu trữ mẫu", RESTORE_TEMPLATE: "Khôi phục mẫu", TEMPLATE_VISIBILITY: "Đổi phạm vi mẫu",
  CREATE_BLOCK: "Tạo khối", UPDATE_BLOCK: "Sửa khối", SUBMIT_BLOCK: "Gửi duyệt khối", WITHDRAW_BLOCK: "Rút khối", DELETE_BLOCK: "Xóa khối",
  APPROVE_BLOCK: "Duyệt khối", REJECT_BLOCK: "Từ chối khối", DEPRECATE_BLOCK: "Ngừng dùng khối", RESTORE_BLOCK: "Khôi phục khối"
};
export const actionLabel = (a: string) => ACTION_LABEL[a] ?? a;

/** Role names shown to people. Unknown values fall back to the raw value so a new server-side role is never hidden. */
export const ROLE_LABEL: Record<string, string> = {
  OWNER: "Chủ sở hữu", EDITOR: "Người chỉnh sửa", PUBLISHER: "Người xuất bản", VIEWER: "Người xem",
  WORKSPACE_ADMIN: "Quản trị workspace", SYSTEM_ADMIN: "Quản trị hệ thống"
};
export const roleName = (r: string) => ROLE_LABEL[r] ?? r;

/** Portal-level words. */
export const PORTAL_TEXT = {
  platform: { title: "Xweb Platform", sub: "Quản trị nền tảng" },
  admin: { title: "Quản trị công ty", sub: "Tổ chức, người dùng và dữ liệu của công ty" },
  studio: { title: "Xweb Studio", sub: "Xây dựng ứng dụng" }
} as const;
