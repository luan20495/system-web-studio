// @class: unit
// C0 guard 2 (D-C0-44) — runtime half: the organization adapter stays FAIL-CLOSED until C1 publishes H-C1-17. Own file (C5 owns organization.test.ts). No browser, no backend, no mock "E2E".
// Contract-independent: it names no route and no permission; it only checks the INVARIANTS of the capability model.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CAPABILITIES, OrganizationNotReady, createOrganizationApi, type OrgCapabilityId, type OrganizationApi, type OrganizationTransport } from "../../features/admin/organization";
import { employeesFromMembers } from "../../features/admin/organizationModel";

const T = "tenant-1";
// ONE call per capability. `Record<OrgCapabilityId, ...>`: a capability added to the contract WITHOUT a line here does not compile, so a new operation can never skip this guard.
const CALL: Record<OrgCapabilityId, (a: OrganizationApi) => Promise<unknown>> = {
  listOrganizationUnits: (a) => a.listOrganizationUnits(T),
  createOrganizationUnit: (a) => a.createOrganizationUnit(T, { parentId: null, typeId: null, name: "X" }),
  updateOrganizationUnit: (a) => a.updateOrganizationUnit(T, "u1", 1, { name: "Y" }),
  moveOrganizationUnit: (a) => a.moveOrganizationUnit(T, "u1", 1, "u2"),
  deleteOrganizationUnit: (a) => a.deleteOrganizationUnit(T, "u1", 1),
  listOrganizationUnitTypes: (a) => a.listOrganizationUnitTypes(T),
  createOrganizationUnitType: (a) => a.createOrganizationUnitType(T, { name: "K", code: "k", icon: "building" }),
  listPositions: (a) => a.listPositions(T),
  listEmployees: (a) => a.listEmployees(T, { page: 1, size: 20 }),
  createEmployee: (a) => a.createEmployee(T, { userId: "u1" }),
  updateEmployeeOrganization: (a) => a.updateEmployeeOrganization(T, "u1", "unit-1"),
  updateEmployeePosition: (a) => a.updateEmployeePosition(T, "u1", "pos-1"),
};
const IDS = Object.keys(CALL) as OrgCapabilityId[];

/** a transport that SUCCEEDS at everything and counts: if the adapter lets a NOT_READY operation through, the count moves and the call resolves */
function eagerTransport() {
  const calls: string[] = []; const t: Record<string, unknown> = {};
  for (const id of IDS) t[id] = (...a: unknown[]) => { calls.push(id); return Promise.resolve({ ok: true, a }); };
  t.tenantMembers = () => { calls.push("tenantMembers"); return Promise.resolve([]); };
  return { calls, transport: t as unknown as OrganizationTransport };
}
const wired = (): string[] => { try { return (JSON.parse(readFileSync(join(process.cwd(), "tests/guards/org-contract.json"), "utf8")).wired ?? []) as string[]; } catch { return ["<org-contract.json unreadable>"]; } };

test("every capability has a state, and the READY set is EXACTLY the wired list of tests/guards/org-contract.json (a route cannot appear by accident)", () => {
  assert.deepEqual(Object.keys(CAPABILITIES).sort(), [...IDS].sort(), "CAPABILITIES and the guard table list the same operations");
  const ready = IDS.filter((id) => CAPABILITIES[id].status === "READY").sort();
  assert.deepEqual(ready, [...wired()].sort(), "to make a capability READY change CAPABILITIES AND tests/guards/org-contract.json in the same commit (and wire it from C1's published contract)");
});

test("NOT_READY: the call REJECTS with OrganizationNotReady (never resolves), the transport is never touched, nothing is returned", async () => {
  for (const id of IDS.filter((x) => CAPABILITIES[x].status === "NOT_READY")) {
    const { calls, transport } = eagerTransport(); const api = createOrganizationApi(transport, employeesFromMembers);
    if (id === "listEmployees") continue;                                               // its documented, non-invented fallback is covered below
    await assert.rejects(CALL[id](api), (e: unknown) => e instanceof OrganizationNotReady && e.code === "ORGANIZATION_NOT_READY" && e.capability === id && e.reason.length > 0, id);
    assert.deepEqual(calls, [], `${id}: a NOT_READY operation must send nothing`);
  }
});

test("NOT_READY holds even when the transport offers the method: availability of a function is not a contract", async () => {
  const { calls, transport } = eagerTransport(); const api = createOrganizationApi(transport, employeesFromMembers);
  for (const id of IDS.filter((x) => x !== "listEmployees" && CAPABILITIES[x].status === "NOT_READY")) await CALL[id](api).then(() => assert.fail(`${id} resolved`), () => undefined);
  assert.equal(calls.length, 0);
});

test("a failed mutation leaves no state behind: the next read is equally NOT_READY and returns no fabricated data", async () => {
  const { transport } = eagerTransport(); const api = createOrganizationApi(transport, employeesFromMembers);
  if (CAPABILITIES.createOrganizationUnit.status === "READY") return;                    // contract wired: this guard no longer applies to that pair
  await assert.rejects(api.createOrganizationUnit(T, { parentId: null, typeId: null, name: "Phantom" }), OrganizationNotReady);
  const read = await api.listOrganizationUnits(T).then((v) => ({ ok: true as const, v }), (e: unknown) => ({ ok: false as const, e }));
  assert.equal(read.ok, false, "a list after a failed create must not contain (or invent) the unit");
  assert.ok(!read.ok && read.e instanceof OrganizationNotReady);
});

test("the ONLY non-organization call while listEmployees is NOT_READY is the existing tenant member list; no organization method is invoked", async () => {
  if (CAPABILITIES.listEmployees.status !== "NOT_READY") return;
  const { calls, transport } = eagerTransport(); const api = createOrganizationApi(transport, employeesFromMembers);
  const page = await api.listEmployees(T, { page: 1, size: 20 });
  assert.deepEqual(calls, ["tenantMembers"]); assert.equal(page.source, "members", "the screen is told these are members, not the organization directory");
  const none = createOrganizationApi({}, employeesFromMembers);
  await assert.rejects(none.listEmployees(T, { page: 1, size: 20 }), OrganizationNotReady, "no transport at all: not-ready, not an empty success");
});

test("a READY capability must name its route and the capability the SERVER lists; an error from the transport is passed through untouched (409 is never turned into success)", async () => {
  for (const id of IDS) { const c = CAPABILITIES[id]; if (c.status === "READY") { assert.ok(c.route.trim().length > 0, `${id}: READY without a route`); assert.ok(c.needs.length > 0, `${id}: READY without the capability that gates it`); } else assert.ok(!("route" in c), `${id}: NOT_READY must not name a route`); }
  const conflict = Object.assign(new Error("ORG_CYCLE"), { status: 409, code: "ORG_CYCLE" });
  const api = createOrganizationApi({ moveOrganizationUnit: () => Promise.reject(conflict) }, employeesFromMembers, { ...CAPABILITIES, moveOrganizationUnit: { status: "READY", needs: ["TENANT_MANAGE"], route: "(test fixture: not a real route)" } });
  await assert.rejects(api.moveOrganizationUnit(T, "u1", 1, "u2"), (e: unknown) => e === conflict, "the adapter must not swallow or rewrite the server's 409");
});
