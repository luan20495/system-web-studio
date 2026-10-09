/**
 * Pure helpers of the project workspace (no React, no network): what a failed request means for the person, and the shape of the AI conversation.
 * Extracted from `ProjectWorkspace` (M-048) without changing behaviour.
 */
import { ApiError } from "@/lib/http-api";
import type { PromptHistoryItem } from "@/lib/http-types";
import { explainError } from "../builder/core/errors";
import { errText, tok, usd } from "../../ui";

export type Msg = { id: string; role: "user" | "assistant"; content: string; meta?: string[]; detail?: string };

/** Provider-reported usage of one prompt. The simulator makes no model call, so it has no tokens to show. */
export function usageChip(calls: number, tokens: number | null, cost: number | null): string {
  if (!calls) return "không tính token";
  return [tokens == null ? "token: nhà cung cấp không báo" : `${tok(tokens)} token`, ...(cost == null ? [] : [usd(cost)]), ...(calls > 1 ? [`${calls} lượt gọi model`] : [])].join(" · ");
}

/** the CSP nonce of the page, passed to the preview document so its own script is allowed */
export const nonceOfPage = () => (typeof document === "undefined" ? undefined : (document.querySelector("script[nonce]") as HTMLScriptElement | null)?.nonce || undefined);

/** prompt history (newest first from the API) -> chat messages, oldest first */
export const toMessages = (items: PromptHistoryItem[]): Msg[] => [...items].reverse().flatMap((p) => [
  { id: `${p.id}-u`, role: "user" as const, content: p.text },
  { id: `${p.id}-a`, role: "assistant" as const, content: p.assistantMessage,
    meta: [p.outcome, ...(p.model ? [p.model === "mock" ? "Chế độ thử nghiệm" : p.model] : []), usageChip(p.aiCalls ?? 0, p.totalTokens ?? null, p.costUsd ?? null)] }
]);

export type RunFailure = {
  /** the person cancelled before the server answered: nothing failed */
  aborted: boolean;
  /** nothing definite was decided by the server (no answer, 5xx, 429): the same edit may be retried; conflict / validation / permission failures are final */
  retryable: boolean;
  /** the document changed elsewhere: reload it */
  reload: boolean;
  /** what to tell the person; null = nothing (a 401 is handled by the session) */
  notice: string | null;
};

/** error -> notice mapping of every save / AI request of the workspace */
export function describeRunFailure(e: unknown, fallback: string): RunFailure {
  if (e instanceof ApiError && e.code === "ABORTED") return { aborted: true, retryable: false, reload: false, notice: "Đã huỷ yêu cầu AI." };
  const st = e instanceof ApiError ? e.status : 0;
  const retryable = st === 0 || st >= 500 || st === 429;
  if (e instanceof ApiError && e.code === "REVISION_CONFLICT") return { aborted: false, retryable, reload: true, notice: "Project vừa được thay đổi ở nơi khác. Đã tải lại bản mới nhất, hãy thử lại." };
  if (e instanceof ApiError && e.code === "AI_TOKEN_LIMIT") {
    const d = e.details as { scope?: string; used?: number; limit?: number } | undefined;
    return { aborted: false, retryable, reload: false, notice: `${d?.scope === "workspace" ? "Workspace đã dùng hết ngân sách token AI của tháng" : "Bạn đã dùng hết hạn mức token AI trong 24 giờ"}${d?.limit ? ` (${tok(d.used ?? 0)} / ${tok(d.limit)} token)` : ""}. Có thể chọn “Mô phỏng” để tiếp tục chỉnh sửa.` };
  }
  if (e instanceof ApiError && (e.status === 409 || e.status === 422 || e.status === 429 || e.code === "TENANT_SUSPENDED")) { const m = explainError(e); return { aborted: false, retryable, reload: false, notice: `${m.title}. ${m.detail}` }; }
  if (!(e instanceof ApiError && e.status === 401)) return { aborted: false, retryable, reload: false, notice: errText(e, fallback) };
  return { aborted: false, retryable, reload: false, notice: null };
}
