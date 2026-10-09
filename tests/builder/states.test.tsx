// @class: unit — pure logic / server-side render of components; no browser, no network
/** M-079 / M-084 / M-041 / M-056 (component part): ErrorState says a failure once, StateView takes a heading level, LoadGate walks loading / empty / error / ready. */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { ApiError } from "../../packages/api-client/src/core";
import { ErrorState, StateView, retryHelps, stateOf } from "../../packages/ui/src/States";
import { LoadGate, StaleBanner } from "../../packages/ui/src/LoadGate";
import { a11yProblems } from "./a11y";

const E = (status: number, code: string, message = "English", requestId?: string) => new ApiError(status, code, message, requestId);
const count = (h: string, re: RegExp) => (h.match(re) ?? []).length;
const texts = (h: string) => h.replace(/<[^>]+>/g, "|").split("|").map((x) => x.trim()).filter(Boolean);

test("404: the failure is said ONCE (no 'Không tìm thấy' title + 'Không tìm thấy…' message) and there is no 'Thử lại'", () => {
  const h = renderToStaticMarkup(<ErrorState error={E(404, "PROJECT_NOT_FOUND")} retry={() => {}}/>);
  assert.equal(count(h, /Không tìm thấy/g), 1, h);
  assert.doesNotMatch(h, /Thử lại/); assert.doesNotMatch(h, /<button/);
  assert.match(h, /bạn không có quyền xem/); // the detail the kind carries
});

test("a retry is offered only where retrying can change the outcome", () => {
  for (const [e, shown] of [[E(0, "NETWORK"), true], [E(500, "INTERNAL_ERROR", "x", "r1"), true], [E(503, "SCOPE_BUSY"), true], [E(409, "REVISION_CONFLICT"), true], [E(404, "X_NOT_FOUND"), false], [E(403, "FORBIDDEN"), false], [E(401, "AUTHENTICATION_REQUIRED"), false]] as const) {
    const h = renderToStaticMarkup(<ErrorState error={e} retry={() => {}}/>);
    assert.equal(/<button/.test(h), shown, `${e.status} ${e.code}`);
  }
  assert.equal(retryHelps(stateOf(E(404, "x"))), false); assert.equal(retryHelps(stateOf(E(0, "NETWORK"))), true);
  assert.match(renderToStaticMarkup(<ErrorState error={E(409, "REVISION_CONFLICT")} retry={() => {}}/>), />Tải lại</); // a conflict is "reload", not "try again"
  assert.doesNotMatch(renderToStaticMarkup(<ErrorState error={E(500, "INTERNAL_ERROR")}/>), /<button/); // no handler, no button
});

test("a message that only repeats the kind's own sentence is not printed twice; a specific one is", () => {
  const generic = renderToStaticMarkup(<ErrorState error={E(403, "FORBIDDEN")}/>);
  const t = texts(generic); assert.equal(new Set(t).size, t.length, `duplicate text in ${t.join(" | ")}`);
  const specific = renderToStaticMarkup(<ErrorState error={E(409, "LAST_ADMIN")}/>);
  assert.match(specific, /ít nhất một quản trị viên/);
});

test("the reference code is shown for server failures (and selectable), not for plain refusals; the stack / raw message is never shown", () => {
  const h = renderToStaticMarkup(<ErrorState error={E(500, "INTERNAL_ERROR", "NullPointerException at Foo.kt:12", "req-42")}/>);
  assert.match(h, /Mã tham chiếu: <code>req-42<\/code>/); assert.doesNotMatch(h, /NullPointer|Foo\.kt/);
  assert.doesNotMatch(renderToStaticMarkup(<ErrorState error={E(404, "X_NOT_FOUND", "x", "req-9")}/>), /req-9/);
  assert.doesNotMatch(renderToStaticMarkup(<ErrorState error={new SyntaxError("Unexpected token '<'")}/>), /Unexpected/);
});

test("M-084: StateView / ErrorState take a heading level: a page-level state is the page's h1, inside a card it is an h2, a compact state has no heading", () => {
  assert.match(renderToStaticMarkup(<StateView kind="loading" level={1}/>), /<h1>Đang tải…<\/h1>/);
  assert.match(renderToStaticMarkup(<StateView kind="error"/>), /<h2>Đã có lỗi xảy ra<\/h2>/);
  assert.match(renderToStaticMarkup(<ErrorState error={E(404, "x")} level={1}/>), /<h1>Không tìm thấy<\/h1>/);
  const c = renderToStaticMarkup(<ErrorState error={E(0, "NETWORK")} compact retry={() => {}}/>);
  assert.doesNotMatch(c, /<h[12]/); assert.match(c, /class="stateView state-network compact"/);
});

test("StateView roles: loading is a polite status, errors are alerts, empty is neither (it is content, not an event)", () => {
  assert.match(renderToStaticMarkup(<StateView kind="loading"/>), /role="status"/);
  assert.match(renderToStaticMarkup(<StateView kind="error"/>), /role="alert"/);
  assert.doesNotMatch(renderToStaticMarkup(<StateView kind="empty"/>), /role=/);
});

const load = <T,>(o: Partial<{ data: T | null; error: unknown; loading: boolean }>) => ({ data: null as T | null, error: null as unknown, loading: false, reload: () => {}, ...o });

test("LoadGate ladder: loading → says WHAT loads; error with nothing to show → ErrorState + retry; empty → empty state; ready → children", () => {
  const rows = (s: ReturnType<typeof load<string[]>>, extra = {}) => renderToStaticMarkup(<LoadGate load={s} label="danh sách người dùng" isEmpty={(d) => d.length === 0} empty={{ title: "Chưa có người dùng", detail: <p>Thêm người đầu tiên.</p> }} {...extra}>{(d) => <ul>{d.map((x) => <li key={x}>{x}</li>)}</ul>}</LoadGate>);
  const l = rows(load<string[]>({ loading: true })); assert.match(l, /role="status"/); assert.match(l, /Đang tải danh sách người dùng…/);
  const e = rows(load<string[]>({ error: E(500, "INTERNAL_ERROR", "x", "r7") })); assert.match(e, /role="alert"/); assert.match(e, /<button/); assert.match(e, /r7/); assert.doesNotMatch(e, /<ul>/);
  const m = rows(load<string[]>({ data: [] })); assert.match(m, /Chưa có người dùng/); assert.match(m, /Thêm người đầu tiên/); assert.doesNotMatch(m, /<ul>/);
  const r = rows(load<string[]>({ data: ["An", "Bình"] })); assert.match(r, /<li>An<\/li><li>Bình<\/li>/); assert.doesNotMatch(r, /role=/);
});

test("LoadGate never leaves a spinner on a failed load (the M-056 defect): loading=false + error is an error state, whatever the data was", () => {
  const h = renderToStaticMarkup(<LoadGate load={load<number>({ error: E(0, "NETWORK"), loading: false })} label="số liệu">{(n) => <b>{n}</b>}</LoadGate>);
  assert.doesNotMatch(h, /spinner|Đang tải/); assert.match(h, /Không kết nối được máy chủ/);
});

test("LoadGate: a failed REFRESH keeps the last good data on screen and adds a banner with a retry", () => {
  const h = renderToStaticMarkup(<LoadGate load={load<string[]>({ data: ["An"], error: E(500, "INTERNAL_ERROR", "x", "r8") })}>{(d) => <p>{d[0]}</p>}</LoadGate>);
  assert.match(h, /<p>An<\/p>/); assert.match(h, /xp-stateBanner/); assert.match(h, /đang hiển thị bản đã tải trước đó/); assert.match(h, /r8/); assert.match(h, /Thử lại/);
  const nf = renderToStaticMarkup(<StaleBanner error={E(404, "X_NOT_FOUND")} onRetry={() => {}}/>); assert.doesNotMatch(nf, /<button/);
});

test("LoadGate level / compact reach the state (page-level h1; a KPI gets a one-line state without a heading)", () => {
  assert.match(renderToStaticMarkup(<LoadGate load={load<number>({ loading: true })} level={1}>{() => null}</LoadGate>), /<h1>/);
  const k = renderToStaticMarkup(<LoadGate load={load<number>({ error: E(500, "INTERNAL_ERROR") })} compact>{() => null}</LoadGate>);
  assert.doesNotMatch(k, /<h[12]/); assert.match(k, /compact/);
});

test("a11y of every state markup", () => {
  const samples = [<ErrorState key="a" error={E(404, "x")} level={1}/>, <ErrorState key="b" error={E(500, "INTERNAL_ERROR", "x", "r")} retry={() => {}}/>, <StateView key="c" kind="empty" title="Trống" action={<button className="btn">Thêm</button>}/>, <StateView key="d" kind="loading"/>];
  for (const s of samples) assert.deepEqual(a11yProblems(renderToStaticMarkup(s)), []);
});

test("M-084 adoption (shell level): a state that IS the whole page (portal Suspense fallback, splash, unknown-route page) renders its title as the page h1", () => {
  const files = ["apps/studio/app/entry.tsx", "apps/platform/app/entry.tsx", "apps/admin/app/entry.tsx", "packages/auth/src/PortalApp.tsx", "components/app/AppEntry.tsx"];
  for (const f of files) {
    const src = readFileSync(`${process.cwd()}/${f}`, "utf8");
    const states = src.match(/<StateView\b[^>]*>/g) ?? [];
    assert.ok(states.length > 0, f);
    for (const s of states) assert.match(s, /level=\{1\}/, `${f}: ${s}`);
  }
  assert.match(renderToStaticMarkup(<StateView level={1} kind="loading"/>), /<h1>Đang tải…<\/h1>/);
});
