import { mockSnapshot } from "./mock-data";
import type { PageContent, Project, PromptResult, PublishResult, StudioSnapshot, Version, Visibility } from "./types";

const API_MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) }
  });

  if (!response.ok) throw new Error(`API ${response.status}: ${response.statusText}`);
  return response.json() as Promise<T>;
}

function applyMockPrompt(content: PageContent, prompt: string): PageContent {
  const next = clone(content);
  const normalized = prompt.toLowerCase();

  if (normalized.includes("so sánh") || normalized.includes("compare")) next.showComparison = true;
  if (normalized.includes("bỏ") && normalized.includes("đánh giá")) next.showTestimonials = false;
  if (normalized.includes("xóa") && normalized.includes("đánh giá")) next.showTestimonials = false;
  if (normalized.includes("rút gọn") && normalized.includes("hero")) next.heroTitle = "Nước sạch. Sống khỏe.";

  if (normalized.includes("thêm") && normalized.includes("sản phẩm")) {
    next.products.push({
      id: `p_${Date.now()}`,
      name: "Ultra Fresh",
      description: "Thiết kế mới • Khoáng tự nhiên • Phù hợp gia đình hiện đại"
    });
  }
  return next;
}

export const studioApi = {
  async getStudio(): Promise<StudioSnapshot> {
    if (API_MODE === "http") return http<StudioSnapshot>("/v1/studio");
    await sleep(220);
    return clone(mockSnapshot);
  },

  async sendPrompt(projectId: string, prompt: string, current: PageContent): Promise<PromptResult> {
    if (API_MODE === "http") {
      return http<PromptResult>(`/v1/projects/${projectId}/prompts`, {
        method: "POST",
        body: JSON.stringify({ prompt })
      });
    }

    await sleep(520);
    const content = applyMockPrompt(current, prompt);
    const version: Version = {
      id: `v_${Date.now()}`,
      label: "new",
      createdAt: "just now",
      summary: prompt,
      commitSha: Math.random().toString(16).slice(2, 9)
    };

    return {
      content,
      version,
      registryReuse: 94,
      message: {
        id: `m_${Date.now()}`,
        role: "assistant",
        content: "Đã cập nhật website và tạo version mới.",
        meta: ["schema patched", "preview updated", "git version ready"]
      }
    };
  },

  async updateProject(project: Project): Promise<Project> {
    if (API_MODE === "http") {
      return http<Project>(`/v1/projects/${project.id}`, { method: "PUT", body: JSON.stringify(project) });
    }
    await sleep(260);
    return clone(project);
  },

  async publish(projectId: string, visibility: Visibility): Promise<PublishResult> {
    if (API_MODE === "http") {
      return http<PublishResult>(`/v1/projects/${projectId}/publish`, {
        method: "POST",
        body: JSON.stringify({ visibility })
      });
    }
    await sleep(760);
    return { status: "success", visibility, url: `https://web42.apps.company.vn` };
  }
};
