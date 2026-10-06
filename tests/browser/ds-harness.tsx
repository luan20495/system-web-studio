/**
 * TEST-ONLY harness for <DataSourcesPanel>. NOT a backend and NOT an E2E of the product: `calls` is an in-page fake that records every call in window.__calls and answers
 * according to ?s=<scenario>. It proves what the PANEL does with the answers the C3 contract describes (states, locks, secret handling), never what the real routes answer.
 */
import { createRoot } from "react-dom/client";
import type { AppDefinitionV2, ConnectorDescriptor, DataBinding, DataSourceView } from "@xweb/types";
import { DataSourcesPanel } from "../../features/studio/builder/DataSourcesPanel";
import type { DataManagementCalls } from "../../features/studio/builder/core/dataManagement";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/builder.css";

declare global { interface Window { __calls: { name: string; args: unknown[] }[]; __secretsSeenInDom: () => boolean } }
window.__calls = [];
const S = new URLSearchParams(location.search).get("s") ?? "ok";
const err = (status: number, code: string, message = "fixed text") => Object.assign(new Error(message), { status, code });
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const rec = <T,>(name: string, args: unknown[], f: () => T) => { window.__calls.push({ name, args }); return f(); };

const connectors: ConnectorDescriptor[] = [
  { type: "postgres", displayName: "PostgreSQL", status: "AVAILABLE", capabilities: ["QUERY"], configKeys: [{ name: "host", required: true, description: "Máy chủ công khai" }, { name: "database", required: true, description: "" }, { name: "port", required: false, description: "" }], credentialKeys: ["username", "password"], notes: "" },
  { type: "mysql", displayName: "MySQL", status: "PLANNED", capabilities: [], configKeys: [], credentialKeys: [], notes: "" },
];
let sources: DataSourceView[] = S === "empty" ? [] : [{ id: "ds-1", workspaceId: "w1", name: "billing-db", type: "postgres", config: { host: "db.example.com", database: "shop" }, hasCredential: true, status: "ACTIVE", version: 1, createdBy: null, createdAt: "", updatedAt: "" }];
let bindings: DataBinding[] = [];
let listFails = S === "listerr" ? 1 : 0;
let seq = 1;

const calls: DataManagementCalls = {
  connectors: () => rec("connectors", [], async () => { if (S === "flagoff") throw err(404, "HTTP_404"); if (S === "forbidden") throw err(403, "PERMISSION_DENIED"); if (listFails-- > 0) throw err(500, "INTERNAL"); return connectors; }),
  list: () => rec("list", [], async () => [...sources]),
  listBindings: () => rec("listBindings", [], async () => [...bindings]),
  credential: (id) => rec("credential", [id], async () => ({ configured: true, type: "postgres", keys: ["username", "password"], updatedAt: "t", updatedBy: null })),
  create: (b) => rec("create", [b], async () => {
    await wait(300);
    if (S === "createfail") { sources.push({ id: `ds-${++seq}`, workspaceId: "w1", name: b.name, type: b.type, config: b.config ?? {}, hasCredential: !!b.credential, status: "ACTIVE", version: 1, createdBy: null, createdAt: "", updatedAt: "" }); throw err(0, "NETWORK"); }
    if (S === "conflictname") throw err(409, "CONFLICT", "name already used");
    const d: DataSourceView = { id: `ds-${++seq}`, workspaceId: "w1", name: b.name, type: b.type, config: b.config ?? {}, hasCredential: !!b.credential, status: "ACTIVE", version: 1, createdBy: null, createdAt: "", updatedAt: "" };
    sources.push(d); return d;
  }),
  update: (id, b) => rec("update", [id, b], async () => { const d = sources.find((x) => x.id === id)!; Object.assign(d, b.status ? { status: b.status } : {}); return { ...d }; }),
  remove: (id) => rec("remove", [id], async () => { if (S === "bound" || bindings.some((x) => x.dataSourceId === id)) throw err(409, "CONFLICT", "data source is in use"); sources = sources.filter((x) => x.id !== id); }),
  setCredential: (id, c) => rec("setCredential", [id, c], async () => { await wait(300); return { configured: true, type: "postgres", keys: Object.keys(c), updatedAt: "t2", updatedBy: null }; }),
  removeCredential: (id) => rec("removeCredential", [id], async () => { sources = sources.map((x) => (x.id === id ? { ...x, hasCredential: false } : x)); }),
  test: (id) => rec("test", [id], async () => {
    await wait(S === "slowtest" ? 900 : 200);
    if (S === "disabledtest") throw err(409, "DISABLED");
    if (S === "failtest") return { ok: false as const, code: "AUTH_REJECTED", message: "fixed text" };
    if (S === "warntest") return { ok: true as const, latencyMs: 12, warnings: ["the database role can write to 3 table(s); use a SELECT-only role"] };
    return { ok: true as const, latencyMs: 12, warnings: [] };
  }),
  bind: (mode, slot, id) => rec("bind", [mode, slot, id], async () => { await wait(300); const b = { mode, slotId: slot, dataSourceId: id, updatedAt: null }; bindings = [...bindings.filter((x) => !(x.mode === mode && x.slotId === slot)), b]; return b; }),
  unbind: (mode, slot) => rec("unbind", [mode, slot], async () => { bindings = bindings.filter((x) => !(x.mode === mode && x.slotId === slot)); }),
};

const doc = S === "noslots" ? ({ sections: [] } as unknown as AppDefinitionV2)
  : ({ sections: [], dataSources: [{ id: "erp-db", name: "ERP", type: "CONNECTOR" }, { id: "crm", name: "CRM", type: "CONNECTOR" }] } as unknown as AppDefinitionV2);

window.__secretsSeenInDom = () => [...document.querySelectorAll("input")].some((i) => /hunter2|S3cr3t/.test(i.value)) || /hunter2|S3cr3t/.test(document.body.innerText);
createRoot(document.getElementById("root")!).render(<div style={{ maxWidth: 560, padding: 12 }}><DataSourcesPanel doc={doc} calls={S === "nocalls" ? undefined : calls} canManage={S !== "readonly"} manageReason="Bạn chưa được cấp quyền quản lý nguồn dữ liệu."/></div>);
