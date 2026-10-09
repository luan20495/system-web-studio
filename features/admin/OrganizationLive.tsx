"use client";
/** The real wiring of the organization and employee screens (session, live adapters). The presentational parts are OrganizationScreens.tsx / EmployeesScreens.tsx (also used by the browser harness). */
import { useMemo, useState } from "react";
import { useSession } from "../session";
import { PageHead } from "./PageHead";
import { adminScope } from "./adminModel";
import { organizationPlan } from "./organizationModel";
import { liveOrganization } from "./organizationAdapter";
import { liveProvisioning } from "./provisioningAdapter";
import { provisioningPlan } from "./provisioningModel";
import { OrganizationView } from "./OrganizationScreens";
import { EmployeesView } from "./EmployeesScreens";
import { TenantSwitch } from "./shared/TenantSwitch";
import { PeopleLinks } from "./shared/PeopleLinks";
import { useOwnWorkspacesOf } from "./shared/ownWorkspaces";

/** the tenant of the page: the session's (exactly one) or one of the caller's OWN tenants; never typed */
function useTenant() {
  const { me } = useSession();
  const scope = useMemo(() => adminScope(me), [me]);
  const plan = useMemo(() => organizationPlan(scope, liveOrganization.state), [scope]);
  const [chosen, setChosen] = useState<string>("");
  const tenant = plan.fixedTenant ?? plan.tenantChoice.find((t) => t.id === chosen) ?? plan.tenantChoice[0] ?? { id: "", name: "" };
  return { me, scope, plan, tenant, setChosen };
}

export function OrganizationPage() {
  const { plan, tenant, setChosen } = useTenant();
  return (<>
    <PageHead title="Cơ cấu tổ chức" sub="Dựng cơ cấu của công ty bằng các đơn vị và loại đơn vị do bạn tự định nghĩa."/>
    <PeopleLinks current="organization"/>
    <TenantSwitch tenants={plan.tenantChoice} value={tenant.id} onChange={setChosen} testId="org-tenant-switch"/>
    <OrganizationView api={liveOrganization} plan={plan} tenant={tenant}/>
  </>);
}

export function EmployeesPage() {
  const { scope, plan, tenant, setChosen } = useTenant();
  const provPlan = useMemo(() => provisioningPlan(scope, "admin", liveProvisioning.state), [scope]);
  const ownOf = useOwnWorkspacesOf();
  return (<>
    <PageHead title="Nhân viên" sub="Danh bạ nhân viên của công ty: tìm kiếm, lọc theo đơn vị, thêm nhân viên và xem chi tiết."/>
    <PeopleLinks current="employees"/>
    <EmployeesView api={liveOrganization} plan={plan} tenant={tenant} onTenant={setChosen} canToggleStatus={scope.platform}
      prov={{ api: liveProvisioning, plan: provPlan, workspacesOf: ownOf, tenants: plan.tenantChoice.length ? plan.tenantChoice : plan.fixedTenant ? [plan.fixedTenant] : [] }}/>
  </>);
}
