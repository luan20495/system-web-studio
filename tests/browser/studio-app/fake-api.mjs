// @class: harness — test-only host for the REAL <StudioApp>; NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Playwright-side fake of /api/v1/** (page.route). Every request is recorded in state.log. It validates nothing the server validates.
// HARNESS, NOT REAL BACKEND: a Playwright-side fake of /api/v1/** for the real <StudioApp>. Every request is recorded in state.log.
const now = "2026-10-08T08:00:00Z";
const comp = (id, name, category, required, properties) => ({ id, name, category, description: `${name} (mô tả)`, latestVersion: "1.0.0", status: "ACTIVE", usedInProjects: 3,
  versions: [{ version: "1.0.0", status: "ACTIVE", propsSchema: { required, properties } }] });
export const registry = [
  comp("Navbar", "Navbar", "layout", ["brand"], { brand: { type: "string" } }),
  comp("Hero", "Hero", "marketing", ["title"], { title: { type: "string", maxLength: 120 }, subtitle: { type: "string" }, ctaLabel: { type: "string" }, theme: { type: "string", enum: ["light", "dark"] } }),
  comp("ProductGrid", "ProductGrid", "commerce", ["heading"], { heading: { type: "string" }, items: { type: "array", itemProperties: { name: { type: "string" }, description: { type: "string" } }, itemRequired: ["id", "name"] } }),
  comp("Testimonials", "Testimonials", "marketing", ["heading"], { heading: { type: "string" } }),
  comp("ContactForm", "ContactForm", "forms", ["title"], { title: { type: "string" }, submitLabel: { type: "string" } }),
  comp("Footer", "Footer", "layout", ["text"], { text: { type: "string" } }),
];
const long = (n) => "Nội dung dài ".repeat(n);
export const baseSchema = () => ({
  page: "Trang chủ", pages: [{ id: "p-about", slug: "gioi-thieu", title: "Giới thiệu", sections: [{ id: "about-hero", type: "Hero", props: { title: "Về chúng tôi", subtitle: "..." } }] }],
  site: { navigation: [{ id: "n1", label: "Giới thiệu", pageId: "p-about" }] },
  sections: [
    { id: "s-nav", type: "Navbar", props: { brand: "Demo" } },
    { id: "s-hero", type: "Hero", props: { title: "Xin chào thế giới", subtitle: long(8), ctaLabel: "Mua ngay", theme: "light" } },
    { id: "s-grid", type: "ProductGrid", props: { heading: "Sản phẩm", items: [1, 2, 3, 4, 5, 6].map((i) => ({ id: `i${i}`, name: `Sản phẩm ${i}`, description: long(10) })) } },
    { id: "s-test", type: "Testimonials", props: { heading: "Khách hàng nói gì" } },
    { id: "s-form", type: "ContactForm", props: { title: "Liên hệ", submitLabel: "Gửi" } },
    { id: "s-foot", type: "Footer", props: { text: "© Demo" } },
  ],
});
export function newState(over = {}) {
  const perms = ["APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE"];
  const s = {
    log: [], delay: 0, fail: {}, hold: {},
    me: { id: "u1", username: "luan", displayName: "Nguyễn Luân", roles: ["EDITOR"], workspaces: [{ id: "w1", name: "Workspace chính", role: "EDITOR", permissions: [...perms, "DATA_SOURCE_VIEW"] }], permissions: [] },
    projectPerms: perms, schema: baseSchema(), revision: 3, versions: [], prompts: [], assets: [], members: [], blocks: [], templates: [], projects: null, projectKind: "WEBSITE_STATIC",
    project: null, ai: { provider: "mock", configured: false, defaultModel: "mock", dailyLimitPerUser: 0, models: [], dataNotice: "" },
    config: { localLogin: true, oidc: false, oidcLoginUrl: "", publicPublish: true, codeProjects: false, serverApps: false }, metadata: "404", site: null, ...over,
  };
  s.project ??= { id: "p1", workspaceId: "w1", name: "Website máy lọc nước", description: null, ownerUserId: "u1", framework: "NEXT", siteVisibility: "PRIVATE", authMode: "NONE", domain: null, customDomain: null,
    deploymentMode: "MOCK", deploymentTarget: null, status: "DRAFT", revision: 3, createdAt: now, updatedAt: now, permissions: s.projectPerms, appType: "PAGE_SCHEMA", appKind: "WEBSITE_STATIC" };
  s.projects ??= [s.project, { ...s.project, id: "p2", name: "Dashboard bán hàng", appKind: "DASHBOARD", ownerUserId: "u2" }];
  s.versions = over.versions ?? [3, 2, 1].map((n) => ({ id: `v${n}`, versionNumber: n, kind: n === 1 ? "AI_GENERATED" : "MANUAL_EDIT", summary: `Phiên bản ${n}`, createdBy: "luan", createdAt: now, current: n === 3, restorable: true, restoredFromVersionId: null }));
  return s;
}
const json = (route, body, status = 200, headers = {}) => route.fulfill({ status, contentType: "application/json", headers: { "x-total-count": String(Array.isArray(body) ? body.length : 0), ...headers }, body: JSON.stringify(body) });
export async function installFake(page, s) {
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request(); const u = new URL(req.url()); const path = u.pathname.replace(/^\/api\/v1/, ""); const method = req.method();
    const body = req.postData() ? (() => { try { return JSON.parse(req.postData()); } catch { return null; } })() : null;
    s.log.push({ method, path: path + u.search, body });
    if (s.delay) await new Promise((r) => setTimeout(r, s.delay));
    for (const [k, v] of Object.entries(s.fail)) { if (new RegExp(k).test(`${method} ${path}`)) { if (v.once) delete s.fail[k]; return json(route, { code: v.code ?? "INTERNAL_ERROR", message: v.message ?? "Lỗi giả lập", requestId: "req-1" }, v.status ?? 500); } }
    for (const [k, v] of Object.entries(s.hold)) { if (new RegExp(k).test(`${method} ${path}`)) await v.promise; }
    if (path === "/auth/csrf") return json(route, { token: "t" });
    if (path === "/auth/me") return json(route, s.me);
    if (path === "/auth/config") return json(route, s.config);
    if (path === "/me/usage") return json(route, { aiConfigured: false, aiRequestsUsed: 0, aiRequestsLimit: 0, aiWindowResetsInSeconds: 0, tokensLast24h: 0, tokensLimitPerDay: 0, promptsToday: 2, promptsPerMinute: 10, usageLast30Days: { calls: 0 } });
    if (path === "/components") return json(route, s.registry ?? registry);
    if (path === "/component-metadata") return s.metadata === "404" ? json(route, { code: "NOT_FOUND", message: "Not Found" }, 404) : json(route, s.metadata);
    if (path === "/ai/status") return json(route, s.ai);
    if (path === "/library/categories") return json(route, { templates: { general: "Chung" }, blocks: {} });
    if (path === "/templates") return json(route, s.templates);
    if (path === "/component-packages") return json(route, s.blocks);
    let m;
    if ((m = path.match(/^\/workspaces\/w1\/projects$/))) { if (method === "POST") { const p = { ...s.project, id: "pnew", name: body.name }; s.projects.push(p); return json(route, p, 201); } return json(route, s.projects, 200, { "x-total-count": String(s.projects.length) }); }
    if ((m = path.match(/^\/projects\/(\w+)$/))) { const p = s.projects.find((x) => x.id === m[1]); return p ? json(route, p) : json(route, { code: "NOT_FOUND", message: "Không tìm thấy" }, 404); }
    if ((m = path.match(/^\/workspaces\/w1\/projects\/(\w+)(\/.*)?$/))) {
      const sub = m[2] ?? "";
      if (sub === "/schema" && method === "GET") return json(route, { schema: s.schema, revision: s.revision, version: null });
      if (sub === "/schema" && method === "PATCH" && s.patchFail) return json(route, s.patchFail.body, s.patchFail.status);
      if (sub === "/schema" && method === "PATCH") { s.revision += 1; s.lastPatch = body; if (s.patchApply) s.schema = s.patchApply(s.schema, body); return json(route, { schema: s.schema, revision: s.revision, version: s.versions[0] }); }
      if (sub.startsWith("/versions") && method === "GET") return json(route, s.versions);
      if (sub.startsWith("/prompts") && method === "GET") return json(route, s.prompts);
      if (sub === "/prompts" && method === "POST") { s.revision += 1; return json(route, { promptId: "pr" + s.log.length, outcome: "UPDATED", message: { role: "assistant", content: "Đã cập nhật theo yêu cầu." }, schemaPatch: [{ type: "UPDATE_PROP", sectionId: "s-hero", path: "title", value: "x" }], pageSchema: s.schema, revision: s.revision, version: { versionNumber: 4 }, registryReuse: 0, model: "mock", usage: null }); }
      if (sub === "/code/clone-access" && method === "POST") return json(route, { cloneUrl: "https://git.example.vn/studio/p1.git", username: "luan", token: s.cloneToken === undefined ? "tok_secret_123456" : s.cloneToken, note: "Token chỉ hiện một lần." });
      if (sub.startsWith("/code/tree")) return json(route, [{ path: "src/App.tsx", size: 100 }]);
      if (sub.startsWith("/code/file")) return json(route, { path: "src/App.tsx", text: "export default function App(){ return null }", editable: true });
      if (sub === "/code/changes" && method === "GET") return json(route, s.changes ?? []);
      if (/^\/code\/changes\/\w+\/diff$/.test(sub)) return json(route, s.diff ?? []);
      if (/^\/code\/changes\/\w+\/(approve|discard|merge)$/.test(sub)) return json(route, { ...(s.changes ?? [])[0], status: "READY" });
      if (sub === "/code/ai" && method === "GET") return json(route, []);
      if (sub === "/assets") return json(route, s.assets);
      if (sub === "/members") return json(route, s.members);
      if (sub === "/site") return json(route, s.site ?? { slug: null, url: null, online: false, visibility: null, currentDeploymentId: null, currentVersionNumber: null, provider: "mock", updatedAt: null, pointerVersion: 0, operation: null });
      if (sub === "/deployments") return json(route, []);
      if (sub === "/domains") return json(route, []);
      if (sub.startsWith("/form-submissions")) return json(route, { items: [], total: 0, page: 0, size: 20 });
      if (/^\/versions\/\w+\/restore$/.test(sub)) { s.revision += 1; return json(route, { schema: s.schema, revision: s.revision, version: s.versions[0] }); }
      if (method === "PATCH" && sub === "") { return json(route, { ...s.project, ...body, revision: s.revision + 1 }); }
    }
    if (path === "/workspaces/w1/members") return json(route, [{ userId: "u1", username: "luan", displayName: "Nguyễn Luân", email: "l@x.vn", role: "EDITOR" }]);
    if (path === "/me/activity") return json(route, s.activity ?? []);
    if (path === "/workspaces/w1/data-sources") return json(route, { items: s.sources ?? [] });
    if (path === "/workspaces/w1/data-sources/connectors") return json(route, { items: [] });
    return json(route, { code: "NOT_FOUND", message: `fake: ${method} ${path}` }, 404);
  });
}

/** a STATIC_APP (code) project with one READY change that needs a second person's review */
export function newCodeState(over = {}) {
  const s = newState(over);
  s.project = { ...s.project, appType: "STATIC_APP", appKind: "SOURCE_WEB_APP" }; s.projects = [s.project];
  s.changes = [{ id: "c1", status: "READY", reviewRequired: true, approvedBy: null, createdBy: "Người Khác", summary: "Đổi tiêu đề", files: ["src/App.tsx"], headSha: "abcdef1234567", kind: "USER", createdAt: "2026-10-08T08:00:00Z", previewUrl: null, build: null }];
  s.diff = [];
  return s;
}

/** applies typed definition operations (ADD_ / UPDATE_ / REMOVE_ DATA_SOURCE | QUERY | DATA_BINDING | MAPPING | VIEW_MODEL) to a page schema the way the server stores them (no validation) */
export function applyDefinitionOps(schema, ops) {
  const coll = { DATA_SOURCE: "dataSources", QUERY: "queries", DATA_BINDING: "dataBindings", MAPPING: "mappings", VIEW_MODEL: "viewModels" };
  const d = { ...schema };
  for (const o of ops ?? []) {
    const m = /^(ADD|UPDATE|REMOVE)_([A-Z_]+)$/.exec(o.type ?? ""); const key = m && coll[m[2]]; if (!key) continue;
    const list = [...(d[key] ?? [])];
    if (m[1] === "ADD") list.push(o.definition);
    else if (m[1] === "UPDATE") { const i = list.findIndex((x) => x.id === o.definitionId); if (i >= 0) list[i] = { ...list[i], ...o.definition }; }
    else { const i = list.findIndex((x) => x.id === o.definitionId); if (i >= 0) list.splice(i, 1); }
    d[key] = list;
  }
  return d;
}

/** a page-schema project where the typed V2 operations are available (component-metadata answers) and the person may view workspace data sources */
export function newDataState(over = {}) {
  const perms = ["APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_SOURCE_VIEW"];
  const s = newState({ projectPerms: perms, ...over });
  s.project = { ...s.project, permissions: s.projectPerms }; s.projects = [s.project];
  s.metadata = [{ type: "ProductGrid", source: "OVERLAY", bindableProps: [{ prop: "items", cardinality: "LIST", itemFields: ["name", "description"] }], events: [] }];
  s.sources = [{ id: "src1", name: "Kho đơn hàng", type: "POSTGRES", status: "ACTIVE", hasCredential: false, config: {} }];
  s.patchApply = (schema, body) => applyDefinitionOps(schema, body.operations);
  return s;
}
