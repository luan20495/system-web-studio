// @class: harness — the real <AiAdmin> providers tab with an in-page FAKE of `fetch` for /api/v1/admin/ai/**; NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY harness for Platform → AI → Nhà cung cấp (list + "Thêm nhà cung cấp" dialog). `window.fetch` is replaced by a recorder that answers the admin AI routes per ?s=<scenario>
 * and keeps every request in window.__ai (method, path, body) so the spec can prove what the screen SENDS (the backend contract is unchanged: same fields, nothing new).
 * It proves the screen's behaviour, never what the real server answers.
 */
import { createRoot } from "react-dom/client";
import type { AiProviderInfo } from "@xweb/types";
import { AiAdmin } from "../../features/admin/AiSetup";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/ui.css";   // the real layouts load it last (apps/*/app/layout.tsx); a harness without it is not the product

declare global { interface Window { __ai: { method: string; path: string; body: unknown }[] } }
window.__ai = [];
const S = new URLSearchParams(location.search).get("s") ?? "ok";
const model = (id: string, enabled: boolean, paid = false) => ({ id, name: id, enabled, paid, price: null, default: false });
const mk = (o: Partial<AiProviderInfo> & { id: string; name: string; kind: AiProviderInfo["kind"] }): AiProviderInfo => ({ configured: true, paid: false, endpointHost: null, defaultPolicy: "DISABLED_UNLESS_ENABLED", models: [], enabled: true, managedBySystem: false, keySet: true, baseUrl: null, defaultModel: null, savedModels: [], ...o });
let providers: AiProviderInfo[] = S === "empty" ? [] : [
  mk({ id: "p1", name: "OpenRouter", kind: "OPENROUTER", models: [model("meta/llama-free", true), model("qwen/free", true), model("mistral/free", false)] }),
  mk({ id: "p2", name: "Claude công ty", kind: "ANTHROPIC", paid: true, models: [model("claude-x", true, true), model("claude-y", false, true)] }),
  mk({ id: "p3", name: "Gemini", kind: "GEMINI", paid: true, enabled: false, models: [] , configured: false}),
  mk({ id: "p4", name: "OpenAI", kind: "OPENAI", paid: true, models: [model("gpt-x", false, true)] }),
  mk({ id: "p5", name: "Ollama nội bộ", kind: "LOCAL", keySet: false, baseUrl: "https://ai.example.com/v1", models: [model("llama3", true)] }),
  mk({ id: "p6", name: "Cổng tương thích", kind: "OPENAI_COMPATIBLE", paid: true, models: [] }),
];
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const real = window.fetch.bind(window);
window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href); const path = url.pathname.replace(/^\/api\/v1/, ""); const method = (init?.method ?? "GET").toUpperCase();
  if (!url.pathname.startsWith("/api/v1/")) return real(input, init);
  if (path === "/auth/csrf") return json({ token: "t" });
  const body = init?.body ? JSON.parse(String(init.body)) : undefined;
  if (path.startsWith("/admin/ai")) window.__ai.push({ method, path, body });
  if (path === "/admin/ai/providers" && method === "GET") return S === "error" ? json({ code: "INTERNAL_ERROR", message: "boom" }, 500) : json(providers);
  if (path === "/admin/ai/providers" && method === "POST") {
    if (S === "dup") return json({ code: "CONFLICT", message: "name already used" }, 409);
    const p = mk({ id: "pn", name: body.name, kind: body.kind, enabled: body.enabled, paid: body.paid, keySet: !!body.apiKey, baseUrl: body.baseUrl ?? null }); providers = [...providers, p]; return json(p, 201);
  }
  const m = /^\/admin\/ai\/providers\/([^/]+)(\/probe)?$/.exec(path);
  if (m?.[2]) { await new Promise((r) => setTimeout(r, 200)); return S === "probefail" || m[1] === "p3" ? json({ id: m[1], ok: false, latencyMs: 0, detail: "Khóa kết nối bị từ chối" }) : json({ id: m[1], ok: true, latencyMs: 120, detail: "Kết nối thành công (120 ms)" }); }
  if (m && method === "PUT") { providers = providers.map((p) => (p.id === m[1] ? { ...p, enabled: body.enabled ?? p.enabled } : p)); return json(providers.find((p) => p.id === m[1])); }
  return json({}, 404);
};
createRoot(document.getElementById("root")!).render(<div className="shell admin" style={{ display: "block" }}><main className="page" style={{ maxWidth: 1100, margin: "0 auto", padding: 16 }}><AiAdmin tab="providers" usage={null} pricing={null}/></main></div>);
