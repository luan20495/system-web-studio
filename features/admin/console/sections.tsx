"use client";
/**
 * THE section registry of the Platform and Admin consoles: one entry per page. The sidebar, the ownership check, the access rule, the document title and the routing are all derived from this table
 * (sectionPolicy.ts). To add a page: write the component, add ONE entry here (in the order it should appear in the sidebar).
 */
import type { ReactNode } from "react";
import { AppWindow, Boxes, Building2, CircleDollarSign, Database, Fingerprint, FolderTree, HardDrive, Hammer, HeartPulse, KeyRound, Layers, LayoutDashboard, LayoutTemplate, Network, Package, Plug, ScrollText, Scale, Settings, Share2, ShieldCheck, Sparkles, TriangleAlert, UserRound, Users } from "@xweb/ui";
import { AiAdmin } from "../AiSetup";
import { EmployeesPage, OrganizationPage } from "../OrganizationLive";
import { PeoplePage } from "../ProvisioningLive";
import { CompanyPage, DataSourcesAdminPage, MyWorkspacesPage, TenantDetailPage, TenantsPage } from "../TenantScreens";
import { AiGovernancePage } from "../pages/AiGovernancePage";
import { AiPage, PricingCard } from "../pages/AiUsagePages";
import { AppDetail, AppsPage } from "../pages/ApplicationsPages";
import { AuditPage } from "../pages/AuditPage";
import { Overview } from "../pages/Overview";
import { AlertsPage, CostsPage, SecurityPage } from "../pages/OperationsPages";
import { ComponentsPage, TemplatesAdmin } from "../pages/RegistryPages";
import { BuildsPage, HealthPage, PackagesPage, SettingsPage } from "../pages/SystemPages";
import { BackupsPage, ConnectorsPage, DepartmentsPage, IdentityPage } from "../pages/IdentityPages";
import { UserDetail, UsersPage, WorkspaceDetail } from "../pages/UsersPages";
import type { SectionMeta } from "./sectionPolicy";
import { peopleWhen } from "../shared/peopleSections";

export type Section = SectionMeta & {
  icon: ReactNode;
  /** the screen for the path segments below the section key (`["u1"]` for /users/u1); absent for `coming` entries */
  render?: (seg: string[]) => ReactNode;
  /** `surface: "coming"` */
  coming?: { why: string; needs: string };
};

const both = ["platform", "admin"] as const, platform = ["platform"] as const, admin = ["admin"] as const, none = [] as const;
const ic = (I: typeof Users) => <I size={18}/>;

/** Order = sidebar order in every console (a console lists the subset it owns / the person may use). */
export const SECTIONS: readonly Section[] = [
  { key: "", label: "Tổng quan", icon: ic(LayoutDashboard), portals: both, access: "open", surface: "standard", listed: "main", render: () => <Overview/> },
  { key: "tenants", label: "Công ty (tenant)", icon: ic(Building2), portals: platform, access: "system", surface: "platform-only", listed: "platform-main", render: (seg) => (seg[1] ? <TenantDetailPage id={seg[1]}/> : <TenantsPage/>) },
  { key: "users", label: "Người dùng & Workspace", icon: ic(Users), portals: both, access: "system", surface: "standard", listed: "main", render: (seg) => (seg[1] ? <UserDetail id={seg[1]}/> : <UsersPage/>) },
  { key: "workspaces", label: "Người dùng & Workspace", icon: ic(Users), portals: both, access: "system", surface: "standard", listed: "hidden", render: (seg) => (seg[1] ? <WorkspaceDetail id={seg[1]}/> : <UsersPage tab="workspaces"/>) },
  { key: "applications", label: "Ứng dụng", icon: ic(AppWindow), portals: admin, access: "system", surface: "standard", listed: "main", render: (seg) => (seg[1] ? <AppDetail id={seg[1]}/> : <AppsPage/>) },
  { key: "ai", label: "AI", icon: ic(Sparkles), portals: platform, access: "system", surface: "standard", listed: "main", render: (seg) => <AiAdmin tab={seg[1]} usage={<AiPage/>} pricing={<PricingCard/>}/> },
  { key: "ai-governance", label: "Quyền & ngân sách AI", icon: ic(Scale), portals: admin, access: "system", surface: "standard", listed: "main", render: () => <AiGovernancePage/> },
  { key: "alerts", label: "Cảnh báo", icon: ic(TriangleAlert), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <AlertsPage/> },
  { key: "security", label: "Bảo mật", icon: ic(ShieldCheck), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <SecurityPage/> },
  { key: "costs", label: "Chi phí", icon: ic(CircleDollarSign), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <CostsPage/> },
  { key: "departments", label: "Phòng ban", icon: ic(Network), portals: admin, access: "system", surface: "standard", listed: "main", render: () => <DepartmentsPage/> },
  { key: "identity", label: "Định danh (SSO/SCIM)", icon: ic(Fingerprint), portals: admin, access: "system", surface: "standard", listed: "main", render: () => <IdentityPage/> },
  { key: "connectors", label: "Connector", icon: ic(Plug), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <ConnectorsPage/> },
  { key: "backups", label: "Sao lưu", icon: ic(HardDrive), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <BackupsPage/> },
  { key: "components", label: "Components", icon: ic(Boxes), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <ComponentsPage/> },
  { key: "templates", label: "Templates", icon: ic(LayoutTemplate), portals: both, access: "system", surface: "standard", listed: "main", render: () => <TemplatesAdmin/> },
  { key: "audit", label: "Nhật ký kiểm toán", icon: ic(ScrollText), portals: both, access: "system", surface: "standard", listed: "main", render: () => <AuditPage/> },
  { key: "builds", label: "Build & lưu trữ", icon: ic(Hammer), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <BuildsPage/> },
  { key: "packages", label: "Packages", icon: ic(Package), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <PackagesPage/> },
  { key: "system", label: "Sức khỏe hệ thống", icon: ic(HeartPulse), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <HealthPage/> },
  { key: "settings", label: "Cài đặt", icon: ic(Settings), portals: platform, access: "system", surface: "standard", listed: "main", render: () => <SettingsPage/> },
  // screens that run on the tenant / workspace APIs (TenantScreens.tsx, OrganizationLive.tsx, ProvisioningLive.tsx): the Admin console only, for the people the server lists as tenant / workspace admins
  { key: "company", label: "Công ty của tôi", icon: ic(Building2), portals: none, access: "company", surface: "scoped", listed: "scoped", navWhen: peopleWhen("company"), denied: "công ty", render: () => <CompanyPage/> },
  { key: "organization", label: "Cơ cấu tổ chức", icon: ic(FolderTree), portals: none, access: "tenant", surface: "scoped", listed: "scoped", navWhen: peopleWhen("organization"), denied: "công ty", render: () => <OrganizationPage/> },
  { key: "employees", label: "Nhân viên", icon: ic(Users), portals: none, access: "tenant", surface: "scoped", listed: "scoped", navWhen: peopleWhen("employees"), denied: "công ty", render: () => <EmployeesPage/> },
  { key: "people", label: "Người dùng", icon: ic(UserRound), portals: none, access: "open", surface: "people", listed: "scoped", navWhen: peopleWhen("people"), denied: "công ty hay workspace", render: () => <PeoplePage/> },
  { key: "my-workspaces", label: "Workspace của tôi", icon: ic(Layers), portals: none, access: "workspace", surface: "scoped", listed: "scoped", navWhen: (s) => !s.platform && s.workspaces.length > 0, denied: "workspace", render: () => <MyWorkspacesPage/> },
  { key: "data-sources", label: "Nguồn dữ liệu", icon: ic(Database), portals: admin, access: "data", surface: "scoped", listed: "scoped", navWhen: (s) => s.dataWorkspaces.length > 0, denied: "nguồn dữ liệu", render: () => <DataSourcesAdminPage/> },
  // sections whose backend does not exist yet: the screen says so plainly instead of showing invented data
  { key: "groups", label: "Nhóm", icon: ic(Users), portals: admin, access: "open", surface: "coming", listed: "coming",
    coming: { why: "Nhóm người dùng để cấp quyền và chia sẻ ứng dụng hàng loạt.", needs: "Máy chủ chưa có API nhóm (chỉ có nhóm đồng bộ từ hệ thống đăng nhập một lần, chưa quản lý được). Mục này sẽ mở khi máy chủ hỗ trợ." } },
  { key: "sharing", label: "Chia sẻ", icon: ic(Share2), portals: admin, access: "open", surface: "coming", listed: "coming",
    coming: { why: "Chia sẻ ứng dụng cho người dùng, nhóm, phòng ban, cả công ty hoặc công ty khác.", needs: "Máy chủ chưa có API chia sẻ giữa người dùng, nhóm và công ty (chia sẻ trong từng ứng dụng đã có ở Studio, mục Chia sẻ). Mục này sẽ mở khi máy chủ hỗ trợ." } },
  { key: "byok", label: "AI riêng của công ty", icon: ic(KeyRound), portals: admin, access: "open", surface: "coming", listed: "coming",
    coming: { why: "Dùng AI mặc định của Xweb hoặc tự đưa khóa AI của công ty (BYOK).", needs: "Tính năng đã có nhưng đang tắt trên máy chủ. Khi bật, khóa chỉ ghi và không bao giờ hiển thị lại." } },
];
