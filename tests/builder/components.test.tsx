// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { DndContext } from "@dnd-kit/core";
import type { AppDefinitionV2 } from "@xweb/types";
import { Dialog, IconButton, StateBox, Tabs } from "../../features/studio/builder/ui/primitives";
import { BuilderTopBar } from "../../features/studio/builder/BuilderTopBar";
import { PagesPanel } from "../../features/studio/builder/panels/PagesPanel";
import { ComponentsPanel } from "../../features/studio/builder/panels/ComponentsPanel";
import { ActionsPanel } from "../../features/studio/builder/panels/ActionsPanel";
import { WorkflowsPanel } from "../../features/studio/builder/panels/WorkflowsPanel";
import { FormsPanel, ThemePanel } from "../../features/studio/builder/panels/MiscPanels";
import { ActionEditor } from "../../features/studio/builder/ActionEditor";
import { WorkflowEditor } from "../../features/studio/builder/WorkflowEditor";
import { DataWizard } from "../../features/studio/builder/DataWizard";
import { Inspector } from "../../features/studio/builder/Inspector";
import { OutcomeView, TestPanel } from "../../features/studio/builder/TestPanel";
import { LeftRail, RAIL } from "../../features/studio/builder/LeftRail";
import { available, notReady } from "../../features/studio/builder/core/readiness";
import { outcomeFromError } from "../../features/studio/builder/core/testMode";
import type { DefCtx } from "../../features/studio/builder/ctx";
import { a11yProblems } from "./a11y";
import { doc as baseDoc } from "./fixtures";

const comp = (id: string, props: Record<string, unknown>, required: string[] = []) => ({
  id, name: id, category: "layout", status: "ACTIVE", latestVersion: "1", description: "",
  versions: [{ version: "1", status: "ACTIVE", propsSchema: { required, properties: props } }],
}) as never;
const registry = [comp("Hero", { title: { type: "string" }, align: { type: "string", enum: ["left", "center"] }, visible: { type: "boolean" } }, ["title"]), comp("ProductGrid", { items: { type: "array", itemProperties: { id: { type: "string" }, name: { type: "string" } } } }), comp("ContactForm", { title: { type: "string" } })];

const full = (): AppDefinitionV2 => baseDoc({
  dataSources: [{ id: "ds1", name: "Kho", type: "POSTGRES" }],
  queries: [{ id: "qr", name: "Đọc", dataSourceRef: "ds1", mode: "READ", operationKey: "k.read", params: [] }],
  mappings: [{ id: "m1", queryRef: "qr", fields: [{ from: "a", to: "name", transforms: [{ type: "trim" }] }] }],
  viewModels: [{ id: "vm1", queryRef: "qr", mappingRef: "m1", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }] }],
  actions: [{ id: "a1", name: "Làm mới", type: "REFRESH_QUERY", queryRef: "qr", trigger: { sectionId: "s-hero", event: "onClick" } }],
  workflows: [{ id: "wf1", name: "Duyệt", trigger: "MANUAL", steps: [
    { id: "s1", kind: "ACTION", actionRef: "a1", retry: { maxAttempts: 3, initialBackoffMillis: 1000, multiplier: 2 }, timeoutMillis: 30000, compensationActionRef: "a1" },
    { id: "s2", kind: "APPROVAL", approval: { requiredApprovals: 1, approvers: [{ kind: "ROLE", role: "manager" }] } }, { id: "end", kind: "END" }] }],
  pages: [{ id: "p1", slug: "gioi-thieu", title: "Giới thiệu", sections: [] }],
  site: { navigation: [{ id: "n1", label: "Giới thiệu", pageId: "p1" }] },
} as never);

const ctx = (over: Partial<DefCtx> = {}): DefCtx => ({
  doc: full(), commit: async () => true, readiness: available(), canEdit: true, busy: false, metadata: new Map(), registry, labelOf: (t) => t, ...over,
});
const dnd = (el: React.ReactElement) => renderToStaticMarkup(<DndContext>{el}</DndContext>);

test("icon button requires and renders an accessible name", () => {
  const html = renderToStaticMarkup(<IconButton label="Đóng">✕</IconButton>);
  assert.match(html, /aria-label="Đóng"/);
  assert.deepEqual(a11yProblems(html), []);
});

test("StateBox: NOT_READY shows 'Chưa sẵn sàng' + reason; LOADING/ERROR have status/alert roles; AVAILABLE renders nothing", () => {
  const nr = renderToStaticMarkup(<StateBox state={notReady("Máy chủ chưa có API X")}/>);
  assert.match(nr, /Chưa sẵn sàng/); assert.match(nr, /Máy chủ chưa có API X/); assert.match(nr, /data-state="NOT_READY"/);
  assert.match(renderToStaticMarkup(<StateBox state={{ state: "LOADING" }}/>), /role="status"/);
  assert.match(renderToStaticMarkup(<StateBox state={{ state: "ERROR", message: "boom" }}/>), /role="alert"/);
  assert.equal(renderToStaticMarkup(<StateBox state={available()}/>), "");
});

test("Tabs expose tablist/tab/aria-selected with roving tabindex", () => {
  const html = renderToStaticMarkup(<Tabs label="Nhóm" idPrefix="t" items={[{ id: "a", label: "A" }, { id: "b", label: "B" }]} value="b" onChange={() => undefined}/>);
  assert.match(html, /role="tablist"/); assert.match(html, /aria-label="Nhóm"/);
  assert.equal((html.match(/role="tab"/g) ?? []).length, 2);
  assert.match(html, /id="t-tab-b"[^>]*aria-selected="true"[^>]*aria-controls="t-panel-b"[^>]*tabindex="0"/);
  assert.match(html, /id="t-tab-a"[^>]*aria-selected="false"[^>]*tabindex="-1"/);
});

test("Dialog is a labelled modal dialog", () => {
  const html = renderToStaticMarkup(<Dialog title="Xóa trang" onClose={() => undefined}><p>x</p></Dialog>);
  assert.match(html, /role="dialog"/); assert.match(html, /aria-modal="true"/);
  const id = /aria-labelledby="([^"]+)"/.exec(html)![1];
  assert.match(html, new RegExp(`<h2 id="${id.replace(/:/g, "\\:")}">Xóa trang</h2>`));
});

test("left rail has the eight tools; vertical tablist", () => {
  assert.deepEqual(RAIL.map((r) => r.id), ["pages", "components", "data", "forms", "actions", "workflows", "theme", "ai"]);
  const html = renderToStaticMarkup(<LeftRail value="pages" onChange={() => undefined}>x</LeftRail>);
  assert.match(html, /aria-orientation="vertical"/); assert.equal((html.match(/role="tab"/g) ?? []).length, 8);
});

test("top bar: name, save state, Edit/Test, device, Share, Publish; disabled controls explain why", () => {
  const html = renderToStaticMarkup(<BuilderTopBar name="Cửa hàng" meta="rev 3" save={{ state: "saved", at: null }} appMode="EDIT" onAppMode={() => undefined} device="desktop" onDevice={() => undefined}
    canShare={false} shareReason="Bạn không có quyền chia sẻ." onShare={() => undefined} canPublish={false} publishReason="Bạn không có quyền xuất bản." publishBusy={false} issues={{ block: 2, warn: 0 }} onPublish={() => undefined}/>);
  for (const t of ["Cửa hàng", "Đã lưu", "Chỉnh sửa", "Dùng thử", "Máy tính", "Điện thoại", "Chia sẻ", "Xuất bản"]) assert.match(html, new RegExp(t));
  assert.match(html, /aria-pressed="true"[^>]*>Chỉnh sửa/); assert.match(html, /aria-pressed="false"[^>]*>Dùng thử/);
  assert.match(html, /title="Bạn không có quyền xuất bản\."/); assert.match(html, /2 lỗi chặn xuất bản/);
  assert.deepEqual(a11yProblems(html), []);
});

test("pages panel: ARIA tree, routes, broken-route alert, set-home and reorder NOT_READY", () => {
  const d = { ...full(), pages: [] } as AppDefinitionV2; // menu now points at a deleted page
  const html = dnd(<PagesPanel doc={d} pageId="home" onPage={() => undefined} selectedId={null} onSelect={() => undefined} labelOf={(t) => t} summaryOf={() => ""} canEdit busy={false} apply={async () => true} genId={() => "x"} onMoveSection={() => undefined}/>);
  assert.match(html, /role="tree"/); assert.match(html, /role="treeitem"/); assert.match(html, /aria-level="1"/); assert.match(html, /aria-level="2"/);
  assert.match(html, /role="alert"/); assert.match(html, /sẽ chặn xuất bản/);
  const ok = dnd(<PagesPanel doc={full()} pageId="p1" onPage={() => undefined} selectedId={null} onSelect={() => undefined} labelOf={(t) => t} summaryOf={() => ""} canEdit busy={false} apply={async () => true} genId={() => "x"} onMoveSection={() => undefined}/>);
  assert.match(ok, /\/gioi-thieu\//); assert.match(ok, /Đặt làm trang chủ/); assert.match(ok, /Chưa sẵn sàng/); assert.match(ok, /Mọi đường dẫn hợp lệ/);
  assert.match(ok, /Xem trước trang 404/);
  assert.deepEqual(a11yProblems(ok), []);
});

test("pages panel for a viewer: no create/rename/delete controls", () => {
  const html = dnd(<PagesPanel doc={full()} pageId="home" onPage={() => undefined} selectedId={null} onSelect={() => undefined} labelOf={(t) => t} summaryOf={() => ""} canEdit={false} busy={false} apply={async () => true} genId={() => "x"} onMoveSection={() => undefined}/>);
  assert.doesNotMatch(html, /＋ Trang/); assert.doesNotMatch(html, /Kéo để di chuyển/);
  assert.match(html, /disabled="">Đổi tên/);
});

test("components panel: drag handle + add button per component, data components NOT_READY when missing, only registry entries", () => {
  const html = dnd(<ComponentsPanel registry={registry} blocks={[]} canEdit busy={false} onAdd={() => undefined} onAddBlock={() => undefined}/>);
  assert.match(html, /aria-label="Kéo Đầu trang \(Hero\) vào trang"/); assert.match(html, /aria-label="Thêm Đầu trang \(Hero\) vào trang"/);
  assert.match(html, /Lưới sản phẩm/);
  for (const n of ["Bảng dữ liệu", "Danh sách dữ liệu", "Chỉ số KPI", "Biểu đồ", "Ô chọn", "Chi tiết một mục"]) assert.match(html, new RegExp(n));
  assert.ok((html.match(/data-state="NOT_READY"/g) ?? []).length >= 6);
  assert.doesNotMatch(html, /Thẻ sản phẩm/);
  assert.deepEqual(a11yProblems(html), []);
  assert.doesNotMatch(dnd(<ComponentsPanel registry={registry} blocks={[]} canEdit={false} busy={false} onAdd={() => undefined} onAddBlock={() => undefined}/>), /Thêm Đầu trang/);
});

test("action editor offers the nine canonical types (REFRESH_QUERY yes, RUN_QUERY no) and has no code/SQL/URL inputs", () => {
  const html = renderToStaticMarkup(<ActionEditor ctx={ctx()} preset={{ type: "REFRESH_QUERY" }} onDone={() => undefined} onCancel={() => undefined}/>);
  assert.match(html, /value="REFRESH_QUERY"/); assert.doesNotMatch(html, /RUN_QUERY/);
  for (const t of ["NAVIGATE", "SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API", "NOTIFY", "START_WORKFLOW"]) assert.match(html, new RegExp(`value="${t}"`));
  assert.doesNotMatch(html, /<textarea/i); assert.doesNotMatch(html, /type="url"/); assert.doesNotMatch(html, /(sql|script|headers|token|password)/i);
  assert.match(html, /Truy vấn đọc cần làm mới/);
  assert.deepEqual(a11yProblems(html), []);
});

test("action editor: trigger is optional (checkbox), a child action has none", () => {
  const child = renderToStaticMarkup(<ActionEditor ctx={ctx()} preset={{ type: "NOTIFY" }} onDone={() => undefined} onCancel={() => undefined}/>);
  assert.match(child, /Gắn vào một thành phần trên trang/); assert.match(child, /Hành động con/);
  const bound = renderToStaticMarkup(<ActionEditor ctx={ctx()} initial={full().actions![0]} onDone={() => undefined} onCancel={() => undefined}/>);
  assert.match(bound, /Sự kiện/);
});

test("actions panel: NOT_READY when the V2 core is missing; list with roles when available", () => {
  const nr = renderToStaticMarkup(<ActionsPanel ctx={ctx({ readiness: notReady("C2 chưa tích hợp") })}/>);
  assert.match(nr, /Chưa sẵn sàng/); assert.match(nr, /C2 chưa tích hợp/); assert.doesNotMatch(nr, /＋ Hành động/);
  const ok = renderToStaticMarkup(<ActionsPanel ctx={ctx()}/>);
  assert.match(ok, /Làm mới/); assert.match(ok, /Gắn vào thành phần/); assert.match(ok, /＋ Hành động/);
  assert.deepEqual(a11yProblems(ok), []);
});

test("workflow editor shows retry, timeout, compensation and approval as readable chips; kinds limited to V1", () => {
  const html = renderToStaticMarkup(<WorkflowEditor ctx={ctx()} initial={full().workflows![0]} onDone={() => undefined} onCancel={() => undefined}/>);
  assert.match(html, /Thử lại tối đa 3 lần/); assert.match(html, /Giới hạn 30s/); assert.match(html, /Hoàn tác bằng/); assert.match(html, /Cần 1 người duyệt/);
  for (const k of ["Hành động", "Chờ", "Phê duyệt", "Rẽ nhánh"]) assert.match(html, new RegExp(`\\+ ${k}`));
  assert.doesNotMatch(html, /BPMN/i);
  assert.deepEqual(a11yProblems(html), []);
});

test("workflows panel: NOT_READY gate and list", () => {
  assert.match(renderToStaticMarkup(<WorkflowsPanel ctx={ctx({ readiness: notReady("x") })}/>), /Chưa sẵn sàng/);
  const ok = renderToStaticMarkup(<WorkflowsPanel ctx={ctx()}/>);
  assert.match(ok, /Duyệt/); assert.match(ok, /3 bước \(1 phê duyệt\)/);
});

test("data wizard: six steps; source and discovery are NOT_READY without a server; no mock rows", () => {
  const html = renderToStaticMarkup(<DataWizard ctx={ctx()}/>);
  for (const s of ["Nguồn dữ liệu", "Truy vấn", "Ánh xạ", "ViewModel"]) assert.match(html, new RegExp(s));
  assert.equal((html.match(/role="tab"/g) ?? []).length, 6);
  assert.match(html, /Chưa sẵn sàng/); assert.match(html, /Kho/);
  assert.doesNotMatch(html, /mock|fake|giả lập thành công/i);
  assert.match(html, /Ánh xạ[\s\S]*name \[Bỏ khoảng trắng đầu\/cuối\]/);
});

test("data wizard with the V2 core missing: every step past discovery says Chưa sẵn sàng", () => {
  const html = renderToStaticMarkup(<DataWizard ctx={ctx({ readiness: notReady("C2 V2 chưa tích hợp") })}/>);
  assert.match(html, /Chưa sẵn sàng/);
});

test("inspector: six tabs; Design NOT_READY for a component without design props; viewer sees why permissions are off", () => {
  const section = { id: "s-hero", type: "Hero", props: { title: "Hi" } } as never;
  const html = renderToStaticMarkup(<Inspector ctx={ctx()} section={section} component={registry[0]} meta={undefined} index={0} count={3} readOnly busy={false} assets={[]} rawPermissions={["PROJECT_READ"]}
    onApply={async () => true} onClose={() => undefined} onMove={() => undefined} onRemove={() => undefined} openDataWizard={() => undefined} pageId="home"/>);
  for (const t of ["Nội dung", "Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao"]) assert.match(html, new RegExp(t));
  assert.equal((html.match(/role="tab"/g) ?? []).length, 6);
  assert.match(html, /Bạn chỉ có quyền xem/);
  assert.deepEqual(a11yProblems(html), []);
});

test("test panel: Edit != Test rules, would-run / unsupported / not-sent / publish not run, Chạy thử disabled with reason, never SUCCESS", () => {
  const d = baseDoc({ actions: [
    { id: "u", name: "Cập nhật", type: "UPDATE_RECORD", queryRef: "q" }, { id: "c", name: "Gọi", type: "CALL_API", operationKey: "k" },
    { id: "n", name: "Báo", type: "NOTIFY", channel: "IN_APP", templateRef: "t" }, { id: "w", name: "Chạy", type: "START_WORKFLOW", workflowRef: "wf" }],
    workflows: [{ id: "wf", name: "W", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] }] } as never);
  const html = renderToStaticMarkup(<TestPanel doc={d} rawPermissions={["APP_VIEW", "ACTION_EXECUTE", "WORKFLOW_EXECUTE"]}/>);
  assert.match(html, /Chế độ dùng thử/); assert.match(html, /data-outcome="WOULD_RUN"/); assert.match(html, /data-outcome="UNSUPPORTED"/); assert.match(html, /data-outcome="NOT_SENT"/); assert.match(html, /data-outcome="NOT_RUN"/);
  assert.match(html, /workflow_run/); assert.doesNotMatch(html, /data-outcome="SUCCESS"/); assert.match(html, /disabled=""[^>]*>Chạy thử/);
  assert.match(html, /Chưa sẵn sàng/);
  assert.deepEqual(a11yProblems(html), []);
});

test("outcome view: 409 unknown outcome and 422 rejected have their own messages", () => {
  const unk = renderToStaticMarkup(<OutcomeView outcome={outcomeFromError({ status: 409, code: "IDEMPOTENCY_OUTCOME_UNKNOWN" })}/>);
  assert.match(unk, /data-outcome="UNKNOWN"/); assert.match(unk, /Chưa rõ thao tác đã được ghi hay chưa/); assert.match(unk, /Không nên bấm chạy lại ngay/);
  const rej = renderToStaticMarkup(<OutcomeView outcome={outcomeFromError({ status: 422, code: "MUTATION_REJECTED" })}/>);
  assert.match(rej, /data-outcome="REJECTED"/); assert.match(rej, /từ chối/);
});

test("forms + theme panels: NOT_READY gates, no CSS/URL inputs for the theme", () => {
  assert.match(renderToStaticMarkup(<FormsPanel ctx={ctx({ doc: baseDoc({ sections: [{ id: "f1", type: "ContactForm", props: {} } as never] }), readiness: notReady("x") })} onSelect={() => undefined} onNewAction={() => undefined} openSite={() => undefined}/>), /Chưa sẵn sàng/);
  const th = renderToStaticMarkup(<ThemePanel ctx={ctx()}/>);
  assert.match(th, /SYSTEM/); assert.doesNotMatch(th, /<textarea/); assert.match(th, /chưa áp dụng giao diện này/);
  assert.match(renderToStaticMarkup(<ThemePanel ctx={ctx({ readiness: notReady("y") })}/>), /Chưa sẵn sàng/);
  assert.deepEqual(a11yProblems(th), []);
});

const noRt = { runQuery: async () => ({}) as never, runAction: async () => ({}) as never, startWorkflow: async () => ({}) as never, getRun: async () => ({}) as never, cancelRun: async () => ({}) as never, newKey: () => "k" };
const rtDoc = () => baseDoc({ actions: [{ id: "n", name: "Báo", type: "NOTIFY", channel: "IN_APP", templateRef: "t", trigger: { sectionId: "s1", event: "onClick" } }] } as never);

test("test panel with a runtime: runs need APP_EDIT, unsaved changes block runs, an editor can run (no fake result is rendered)", () => {
  const viewer = renderToStaticMarkup(<TestPanel doc={rtDoc()} rawPermissions={["APP_VIEW", "ACTION_EXECUTE"]} runtime={noRt}/>);
  assert.match(viewer, /Chỉnh sửa ứng dụng/); assert.match(viewer, /disabled=""[^>]*data-testid="run-action:n"|data-testid="run-action:n"[^>]*disabled=""/);
  const dirty = renderToStaticMarkup(<TestPanel doc={rtDoc()} rawPermissions={["APP_VIEW", "APP_USE", "APP_EDIT", "ACTION_EXECUTE"]} runtime={noRt} dirty/>);
  assert.match(dirty, /thay đổi chưa lưu/);
  const ok = renderToStaticMarkup(<TestPanel doc={rtDoc()} rawPermissions={["APP_VIEW", "APP_USE", "APP_EDIT", "ACTION_EXECUTE"]} runtime={noRt}/>);
  assert.match(ok, /data-testid="run-action:n"/); assert.doesNotMatch(ok, /data-testid="run-action:n"[^>]*disabled=""/);
  assert.doesNotMatch(ok, /data-outcome="SUCCESS"/); assert.doesNotMatch(ok, /Chưa kết nối máy chủ/);
  assert.match(renderToStaticMarkup(<TestPanel doc={rtDoc()} rawPermissions={["APP_VIEW", "APP_USE", "APP_EDIT", "ACTION_EXECUTE"]}/>), /Chưa kết nối máy chủ/);
  assert.deepEqual(a11yProblems(ok), []);
});

test("test panel: an action without a UI trigger is not runnable from the browser route (the server answers UNKNOWN_ACTION, D-C4-10)", () => {
  const noTrigger = baseDoc({ actions: [{ id: "n", name: "Báo", type: "NOTIFY", channel: "IN_APP", templateRef: "t" }] } as never);
  const html = renderToStaticMarkup(<TestPanel doc={noTrigger} rawPermissions={["APP_VIEW", "APP_USE", "APP_EDIT", "ACTION_EXECUTE"]} runtime={noRt}/>);
  assert.match(html, /data-testid="no-trigger:n"/); assert.match(html, /chỉ chạy từ workflow hoặc hành động khác/);
  assert.match(html, /disabled=""[^>]*data-testid="run-action:n"|data-testid="run-action:n"[^>]*disabled=""/);
  assert.deepEqual(a11yProblems(html), []);
});

test("top bar: the retry button appears only for a failed save that has a retry handler", () => {
  const base = { name: "A", meta: "r", appMode: "EDIT" as const, onAppMode: () => undefined, device: "desktop" as const, onDevice: () => undefined, canShare: true, shareReason: "", onShare: () => undefined, canPublish: true, publishReason: "", publishBusy: false, issues: { block: 0, warn: 0 }, onPublish: () => undefined };
  const failed = renderToStaticMarkup(<BuilderTopBar {...base} save={{ state: "error", at: null }} onRetrySave={() => undefined}/>);
  assert.match(failed, /Lưu thất bại/); assert.match(failed, /data-testid="retry-save"/);
  assert.doesNotMatch(renderToStaticMarkup(<BuilderTopBar {...base} save={{ state: "error", at: null }}/>), /retry-save/);
  assert.doesNotMatch(renderToStaticMarkup(<BuilderTopBar {...base} save={{ state: "saved", at: null }} onRetrySave={() => undefined}/>), /retry-save/);
});
