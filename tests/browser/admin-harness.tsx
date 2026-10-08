// @class: harness — the REAL PortalApp + AdminApp (Platform / Admin portal) in real Chromium with a FAKE `window.fetch` for /api/v1/**. HARNESS, NOT REAL BACKEND, and NOT a backend E2E.
/**
 * TEST-ONLY harness for tests/browser/admin.spec.mjs. It proves what the SCREENS do with the answers C1's contract describes; it never proves what a server answers.
 * Query: ?portal=platform|admin|all  &me=sys|sysmember|tadmin|wsadmin|plain|sysatenant|none  &start=/platform/tenants  &fail=<path prefix: GET answers 500>  &failw=<prefix: writes answer 500>  &slow=<prefix: 2.5 s>
 *        &empty=1  &big=1  &daily=empty  &bad=audit|schema.   Every request is recorded in window.__calls ({method, path, body}); a request with no fixture answers 404 and is recorded as {unknown}.
 * `window.__cfg` can be changed by the spec at run time (slow / fail / failw). The activation token in the fixtures is a made-up string.
 */
import { createRoot } from "react-dom/client";
import { PortalApp, SessionProvider, useSession } from "@xweb/auth";
import { segments } from "@xweb/permissions";
import { usePathname } from "next/navigation";
import { AdminApp } from "../../features/admin/AdminApp";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";

const P = new URLSearchParams(location.search);
const portal = (P.get("portal") ?? "platform") as "platform" | "admin" | "all";   // "all" = the legacy combined console (components/app/AppEntry.tsx): AdminApp with no dedicated portal
const who = P.get("me") ?? "sys";
const failP = P.get("fail"); const slowP = P.get("slow"); const bigData = P.get("big") === "1"; const emptyData = P.get("empty") === "1";
const start = P.get("start") ?? (portal === "platform" ? "/platform" : "/admin");
/** portal=all: no PortalApp (that one is per portal); the session + AdminApp directly, like the legacy root app */
function AllShell() { const { me, loading } = useSession(); const path = usePathname(); if (loading || !me) return null; return <AdminApp seg={segments(path).slice(1)} portal="all"/>; }
(window as any).__calls = [] as any[];
(window as any).__cfg = { fail: failP, slow: slowP };
const iso = (d = 0) => new Date(Date.now() - d * 86400000).toISOString();

const WS = (id: string, name: string, permissions: string[]) => ({ id, name, role: "x", tenantId: "t1", permissions });
const ME: Record<string, any> = {
  sys: { id: "u-sys", username: "root", displayName: "Quản trị Hệ thống", roles: ["SYSTEM_ADMIN"], systemAdmin: true, platformScope: true, businessAccess: false, workspaces: [], tenants: [], permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] },
  tadmin: { id: "u-ta", username: "ta", displayName: "Trần Quản Trị", roles: [], workspaces: [WS("w1", "Kinh doanh", ["MEMBER_MANAGE", "DATA_SOURCE_MANAGE", "APP_VIEW"])], tenantId: "t1", tenantRole: "TENANT_ADMIN", permissions: ["TENANT_MEMBERS", "TENANT_MANAGE"], tenants: [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }] },
  wsadmin: { id: "u-wa", username: "wa", displayName: "Lê Ws", roles: [], workspaces: [WS("w1", "Kinh doanh", ["MEMBER_MANAGE", "APP_VIEW"])], tenantId: "t1", permissions: [], tenants: [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "MEMBER" }] },
  plain: { id: "u-p", username: "p", displayName: "Nhân viên", roles: [], workspaces: [WS("w1", "Kinh doanh", ["APP_VIEW"])], tenantId: "t1", permissions: [], tenants: [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "MEMBER" }] },
  sysatenant: { id: "u-ta", username: "ta", displayName: "Trần Hệ Thống", roles: ["SYSTEM_ADMIN"], systemAdmin: true, platformScope: true, businessAccess: true, workspaces: [WS("w1", "Kinh doanh", ["MEMBER_MANAGE", "DATA_SOURCE_MANAGE", "APP_VIEW"])], tenantId: "t1", tenantRole: "TENANT_ADMIN", permissions: ["TENANT_MEMBERS", "TENANT_MANAGE"], tenants: [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }] },
  sysmember: { id: "u-sys", username: "root", displayName: "Quản trị Hệ thống", roles: ["SYSTEM_ADMIN"], systemAdmin: true, platformScope: true, businessAccess: false, workspaces: [WS("w1", "Kinh doanh", ["APP_VIEW", "APP_EDIT"])], tenants: [], permissions: ["TENANT_MANAGE", "TENANT_MEMBERS"] },
  none: null as any,
};
let tenants: any[] = [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", createdAt: iso(40) }, { id: "00000000-0000-0000-0000-000000000001", slug: "default", name: "Công ty mặc định", status: "ACTIVE", createdAt: iso(90) }, { id: "t2", slug: "beta", name: "Công ty TNHH Một Thành Viên Rất Dài Tên Phát Triển Phần Mềm Và Giải Pháp Công Nghệ Thông Tin Beta", status: "SUSPENDED", createdAt: iso(10) }];
if (bigData) for (let i = 0; i < 60; i++) tenants.push({ id: "tx" + i, slug: "c-" + i, name: "Công ty " + i, status: "ACTIVE", createdAt: iso(i) });
if (emptyData) tenants = [];
const membersOf: Record<string, any[]> = {};
let members: any[] = [{ tenantId: "t1", userId: "u-ta", role: "TENANT_ADMIN", active: true, username: "ta", displayName: "Trần Quản Trị", email: "ta@acme.vn" }, { tenantId: "t1", userId: "u2", role: "MEMBER", active: true, username: "binh", displayName: "Bình", email: null }];
const users = [
  { id: "u-sys", username: "root", displayName: "Quản trị Hệ thống", email: null, enabled: true, systemAdmin: true, authSource: "LOCAL", createdAt: iso(100), workspaces: 0, projects: 0, lastLoginAt: iso(0) },
  { id: "u2", username: "binh", displayName: "Bình", email: "binh@acme.vn", enabled: true, systemAdmin: false, authSource: "LOCAL", createdAt: iso(20), workspaces: 2, projects: 3, lastLoginAt: iso(2) },
  { id: "u3", username: "chi.pending", displayName: null, email: null, enabled: true, systemAdmin: false, authSource: "LOCAL", createdAt: iso(2), workspaces: 0, projects: 0, lastLoginAt: null, pending: true },
  { id: "u4", username: "dung", displayName: "Dũng", email: "d@x.vn", enabled: false, systemAdmin: false, authSource: "OIDC", createdAt: iso(50), workspaces: 1, projects: 0, lastLoginAt: iso(30) },
];
const page = (items: any[], total = items.length) => ({ items, total, page: 0, size: 25 });
const aiProviders = [
  { id: "p1", name: "OpenAI công ty", configured: true, paid: true, endpointHost: "api.openai.com", defaultPolicy: "DISABLED_UNLESS_ENABLED", kind: "OPENAI", enabled: true, managedBySystem: false, keySet: true, baseUrl: null, defaultModel: "gpt-4o", savedModels: ["gpt-4o", "gpt-4o-mini"], models: [{ id: "openai:gpt-4o", name: "gpt-4o", enabled: true, paid: true, price: null, default: false }, { id: "openai:gpt-4o-mini", name: "gpt-4o-mini", enabled: false, paid: true, price: { id: "pr", provider: "openai", modelId: "x", inputUsdPerMTok: 0.15, outputUsdPerMTok: 0.6, effectiveFrom: iso(1), note: "", createdBy: "root", createdAt: iso(1) }, default: false }] },
  { id: "p2", name: "OpenRouter", configured: false, paid: false, endpointHost: null, defaultPolicy: "ENABLED_UNLESS_DISABLED", kind: "OPENROUTER", enabled: true, managedBySystem: true, keySet: false, baseUrl: null, defaultModel: null, savedModels: [], models: [] },
];
const limits = { defaults: { defaultModel: "auto", requestsPerUserDay: 50, tokensPerUserDay: 0, tokensPerWorkspaceMonth: 0, paidBudgetPerUserMonth: 0, paidBudgetPerWorkspaceMonth: 0 }, overrides: [{ id: "o1", scopeType: "USER", scopeId: "u2", scopeLabel: "Bình", requestsPerDay: 10, tokensPerDay: null, tokensPerMonth: null, paidBudgetMonth: 5 }], customized: false };
const totals = { calls: 12, failedCalls: 1, callsWithoutUsage: 2, promptTokens: 1000, completionTokens: 500, totalTokens: 1500, costUsd: 0.0123, costReportedCalls: 10, avgLatencyMs: 1234 };
const dailyEmpty = P.get("daily") === "empty"; const usageReport0 = { days: 30, since: iso(30), totals, byModel: [{ key: "openai:gpt-4o", label: null, totals }], byUser: [{ key: "u2", label: "Bình", totals }], byWorkspace: [], daily: Array.from({ length: 30 }, (_, i) => ({ day: new Date(Date.now() - (29 - i) * 86400000).toISOString().slice(0, 10), calls: i, failedCalls: 0, totalTokens: i * 100, costUsd: null })), limits: { dailyRequestsPerUser: 50, dailyTokensPerUser: null, monthlyTokensPerWorkspace: null }, tokenSource: "p", costSource: "p" };
const usageReport = dailyEmpty ? { ...usageReport0, daily: [] } : usageReport0;
const policies = [{ key: "ai.enabled", group: "ai", type: "BOOL", label: "Bật AI", risk: "LOW", min: 0, max: 1, unit: "", value: "true", defaultValue: "true", overridden: false, updatedBy: null, updatedAt: null }, { key: "net.domains", group: "network", type: "DOMAINS", label: "Tên miền cho phép", risk: "HIGH", min: 0, max: 0, unit: "", value: "a.com", defaultValue: "", overridden: true, updatedBy: "root", updatedAt: iso(1) }, { key: "limits.maxBuild", group: "limits", type: "INT", label: "Số build tối đa", risk: "LOW", min: 1, max: 100, unit: "build", value: "10", defaultValue: "10", overridden: false, updatedBy: null, updatedAt: null }];
const overview = { users: 4, activeUsers: 3, disabledUsers: 1, usersLoggedIn30d: 3, workspaces: 2, projects: 3, publishedProjects: 1, aiRequestsToday: 5, aiRequestsMonth: 50, versionsToday: 2, recentActivity: [{ id: "a1", createdAt: iso(0), action: "USER_LOGIN", resourceType: "USER", resourceId: "u2", actorId: "u2", actor: "binh", workspaceId: null, projectId: null, ipAddress: "1.2.3.4", requestId: "r1", newValue: P.get("bad") === "audit" ? "not json at all" : "{\"a\":1}", oldValue: null }] };
const health = { checkedAt: iso(0), items: [{ name: "PostgreSQL", status: "HEALTHY", latencyMs: 3, detail: "ok" }, { name: "Redis", status: "NOT_CONFIGURED", latencyMs: null, detail: null }], uptimeSeconds: 7300, javaVersion: "21", schemaVersion: "30", profiles: [], publishQueueDepth: 0, deadLetterDepth: 0 };

type Handler = [string, RegExp, (m: RegExpMatchArray, body: any, url: URL) => any];
const H: Handler[] = [
  ["GET", /^\/auth\/me$/, () => (ME[who] ? ME[who] : Promise.reject({ s: 401, code: "AUTHENTICATION_REQUIRED" }))],
  ["GET", /^\/auth\/csrf$/, () => ({ token: "t" })],
  ["POST", /^\/auth\/logout$/, () => ({ status: "ok" })],
  ["GET", /^\/auth\/config$/, () => ({ oidc: false, saml: false, signup: false })],
  ["GET", /^\/admin\/overview$/, () => overview],
  ["GET", /^\/admin\/users$/, (_m, _b, u) => { const q = u.searchParams.get("q"); return page(emptyData || q === "zzz" ? [] : users); }],
  ["GET", /^\/admin\/users\/([^/]+)$/, (m) => { const user = users.find((x) => x.id === m[1]) ?? users[1]; return { user, workspaces: [{ id: "w1", name: "Kinh doanh", role: "EDITOR", workspaceId: "w1", workspaceName: "Kinh doanh", owner: false }], projects: [], activeSessions: 2, recentActivity: [] }; }],
  ["POST", /^\/admin\/users\/([^/]+)\/activation-link$/, (m) => ({ userId: m[1], username: "binh", displayName: "Bình", purpose: "RESET", token: "SECRET-TOKEN-123", expiresAt: iso(-1) })],
  ["PATCH", /^\/admin\/users\/([^/]+)\/status$/, () => ({})],
  ["POST", /^\/admin\/users\/([^/]+)\/revoke-sessions$/, () => ({ revoked: 2 })],
  ["POST", /^\/admin\/users\/([^/]+)\/system-admin$/, () => ({ systemAdmin: true })],
  ["GET", /^\/admin\/tenants$/, () => tenants],
  ["POST", /^\/admin\/tenants$/, (_m, b) => { if (b.slug === "taken") return Promise.reject({ s: 409, code: "TENANT_SLUG_TAKEN", message: "slug taken" }); const t = { id: "tn" + tenants.length, slug: b.slug, name: b.name, status: "ACTIVE", createdAt: iso(0) }; tenants.push(t); return t; }],
  ["GET", /^\/admin\/tenants\/([^/]+)$/, (m) => tenants.find((t) => t.id === m[1]) ?? Promise.reject({ s: 404, code: "TENANT_NOT_FOUND" })],
  ["PATCH", /^\/admin\/tenants\/([^/]+)\/status$/, (m, b) => { const t = tenants.find((x) => x.id === m[1]); if (t) t.status = b.status; return t; }],
  ["GET", /^\/admin\/tenants\/([^/]+)\/members$/, (m) => (m[1] === "t1" ? members : (membersOf[m[1]] ??= []))],
  ["GET", /^\/admin\/tenants\/([^/]+)\/member-candidates$/, () => [{ userId: "u9", username: "cuong", displayName: "Cường", email: null }]],
  ["PUT", /^\/admin\/tenants\/([^/]+)\/members\/([^/]+)$/, (m, b) => { const x = members.find((y) => y.userId === m[2]); if (x) x.role = b.role; else members.push({ tenantId: m[1], userId: m[2], role: b.role, active: true, username: "cuong", displayName: "Cường", email: null }); return x ?? members[members.length - 1]; }],
  ["DELETE", /^\/admin\/tenants\/([^/]+)\/members\/([^/]+)$/, (m) => { members = members.filter((y) => y.userId !== m[2]); return undefined; }],
  ["POST", /^\/admin\/tenants\/([^/]+)\/users$/, (m, b) => { (membersOf[m[1]] ??= []).push({ tenantId: m[1], userId: "new-" + b.username, role: b.tenantRole ?? "MEMBER", active: true, username: b.username, displayName: b.displayName, email: b.email ?? null }); return ({ userId: "new1", username: b.username, displayName: b.displayName, purpose: "ACTIVATION", token: "ACT-TOKEN", expiresAt: iso(-1) }); }],
  ["GET", /^\/admin\/workspaces$/, () => page(emptyData ? [] : [{ id: "w1", name: "Kinh doanh", slug: "kinh-doanh", createdAt: iso(30), members: 4, projects: 2, lastActivityAt: iso(1) }])],
  ["GET", /^\/admin\/workspaces\/([^/]+)$/, () => ({ workspace: { id: "w1", name: "Kinh doanh", slug: "kinh-doanh", createdAt: iso(30), members: 4, projects: 2, lastActivityAt: iso(1) }, members: [{ userId: "u2", username: "binh", displayName: "Bình", role: "EDITOR", enabled: true }], projects: [], recentActivity: [] })],
  ["GET", /^\/admin\/applications$/, () => page(emptyData ? [] : [APP("a1", "Cổng khách hàng", "w9", "Kho Hà Nội"), APP("a2", "Báo cáo bán hàng", "w1", "Kinh doanh")])],
  ["GET", /^\/admin\/applications\/([^/]+)$/, (m) => { const a = m[1] === "a2" ? APP("a2", "Báo cáo bán hàng", "w1", "Kinh doanh") : APP(m[1], "Cổng khách hàng", "w9", "Kho Hà Nội"); return { app: a, members: [{ userId: "u2", username: "binh", displayName: "Bình", role: "EDITOR", enabled: true }], versions: [{ id: "v2", versionNumber: 2, kind: "AI", summary: "Thêm banner", createdBy: "binh", createdAt: iso(1) }, { id: "v1", versionNumber: 1, kind: "MANUAL", summary: "Bản đầu", createdBy: "binh", createdAt: iso(3) }], prompts: [], deployments: [], audit: [] }; }],
  ["POST", /^\/admin\/applications\/([^/]+)\/(archive|restore)$/, () => ({})],
  ["DELETE", /^\/workspaces\/([^/]+)\/projects\/([^/]+)$/, () => undefined],
  ["GET", /^\/admin\/ai$/, () => ({ provider: "openrouter", configured: false, mode: "x", models: [], dailyLimitPerUser: 50, promptsPerMinute: 10, requestsToday: 1, requestsMonth: 2, externalToday: 0, byModelMonth: [], recent: [], tokenAccounting: "", costAccounting: "" })],
  ["GET", /^\/admin\/ai\/providers$/, () => (emptyData ? [] : aiProviders)],
  ["POST", /^\/admin\/ai\/providers$/, (_m, b) => ({ ...aiProviders[0], id: "p9", name: b.name })],
  ["PUT", /^\/admin\/ai\/providers\/([^/]+)$/, (_m, b) => ({ ...aiProviders[0], ...b })],
  ["DELETE", /^\/admin\/ai\/providers\/([^/]+)$/, () => undefined],
  ["POST", /^\/admin\/ai\/providers\/([^/]+)\/probe$/, () => ({ id: "p1", ok: true, latencyMs: 120, detail: "Kết nối được (120 ms)" })],
  ["POST", /^\/admin\/ai\/providers\/([^/]+)\/discover$/, () => ({ id: "p1", ok: true, models: ["gpt-4o", "gpt-4.1"], detail: "Tìm thấy 2 mô hình" })],
  ["GET", /^\/admin\/ai\/limits$/, () => limits],
  ["PUT", /^\/admin\/ai\/limits\/defaults$/, () => limits],
  ["GET", /^\/admin\/ai\/limits\/users\/([^/]+)$/, () => ({ workspaceId: null, workspaces: [], allowedModels: [], effectiveDefaultModel: "auto", requestsToday: 0, tokensToday: 0, tokensThisMonth: 0, paidSpentThisMonthUsd: 0, limits: { requestsPerDay: { value: 50, source: "DEFAULT" }, tokensPerDay: { value: 0, source: "DEFAULT" }, tokensPerMonthWorkspace: { value: 0, source: "DEFAULT" }, paidBudgetUserMonth: { value: 0, source: "DEFAULT" }, paidBudgetWorkspaceMonth: { value: 0, source: "DEFAULT" } }, override: null })],
  ["GET", /^\/admin\/ai\/pricing$/, () => []],
  ["GET", /^\/admin\/ai\/usage$/, () => usageReport],
  ["GET", /^\/admin\/ai\/calls$/, () => page([])],
  ["GET", /^\/admin\/ai\/access$/, () => []],
  ["GET", /^\/admin\/ai\/budgets$/, () => []],
  ["GET", /^\/admin\/alerts$/, () => ({ open: 1, items: [{ id: "al1", kind: "AI_BUDGET_SOFT", severity: "WARNING", message: "Gần hết", createdAt: iso(0), acknowledgedAt: null, acknowledgedBy: null }] })],
  ["POST", /^\/admin\/alerts\/([^/]+)\/acknowledge$/, () => ({ ok: true })],
  ["GET", /^\/admin\/components$/, () => [{ id: "hero", name: "Hero", category: "layout", description: "Banner", latestVersion: "1.0.0", status: "ACTIVE", usedInProjects: 1, sections: 2, propsSchema: P.get("bad") === "schema" ? "not json at all" : "{\"type\":\"object\"}" }]],
  ["GET", /^\/admin\/component-packages$/, () => ({ page: page([]), counts: {} })],
  ["GET", /^\/admin\/templates$/, () => page([])],
  ["GET", /^\/admin\/audit\/actions$/, () => ["USER_LOGIN"]],
  ["GET", /^\/admin\/audit$/, () => page(overview.recentActivity)],
  ["GET", /^\/admin\/system\/health$/, () => health],
  ["GET", /^\/admin\/settings$/, () => ({ authentication: { sessionTtl: "30m", secret: null } })],
  ["GET", /^\/admin\/settings\/policies$/, () => policies],
  ["PUT", /^\/admin\/settings\/policies\/([^/]+)$/, () => ({})],
  ["GET", /^\/admin\/builds$/, () => ({ days: 30, totals: { key: "t", label: null, builds: 0, succeeded: 0, failed: 0, cpuMs: 0, durationMs: 0, artifactBytes: 0 }, byWorkspace: [], byUser: [], byProject: [], running: 0, queued: 0, rejections: [], storage: { artifactsBytes: 0, artifactsCount: 0, repositoriesBytes: 0, assetsBytes: 0 } })],
  ["GET", /^\/admin\/retention\/preview$/, () => ({ dryRun: true, abandonedUploads: 0, deletedAssetRows: 0, idempotencyKeys: 0, failedDeployments: 0, aiCalls: 0, retention: { previewsExpired: 0, artifactsDeleted: 0, artifactBytesFreed: 0, failedBuildLogsCleared: 0, repositoriesPendingDelete: 1 } })],
  ["GET", /^\/admin\/retention\/repositories$/, () => [{ projectId: "pr1", project: "App", name: "repo-1", state: "PENDING_DELETE", sizeBytes: 1000, archivedAt: iso(5), deleteAfter: iso(-1) }]],
  ["POST", /^\/admin\/retention\/run$/, () => ({ dryRun: false, abandonedUploads: 0, deletedAssetRows: 0, idempotencyKeys: 0, failedDeployments: 0, aiCalls: 0, retention: { previewsExpired: 0, artifactsDeleted: 1, artifactBytesFreed: 10, failedBuildLogsCleared: 0, repositoriesPendingDelete: 0 } })],
  ["POST", /^\/admin\/retention\/repositories\/([^/]+)\/delete$/, () => ({ state: "DELETED" })],
  ["GET", /^\/admin\/packages$/, () => [{ name: "date-fns", versionRange: "^3", pinnedVersion: null, status: "DENIED", dependencies: 3, findings: [{ severity: "HIGH" }], requestedBy: "binh", decidedBy: null, decidedAt: null, riskAccepted: false, note: "" }]],
  ["PUT", /^\/admin\/packages\/([^/]+)\/decision$/, () => ({})],
  ["GET", /^\/admin\/backups$/, () => []],
  ["GET", /^\/admin\/connectors$/, () => [{ key: "crm", name: "CRM", description: "", baseUrl: "https://crm.example.com", authHeader: "Authorization", hasSecret: true, operations: [{ method: "GET", path: "/c" }], grants: 1, status: "APPROVED" }]],
  ["GET", /^\/admin\/departments$/, () => []],
  ["GET", /^\/admin\/costs$/, () => ({ days: 30, prices: [], missingPrices: [], total: { key: "t", label: null, storageBytes: 0, buildCpuMs: 0, buildMs: 0, aiUsd: 0, aiUnknownCalls: 0, totalKnownUsd: 0, complete: true, storageUsd: null, cpuUsd: null, buildUsd: null }, byDepartment: [], byWorkspace: [], byApplication: [], egress: "chưa đo" })],
  ["GET", /^\/admin\/security\/findings$/, () => ({ counts: {}, findings: [], note: "" })],
  ["GET", /^\/admin\/scim$/, () => ({ enabled: false, users: 0, groups: [], mappings: [] })],
  ["GET", /^\/workspaces\/([^/]+)\/members$/, () => [{ userId: "u-wa", username: "wa", displayName: "Lê Ws", email: null, role: "WORKSPACE_ADMIN", joinedAt: iso(5) }, { userId: "u2", username: "binh", displayName: "Bình", email: "b@x.vn", role: "EDITOR", joinedAt: iso(3) }]],
  ["POST", /^\/workspaces\/([^/]+)\/members$/, (_m, b) => { if (b.username === "ghost") return Promise.reject({ s: 404, code: "USER_NOT_FOUND" }); return { userId: "u9", username: b.username ?? "x", displayName: null, email: null, role: b.role, joinedAt: iso(0) }; }],
  ["PATCH", /^\/workspaces\/([^/]+)\/members\/([^/]+)$/, () => ({})],
  ["DELETE", /^\/workspaces\/([^/]+)\/members\/([^/]+)$/, () => undefined],
  ["GET", /^\/workspaces\/([^/]+)\/data-sources$/, () => ({ items: [] })],
  ["GET", /^\/workspaces\/([^/]+)\/data-sources\/connectors$/, () => ({ items: [] })],
];

function APP(id: string, name: string, workspaceId: string, workspaceName: string) {
  return { id, name, workspaceId, workspaceName, ownerId: "u2", owner: "Bình", members: 1, visibility: "PRIVATE", revision: 3, latestVersion: 2, createdAt: iso(10), updatedAt: iso(1), active: true, publishStatus: null, publishedAt: null, lifecycle: "ACTIVE" };
}

const realFetch = window.fetch.bind(window);
window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href);
  if (!url.pathname.startsWith("/api/v1/")) return realFetch(input, init);
  const path = url.pathname.slice(7); const method = (init?.method ?? "GET").toUpperCase();
  let body: any; try { body = init?.body ? JSON.parse(String(init.body)) : undefined; } catch { body = undefined; }
  (window as any).__calls.push({ method, path: path + url.search, body });
  const cfg = (window as any).__cfg;
  if (cfg.slow && path.startsWith(cfg.slow)) await new Promise((r) => setTimeout(r, 2500));
  const json = (status: number, b: unknown) => new Response(b === undefined ? null : JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } });
  if (cfg.fail && path.startsWith(cfg.fail) && method === "GET") return json(500, { code: "INTERNAL", message: "java.lang.NullPointerException at com.systemwebstudio.Foo.bar(Foo.kt:42)", requestId: "req-123" });
  if (cfg.failw && path.startsWith(cfg.failw) && method !== "GET") return json(500, { code: "INTERNAL", message: "Internal Server Error: could not execute statement; SQL [n/a]", requestId: "req-999" });
  for (const [m, re, fn] of H) {
    if (m !== method) continue; const mm = path.match(re); if (!mm) continue;
    try { const out = await fn(mm, body, url); if (out === undefined) return new Response(null, { status: 204 }); return json(200, out); }
    catch (e: any) { if (e && e.s) return json(e.s, { code: e.code, message: e.message ?? "msg", requestId: "rq" }); throw e; }
  }
  (window as any).__calls.push({ unknown: method + " " + path });
  return json(404, { code: "NOT_FOUND", message: "no fixture" });
}) as typeof fetch;

history.replaceState(null, "", start);
createRoot(document.getElementById("root")!).render(portal === "all" ? <SessionProvider><AllShell/></SessionProvider> : <PortalApp portal={portal} render={(seg) => <AdminApp seg={seg} portal={portal}/>}/>);
