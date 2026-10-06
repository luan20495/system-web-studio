/**
 * Component library of the Builder. The library is exactly the Company Component Registry: nothing is listed that the server does not have,
 * and a registry component the preview cannot render yet is shown disabled with the reason instead of being faked (NOT_RENDERED).
 */
import type { RegistryComponent } from "@xweb/types";

/** In the registry but with no renderer in lib/schema-preview.ts yet. Kept in ONE place; adding a renderer removes the entry. */
export const NOT_RENDERED: ReadonlySet<string> = new Set(["LandingTemplate", "ProductCard"]);

export const TYPE_LABELS: Readonly<Record<string, string>> = {
  Navbar: "Thanh điều hướng", Hero: "Đầu trang (Hero)", ProductGrid: "Danh sách sản phẩm", ProductCard: "Thẻ sản phẩm", TechnologySection: "Công nghệ",
  ComparisonBlock: "Bảng so sánh", Testimonials: "Đánh giá khách hàng", ContactForm: "Form liên hệ", Footer: "Chân trang", LandingTemplate: "Mẫu trang",
};
export const typeLabel = (type: string, fallback?: string): string => TYPE_LABELS[type] ?? fallback ?? type;

const DEFAULT_TEXT: Record<string, string> = { heading: "Tiêu đề mục mới", title: "Tiêu đề mới", brand: "Thương hiệu", text: "© Công ty", body: "Nội dung mới" };

export function propsSchemaOf(c: RegistryComponent | undefined) {
  return c?.versions.find((v) => v.version === c.latestVersion)?.propsSchema ?? { properties: {} as Record<string, never> };
}

/** minimum valid props of a new section: every required prop gets a neutral placeholder of its type (validated by the server like any edit) */
export function defaultProps(c: RegistryComponent): Record<string, unknown> {
  const schema = c.versions.find((v) => v.version === c.latestVersion)?.propsSchema ?? {};
  const out: Record<string, unknown> = {};
  for (const r of schema.required ?? []) {
    const d = schema.properties?.[r] ?? {};
    out[r] = d.type === "array" ? [] : d.type === "boolean" ? true : d.type === "number" ? 0 : DEFAULT_TEXT[r] ?? "Nội dung mới";
  }
  if (schema.properties?.visible) out.visible = true;
  return out;
}

export type LibraryEntry = { id: string; label: string; category: string; disabled: boolean; reason?: string };

export function libraryEntries(registry: RegistryComponent[]): LibraryEntry[] {
  return registry.filter((c) => c.status === "ACTIVE").map((c) => ({
    id: c.id, label: typeLabel(c.id, c.name), category: c.category,
    disabled: NOT_RENDERED.has(c.id), ...(NOT_RENDERED.has(c.id) ? { reason: "Chưa có bản xem trước cho component này" } : {}),
  }));
}

/**
 * Components the Data UI wants to offer. Only those that really exist in the registry can be added; the rest are listed as NOT_READY with the
 * reason, so a table/KPI/chart is never faked. (Adding one = a C2 registry entry + renderer, not frontend work.)
 */
export const DATA_COMPONENT_WISHLIST: readonly { id: string; label: string; purpose: string }[] = [
  { id: "DataTable", label: "Bảng dữ liệu", purpose: "Hiển thị nhiều dòng theo cột" },
  { id: "DataList", label: "Danh sách dữ liệu", purpose: "Hiển thị danh sách theo từng mục" },
  { id: "ProductGrid", label: "Lưới sản phẩm", purpose: "Hiển thị các mục dạng thẻ" },
  { id: "KPI", label: "Chỉ số KPI", purpose: "Một con số chính" },
  { id: "Chart", label: "Biểu đồ", purpose: "Biểu đồ từ dữ liệu" },
  { id: "Form", label: "Biểu mẫu", purpose: "Nhập và gửi dữ liệu" },
  { id: "Select", label: "Ô chọn", purpose: "Chọn một giá trị từ danh sách" },
  { id: "Detail", label: "Chi tiết một mục", purpose: "Hiển thị một bản ghi" },
];

export type DataComponentEntry = { id: string; label: string; purpose: string; available: boolean; reason?: string };
export function dataComponents(registry: RegistryComponent[]): DataComponentEntry[] {
  return DATA_COMPONENT_WISHLIST.map((w) => {
    const c = registry.find((r) => r.id === w.id && r.status === "ACTIVE");
    if (!c) return { ...w, available: false, reason: "Component này chưa có trong Company Component Registry." };
    if (NOT_RENDERED.has(w.id)) return { ...w, available: false, reason: "Component có trong registry nhưng chưa có bản xem trước." };
    return { ...w, available: true };
  });
}
