// @class: unit — pure logic / server-side render of components; no browser, no network
/** M-024 / M-028 / M-029 / M-030 / M-031 / M-032 (+ the session-change seam of the load cache): markup and pure logic of the shared widgets. Keyboard behaviour: tests/browser/ui-widgets.spec.mjs. */
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { Tabs, TabPanel, nextTabIndex, panelId, tabId } from "../../packages/ui/src/Tabs";
import { RadioGroup } from "../../packages/ui/src/RadioGroup";
import { DisclosureRow } from "../../packages/ui/src/DisclosureRow";
import { ReasonButton } from "../../packages/ui/src/ReasonButton";
import { Pill, PILL_TONE, pillTone } from "../../packages/ui/src/Pill";
import { Button, buttonClass } from "../../packages/ui/src/Button";
import { Picker } from "../../packages/ui/src/Picker";
import { onSessionChange, sessionChanged, resetCsrf } from "../../packages/api-client/src/core";
import { a11yProblems } from "./a11y";

test("nextTabIndex: arrows wrap and skip disabled tabs, Home / End go to the first / last ENABLED tab, other keys are ignored", () => {
  const en = [true, true, false, true];
  assert.equal(nextTabIndex("ArrowRight", 0, en), 1); assert.equal(nextTabIndex("ArrowRight", 1, en), 3); assert.equal(nextTabIndex("ArrowRight", 3, en), 0);
  assert.equal(nextTabIndex("ArrowLeft", 0, en), 3); assert.equal(nextTabIndex("ArrowLeft", 3, en), 1);
  assert.equal(nextTabIndex("Home", 3, en), 0); assert.equal(nextTabIndex("End", 0, en), 3); assert.equal(nextTabIndex("End", 0, [true, false]), 0);
  assert.equal(nextTabIndex("Enter", 0, en), null); assert.equal(nextTabIndex("ArrowDown", 0, en), null); assert.equal(nextTabIndex("ArrowRight", 0, [false, false]), null);
  assert.equal(nextTabIndex("ArrowLeft", 0, en, true), 1); // rtl swaps the arrows
});

test("Tabs markup: tablist + tabs + one tab stop; aria-controls only on the selected tab and only when panels exist", () => {
  const tabs = [{ value: "a", label: "A" }, { value: "b", label: "B", count: 3 }, { value: "c", label: "C", disabled: true }];
  const h = renderToStaticMarkup(<Tabs label="Mục" idBase="t" value="b" onChange={() => {}} tabs={tabs}/>);
  assert.match(h, /role="tablist" aria-label="Mục"/); assert.equal((h.match(/role="tab"/g) ?? []).length, 3);
  assert.match(h, new RegExp(`id="${tabId("t", "b")}"[^>]*aria-selected="true"[^>]*aria-controls="${panelId("t", "b")}"[^>]*tabindex="0"`));
  assert.equal((h.match(/aria-controls/g) ?? []).length, 1); assert.equal((h.match(/tabindex="-1"/g) ?? []).length, 2);
  assert.match(h, /B<span class="xp-tabCount"> <span class="srOnly">, <\/span>3<\/span>/);
  assert.doesNotMatch(renderToStaticMarkup(<Tabs label="Lọc" idBase="t" value="a" onChange={() => {}} tabs={tabs} panels={false}/>), /aria-controls/);
  // when the selected tab is disabled / unknown the first enabled tab is the tab stop (the list is never unreachable)
  assert.match(renderToStaticMarkup(<Tabs label="x" idBase="t" value="zzz" onChange={() => {}} tabs={tabs}/>), /id="t-tab-a"[^>]*tabindex="0"/);
  const p = renderToStaticMarkup(<TabPanel idBase="t" value="b">x</TabPanel>);
  assert.match(p, new RegExp(`role="tabpanel" id="${panelId("t", "b")}" aria-labelledby="${tabId("t", "b")}" tabindex="0"`));
  assert.deepEqual(a11yProblems(h), []);
});

test("RadioGroup: a named radiogroup of NATIVE radios (one tab stop, arrows, checked state), the choice is not class-only", () => {
  const h = renderToStaticMarkup(<RadioGroup legend="Ai xem được?" value="PUBLIC" onChange={() => {}} options={[{ value: "PRIVATE", label: "Riêng tư", hint: "Chỉ người được mời" }, { value: "PUBLIC", label: "Công khai" }]}/>);
  assert.match(h, /<fieldset role="radiogroup"/); assert.match(h, /<legend class="xp-radioLegend">Ai xem được\?<\/legend>/);
  assert.equal((h.match(/type="radio"/g) ?? []).length, 2); assert.match(h, /checked="" value="PUBLIC"/); assert.doesNotMatch(h, /checked="" value="PRIVATE"/);
  assert.equal(new Set(Array.from(h.matchAll(/name="([^"]+)"/g), (m) => m[1])).size, 1); // one name = one group
  assert.match(h, /xp-radioCard selected/); assert.doesNotMatch(h, /role="tab"|<button/);
  assert.match(renderToStaticMarkup(<RadioGroup legend="Q" hideLegend value="a" onChange={() => {}} options={[{ value: "a", label: "A" }]}/>), /<legend class="srOnly">/);
  assert.deepEqual(a11yProblems(h), []);
});

test("DisclosureRow: a real button with aria-expanded; aria-controls only while the detail row exists; the name says which row", () => {
  const closed = renderToStaticMarkup(<table><tbody><DisclosureRow label="Chi tiết sự kiện X" colSpan={3} cells={<td>x</td>} detail={<p>d</p>}/></tbody></table>);
  assert.match(closed, /<button type="button" class="xp-discBtn" aria-expanded="false">/); assert.doesNotMatch(closed, /aria-controls|xp-discDetail/); assert.match(closed, /Chi tiết sự kiện X/);
  const open = renderToStaticMarkup(<table><tbody><DisclosureRow label="L" colSpan={3} cells={<td>x</td>} detail={<p>d</p>} defaultOpen/></tbody></table>);
  const id = /aria-controls="([^"]+)"/.exec(open)?.[1]; assert.ok(id); assert.match(open, new RegExp(`<tr id="${id!.replace(/[:]/g, "\\:")}" class="xp-discDetail"><td colSpan="3"><p>d</p>`)); assert.match(open, /aria-expanded="true"/);
  assert.deepEqual(a11yProblems(open), []);
});

test("ReasonButton: unavailable = aria-disabled + visible reason + aria-describedby, never `disabled` / title-only; no reason = native disabled", () => {
  const off = renderToStaticMarkup(<ReasonButton className="btn" unavailable reason="Bật mô hình trước.">Đặt mặc định</ReasonButton>);
  assert.match(off, /aria-disabled="true"/); assert.doesNotMatch(off, / disabled=|title=/);
  const id = /aria-describedby="([^"]+)"/.exec(off)?.[1]; assert.ok(id); assert.match(off, new RegExp(`<small id="${id}" class="xp-reason">Bật mô hình trước\\.</small>`));
  const on = renderToStaticMarkup(<ReasonButton className="btn">Lưu</ReasonButton>); assert.doesNotMatch(on, /aria-|xp-reason<|<small/);
  assert.match(renderToStaticMarkup(<ReasonButton unavailable>Không lý do</ReasonButton>), / disabled=""/);
  const busy = renderToStaticMarkup(<ReasonButton busy>Đang lưu</ReasonButton>); assert.match(busy, /aria-busy="true"/); assert.doesNotMatch(busy, /<small|aria-disabled/);
  const merged = renderToStaticMarkup(<ReasonButton unavailable reason="Lý do" aria-describedby="x">B</ReasonButton>); assert.match(merged, /aria-describedby="[^"]+ x"/);
  assert.deepEqual(a11yProblems(off), []);
});

test("Pill tones: risk / paid / pending / critical carry a warning tone (they were grey), tone overrides, unknown stays muted, every value is one of the 5 tones", () => {
  for (const [k, t] of Object.entries({ HIGH_RISK: "bad", PAID: "warn", AWAITING_REVIEW: "warn", CRITICAL: "bad", WARNING: "warn", PASS: "ok", SKIPPED: "muted", PENDING: "warn", FAILED: "bad" })) assert.equal(pillTone(k), t, k);
  assert.equal(pillTone("NOT_A_STATUS"), "muted");
  for (const t of Object.values(PILL_TONE)) assert.ok(["ok", "warn", "bad", "info", "muted"].includes(t));
  assert.match(renderToStaticMarkup(<Pill value="PAID" label="Trả phí"/>), /class="pill pill-warn">Trả phí/);
  assert.match(renderToStaticMarkup(<Pill value="PAID" tone="info" label="x"/>), /pill-info/);
});

test("Picker (M-024): aria-activedescendant belongs to the focused combobox, aria-controls exists only while the list does", () => {
  const h = renderToStaticMarkup(<Picker label="Loại" value="a" options={[{ value: "a", label: "A" }, { value: "b", label: "B" }]} onChange={() => {}}/>);
  assert.match(h, /role="combobox"/); assert.doesNotMatch(h, /aria-controls|aria-activedescendant/); assert.doesNotMatch(h, /role="listbox"/);
});

test("sessionChanged() resets the CSRF token and notifies listeners (the load cache is cleared on login / logout / 401); a throwing listener cannot break it; csrf refresh alone does not notify", () => {
  let n = 0; const off = onSessionChange(() => { n++; }); const bad = onSessionChange(() => { throw new Error("x"); });
  resetCsrf(); assert.equal(n, 0);
  assert.doesNotThrow(() => sessionChanged()); assert.equal(n, 1);
  off(); bad(); sessionChanged(); assert.equal(n, 1);
});

test("Button renders exactly the documented class vocabulary, type=button by default, busy = aria-busy", () => {
  assert.equal(buttonClass({}), "btn");
  assert.equal(buttonClass({ variant: "primary" }), "btn primary");
  assert.equal(buttonClass({ variant: "danger" }), "btn danger");
  assert.equal(buttonClass({ variant: "danger", filled: true }), "btn primary danger");
  assert.equal(buttonClass({ variant: "ghost", size: "sm", icon: true, block: true, className: "x" }), "btn ghost sm icon block x");
  assert.equal(renderToStaticMarkup(<Button>Lưu</Button>), '<button type="button" class="btn">Lưu</button>');
  assert.match(renderToStaticMarkup(<Button type="submit" variant="primary" size="sm">Gửi</Button>), /<button type="submit" class="btn primary sm">/);
  assert.match(renderToStaticMarkup(<Button busy>Đang lưu</Button>), /aria-busy="true"/);
  { const h = renderToStaticMarkup(<Button icon aria-label="Đóng">x</Button>); assert.match(h, /class="btn icon"/); assert.match(h, /aria-label="Đóng"/); }
});

