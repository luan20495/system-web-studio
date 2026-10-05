import type { StudioSnapshot } from "./types";

export const mockSnapshot: StudioSnapshot = {
  project: {
    id: "web_00042",
    name: "Water Purifier Website",
    owner: "hoang.luan",
    branch: "main",
    visibility: "private",
    framework: "Next.js + React",
    authMode: "sso",
    domain: "web42.apps.company.vn",
    deploymentMode: "auto",
    deploymentTarget: "self-host"
  },
  content: {
    heroEyebrow: "Pure living • smart water",
    heroTitle: "Nước sạch mỗi ngày, sống khỏe mỗi ngày.",
    heroDescription: "Giải pháp lọc nước hiện đại cho gia đình Việt — thiết kế tinh gọn, vận hành thông minh và trải nghiệm chăm sóc an tâm.",
    products: [
      { id: "p1", name: "K-Series Pure", description: "Thiết kế tối giản • 10 lõi lọc • Gia đình 2–4 người" },
      { id: "p2", name: "Smart RO Max", description: "RO thông minh • Theo dõi chất lượng nước" },
      { id: "p3", name: "Eco Compact", description: "Nhỏ gọn • Tiết kiệm điện • Không gian hiện đại" }
    ],
    testimonials: [
      { id: "t1", author: "Nguyễn Mai", location: "Hà Nội", quote: "Thiết kế đẹp, nước uống ngon và trải nghiệm sử dụng tiện.", rating: 5 },
      { id: "t2", author: "Trần Hoàng", location: "Hải Phòng", quote: "Lắp đặt nhanh và sản phẩm phù hợp căn hộ nhỏ.", rating: 5 }
    ],
    showTestimonials: true,
    showComparison: false
  },
  messages: [
    { id: "m1", role: "user", content: "Tạo website bán máy lọc nước hiện đại, có hero, sản phẩm, đánh giá khách hàng và form liên hệ." },
    { id: "m2", role: "assistant", content: "Bản xem trước demo dùng các section đã kiểm duyệt.", meta: ["Navigation", "Hero", "ProductGrid", "Testimonials", "ContactForm"] }
  ],
  versions: [
    { id: "demo-v5", label: "Demo snapshot", createdAt: "2026-09-30T08:15:00.000Z", summary: "Current preview", sourceRevision: null },
    { id: "demo-v4", label: "Demo snapshot", createdAt: "2026-09-30T08:00:00.000Z", summary: "Update hero copy", sourceRevision: null },
    { id: "demo-v3", label: "Demo snapshot", createdAt: "2026-09-30T07:45:00.000Z", summary: "Add testimonials", sourceRevision: null },
    { id: "demo-v1", label: "Demo snapshot", createdAt: "2026-09-30T07:30:00.000Z", summary: "Initial website", sourceRevision: null }
  ]
};
