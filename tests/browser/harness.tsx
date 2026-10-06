/**
 * TEST-ONLY harness. NOT a backend and NOT an E2E of the product.
 * It mounts the real <BuilderWorkspace> in a real browser so pointer/keyboard drag and drop, focus and ARIA can be exercised.
 * The host below stands in for ProjectWorkspace: `applyOps` records every operation the Builder emits (window.__ops) and applies the few
 * section operations locally so the canvas re-renders. It validates nothing the server validates and it is never shipped.
 */
import { createRoot } from "react-dom/client";
import { useMemo, useState } from "react";
import type { ApiProject, AppDefinitionV2, DefinitionOperation, RegistryComponent, SchemaOperation, Section } from "@xweb/types";
import { BuilderWorkspace } from "../../features/studio/builder/BuilderWorkspace";
import { renderSchemaDocument } from "../../lib/schema-preview";
import { sectionLabel, sectionSummary } from "../../components/SectionInspector";
import { backendFrom } from "../../features/studio/builder/core/backend";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/builder.css";

type Ops = (SchemaOperation | DefinitionOperation)[];
declare global { interface Window { __ops: { summary: string; ops: Ops }[]; __published: number; } }
window.__ops = []; window.__published = 0;
const BROKEN = new URLSearchParams(location.search).has("broken");

const comp = (id: string, name: string, category: string, required: string[], properties: RegistryComponent["versions"][number]["propsSchema"]["properties"]): RegistryComponent =>
  ({ id, name, category, description: name, latestVersion: "1.0.0", status: "ACTIVE", versions: [{ version: "1.0.0", status: "ACTIVE", propsSchema: { required, properties } }] });
const registry: RegistryComponent[] = [
  comp("Navbar", "Navbar", "layout", ["brand"], { brand: { type: "string" } }),
  comp("Hero", "Hero", "marketing", ["title"], { title: { type: "string", maxLength: 120 }, subtitle: { type: "string" } }),
  comp("Testimonials", "Testimonials", "marketing", ["heading"], { heading: { type: "string" } }),
  comp("ContactForm", "ContactForm", "forms", ["title"], { title: { type: "string" } }),
  comp("Footer", "Footer", "layout", ["text"], { text: { type: "string" } }),
];
const project = { id: "p1", workspaceId: "w1", name: "Harness", description: null, ownerUserId: "u1", framework: "NEXT", siteVisibility: "PRIVATE", authMode: "NONE", domain: null, customDomain: null,
  deploymentMode: "MOCK", deploymentTarget: null, status: "DRAFT", revision: 1, createdAt: "", updatedAt: "", permissions: ["PROJECT_EDIT", "PROJECT_VIEW", "PROJECT_PUBLISH", "PROJECT_SETTINGS", "PROJECT_MEMBERS"], appType: "PAGE_SCHEMA" } as unknown as ApiProject;

function applyLocal(doc: AppDefinitionV2, ops: Ops): AppDefinitionV2 {
  let d: AppDefinitionV2 = { ...doc, pages: [...(doc.pages ?? [])] };
  const pagesOf = () => d.pages as unknown as { id: string; slug: string; title: string; sections: Section[] }[];
  for (const o of ops as SchemaOperation[]) {
    const onPage = o.pageId && o.pageId !== "home" ? pagesOf().find((x) => x.id === o.pageId) : undefined;
    let secs = onPage ? [...onPage.sections] : [...d.sections];
    const put = (next: Section[]) => { if (onPage) d = { ...d, pages: pagesOf().map((x) => (x === onPage ? { ...x, sections: next } : x)) } as AppDefinitionV2; else d = { ...d, sections: next }; };
    if (o.type === "ADD_SECTION" && o.sectionId) {
      const s = { id: o.sectionId, type: o.sectionType!, props: (o.props ?? {}) as Record<string, unknown> } as Section;
      const at = o.beforeSectionId ? secs.findIndex((x) => x.id === o.beforeSectionId) : typeof o.index === "number" ? o.index : secs.length;
      secs.splice(at < 0 ? secs.length : at, 0, s); put(secs);
    } else if (o.type === "MOVE_SECTION" && o.sectionId) {
      const from = secs.findIndex((x) => x.id === o.sectionId); if (from < 0) continue;
      const [s] = secs.splice(from, 1); secs.splice(typeof o.index === "number" ? o.index : secs.length, 0, s); put(secs);
    } else if (o.type === "REMOVE_SECTION") put(secs.filter((x) => x.id !== o.sectionId));
    else if (o.type === "UPDATE_PROP" && o.sectionId && o.path) put(secs.map((x) => (x.id === o.sectionId ? ({ ...x, props: { ...x.props, [o.path!]: o.value } } as Section) : x)));
    else if (o.type === "ADD_PAGE" && o.pageId) d = { ...d, pages: [...pagesOf(), { id: o.pageId, ...(o.props as { slug: string; title: string }), sections: [] }] } as AppDefinitionV2;
    else if (o.type === "UPDATE_PAGE") {
      if (o.pageId === "home") d = { ...d, page: String((o.props as { title?: string }).title ?? d.page) };
      else d = { ...d, pages: pagesOf().map((x) => (x.id === o.pageId ? { ...x, ...(o.props as object) } : x)) } as AppDefinitionV2;
    } else if (o.type === "REMOVE_PAGE") {
      const nav = (d.site?.navigation ?? []).filter((l) => l.pageId !== o.pageId);
      d = { ...d, pages: pagesOf().filter((x) => x.id !== o.pageId), site: { ...d.site, navigation: nav } } as AppDefinitionV2;
    } else if (o.type === "SET_NAVIGATION") d = { ...d, site: { ...d.site, navigation: o.value as never } };
    else if (o.type === "UPDATE_SITE") d = { ...d, site: { ...d.site, ...(o.props as object) } };
  }
  return d;
}

function Host() {
  const [doc, setDoc] = useState<AppDefinitionV2>({ page: "Trang chủ", sections: [
    { id: "s-nav", type: "Navbar", props: { brand: "Harness" } }, { id: "s-hero", type: "Hero", props: { title: "Xin chào", subtitle: "Mô tả" } },
    { id: "s-test", type: "Testimonials", props: { heading: "Khách hàng" } }, { id: "s-foot", type: "Footer", props: { text: "© Harness" } },
  ], pages: [], ...(BROKEN ? { site: { navigation: [{ id: "n1", label: "Trang đã mất", pageId: "ghost" }] } } : {}) } as unknown as AppDefinitionV2);
  const [revision, setRevision] = useState(1);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pageId, setPageId] = useState("home");
  const [device, setDevice] = useState<"desktop" | "tablet" | "mobile">("desktop");
  const backend = useMemo(() => backendFrom({ status: "error", error: { status: 404, message: "Not Found" } }), []);
  return <div className="studio workspace3 bx-root"><BuilderWorkspace
    project={project} doc={doc} revision={revision} registry={registry} assets={[]} blocks={[]} backend={backend} pageId={pageId} onPage={(id) => { setPageId(id); setSelectedId(null); }}
    selectedId={selectedId} onSelect={setSelectedId} device={device} onDevice={setDevice} busy={false} save={{ state: "saved", at: null }} readOnly={false} latest={1}
    applyOps={async (ops, summary) => { window.__ops.push({ summary, ops }); setDoc((d) => applyLocal(d, ops)); setRevision((r) => r + 1); return true; }}
    addBlock={() => undefined}
    renderPreview={(o) => renderSchemaDocument(doc, { selectedId: o.selectedId, interactive: o.interactive, nonce: "", assets: {}, pageId: o.pageId })}
    labelOf={(t) => sectionLabel(t, registry.find((c) => c.id === t)?.name)} summaryOf={sectionSummary}
    goAi={() => undefined} openSite={() => undefined} openMembers={() => undefined} openPublish={() => { window.__published += 1; }} saveBlock={() => undefined}/></div>;
}
createRoot(document.getElementById("root")!).render(<Host/>);
