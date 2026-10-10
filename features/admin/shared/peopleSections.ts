/**
 * M-065: the four people screens of the Admin console are DIFFERENT jobs (docs/parallel/c5/audit/S2-people-screens.md), so they stay separate routes.
 * What they share is this one table: who sees each (also the sidebar's `navWhen` in console/sections.tsx) and the one-line job the cross-links show. Pure: no React.
 */
import { anyTenantWith, type AdminScope } from "../adminModel";

export type PeopleKey = "company" | "organization" | "employees" | "people";
const companyAdmin = (s: AdminScope) => !s.platform && s.tenants.length > 0;
export const PEOPLE_SECTIONS: readonly { key: PeopleKey; label: string; job: string; when: (s: AdminScope) => boolean }[] = [
  { key: "company", label: "Công ty của tôi", job: "quản trị viên và thành viên của công ty", when: companyAdmin },
  { key: "organization", label: "Cơ cấu tổ chức", job: "đơn vị và loại đơn vị", when: (s) => anyTenantWith(s, (c) => c.org.structureView) },          // the code ORG_STRUCTURE_VIEW (D-C0-51), not "administers a company"
  { key: "employees", label: "Nhân viên", job: "danh bạ nhân viên, thêm nhân viên vào đơn vị", when: (s) => anyTenantWith(s, (c) => c.org.employeeView) },     // the code EMPLOYEE_VIEW
  { key: "people", label: "Người dùng", job: "tạo tài khoản, thêm người vào workspace", when: (s) => !s.platform && (s.tenants.length > 0 || s.workspaces.length > 0) },
];
export const peopleWhen = (key: PeopleKey) => PEOPLE_SECTIONS.find((p) => p.key === key)!.when;
/** the other people screens this person can open (none for a SYSTEM_ADMIN: those screens are not theirs) */
export const relatedPeople = (current: PeopleKey, scope: AdminScope) => PEOPLE_SECTIONS.filter((p) => p.key !== current && p.when(scope));
