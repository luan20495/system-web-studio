import { mockSnapshot } from "./mock-data";
import type { PageContent, Project, PromptResult, PublishResult, StudioSnapshot, Version, Visibility } from "./types";

const API_MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
if (API_MODE !== "mock" && API_MODE !== "http") {
  throw new Error(`Unsupported NEXT_PUBLIC_API_MODE: ${API_MODE}`);
}
export const isDemoMode = API_MODE === "mock";
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));

function applyMockPrompt(content: PageContent, prompt: string): { content: PageContent; outcome: PromptResult["outcome"] } {
  const next = clone(content);
  const normalized = prompt.toLowerCase();
  let supported = false;

  if (normalized.includes("so sánh") || normalized.includes("compare")) {
    supported = true;
    next.showComparison = true;
  }
  if ((normalized.includes("bỏ") || normalized.includes("xóa") || normalized.includes("ẩn")) && normalized.includes("đánh giá")) {
    supported = true;
    next.showTestimonials = false;
  }
  if ((normalized.includes("hiện") || normalized.includes("thêm")) && normalized.includes("đánh giá")) {
    supported = true;
    next.showTestimonials = true;
  }
  if (normalized.includes("rút gọn") && normalized.includes("hero")) {
    supported = true;
    next.heroTitle = "Nước sạch. Sống khỏe.";
  }

  if (normalized.includes("thêm") && normalized.includes("sản phẩm")) {
    supported = true;
    next.products.push({
      id: `demo_product_${next.products.length + 1}`,
      name: "Ultra Fresh",
      description: "Thiết kế mới • Khoáng tự nhiên • Phù hợp gia đình hiện đại"
    });
  }

  const changed = JSON.stringify(next) !== JSON.stringify(content);
  return { content: next, outcome: !supported ? "unsupported" : changed ? "updated" : "no-change" };
}

export const studioApi = {
  async getStudio(): Promise<StudioSnapshot> {
    await sleep(220);
    return clone(mockSnapshot);
  },

  async sendPrompt(projectId: string, prompt: string, current: PageContent): Promise<PromptResult> {
    await sleep(520);
    const { content, outcome } = applyMockPrompt(current, prompt);
    const version: Version | undefined = outcome === "updated" ? {
      id: `demo_${Date.now()}`,
      label: "Demo snapshot",
      createdAt: new Date().toISOString(),
      summary: prompt,
      sourceRevision: null
    } : undefined;
    const responseText = outcome === "updated"
      ? "Bản xem trước demo đã cập nhật. Chưa lưu vào backend hoặc Git."
      : outcome === "no-change"
        ? "Yêu cầu đã được nhận diện nhưng nội dung hiện tại không cần thay đổi."
        : "Demo chưa hỗ trợ yêu cầu này; nội dung không thay đổi.";

    return {
      content,
      outcome,
      version,
      message: {
        id: `m_${Date.now()}`,
        role: "assistant",
        content: responseText,
        meta: ["Demo mode", outcome]
      }
    };
  },

  async updateProject(project: Project): Promise<Project> {
    await sleep(260);
    return clone(project);
  },

  async publish(projectId: string, visibility: Visibility): Promise<PublishResult> {
    await sleep(760);
    return { status: "demo", visibility };
  }
};
