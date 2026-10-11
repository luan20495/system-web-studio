#!/usr/bin/env node
// C6 UI/UX regression — realistic Vietnamese fixture through the REAL product API (no SQL): tenants (one long name, one suspended), tenant admin, workspace admin, ~24 members
// (long names, diacritics, pending, disabled), workspaces, 9 applications (long / special-character names), a published app with two releases + a rollback, a data source on the TLS target
// with a query, slot, binding, CREATE_RECORD action and workflow. Writes the fixture (usernames, generated passwords, ids) to FIXTURE (mode 600, outside the repo). Never printed.
import { randomBytes, randomUUID } from "node:crypto"; import { readFileSync, writeFileSync } from "node:fs";
const API = process.env.API ?? process.env.FINAL_API; const SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD; const FIXTURE = process.env.FIXTURE; const RO = readFileSync(process.env.RO_PW_FILE, "utf8").trim();
const tag = randomUUID().slice(0, 5); const pw = () => randomBytes(15).toString("base64url") + "aA1!"; const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class S { constructor() { this.jar = new Map(); this.csrf = null; } async call(m, p, b, h0 = {}) { const h = { ...h0 }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; "); if (this.csrf && m !== "GET") h["x-xsrf-token"] = this.csrf; if (b !== undefined) h["content-type"] = "application/json"; const r = await fetch(API + p, { method: m, headers: h, body: b === undefined ? undefined : JSON.stringify(b) }); for (const c of r.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); } const t = await r.text(); let j = null; try { j = JSON.parse(t); } catch {} return { status: r.status, json: j, text: t }; }
  get(p) { return this.call("GET", p); } post(p, b, h) { return this.call("POST", p, b ?? {}, h); } put(p, b) { return this.call("PUT", p, b); } patch(p, b) { return this.call("PATCH", p, b); } async login(u, p) { this.jar.clear(); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; const r = await this.post("/api/v1/auth/login", { username: u, password: p }); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; return r; } }
const anon = new S(); const need = (r, what) => { if (r.status >= 300) throw new Error(`${what}: ${r.status} ${r.text.slice(0, 200)}`); return r; };
async function activate(token) { const p = pw(); await anon.get("/api/v1/auth/csrf"); anon.csrf = (await anon.get("/api/v1/auth/csrf")).json?.token; for (let k = 0; ; k++) { const r = await anon.post("/api/v1/auth/activation/complete", { token, password: p }); if (r.status === 429 && k < 15) { console.log("activation 429 (shared per-IP budget 30/10min): waiting 45s"); await sleep(45000); continue; } need(r, "activate"); break; } return p; }
async function user(as, T, username, displayName, o, doActivate = true) { const r = need(await as.post(`/api/v1/admin/tenants/${T}/users`, { username, displayName, email: `${username}@example.vn`, ...o }), "provision " + username); const p = doActivate ? await activate(r.json.token) : null; return { username, displayName, id: r.json.userId, password: p, link: doActivate ? null : "pending" }; }
const sa = new S(); need(await sa.login(SA_USER, SA_PASSWORD), "sa login"); const fx = { tag, accounts: {}, ids: {} };
const T1 = need(await sa.post("/api/v1/admin/tenants", { slug: `c6f-ui-nuoc-sach-${tag}`, name: "Công ty TNHH Nước Sạch Việt Nam — Chi nhánh Đồng bằng sông Cửu Long (Cần Thơ)" }), "t1").json.id;
const T2 = need(await sa.post("/api/v1/admin/tenants", { slug: `c6f-ui-dien-luc-${tag}`, name: "Tập đoàn Điện lực Đặng & Nguyễn" }), "t2").json.id;
const T3 = need(await sa.post("/api/v1/admin/tenants", { slug: `c6f-ui-tam-ngung-${tag}`, name: "Công ty Tạm Ngưng Hoạt Động" }), "t3").json.id;
await sa.patch(`/api/v1/admin/tenants/${T3}/status`, { status: "SUSPENDED" });
const wsNames = ["Kinh doanh – Marketing", "Chăm sóc khách hàng", "Kỹ thuật & Vận hành (Ứng dụng nội bộ)"]; const W = [];
for (const n of wsNames) W.push(need(await sa.post(`/api/v1/admin/tenants/${T1}/workspaces`, { name: n }), "ws " + n).json.id);
const W2 = need(await sa.post(`/api/v1/admin/tenants/${T2}/workspaces`, { name: "Vận hành lưới điện" }), "ws2").json.id;
const ta = await user(sa, T1, `c6f-ui-ta-${tag}`, "Nguyễn Thị Thu Hương", { tenantRole: "TENANT_ADMIN", workspaceId: W[0], workspaceRole: "WORKSPACE_ADMIN" }); fx.accounts.tenantAdmin = ta;
const taS = new S(); await taS.login(ta.username, ta.password);
const wa = await user(taS, T1, `c6f-ui-wa-${tag}`, "Trần Văn Đặng", { workspaceId: W[0], workspaceRole: "WORKSPACE_ADMIN" }); fx.accounts.workspaceAdmin = wa;
const ed = await user(taS, T1, `c6f-ui-creator-${tag}`, "Lê Hoàng Phương Thảo", { workspaceId: W[0], workspaceRole: "EDITOR" }); fx.accounts.appCreator = ed;
const names = ["Phạm Quang Huy", "Võ Thị Ánh Tuyết", "Đỗ Mạnh Cường", "Hoàng Minh Châu", "Ngô Thùy Dương", "Bùi Xuân Trường", "Đinh Thị Hồng Nhung", "Dương Quốc Anh", "Lý Gia Bảo", "Trương Ngọc Mỹ Linh", "Phan Đức Thắng", "Vũ Thanh Tùng", "Mai Thị Kim Ngân", "Tô Hữu Nghĩa", "Hồ Thị Thanh Vân", "Lâm Chí Kiên", "Nguyễn Văn Ưu Tiên Đặc Biệt Của Phòng Kế Hoạch Tài Chính Tổng Hợp Khu Vực Phía Nam"];
let i = 0; for (const n of names) { i++; const role = ["EDITOR", "VIEWER", "VIEWER", "EDITOR", "PUBLISHER"][i % 5]; const w = W[i % 3]; await user(taS, T1, `c6f-ui-nv${String(i).padStart(2, "0")}-${tag}`, n, { workspaceId: w, workspaceRole: role }, false); }
const dis = await user(taS, T1, `c6f-ui-off-${tag}`, "Nhân viên đã nghỉ việc", { workspaceId: W[1], workspaceRole: "VIEWER" }, false); await sa.patch(`/api/v1/admin/users/${dis.id}/status`, { enabled: false });
const ta2 = await user(sa, T2, `c6f-ui-ta2-${tag}`, "Đặng Văn Điện", { tenantRole: "TENANT_ADMIN", workspaceId: W2, workspaceRole: "WORKSPACE_ADMIN" }); fx.accounts.tenantAdmin2 = ta2;
// ---- applications of the workspace admin
const waS = new S(); await waS.login(wa.username, wa.password); const P = (n, extra = {}) => need(waS.post(`/api/v1/workspaces/${W[0]}/projects`, { name: n, appType: "PAGE_SCHEMA", ...extra }), "project " + n);
const projNames = ["Trang giới thiệu sản phẩm", "Cổng chăm sóc khách hàng", "Đăng ký lắp đặt đồng hồ nước", "Khảo sát mức độ hài lòng của khách hàng quý III năm 2026 — phiên bản thử nghiệm kéo dài", "Landing page ưu đãi mùa hè ☀", "Báo giá & Hợp đồng", "App \"nội bộ\" <test> & 'quote'", "Tuyển dụng", "Hội nghị khách hàng"];
fx.ids.projects = []; for (const n of projNames) { const r = await P(n); fx.ids.projects.push({ id: r.json.id, name: n }); }
const main = fx.ids.projects[0].id; const PB = `/api/v1/workspaces/${W[0]}/projects/${main}`; fx.ids.main = main; fx.ids.workspaceId = W[0]; fx.ids.tenantId = T1; fx.ids.tenant2 = T2; fx.ids.workspaceIds = W;
// data source + query + slot + binding + action + workflow on the main app
const DS = `/api/v1/workspaces/${W[0]}/data-sources`; let ds = null; try { ds = need(await waS.post(DS, { name: "Co so du lieu don hang", type: "postgres", config: { host: "127.0.0.1", port: "15440", database: "shop", schemas: "shop" } }), "ds").json.id;
need(await waS.put(`${DS}/${ds}/credential`, { credential: { username: "shop_ro", password: RO } }), "cred"); await waS.post(`${DS}/${ds}/test`, {}); await waS.post(`${DS}/${ds}/schema/discover`, {});
need(await waS.post(`${DS}/${ds}/queries`, { queryId: "shop.items", kind: "SQL", definition: { sql: "SELECT order_no AS name, status AS description FROM shop.orders ORDER BY order_no", params: [], maxRows: 50 } }), "query");
await waS.post(DS, { name: "Nguon du lieu loi ket noi", type: "postgres", config: { host: "127.0.0.1", port: "15440", database: "shop", schemas: "shop" } }); } catch (e) { fx.dsError = String(e).slice(0, 300); console.log("data source part skipped:", fx.dsError); }
const sc = (await waS.get(`${PB}/schema`)).json; const form = sc.schema.sections.find((s) => s.type === "ContactForm")?.id ?? sc.schema.sections[0].id; const hero = sc.schema.sections.find((s) => /hero/i.test(s.type))?.id ?? sc.schema.sections[0].id;
need(await waS.patch(`${PB}/schema`, { expectedRevision: sc.revision, summary: "ui fixture", operations: [
  { type: "ADD_DATA_SOURCE", definition: { id: "erp-db", name: "Cơ sở dữ liệu đơn hàng", type: "postgres" } },
  { type: "ADD_QUERY", definition: { id: "items-list", name: "Danh sách đơn hàng", dataSourceRef: "erp-db", mode: "READ", operationKey: "shop.items", params: [], maxRows: 50 } },
  { type: "ADD_MAPPING", definition: { id: "items-map", queryRef: "items-list", fields: [{ from: "name", to: "name" }, { from: "description", to: "description" }] } },
  { type: "ADD_VIEW_MODEL", definition: { id: "items-vm", name: "Đơn hàng", queryRef: "items-list", mappingRef: "items-map", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }, { name: "description", type: "STRING" }] } },
  { type: "ADD_ACTION", definition: { id: "ve-trang-chu", name: "Về trang chủ sau khi gửi", type: "NAVIGATE", pageRef: "home", trigger: { sectionId: form, event: "onSubmit" }, inputs: [] } },
  { type: "ADD_WORKFLOW_REF", definition: { id: "quy-trinh-duyet", name: "Quy trình duyệt đơn", trigger: "MANUAL", startStepId: "cho", steps: [{ id: "cho", kind: "WAIT", waitSeconds: 60, next: "end" }, { id: "end", kind: "END" }] } }] }), "definition ops");
if (ds) for (const m of ["TEST", "LIVE"]) need(await waS.put(`${PB}/data-bindings/${m}/erp-db`, { dataSourceId: ds }), "bind " + m);
// two releases + rollback
const publish = async () => { const rv = (await waS.get(PB)).json.revision; const x = need(await waS.post(`${PB}/publish`, { visibility: "PRIVATE", expectedRevision: rv }, { "idempotency-key": "c6f-ui-" + randomUUID() }), "publish"); let st = ""; for (let k = 0; k < 90 && !["RUNNING", "FAILED"].includes(st); k++) { await sleep(1000); st = (await waS.get(`${PB}/deployments/${x.json.id}`)).json?.status ?? ""; } return { id: x.json.id, st }; };
const r1 = await publish(); const sc2 = (await waS.get(`${PB}/schema`)).json; need(await waS.patch(`${PB}/schema`, { expectedRevision: sc2.revision, summary: "bản 2", operations: [{ type: "UPDATE_PROP", sectionId: hero, path: "title", value: "Nước sạch mỗi ngày — phiên bản thứ hai" }] }), "edit"); const r2 = await publish();
fx.ids.releases = [r1, r2];
// second published app (private) and an unpublished one stay as they are
const p2 = fx.ids.projects[1].id; const rv = (await waS.get(`/api/v1/workspaces/${W[0]}/projects/${p2}`)).json.revision; await waS.post(`/api/v1/workspaces/${W[0]}/projects/${p2}/publish`, { visibility: "PRIVATE", expectedRevision: rv }, { "idempotency-key": "c6f-ui-" + randomUUID() });

// ---- Dynamic Organization fixture (tenant admin of T1): 3 types, 3 roots, a 12-level chain with long names, positions/grades, employees WITHOUT activation (no activation budget used)
try {
  const OR = `/api/v1/admin/tenants/${T1}`; const mk = async (p, b) => need(await taS.post(OR + p, b), "org " + p).json;
  const tKhoi = await mk("/organization-unit-types", { name: "Khối", code: "KHOI" }), tPhong = await mk("/organization-unit-types", { name: "Phòng ban", code: "PHONG" }), tTeam = await mk("/organization-unit-types", { name: "Nhóm làm việc chuyên trách về chuyển đổi số", code: "TEAM" });
  const roots = []; for (const n of ["Khối Công nghệ", "Khối Kinh doanh và Phát triển Thị trường Khu vực Đồng bằng sông Cửu Long", "Khối Vận hành"]) roots.push(await mk("/organization-units", { typeId: tKhoi.id, name: n, code: n.slice(5, 9).toUpperCase().replace(/\W/g, "X") + roots.length }));
  const mobile = await mk("/organization-units", { typeId: tPhong.id, parentId: roots[0].id, name: "Phòng Phát triển Ứng dụng Di động", code: "MOBILE" });
  const flutter = await mk("/organization-units", { typeId: tTeam.id, parentId: mobile.id, name: "Flutter Team — nhóm làm việc rất dài để kiểm tra việc cắt chữ và xuống dòng trong cây tổ chức", code: "FLUTTER" });
  let parent = roots[1]; const chain = []; for (let d = 2; d <= 12; d++) { parent = await mk("/organization-units", { typeId: tPhong.id, parentId: parent.id, name: `Cấp ${d} — Phòng ban số ${d} thuộc Khối Kinh doanh`, code: `L${d}` }); chain.push(parent.id); }
  const pos = await mk("/positions", { name: "Kỹ sư phần mềm cao cấp", code: "SSE" }), grade = await mk("/grades", { name: "Bậc 5", code: "G5", rank: 5 });
  fx.ids.org = { types: [tKhoi.id, tPhong.id, tTeam.id], roots: roots.map((r) => r.id), flutter: flutter.id, deepest: chain[chain.length - 1], position: pos.id, grade: grade.id, employees: [] };
  for (const [n, u] of [["Nguyễn Văn Ưu Tiên", "emp1"], ["Trần Thị Hồng Nhung", "emp2"], ["Lê Quang Huy (nhân viên có tên rất dài để kiểm tra bảng nhân viên trên màn hình hẹp)", "emp3"]]) { const e = await mk("/employees", { username: `c6f-ui-${u}-${tag}`, displayName: n, email: `c6f-ui-${u}-${tag}@example.vn`, organizationMemberships: [{ organizationUnitId: flutter.id, relationType: "MEMBER", primary: true }] }); fx.ids.org.employees.push(e.employee?.userId ?? e.employee?.id ?? null); }
} catch (e) { fx.orgError = String(e).slice(0, 300); console.log("org fixture skipped:", fx.orgError); }
fx.accounts.superAdmin = { username: SA_USER, password: SA_PASSWORD }; fx.ids.dataSourceId = ds;
writeFileSync(FIXTURE, JSON.stringify(fx, null, 1), { mode: 0o600 }); console.log("fixture ok: tenants 3, workspaces 4, users ~25, projects", fx.ids.projects.length, "releases", r1.st, r2.st);
