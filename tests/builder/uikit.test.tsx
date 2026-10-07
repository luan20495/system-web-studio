// @class: unit — shared UI parts of the AI provider polish (packages/ui): ProviderLogo, Switch, Picker, ModalHeader (server-side render, a11y, no external assets)
import test from "node:test";
import assert from "node:assert/strict";
import { renderToStaticMarkup } from "react-dom/server";
import { ModalHeader } from "../../packages/ui/src/ModalHeader";
import { Picker } from "../../packages/ui/src/Picker";
import { ProviderLogo, hasBrandLogo } from "../../packages/ui/src/ProviderLogo";
import { Switch } from "../../packages/ui/src/Switch";
import { Server } from "../../packages/ui/src/icons";
import { a11yProblems } from "./a11y";

test("ProviderLogo: brand marks only where Simple Icons has one (OpenRouter, Anthropic, Gemini); OpenAI and the generic kinds get a Lucide icon; always inline SVG, decorative, no <img>, no URL", () => {
  for (const k of ["OPENROUTER", "ANTHROPIC", "GEMINI"]) { const h = renderToStaticMarkup(<ProviderLogo kind={k}/>); assert.match(h, /data-brand="brand"/); assert.match(h, /<svg[^>]*><path d="/); assert.equal(hasBrandLogo(k), true); }
  for (const k of ["OPENAI", "OPENAI_COMPATIBLE", "LOCAL", "UNKNOWN"]) { const h = renderToStaticMarkup(<ProviderLogo kind={k}/>); assert.match(h, /data-brand="generic"/); assert.equal(hasBrandLogo(k), false); }
  const all = ["OPENROUTER", "ANTHROPIC", "GEMINI", "OPENAI", "LOCAL"].map((k) => renderToStaticMarkup(<ProviderLogo kind={k}/>)).join("");
  assert.doesNotMatch(all, /<img|\b(?:src|href)=|url\(/i);   // the SVG namespace attribute is not a fetch assert.match(all, /aria-hidden="true"/);
});
test("Switch: a real role=switch with aria-checked, labelled by its text, hint shown, disabled respected", () => {
  const on = renderToStaticMarkup(<Switch checked onChange={() => undefined} label="Bật nhà cung cấp này" hint="Đang bật"/>);
  assert.match(on, /role="switch"/); assert.match(on, /aria-checked="true"/); assert.match(on, /<label[^>]*>Bật nhà cung cấp này<\/label>/); assert.deepEqual(a11yProblems(on), []);
  const off = renderToStaticMarkup(<Switch checked={false} disabled onChange={() => undefined} label="x"/>);
  assert.match(off, /aria-checked="false"/); assert.match(off, /disabled=""/);
});
test("Picker: closed state is a combobox button + a hidden native select mirror (same label) with every option; ModalHeader has an icon tile, title and subtitle", () => {
  const h = renderToStaticMarkup(<Picker label="Loại" buttonLabel="Nhà cung cấp" value="B" options={[{ value: "A", label: "Alpha", hint: "a" }, { value: "B", label: "Beta" }]} onChange={() => undefined}/>);
  assert.match(h, /role="combobox"/); assert.match(h, /aria-expanded="false"/); assert.match(h, /aria-label="Nhà cung cấp: Beta"/); assert.match(h, /<select[^>]*class="srOnly"/); assert.equal((h.match(/<option/g) ?? []).length, 2); assert.match(h, /<label[^>]*>Loại<\/label>/);
  const m = renderToStaticMarkup(<ModalHeader icon={<Server/>} title="Thêm nhà cung cấp" subtitle="Kết nối"/>);
  assert.match(m, /xp-headIcon/); assert.match(m, /<h2>Thêm nhà cung cấp<\/h2>/); assert.match(m, /<p>Kết nối<\/p>/);
});
