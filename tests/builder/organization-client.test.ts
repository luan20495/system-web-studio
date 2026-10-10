// @class: mock — fetch is stubbed; proves what api.org SENDS (method, path, query, body) and how it surfaces the contract's refusals, NOT backend behaviour (that is E2E-ORG01 against a flag-ON stack)
/**
 * api.org (packages/api-client/src/org.ts) against a stubbed `fetch`, written from docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md and OrganizationControllers.kt. It cannot prove that the real routes
 * answer like this; it proves the client builds exactly the contract's requests (no guessed route, no tenant in a body, the directory limits never exceeded, newParentId always present).
 */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, resetCsrf } from "../../packages/api-client/src/core";
import { api } from "../../packages/api-client/src/api";
import { ORG_OFFSET_MAX, ORG_PAGE_SIZE_MAX, ORG_SEARCH_MIN, clampOrgPaging, orgMaxPage } from "../../packages/api-client/src/org";

type Seen = { url: string; method: string; headers: Record<string, string>; body?: string };
function stub(handler: (u: string, i: RequestInit) => { status: number; body?: unknown; headers?: Record<string, string> }) {
  const seen: Seen[] = [];
  (globalThis as { fetch: unknown }).fetch = async (u: string, i: RequestInit = {}) => {
    seen.push({ url: String(u), method: String(i.method ?? "GET"), headers: { ...(i.headers as Record<string, string> ?? {}) }, body: i.body as string | undefined });
    if (String(u).endsWith("/auth/csrf")) return new Response(JSON.stringify({ token: "t1" }), { status: 200 });
    const r = handler(String(u), i);
    return new Response(r.status === 204 ? null : JSON.stringify(r.body ?? {}), { status: r.status, headers: r.headers });
  };
  resetCsrf();
  return seen;
}
const TT = "11111111-1111-1111-1111-111111111111"; const B = `/api/v1/admin/tenants/${TT}`;
const ok = () => ({ status: 200, body: [] });
const sent = (seen: Seen[]) => seen.filter((s) => !s.url.endsWith("/auth/csrf")).map((s) => `${s.method} ${s.url.replace(B, "")}`);

test("reads: exact method + path + query, no body, no CSRF header on GET (types, units tree / flat / detail, catalogs, memberships, held positions)", async () => {
  const seen = stub(ok);
  await api.org.listUnitTypes(TT); await api.org.listUnitTypes(TT, false); await api.org.getUnitType(TT, "ty1");
  await api.org.unitTree(TT); await api.org.unitTree(TT, true); await api.org.unitList(TT); await api.org.unitList(TT, true); await api.org.getUnit(TT, "u1");
  await api.org.listPositions(TT); await api.org.listGrades(TT, false);
  await api.org.getEmployee(TT, "e1"); await api.org.listMemberships(TT, "e1"); await api.org.listMemberships(TT, "e1", true); await api.org.listEmployeePositions(TT, "e1");
  assert.deepEqual(sent(seen), [
    "GET /organization-unit-types?includeInactive=true", "GET /organization-unit-types?includeInactive=false", "GET /organization-unit-types/ty1",
    "GET /organization-units?format=tree&includeArchived=false", "GET /organization-units?format=tree&includeArchived=true", "GET /organization-units?format=flat&includeArchived=false", "GET /organization-units?format=flat&includeArchived=true", "GET /organization-units/u1",
    "GET /positions?includeInactive=true", "GET /grades?includeInactive=false",
    "GET /employees/e1", "GET /employees/e1/organization-memberships?includeInactive=false", "GET /employees/e1/organization-memberships?includeInactive=true", "GET /employees/e1/positions?includeInactive=false",
  ]);
  for (const s of seen.filter((x) => !x.url.endsWith("/auth/csrf"))) { assert.equal(s.body, undefined); assert.equal(s.headers["X-XSRF-TOKEN"], undefined); }
});

test("writes: POST / PATCH / DELETE with CSRF, the contract bodies, the tenant only in the PATH (a tenantId in the body is never sent)", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await api.org.createUnitType(TT, { name: "Khối", code: "division", icon: "building", rules: { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: null, maxDepth: 5 } });
  await api.org.updateUnitType(TT, "ty1", { name: "K", expectedVersion: 3 }); await api.org.disableUnitType(TT, "ty1", 4); await api.org.enableUnitType(TT, "ty1", 5);
  await api.org.createUnit(TT, { typeId: "ty1", parentId: null, name: "Tech", code: "TECH" }); await api.org.updateUnit(TT, "u1", { name: "T2", expectedVersion: 1 });
  await api.org.archiveUnit(TT, "u1", 2); await api.org.restoreUnit(TT, "u1", 3);
  await api.org.createPosition(TT, { name: "Kỹ sư", code: "ENG" }); await api.org.updatePosition(TT, "p1", { name: "KS", expectedVersion: 0 }); await api.org.disablePosition(TT, "p1", 1); await api.org.enablePosition(TT, "p1", 2);
  await api.org.createGrade(TT, { name: "Senior", code: "SR", rank: 3 }); await api.org.updateGrade(TT, "g1", { clearRank: true, expectedVersion: 0 }); await api.org.disableGrade(TT, "g1", 1); await api.org.enableGrade(TT, "g1", 2);
  await api.org.createEmployee(TT, { username: "an", displayName: "An", organizationMemberships: [{ organizationUnitId: "u1", primary: true, positions: [{ positionId: "p1" }] }] }); await api.org.disableEmployee(TT, "e1"); await api.org.enableEmployee(TT, "e1");
  await api.org.addMembership(TT, "e1", { organizationUnitId: "u2", relationType: "MANAGER" }); await api.org.updateMembership(TT, "e1", "m1", { primary: true, expectedVersion: 1 }); await api.org.removeMembership(TT, "e1", "m1", 2);
  await api.org.addEmployeePosition(TT, "e1", { membershipId: "m1", positionId: "p1", gradeId: "g1" }); await api.org.updateEmployeePosition(TT, "e1", "ep1", { clearGrade: true, expectedVersion: 1 }); await api.org.removeEmployeePosition(TT, "e1", "ep1", 2);
  const writes = seen.filter((s) => !s.url.endsWith("/auth/csrf"));
  assert.deepEqual(sent(seen), [
    "POST /organization-unit-types", "PATCH /organization-unit-types/ty1", "POST /organization-unit-types/ty1/disable", "POST /organization-unit-types/ty1/enable",
    "POST /organization-units", "PATCH /organization-units/u1", "POST /organization-units/u1/archive", "POST /organization-units/u1/restore",
    "POST /positions", "PATCH /positions/p1", "POST /positions/p1/disable", "POST /positions/p1/enable", "POST /grades", "PATCH /grades/g1", "POST /grades/g1/disable", "POST /grades/g1/enable",
    "POST /employees", "POST /employees/e1/disable", "POST /employees/e1/enable", "POST /employees/e1/organization-memberships", "PATCH /employees/e1/organization-memberships/m1", "DELETE /employees/e1/organization-memberships/m1?expectedVersion=2",
    "POST /employees/e1/positions", "PATCH /employees/e1/positions/ep1", "DELETE /employees/e1/positions/ep1?expectedVersion=2",
  ]);
  for (const w of writes) assert.equal(w.headers["X-XSRF-TOKEN"], "t1", `${w.method} ${w.url}`);
  for (const w of writes) if (w.body) assert.ok(!/tenantId|tenant_id/.test(w.body), "a body never names the tenant");
  assert.deepEqual(JSON.parse(writes[6].body!), { expectedVersion: 2 }); assert.deepEqual(JSON.parse(writes[7].body!), { expectedVersion: 3 });
  assert.equal(writes[17].body, undefined, "disable / enable of an employee carry no body"); assert.equal(writes[21].body, undefined, "DELETE carries the version in the query");
  assert.deepEqual(JSON.parse(writes[16].body!).organizationMemberships[0], { organizationUnitId: "u1", primary: true, positions: [{ positionId: "p1" }] });
});

test("move: `newParentId` is ALWAYS in the body (a UUID, or an explicit null = root); an absent key would be a 400 on the server, never a silent move to the root", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await api.org.moveUnit(TT, "u1", { newParentId: null, expectedVersion: 1 });
  await api.org.moveUnit(TT, "u1", { newParentId: "p2", expectedVersion: 2, sortOrder: 4 });
  // @ts-expect-error the type requires the key; even a caller that leaves it out (undefined) must send an explicit null, not omit it
  await api.org.moveUnit(TT, "u1", { expectedVersion: 3 });
  const w = seen.filter((s) => !s.url.endsWith("/auth/csrf"));
  assert.deepEqual(JSON.parse(w[0].body!), { newParentId: null, expectedVersion: 1 }); assert.ok(w[0].body!.includes('"newParentId":null'));
  assert.deepEqual(JSON.parse(w[1].body!), { newParentId: "p2", expectedVersion: 2, sortOrder: 4 });
  assert.ok(w[2].body!.includes('"newParentId":null'), "an omitted parent is sent as null, i.e. the caller's intent is explicit in the request");
  assert.deepEqual(w.map((x) => x.url.replace(B, "")), ["/organization-units/u1/move", "/organization-units/u1/move", "/organization-units/u1/move"]);
});

test("directory: size 1..100 and page * size <= 10000 are enforced BEFORE the request, a search shorter than 2 characters is not sent, filters map to the contract's names", async () => {
  const seen = stub(() => ({ status: 200, body: { items: [], total: 0, page: 0, size: 25 } }));
  await api.org.listEmployees(TT); await api.org.listEmployees(TT, { page: 2, size: 20, q: "  an  " });
  await api.org.listEmployees(TT, { size: 500 }); await api.org.listEmployees(TT, { page: 1_000_000, size: 100 }); await api.org.listEmployees(TT, { page: -4, size: -1 }); await api.org.listEmployees(TT, { page: 3, size: Number.NaN });
  await api.org.listEmployees(TT, { q: "a" }); await api.org.listEmployees(TT, { organizationUnitId: "u1", includeDescendants: false, positionId: "p", gradeId: "g", active: true, sort: "username", dir: "desc" }); await api.org.listEmployees(TT, { organizationUnitId: "u1" });
  const urls = sent(seen);
  assert.deepEqual(urls, [
    "GET /employees?page=0&size=25", "GET /employees?q=an&page=2&size=20", "GET /employees?page=0&size=100", "GET /employees?page=100&size=100", "GET /employees?page=0&size=1", "GET /employees?page=3&size=25", "GET /employees?page=0&size=25",
    "GET /employees?organizationUnitId=u1&includeDescendants=false&positionId=p&gradeId=g&active=true&page=0&size=25&sort=username&dir=desc", "GET /employees?organizationUnitId=u1&includeDescendants=true&page=0&size=25",
  ]);
  for (const u of urls) { const q = new URL(`http://x${u.slice(4)}`).searchParams; const size = Number(q.get("size")); const page = Number(q.get("page")); assert.ok(size >= 1 && size <= ORG_PAGE_SIZE_MAX, u); assert.ok(page >= 0 && page * size <= ORG_OFFSET_MAX, u); assert.ok(!q.has("q") || q.get("q")!.length >= ORG_SEARCH_MIN, u); }
  assert.equal(new URL(`http://x${urls[6].slice(4)}`).searchParams.has("includeDescendants"), false, "includeDescendants means nothing without a unit");
});

test("clampOrgPaging / orgMaxPage: the limits (100, 10000, offset = page * size) in one place; `clamped` says when the request was pulled back", () => {
  assert.equal(ORG_PAGE_SIZE_MAX, 100); assert.equal(ORG_OFFSET_MAX, 10_000); assert.equal(ORG_SEARCH_MIN, 2);
  assert.deepEqual(clampOrgPaging(0, 25), { page: 0, size: 25, clamped: false }); assert.deepEqual(clampOrgPaging(undefined, undefined), { page: 0, size: 25, clamped: false });
  assert.deepEqual(clampOrgPaging(500, 20), { page: 500, size: 20, clamped: false }, "offset 10000 is the last accepted one"); assert.deepEqual(clampOrgPaging(501, 20), { page: 500, size: 20, clamped: true });
  assert.deepEqual(clampOrgPaging(0, 101), { page: 0, size: 100, clamped: true }); assert.deepEqual(clampOrgPaging(101, 100), { page: 100, size: 100, clamped: true }); assert.deepEqual(clampOrgPaging(-1, 0), { page: 0, size: 1, clamped: true });
  assert.deepEqual(clampOrgPaging(3.9, 20.7), { page: 3, size: 20, clamped: false }, "fractions are truncated, not clamped"); assert.equal(clampOrgPaging(1e12, 1).page, 10_000);
  assert.equal(orgMaxPage(20), 500); assert.equal(orgMaxPage(100), 100); assert.equal(orgMaxPage(1), 10_000); assert.equal(orgMaxPage(1000), 100, "size is capped first"); assert.equal(orgMaxPage(0), 10_000);
  for (let size = -3; size < 300; size += 7) for (const page of [0, 1, 99, 100, 101, 500, 501, 10_000, 99_999]) { const c = clampOrgPaging(page, size); assert.ok(c.size >= 1 && c.size <= 100 && c.page >= 0 && c.page * c.size <= 10_000, `${page}/${size} -> ${c.page}/${c.size}`); }
});

test("ids are path segments, escaped: an id with a slash or a query character cannot change the route", async () => {
  const seen = stub(() => ({ status: 200, body: {} }));
  await api.org.getUnit(TT, "../x?y=1"); await api.org.getEmployee("t/../1", "a b");
  assert.deepEqual(seen.filter((s) => !s.url.endsWith("/auth/csrf")).map((s) => s.url), [`${B}/organization-units/..%2Fx%3Fy%3D1`, "/api/v1/admin/tenants/t%2F..%2F1/employees/a%20b"]);
});

test("503 ORG_STRUCTURE_BUSY: the ApiError carries status, code, details and the Retry-After header seconds; nothing is retried by the client", async () => {
  const seen = stub(() => ({ status: 503, body: { code: "ORG_STRUCTURE_BUSY", message: "busy", requestId: "r1", details: { retryable: true, retryAfterSeconds: 5 } }, headers: { "Retry-After": "5" } }));
  const e = await api.org.createUnit(TT, { typeId: "t", parentId: null, name: "x", code: "X" }).then(() => null, (x: unknown) => x as ApiError);
  assert.ok(e instanceof ApiError); assert.equal(e.status, 503); assert.equal(e.code, "ORG_STRUCTURE_BUSY"); assert.equal(e.retryAfterSeconds, 5); assert.deepEqual(e.details, { retryable: true, retryAfterSeconds: 5 });
  assert.equal(seen.filter((s) => !s.url.endsWith("/auth/csrf")).length, 1, "one request, no automatic retry");
});

test("501 ORG_PERSISTENCE_NOT_AVAILABLE, 409 VERSION_CONFLICT (currentVersion) and 400 OFFSET_TOO_LARGE reach the caller as the server's code, never as an empty success", async () => {
  stub(() => ({ status: 501, body: { code: "ORG_PERSISTENCE_NOT_AVAILABLE", message: "off" } }));
  for (const f of [() => api.org.unitTree(TT), () => api.org.listEmployees(TT), () => api.org.createEmployee(TT, { username: "a", displayName: "A" })]) { const e = await f().then(() => null, (x: unknown) => x as ApiError); assert.ok(e instanceof ApiError); assert.equal(e.status, 501); assert.equal(e.code, "ORG_PERSISTENCE_NOT_AVAILABLE"); }
  stub(() => ({ status: 409, body: { code: "VERSION_CONFLICT", message: "stale", details: { currentVersion: 9 } } }));
  const c = await api.org.archiveUnit(TT, "u", 1).then(() => null, (x: unknown) => x as ApiError); assert.equal(c!.status, 409); assert.equal(c!.code, "VERSION_CONFLICT"); assert.deepEqual(c!.details, { currentVersion: 9 });
  stub(() => ({ status: 400, body: { code: "OFFSET_TOO_LARGE", message: "deep" } }));
  const o = await api.org.listEmployees(TT, { page: 1, size: 1 }).then(() => null, (x: unknown) => x as ApiError); assert.equal(o!.code, "OFFSET_TOO_LARGE");
});
