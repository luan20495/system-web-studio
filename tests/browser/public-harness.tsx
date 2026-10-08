// @class: harness — in-page host of the real <DataWizard>; NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY harness for the public-data editors (slots, QueryDef.public, bindings, readiness) inside the real <DataWizard>. NOT a backend: `commit` records every typed operation
 * the editors emit in window.__pops and applies the DEFINITION operations locally (ADD/UPDATE/REMOVE_*, null clears) so the next render shows the result. It validates nothing the
 * server validates (the server's refusals are scenarios: ?s=reject makes the next commit answer "not saved"). It never runs a query and has no rows.
 */
import { createRoot } from "react-dom/client";
import { useState } from "react";
import type { AppDefinitionV2, DefinitionOperation } from "@xweb/types";
import { DEFINITION_COLLECTIONS } from "../../packages/types/src/contract/v2/appDefinition";
import { DataWizard } from "../../features/studio/builder/DataWizard";
import { available } from "../../features/studio/builder/core/readiness";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/builder.css";
import "../../packages/ui/src/styles/ui.css";   // the real layouts load it last (apps/*/app/layout.tsx); a harness without it is not the product

declare global { interface Window { __pops: { summary: string; ops: DefinitionOperation[] }[]; __doc: () => AppDefinitionV2 } }
window.__pops = [];
const S = new URLSearchParams(location.search).get("s") ?? "ok";
const FAMILY_TO_COLLECTION = Object.fromEntries(Object.entries(DEFINITION_COLLECTIONS).map(([c, f]) => [f, c])) as Record<string, string>;

const seed = (): AppDefinitionV2 => {
  const sections = [{ id: "hero", type: "Hero", props: { title: "Xin chào" } }, { id: "grid", type: "ProductGrid", props: { heading: "Sản phẩm", items: [] } }, { id: "txt", type: "TextBlock", props: { body: "x" } }];
  if (S === "empty") return { page: "p", sections, pages: [] } as unknown as AppDefinitionV2;
  return {
    page: "p", sections, pages: [],
    dataSources: [{ id: "orders", name: "Đơn hàng", type: "postgres" }, { id: "spare", name: "Dự phòng", type: "rest" }, { id: "granted", name: "Đã cấp", type: "postgres", sourceRef: "11111111-2222-3333-4444-555555555555" }],
    queries: [
      { id: "q-title", name: "Tiêu đề", dataSourceRef: "orders", mode: "READ", operationKey: "orders.title", params: [] },
      { id: "q-items", name: "Sản phẩm", dataSourceRef: "orders", mode: "READ", operationKey: "orders.items", params: [], public: true },
      { id: "q-write", name: "Tạo đơn", dataSourceRef: "orders", mode: "WRITE", operationKey: "orders.create", params: [] },
      ...(S === "invalid" ? [{ id: "q-bad", name: "Ghi nhưng công khai", dataSourceRef: "orders", mode: "WRITE", operationKey: "orders.bad", params: [], public: true }] : []),
    ],
    ...(S === "bound" ? { dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-items" }, { id: "b2", sectionId: "hero", prop: "ctaLabel", queryRef: "q-title" }] } : {}),
  } as unknown as AppDefinitionV2;
};

function apply(doc: AppDefinitionV2, op: DefinitionOperation): AppDefinitionV2 {
  const [verb, ...fam] = op.type.split("_"); const coll = FAMILY_TO_COLLECTION[fam.join("_")];
  if (!coll) return doc;
  const cur = ((doc as unknown as Record<string, { id: string }[]>)[coll] ?? []).slice();
  if (verb === "ADD") cur.push(op.definition as never);
  else if (verb === "REMOVE") return { ...doc, [coll]: cur.filter((x) => x.id !== op.definitionId) } as AppDefinitionV2;
  else if (verb === "UPDATE") {
    const i = cur.findIndex((x) => x.id === op.definitionId); if (i < 0) return doc;
    const next = { ...cur[i] } as Record<string, unknown>;
    for (const [k, v] of Object.entries(op.definition ?? {})) { if (v === null) delete next[k]; else next[k] = v; }
    cur[i] = next as never;
  }
  return { ...doc, [coll]: cur } as AppDefinitionV2;
}

let rejectNext = S === "reject";
let current: AppDefinitionV2 = seed();
window.__doc = () => current;

function Host() {
  const [doc, setDoc] = useState<AppDefinitionV2>(current);
  const commit = async (ops: DefinitionOperation[], summary: string) => {
    window.__pops.push({ summary, ops });
    if (rejectNext) { rejectNext = false; return false; }
    let d = doc; for (const o of ops) d = apply(d, o);
    current = d; setDoc(d); return true;
  };
  return <div className="studio bx-root" style={{ maxWidth: 640, padding: 12 }}><DataWizard ctx={{
    doc, commit, readiness: available(), canEdit: S !== "readonly", busy: false, siteVisibility: S === "private" ? "PRIVATE" : "PUBLIC", metadata: new Map(), registry: [], labelOf: (t) => t,
  }}/></div>;
}
createRoot(document.getElementById("root")!).render(<Host/>);
