// @class: real-backend — ORG01: TENANT ADMIN builds a dynamic organization tree (Khối Công nghệ → Mobile → Flutter Team), creates an employee, assigns them to Flutter Team, reloads (persisted), then MOVES Flutter Team and checks the subtree.
// WAITING_FOR_C1: the organization / employee-directory contract is not published (no route, table or capability in integration/v2 or the fix/c1-* branches), so this flow cannot call anything: C5 invents no URL.
// When C1 publishes it: flip CAPABILITIES in features/admin/organization.ts, then replace the Blocked below with the steps (the screens already exist: /admin/organization, /admin/employees; test ids in tests/browser/org.spec.mjs).
// Steps to run then (no mock, no SQL): makeTenant + makeTenantUser(TENANT_ADMIN) → Admin portal login → Cơ cấu tổ chức: create types (Khối / Phòng / Team), the three units → Nhân viên: Thêm nhân viên (unit Flutter Team) → activate by link →
//   reload: employee still in Flutter Team, counts right → move Flutter Team under Web → reload: the subtree and the employee follow; 404 across tenants; 403 for a workspace admin; no tenantId in any body; X-XSRF-TOKEN on every write.
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-ORG01", title = "Tenant admin: dynamic organization tree + employee assigned to a unit, persisted after reload, move keeps the subtree (WAITING_FOR_C1: no contract yet)";
export async function run() {
  throw new Blocked("C1", "WAITING_FOR_C1: no published contract for organization units, unit types, positions or the employee directory (routes, fields, error codes, capability). The UI is built behind a NOT_READY adapter and verified in the harness only (tests/browser/org.spec.mjs, 89 checks, NOT a backend run).", "H-C1-17");
}
