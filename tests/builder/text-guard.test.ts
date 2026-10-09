// @class: unit — pure logic / server-side render of components; no browser, no network
/**
 * Text guards (M-060 / M-061 / M-062 / M-063 / M-064): RATCHET ONLY. Each rule counts occurrences in the user-visible strings of the portals (tests/builder/text-extract.ts) and may not
 * go ABOVE the number recorded here. The numbers only go down: when you fix strings, lower the number in the same commit (the test tells you the new value). A NEW offence anywhere makes
 * the count rise and fails with the file:line of every current offender so it is easy to find yours.
 *
 * The rules and the wording they protect: docs/parallel/c5/audit/S3-glossary.md. The library to use instead: packages/i18n (BRAND, ROLE_LABEL, the enum label maps, labelOf).
 */
import test from "node:test";
import assert from "node:assert/strict";
import { scan, type Found } from "./text-extract";
import { BRAND } from "../../packages/i18n/src/brand";
import * as labels from "../../packages/i18n/src/labels";
import { ROLE_CODES, ROLE_LABEL, ROLE_HINT, roleName, WORKSPACE_ROLE_ORDER } from "../../packages/i18n/src/roles";
import { labelOf, labelKeys, onUnmappedLabel, NEUTRAL_LABEL } from "../../packages/i18n/src/text";
import { ACTION_LABEL, PORTAL_TEXT } from "../../packages/i18n/src/index";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// the library itself defines the vocabulary (it must be able to name the legacy words in `legacyNames`); tests are not scanned
const result = scan(["features", "apps", "packages"], (rel) => rel === "packages/i18n/src/brand.ts");
const strings = result.strings;

type Rule = { id: string; why: string; re: RegExp; /** only strings that are Vietnamese text (a code inside an English / technical string is not a leak) */ vi?: boolean; baseline: number };
// BASELINES: measured on agent/c5-web 7bff844 + this branch (2026-10-09). Lower them as strings are fixed; never raise one.
const RULES: Rule[] = [
  { id: "internal-constant", why: "an env / enum / flag constant inside user-visible Vietnamese text (OPENROUTER_API_KEY, SCIM_TOKEN, APP_PUBLISH, REFRESH_QUERY...) (M-060)", re: /\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\b/, vi: true, baseline: 15 },
  { id: "internal-term", why: "an implementation word shown to a person: backend, CSRF, sandbox, registry, MinIO, Forgejo, DataGateway, Page Schema, ViewModel, pointerVersion, apiBase, workflow_run, lockfile, runner (M-060)", re: /\b(backend|CSRF|sandbox|registry|MinIO|Forgejo|DataGateway|Page Schema|ViewModel|pointerVersion|apiBase|workflow_run|component-metadata|data-xw-state|lockfile|runner|Dead-letter)\b/i, baseline: 72 },
  { id: "legacy-name", why: "a product / portal name that is not in BRAND: AI Software Factory, Company Builder Studio, Admin Console, Builder Studio (M-063)", re: new RegExp(`\\b(${BRAND.legacyNames.join("|")})\\b`), baseline: 11 },
  { id: "english-nav-word", why: "English left in a Vietnamese UI: Components, Templates, Packages, Registry, AI Control, Mock, Demo, Self-host, Project ID, Request ID, Workspace ID (M-063)", re: /\b(Components|Templates|Packages|Registry|AI Control|Mock|Demo|Self-host|Project ID|Request ID|Workspace ID)\b/, baseline: 22 },
  { id: "term-project", why: "'project' / 'dự án' where the glossary says 'ứng dụng' (M-062)", re: /\b(project|dự án)\b/i, vi: true, baseline: 14 },
  { id: "term-workspace-long", why: "'không gian làm việc' where the glossary says 'workspace' (M-062)", re: /không gian làm việc/i, baseline: 14 },
  { id: "term-tenant", why: "'tenant' shown to people: it is 'công ty' (M-062)", re: /\btenant\b/i, vi: true, baseline: 2 },
  { id: "term-model", why: "'model' where the glossary says 'mô hình' (M-062)", re: /\bmodel\b/, vi: true, baseline: 10 },
  { id: "term-component", why: "'component' where the glossary says 'thành phần' (M-062)", re: /\bcomponents?\b/i, vi: true, baseline: 28 },
  { id: "term-template", why: "'template' where the glossary says 'mẫu' (M-062)", re: /\btemplates?\b/i, vi: true, baseline: 2 },
  { id: "tone-old-style", why: "old tone-mark placement xoá huỷ tuỳ khoá hoà: the product standard is the modern style xóa hủy tùy khóa hòa (M-062)", re: /xoá|huỷ|tuỳ|khoá|hoà/i, baseline: 57 },
  { id: "revision-counter", why: "'revision N' / 'r{n}': an internal concurrency counter shown to people (M-060)", re: /\brevision\b/i, vi: true, baseline: 1 }
];

const count = (r: Rule): Found[] => strings.filter((s) => r.re.test(s.text) && (!r.vi || HAS(s.text)));
const HAS = (t: string) => /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i.test(t);

for (const r of RULES) {
  test(`ratchet: ${r.id} may not grow — ${r.why}`, () => {
    const found = count(r);
    if (found.length > r.baseline) assert.fail(`${r.id}: ${found.length} > baseline ${r.baseline}. Offenders:\n` + found.map((f) => `  ${f.file}:${f.line}  ${f.text.slice(0, 110)}`).join("\n"));
    if (found.length < r.baseline) console.log(`note: ${r.id} is ${found.length}, baseline ${r.baseline}: lower the baseline`);
  });
}

test("ratchet: native confirm / prompt / alert calls may not grow (use confirm() / prompt() / toast from @xweb/ui)", () => {
  const BASELINE = 12;
  const d = result.dialogs;
  if (d.length > BASELINE) assert.fail(`native dialogs: ${d.length} > ${BASELINE}\n` + d.map((f) => `  ${f.file}:${f.line}  ${f.text}`).join("\n"));
  if (d.length < BASELINE) console.log(`note: native dialogs ${d.length}, baseline ${BASELINE}`);
});

test("the extractor really sees the product text (a guard that scans nothing guards nothing)", () => {
  assert.ok(strings.length > 1500, `only ${strings.length} strings`);
  assert.ok(strings.some((s) => s.file.startsWith("features/admin/") && /Người dùng/.test(s.text)));
  assert.ok(strings.some((s) => s.file.startsWith("features/studio/") && /Xuất bản/.test(s.text)));
  assert.ok(strings.some((s) => s.file.startsWith("packages/ui/") && /Đang tải/.test(s.text)));
  assert.ok(!strings.some((s) => s.file.startsWith("tests/")), "tests are not scanned");
});

// ---------------------------------------------------------------------------------------------------------------------------------------------- the library itself
test("BRAND: the portal names agree with PORTAL_LABEL (@xweb/permissions) and PORTAL_TEXT; no legacy name is a portal name", () => {
  const perm = readFileSync(join(process.cwd(), "packages/permissions/src/index.ts"), "utf8");
  const m = /PORTAL_LABEL: Record<PortalId, string> = \{ platform: "([^"]+)", admin: "([^"]+)", studio: "([^"]+)" \}/.exec(perm); assert.ok(m, "PORTAL_LABEL shape");
  assert.deepEqual([m![1], m![2], m![3]], [BRAND.portal.platform, BRAND.portal.admin, BRAND.portal.studio]);
  assert.deepEqual([PORTAL_TEXT.platform.title, PORTAL_TEXT.admin.title, PORTAL_TEXT.studio.title], [BRAND.portal.platform, BRAND.portal.admin, BRAND.portal.studio]);
  for (const n of Object.values(BRAND.portal)) assert.ok(!BRAND.legacyNames.includes(n as never));
});

test("ROLE_LABEL: every role code has a label and a hint, labels are unique, the old competing names are gone", () => {
  for (const c of ROLE_CODES) { assert.ok(ROLE_LABEL[c], c); assert.ok(ROLE_HINT[c], c); }
  assert.equal(new Set(Object.values(ROLE_LABEL)).size, ROLE_CODES.length);
  assert.deepEqual(Object.keys(ROLE_LABEL).sort(), [...ROLE_CODES].sort());
  for (const old of ["Biên tập viên", "Biên tập", "Chỉ xem", "Xuất bản", "Quản trị không gian làm việc"]) assert.ok(!Object.values(ROLE_LABEL).includes(old as never), old);
  assert.equal(roleName("EDITOR"), "Người chỉnh sửa"); assert.equal(roleName("WORKSPACE_ADMIN"), "Quản trị workspace");
  assert.ok(WORKSPACE_ROLE_ORDER.every((r) => ROLE_CODES.includes(r)));
});

test("labelOf: an unknown, empty or inherited key is never printed raw; it is neutral and reported once", () => {
  const seen: string[] = []; onUnmappedLabel((i) => seen.push(`${i.map}:${i.value}`));
  assert.equal(roleName("SOMETHING_NEW"), NEUTRAL_LABEL); assert.equal(roleName("SOMETHING_NEW"), NEUTRAL_LABEL);
  assert.equal(roleName(""), NEUTRAL_LABEL); assert.equal(roleName(null), NEUTRAL_LABEL); assert.equal(roleName(undefined), NEUTRAL_LABEL);
  assert.equal(labelOf({ A: "a" }, "toString"), NEUTRAL_LABEL); assert.equal(labelOf({ A: "a" }, "__proto__"), NEUTRAL_LABEL);
  assert.equal(labelOf({ A: "a" }, "x", "—"), "—");
  assert.deepEqual(seen.filter((s) => s === "role:SOMETHING_NEW"), ["role:SOMETHING_NEW"], "reported once");
  onUnmappedLabel(() => { throw new Error("logger bug"); }); assert.doesNotThrow(() => roleName("ANOTHER_NEW")); onUnmappedLabel(null);
  assert.deepEqual(labelKeys({ A: "a", B: "b" }), ["A", "B"]);
});

test("every enum label map: no empty label, no label that IS its code, Vietnamese (or an allow-listed brand / loan word), no English-only words", () => {
  const maps = Object.entries(labels).filter(([k, v]) => /_LABEL$|_TEXT$|_PROGRESS$/.test(k) && typeof v === "object") as [string, Record<string, string>][];
  assert.ok(maps.length >= 20, `only ${maps.length} maps`);
  const ALLOWED_ASCII = new Set(["Website", "Workflow", "OpenRouter", "OpenAI", "Anthropic", "Gemini", "Workspace", "Token", "Build"]);
  for (const [name, map] of maps) for (const [code, label] of Object.entries(map)) {
    assert.ok(label.trim().length > 0, `${name}.${code} empty`);
    if (!ALLOWED_ASCII.has(label)) assert.notEqual(label.toUpperCase().replace(/[^A-Z]/g, ""), code.toUpperCase().replace(/[^A-Z]/g, ""), `${name}.${code} shows its own code`);   // a loan word kept on purpose (Workflow, Website...) may equal its code
    assert.ok(HAS(label) || ALLOWED_ASCII.has(label) || /\b(workspace|Workflow|Website)\b/i.test(label) || label.length <= 6, `${name}.${code} = "${label}" does not look Vietnamese`);
    assert.doesNotMatch(label, /xoá|huỷ|tuỳ|khoá|hoà/i, `${name}.${code} uses the old tone-mark style`);
  }
});

test("every enum has a label for every member of its own list (the const arrays the UI and the tests share)", () => {
  const pairs: [readonly string[], Record<string, string>][] = [
    [labels.ACCOUNT_STATES, labels.ACCOUNT_STATE_LABEL], [labels.VISIBILITIES, labels.VISIBILITY_LABEL], [labels.VERSION_KINDS, labels.VERSION_KIND_LABEL], [labels.REPO_STATES, labels.REPO_STATE_LABEL],
    [labels.PROMPT_OUTCOMES, labels.PROMPT_OUTCOME_LABEL], [labels.LIMIT_SCOPES, labels.LIMIT_SCOPE_LABEL], [labels.SEVERITIES, labels.SEVERITY_LABEL], [labels.HEALTH_STATUSES, labels.HEALTH_LABEL],
    [labels.DRILL_RESULTS, labels.DRILL_RESULT_LABEL], [labels.BACKUP_STATES, labels.BACKUP_STATE_LABEL], [labels.ENVIRONMENTS, labels.ENVIRONMENT_LABEL], [labels.LIBRARY_STATUSES, labels.LIBRARY_STATUS_LABEL],
    [labels.PACKAGE_STATUSES, labels.PACKAGE_STATUS_LABEL], [labels.CODE_CHANGE_STATUSES, labels.CODE_CHANGE_STATUS_LABEL], [labels.SERVER_APP_STATUSES, labels.SERVER_APP_STATUS_LABEL],
    [labels.PARAM_TYPES, labels.PARAM_TYPE_LABEL], [labels.DATA_OPERATIONS, labels.DATA_OPERATION_LABEL], [labels.APPROVER_KINDS, labels.APPROVER_KIND_LABEL]
  ];
  for (const [list, map] of pairs) assert.deepEqual(Object.keys(map).sort(), [...list].sort());
  // the maps typed from the backend contract: every member of the contract union is present (the `satisfies` makes the typecheck fail first; this pins the spelling of the keys)
  assert.deepEqual(Object.keys(labels.TENANT_STATUS_LABEL).sort(), ["ACTIVE", "DELETED", "SUSPENDED"]);
  assert.deepEqual(Object.keys(labels.APP_KIND_LABEL).sort(), ["DASHBOARD", "INTERNAL_TOOL", "SERVER_APP", "SOURCE_WEB_APP", "WEBSITE_STATIC", "WORKFLOW"]);
  assert.deepEqual(Object.keys(labels.DEPLOYMENT_STATUS_TEXT).sort(), ["BUILDING", "DEPLOYING", "FAILED", "POLICY_CHECK", "QUEUED", "ROLLED_BACK", "ROLLING_BACK", "RUNNING", "SECURITY_CHECK"]);
  assert.deepEqual(Object.keys(labels.RELEASE_OPERATION_LABEL).sort(), ["PUBLISH", "ROLLBACK", "UNPUBLISH"]);
  assert.deepEqual(Object.keys(labels.AI_PROVIDER_KIND_LABEL).sort(), ["ANTHROPIC", "GEMINI", "LOCAL", "OPENAI", "OPENAI_COMPATIBLE", "OPENROUTER"]);
});

test("the lookups: known codes read in Vietnamese, unknown ones are 'Khác', never the code", () => {
  assert.equal(labels.severityLabel("CRITICAL"), "Nghiêm trọng"); assert.equal(labels.drillResultLabel("PASS"), "Đạt"); assert.equal(labels.promptOutcomeLabel("BAD_OUTPUT"), "Trả lời không dùng được");
  assert.equal(labels.visibilityLabel("PUBLIC"), "Công khai"); assert.equal(labels.tenantStatusLabel("SUSPENDED"), "Tạm khóa");
  for (const f of [labels.severityLabel, labels.drillResultLabel, labels.promptOutcomeLabel, labels.visibilityLabel, labels.healthLabel, labels.appKindLabel, labels.deploymentStatusText]) assert.equal(f("NEW_UNKNOWN_CODE"), NEUTRAL_LABEL);
  assert.equal(ACTION_LABEL.USER_DISABLED, "Khóa tài khoản");
});

test("the extractor: reads JSX text, label attributes and Vietnamese literals; skips module specifiers, property names and types; tells native dialogs from the shared ones", () => {
  const r = scan(["tests/builder/fixtures-text"]);
  const t = r.strings.map((s) => s.text);
  assert.ok(t.includes("Xóa ứng dụng này?") && t.includes("Văn bản JSX") && t.includes("Tooltip") && t.includes("Nhãn đọc to") && t.includes("Xóa shared dialog?"));
  assert.ok(t.some((x) => x === "Chưa có ${…} OPENROUTER_API_KEY"), "template literals are read with ${…} holes");
  assert.ok(!t.includes("khóa-thuộc-tính") && !t.some((x) => /Không hiển thị/.test(x)) && !t.some((x) => /dialogs|labels/.test(x)), "property names, types and import paths are not user text");
  assert.deepEqual(r.dialogs.map((d) => d.text.slice(0, 22)), ['window.confirm("Native', 'prompt("Native hai")']);
  const leaked = RULES.find((x) => x.id === "internal-constant")!;
  assert.equal(r.strings.filter((s) => leaked.re.test(s.text)).length, 1);
});

test("glossary section 9: the guided data flow (dataWording.ts, S1) never uses the words the glossary keeps out of the main flow", async () => {
  const { DATA_WORDS } = await import("../../features/studio/builder/core/dataWording");
  const out: string[] = [];
  const walk = (v: unknown): void => {
    if (typeof v === "string") out.push(v);
    else if (typeof v === "function") { for (const arg of [["A", "B"], [true], [false]]) { try { const r = (v as (...a: unknown[]) => unknown)(...arg); if (typeof r === "string") out.push(r); } catch { /* wrong arity: skip */ } } }
    else if (v && typeof v === "object") Object.values(v).forEach(walk);
  };
  walk(DATA_WORDS);
  assert.ok(out.length > 30, `only ${out.length} sentences read`);
  for (const t of out) assert.doesNotMatch(t, /\b(khe|ViewModel|ánh xạ|binding|slot)\b/i, t);
});
