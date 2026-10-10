"use client";
/** The real wiring of the organization and employee screens (session, live adapters). The presentational parts are OrganizationScreens.tsx / EmployeesScreens.tsx (also used by the browser harness). */
import { useMemo, useState } from "react";
import { useSession } from "../session";
import { PageHead } from "./PageHead";
import { adminScope } from "./adminModel";
import { employeeProvisioningPlan, organizationPlan } from "./organizationModel";
import { liveOrganization } from "./organizationAdapter";
import { liveProvisioning } from "./provisioningAdapter";
import { provisioningPlan } from "./provisioningModel";
import { OrganizationView } from "./OrganizationScreens";
import { EmployeesView } from "./EmployeesScreens";
import { TenantSwitch } from "./shared/TenantSwitch";
import { PeopleLinks } from "./shared/PeopleLinks";
import { useOwnWorkspacesOf } from "./shared/ownWorkspaces";

/** the tenant of the page: the session's (exactly one) or one of the caller's OWN tenants; never typed. Every permission is judged with the codes of THAT company only (M-052). */
function useTenant() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const list = useMemo(() => organizationPlan(scope, liveOrganization.state), [scope]);         // which companies have an organization screen for this person
  const [chosen, setChosen] = useState<string>("");
  const tenant = list.fixedTenant ?? list.tenantChoice.find((t) => t.id === chosen) ?? list.tenantChoice[0] ?? { id: "", name: "" };
  const plan = useMemo(() => organizationPlan(scope, liveOrganization.state, tenant.id), [scope, tenant.id]);
  return { me, scope, plan, tenant, setChosen };
}

export function OrganizationPage() {
  const { plan, tenant, setChosen } = useTenant();
  return (<>
    <PageHead title="Cơ cấu tổ chức" sub="Dựng cơ cấu của công ty bằng các đơn vị và loại đơn vị do bạn tự định nghĩa. Đơn vị được lưu trữ và khôi phục, không xóa."/>
    <PeopleLinks current="organization"/>
    <TenantSwitch tenants={plan.tenantChoice} value={tenant.id} onChange={setChosen} testId="org-tenant-switch"/>
    <OrganizationView api={liveOrganization} plan={plan} tenant={tenant}/>
  </>);
}

export function EmployeesPage() {
  const { scope, plan, tenant, setChosen } = useTenant();
  // the account is created in the company the page shows (never another): its tenant is fixed to the selected one, and the codes judged are that company's
  const provPlan = useMemo(() => { const base = employeeProvisioningPlan(scope, provisioningPlan(scope, "admin", liveProvisioning.state), tenant.id); return { ...base, fixedTenant: tenant.id ? tenant : null, tenantChoice: false }; }, [scope, tenant]);
  const ownOf = useOwnWorkspacesOf();
  return (<>
    <PageHead title="Nhân viên" sub="Danh bạ nhân viên của công ty: tìm kiếm, lọc theo đơn vị, vị trí và cấp bậc, thêm nhân viên và quản lý đơn vị, vị trí của từng người."/>
    <PeopleLinks current="employees"/>
    <EmployeesView api={liveOrganization} plan={plan} tenant={tenant} onTenant={setChosen}
      prov={{ api: liveProvisioning, plan: provPlan, workspacesOf: ownOf, tenants: plan.tenantChoice.length ? plan.tenantChoice : plan.fixedTenant ? [plan.fixedTenant] : [] }}/>
  </>);
}
