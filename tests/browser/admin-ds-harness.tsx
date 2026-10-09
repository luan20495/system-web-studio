// @class: harness — HARNESS, NOT REAL BACKEND. features/studio/builder/DataSourcesPanel (+ its Dialog) rendered the way Platform / Admin render it: the Admin CSS set, inside .shell > .page > .card (TenantScreens.tsx DataSourcesAdminPage). `calls` is an in-page fake.
import { createRoot } from "react-dom/client";
import { DataSourcesPanel } from "../../features/studio/builder/DataSourcesPanel";
import "./admin-css";

const calls = {
  connectors: async () => [{ type: "postgres", displayName: "PostgreSQL", status: "AVAILABLE", capabilities: ["QUERY"], configKeys: [{ name: "host", required: true, description: "Máy chủ" }], credentialKeys: ["password"], notes: "" }],
  list: async () => [{ id: "ds-1", workspaceId: "w1", name: "billing-db", type: "postgres", config: { host: "x" }, hasCredential: true, status: "ACTIVE", version: 1, createdBy: null, createdAt: "", updatedAt: "" }],
  credential: async () => ({ configured: true, keys: ["password"], updatedAt: "" }),
  create: async () => ({}), update: async () => ({}), remove: async () => ({}), setCredential: async () => ({}), removeCredential: async () => ({}), test: async () => ({}),
  listBindings: async () => [], bind: async () => ({}), unbind: async () => ({}),
} as never;
createRoot(document.getElementById("root")!).render(
  <div className="shell admin" data-nav="closed">
    <aside className="sidebar dark"><nav><a className="navLink" href="#x">Mục 1</a></nav></aside>
    <div className="shellMain">
      <header className="topHeader"><div className="crumb">Quản trị công ty</div></header>
      <main className="page" id="main"><div className="pageHead"><div><h1>Nguồn dữ liệu</h1></div></div>
        <section className="card"><DataSourcesPanel headingLevel={2} doc={{ page: "", sections: [] } as never} calls={calls} canView viewReason="" canManage manageReason="" canBind={false} bindReason="Liên kết khe dữ liệu được thực hiện trong Studio."/></section>
      </main>
    </div>
  </div>);
