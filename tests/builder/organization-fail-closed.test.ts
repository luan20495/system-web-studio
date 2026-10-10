// @class: unit
// C0 guard 2 (D-C0-44) — runtime half, updated for the wiring of D-C0-52 (C5): the organization service stays FAIL-CLOSED. Own file (C5 owns organization.test.ts). No browser, no backend, no mock "E2E".
// The invariants of the capability model: the READY set is EXACTLY tests/guards/org-contract.json `wired`; a NOT_READY operation never reaches the transport and never resolves; an error of the transport is
// passed through untouched (a 409 is never turned into success); there is NO fallback list (the old tenant-member fallback of the NOT_READY era is gone).
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CAPABILITIES, OrganizationNotReady, createOrganizationApi, type OrgCapabilityId, type OrgCapabilityState, type OrganizationApi, type OrganizationTransport } from "../../features/admin/organization";
import { orgApi } from "../../packages/api-client/src/org";

const T = "tenant-1";
// ONE call per capability. `Record<OrgCapabilityId, ...>`: a capability added to the contract WITHOUT a line here does not compile, so a new operation can never skip this guard.
const CALL: Record<OrgCapabilityId, (a: OrganizationApi) => Promise<unknown>> = {
  listOrganizationUnits: (a) => a.listOrganizationUnits(T),
  getOrganizationUnit: (a) => a.getOrganizationUnit(T, "u1"),
  createOrganizationUnit: (a) => a.createOrganizationUnit(T, { parentId: null, typeId: "t1", name: "X", code: "X" }),
  updateOrganizationUnit: (a) => a.updateOrganizationUnit(T, "u1", 1, { name: "Y" }),
  moveOrganizationUnit: (a) => a.moveOrganizationUnit(T, "u1", 1, "u2"),
  archiveOrganizationUnit: (a) => a.archiveOrganizationUnit(T, "u1", 1),
  restoreOrganizationUnit: (a) => a.restoreOrganizationUnit(T, "u1", 1),
  listOrganizationUnitTypes: (a) => a.listOrganizationUnitTypes(T),
  createOrganizationUnitType: (a) => a.createOrganizationUnitType(T, { name: "K", code: "k", icon: "building" }),
  updateOrganizationUnitType: (a) => a.updateOrganizationUnitType(T, "t1", 1, { name: "K2" }),
  setOrganizationUnitTypeActive: (a) => a.setOrganizationUnitTypeActive(T, "t1", 1, false),
  listPositions: (a) => a.listPositions(T),
  createPosition: (a) => a.createPosition(T, { name: "P", code: "p" }),
  updatePosition: (a) => a.updatePosition(T, "p1", 1, { name: "P2" }),
  setPositionActive: (a) => a.setPositionActive(T, "p1", 1, false),
  listGrades: (a) => a.listGrades(T),
  createGrade: (a) => a.createGrade(T, { name: "G", code: "g" }),
  updateGrade: (a) => a.updateGrade(T, "g1", 1, { name: "G2" }),
  setGradeActive: (a) => a.setGradeActive(T, "g1", 1, false),
  listEmployees: (a) => a.listEmployees(T, { page: 0, size: 20 }),
  getEmployee: (a) => a.getEmployee(T, "e1"),
  createEmployee: (a) => a.createEmployee(T, { username: "an", displayName: "An", tenantRole: "MEMBER" }),
  setEmployeeActive: (a) => a.setEmployeeActive(T, "e1", false),
  listMemberships: (a) => a.listMemberships(T, "e1"),
  addMembership: (a) => a.addMembership(T, "e1", { unitId: "u1" }),
  updateMembership: (a) => a.updateMembership(T, "e1", "m1", 1, { primary: true }),
  removeMembership: (a) => a.removeMembership(T, "e1", "m1", 1),
  listEmployeePositions: (a) => a.listEmployeePositions(T, "e1"),
  addEmployeePosition: (a) => a.addEmployeePosition(T, "e1", { membershipId: "m1", positionId: "p1" }),
  updateEmployeePosition: (a) => a.updateEmployeePosition(T, "e1", "ep1", 1, { primary: true }),
  removeEmployeePosition: (a) => a.removeEmployeePosition(T, "e1", "ep1", 1),
};
const IDS = Object.keys(CALL) as OrgCapabilityId[];

/** a transport that SUCCEEDS at everything and counts: if the service lets a NOT_READY operation through, the count moves and the call resolves */
function eagerTransport() {
  const calls: string[] = []; const t: Record<string, unknown> = {};
  for (const k of Object.keys(orgApi)) t[k] = (...a: unknown[]) => { calls.push(k); return Promise.resolve({ ok: true, a, items: [], unit: {}, children: [], path: [] }); };
  return { calls, transport: t as unknown as OrganizationTransport };
}
const wired = (): string[] => { try { return (JSON.parse(readFileSync(join(process.cwd(), "tests/guards/org-contract.json"), "utf8")).wired ?? []) as string[]; } catch { return ["<org-contract.json unreadable>"]; } };
/** every capability forced NOT_READY: the shape a future operation without a backend route would have */
const allNotReady = (): Record<OrgCapabilityId, OrgCapabilityState> => Object.fromEntries(IDS.map((id) => [id, { status: "NOT_READY", needs: CAPABILITIES[id].needs, reason: "Máy chủ chưa hỗ trợ.", owner: "C3" }])) as Record<OrgCapabilityId, OrgCapabilityState>;

test("every capability has a state, and the READY set is EXACTLY the wired list of tests/guards/org-contract.json (a route cannot appear by accident)", () => {
  assert.deepEqual(Object.keys(CAPABILITIES).sort(), [...IDS].sort(), "CAPABILITIES and the guard table list the same operations");
  const ready = IDS.filter((id) => CAPABILITIES[id].status === "READY").sort();
  assert.deepEqual(ready, [...wired()].sort(), "to make a capability READY change CAPABILITIES AND tests/guards/org-contract.json in the same commit");
});

test("NOT_READY: the call REJECTS with OrganizationNotReady (never resolves), the transport is never touched, nothing is returned", async () => {
  const { calls, transport } = eagerTransport(); const api = createOrganizationApi(transport, allNotReady());
  for (const id of IDS) await assert.rejects(CALL[id](api), (e: unknown) => e instanceof OrganizationNotReady && e.code === "ORGANIZATION_NOT_READY" && e.capability === id && e.reason.length > 0, id);
  assert.deepEqual(calls, [], "a NOT_READY operation must send nothing");
});

test("a failed mutation leaves no state behind: the next read is equally NOT_READY and returns no fabricated data", async () => {
  const { transport } = eagerTransport(); const api = createOrganizationApi(transport, allNotReady());
  await assert.rejects(api.createOrganizationUnit(T, { parentId: null, typeId: "t1", name: "Phantom", code: "PH" }), OrganizationNotReady);
  const read = await api.listOrganizationUnits(T).then((v) => ({ ok: true as const, v }), (e: unknown) => ({ ok: false as const, e }));
  assert.equal(read.ok, false, "a list after a failed create must not contain (or invent) the unit");
  assert.ok(!read.ok && read.e instanceof OrganizationNotReady);
});

test("no transport at all: every call is not-ready, never an empty success (no fallback list, no local copy)", async () => {
  const none = createOrganizationApi({});
  for (const id of IDS) await assert.rejects(CALL[id](none), OrganizationNotReady, id);
});

test("an unavailable store (501) reaches the caller as the same error: nothing is substituted, no other route is called", async () => {
  const off = Object.assign(new Error("not available"), { status: 501, code: "ORG_PERSISTENCE_NOT_AVAILABLE" }); const calls: string[] = [];
  const t: Record<string, unknown> = {}; for (const k of Object.keys(orgApi)) t[k] = () => { calls.push(k); return Promise.reject(off); };
  const api = createOrganizationApi(t as unknown as OrganizationTransport);
  for (const id of IDS) await assert.rejects(CALL[id](api), (e: unknown) => e === off, id);
  assert.ok(calls.length >= IDS.length, "each operation asked the server once");
  assert.ok(calls.every((c) => c in orgApi), "only api.org methods are ever called");
  assert.ok(!calls.includes("tenantMembers"), "the old tenant-member fallback does not exist any more");
});

test("a READY capability names a real typed transport method and the code(s) the SERVER lists; an error from the transport is passed through untouched (409 is never turned into success)", async () => {
  for (const id of IDS) { const c = CAPABILITIES[id]; assert.equal(c.status, "READY", `${id} is wired`); if (c.status === "READY") { assert.equal(typeof orgApi[c.client], "function", `${id}: client ${String(c.client)} must exist on api.org`); assert.ok(c.needs.length > 0, `${id}: READY without the capability that gates it`); } }
  const conflict = Object.assign(new Error("ORG_CYCLE"), { status: 409, code: "ORG_CYCLE" });
  const api = createOrganizationApi({ moveUnit: () => Promise.reject(conflict) });
  await assert.rejects(api.moveOrganizationUnit(T, "u1", 1, "u2"), (e: unknown) => e === conflict, "the service must not swallow or rewrite the server's 409");
});
