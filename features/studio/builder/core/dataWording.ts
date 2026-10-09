/**
 * ONE place for the user-facing words of the guided data flow (M-005). Plain Vietnamese; no "khe", "ViewModel", "ánh xạ", "binding" in the main flow.
 * Glossary of the proposal (docs/parallel/c5/audit/S1-data-binding-proposal.md section 3). S3 owns the wording pass: change a term HERE and it changes everywhere.
 * Every key marked NEW was introduced by the guided flow; the 7-tab wizard under "Nâng cao" keeps its existing labels (`DATA_STEPS`).
 */
export const DATA_WORDS = {
  panelTitle: "Dữ liệu",
  panelHint: "Hiển thị dữ liệu thật của công ty trong trang. Dữ liệu chỉ được đọc, không sửa.",
  connectedTitle: "Đã kết nối",
  connectedEmpty: "Chưa có dữ liệu nào được hiển thị trong trang.",
  addButton: "+ Hiển thị dữ liệu trong trang",
  advancedTitle: "Nâng cao",
  advancedHint: "Nguồn, kết nối, bộ dữ liệu, cột và dữ liệu công khai, từng bước một.",
  inspectorButton: "Hiển thị dữ liệu…",
  inspectorState: (dataset: string) => `Đang hiển thị: ${dataset}`,
  inspectorNone: "Chưa gắn dữ liệu",
  /** the four sections of the guided form */
  formTitle: "Hiển thị dữ liệu trong trang",
  where: { title: "1. Hiển thị ở đâu?", component: "Thành phần", prop: "Thuộc tính", notBindable: "Thuộc tính này chưa hiển thị được dữ liệu thật." },
  from: {
    title: "2. Lấy dữ liệu từ đâu?", source: "Nguồn dữ liệu", newSource: "Nguồn mới…", newSourceName: "Tên nguồn", newSourceType: "Loại nguồn (ví dụ postgres)",
    dataset: "Tên bộ dữ liệu", operation: "Dữ liệu cần lấy", operationHint: "Do quản trị viên đặt tên, ví dụ products.list. Không nhập câu SQL hay địa chỉ.",
    maxRows: "Số dòng tối đa (tuỳ chọn)", params: "Tham số của dữ liệu (nếu có)",
    manage: "Quản lý nguồn dữ liệu",
  },
  columns: { title: "3. Hiển thị cột nào?", asIs: "Dùng nguyên các cột nguồn trả về", note: "Cột đổi tên hoặc định dạng nằm ở mục Nâng cao." },
  who: { title: "4. Ai xem được?", public: "Khách chưa đăng nhập cũng xem được dữ liệu này", warning: "Bất kỳ ai mở được trang này đều chạy được truy vấn dữ liệu này (chỉ đọc, không cần đăng nhập). Chỉ bật khi dữ liệu được phép công khai.", publicOff: "Chưa công khai: trang đã xuất bản sẽ chưa hiển thị dữ liệu này." },
  save: "Lưu", saving: "Đang lưu…", cancel: "Hủy",
  saved: (slotCreated: boolean) => `Đã thêm dữ liệu vào trang.${slotCreated ? " Hãy liên kết nguồn thật ở Nâng cao > Nguồn dữ liệu." : ""}`,
  unlink: "Gỡ",
  unlinkTitle: "Gỡ dữ liệu khỏi thành phần?",
  readOnly: "Bạn chỉ xem được: không có quyền chỉnh sửa dữ liệu của ứng dụng.",
  /** the summary written in the version history */
  summary: (dataset: string, where: string) => `Hiển thị dữ liệu “${dataset}” trong ${where}`,
  publicBadge: "Khách xem được",
  privateBadge: "Chưa công khai",
} as const;
