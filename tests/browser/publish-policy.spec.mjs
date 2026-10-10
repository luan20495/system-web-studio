// @class: harness — real Chromium on the publish dialog with an in-page FAKE of its calls that enforces the H-C2-07 table against a PERSISTED policy (no backend). Proves what the DIALOG does with 422 PUBLIC_DATA_NOT_APPROVED / 409 PUBLISH_POLICY_MISMATCH.
// NOT a backend E2E: the rule itself is C2's PublicDataApprovalTests; the real-backend proof is tests/e2e-real (publicdata / E2E-P0x) against a stack with app.publish-configs.enabled=true.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/publish-policy.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
import { harnessOrigin, launch, makeChecks } from "./lib/spec.mjs";
const AXE = require.resolve("axe-core/axe.min.js");
const ORIGIN = harnessOrigin();
const { check, finish } = makeChecks();
const errors = []; const browser = await launch();
const T = (p, id) => p.getByTestId(id);
async function open(q) {
  const p = await browser.newPage({ viewport: { width: 1000, height: 1100 } }); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  await p.goto(`${ORIGIN}/release.html?s=ok&${q}`); await T(p, "release-modal").waitFor(); await p.waitForTimeout(350); return p;
}
const calls = (p, n) => p.evaluate((x) => window.__rel.calls.filter((c) => c.name === x), n);
const policy = (p) => p.evaluate(() => window.__rel.policy);
const publicRadio = (p) => p.getByRole("radiogroup", { name: /Ai xem được website/ }).getByRole("radio", { name: /Công khai/ });
const privateRadio = (p) => p.getByRole("radiogroup", { name: /Ai xem được website/ }).getByRole("radio", { name: /Riêng tư/ });
const publishPublic = async (p, ack = true) => { await publicRadio(p).check(); if (ack) await T(p, "publish-ack").check(); await T(p, "publish").click(); await p.waitForTimeout(450); };
const axe = async (p, ctx) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async (c) => (await window.axe.run(c ? document.querySelector(c) : document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`), ctx ?? null); };

// A. approved policy + public data => publish allowed
{ const p = await open("draft=public&policy=public-approved");
  check("POL01 the dialog shows what the SERVER holds: stored policy 'công khai, đã phê duyệt dữ liệu công khai' (bản 3), read through GET publish-config", /đã phê duyệt dữ liệu công khai/.test(await T(p, "release-policy").innerText()) && (await T(p, "release-policy").getAttribute("data-approved")) === "true" && (await calls(p, "getConfig")).length >= 1);
  await publishPublic(p);
  check("A. approved policy + public data → the publish is accepted: ONE publish call, a deployment is shown, no refusal, no publish-config write", (await calls(p, "publish")).length === 1 && (await T(p, "deployment").count()) === 1 && (await T(p, "release-error").count()) === 0 && (await calls(p, "putConfig")).length === 0);
  await p.close(); }

// B. unapproved policy + public data => 422 handled; C. the local checkbox is NOT the approval
{ const p = await open("draft=public&policy=public-unapproved");
  check("POL02 the stored policy says 'chưa phê duyệt' (server state) while the local box is still empty", /chưa phê duyệt dữ liệu công khai/.test(await T(p, "release-policy").innerText()) && (await T(p, "release-policy").getAttribute("data-approved")) === "false");
  await publishPublic(p);
  const er = T(p, "release-error");
  check("B. unapproved policy + public data → 422 PUBLIC_DATA_NOT_APPROVED is handled in its OWN state: 'Cần phê duyệt công khai dữ liệu…', not the generic failure; the dialog says nothing was published", (await er.getAttribute("data-kind")) === "public-data-not-approved" && /Cần phê duyệt công khai dữ liệu/.test(await er.innerText()) && /Chưa có gì được xuất bản/.test(await er.innerText()) && !/Không thực hiện được/.test(await er.innerText()) && (await T(p, "deployment").count()) === 0);
  check("B2. it is ACTIONABLE: an approval box with its own acknowledgement and a 'Lưu phê duyệt trên máy chủ' button; NO blind 'Thử lại'", (await T(p, "release-approve-box").count()) === 1 && (await T(p, "release-approve").count()) === 1 && (await T(p, "release-retry").count()) === 0);
  const sent = await calls(p, "publish");
  check("C. the browser ticked the local box but NOTHING was persisted (no PUT publish-config, server policy unchanged) → the server still refused with 422; the publish call carries no approval argument", (await T(p, "publish-ack").isChecked()) && (await calls(p, "putConfig")).length === 0 && (await policy(p)).publicDataApproved === false && sent.length === 1 && sent[0].args.length === 3);
  await T(p, "publish").click().catch(() => undefined); await p.waitForTimeout(400);
  check("C2. publishing AGAIN without persisting the approval is refused again (the same deterministic 422): the local tick never becomes authority", (await calls(p, "publish")).length === 2 && (await T(p, "release-error").getAttribute("data-kind")) === "public-data-not-approved" && (await policy(p)).publicDataApproved === false);
  check("C3. each refused request used a NEW Idempotency-Key (a refusal rolls the key back on the server; a replayed key would be pointless)", (await calls(p, "publish")).length === 2 && (await calls(p, "publish"))[0].args[2] !== (await calls(p, "publish"))[1].args[2]);
  const v = await axe(p, "[data-testid=release-modal]"); check("B3. axe finds nothing in the dialog with the approval box", v.length === 0, v.join(","));
  await p.close(); }

// F. approval persisted through PUT publish-config → the next publish uses the server state
{ const p = await open("draft=public&policy=public-unapproved");
  await publishPublic(p);
  check("F0. the approve button stays unavailable (aria-disabled, with its reason) until the person ticks the approval's own box", (await T(p, "release-approve").getAttribute("aria-disabled")) === "true" && /Hãy tích xác nhận/.test(await T(p, "release-approve-box").innerText()));
  await T(p, "release-approve-ack").check(); await T(p, "release-approve").click(); await p.waitForTimeout(450);
  const put = await calls(p, "putConfig"); const get = await calls(p, "getConfig");
  check("F. ONE PUT publish-config: the STORED mode / auth / cache, visibility PUBLIC, acknowledgePublicData true, and the revision just read (3) — after a fresh GET, never a cached copy", put.length === 1 && JSON.stringify(put[0].args[0]) === JSON.stringify({ mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, cacheSeconds: null, acknowledgePublicData: true, expectedRevision: 3 }) && get.length >= 2, JSON.stringify(put[0]?.args));
  check("F2. the server now holds the approval (revision 4); the dialog says it was SAVED and tells the person to publish again; the refusal is gone; no deployment was started by the approval", (await policy(p)).publicDataApproved === true && (await policy(p)).revision === 4 && /Đã lưu phê duyệt công khai trên máy chủ \(bản 4\)/.test(await T(p, "release-note").innerText()) && (await T(p, "release-error").count()) === 0 && (await T(p, "deployment").count()) === 0 && /đã phê duyệt dữ liệu công khai/.test(await T(p, "release-policy").innerText()));
  const before = (await calls(p, "publish")).length; await T(p, "publish").click(); await p.waitForTimeout(450);
  const pubs = await calls(p, "publish");
  check("F3. the next publish is ACCEPTED on the server state: one more publish call (same three arguments, no approval), with a key different from the refused one, and a deployment appears", pubs.length === before + 1 && pubs.at(-1).args.length === 3 && pubs.at(-1).args[2] !== pubs[0].args[2] && (await T(p, "deployment").count()) === 1 && (await T(p, "release-error").count()) === 0);
  await p.close(); }
{ const p = await open("draft=public&policy=public-unapproved");
  await publishPublic(p); await T(p, "release-approve-ack").check();
  await p.evaluate(() => window.__rel.set({ errors: { putConfig: { status: 409, code: "REVISION_CONFLICT" } } }));    // someone changed the policy between our read and our write
  await T(p, "release-approve").click(); await p.waitForTimeout(450);
  check("F4. a stale policy at approval time (REVISION_CONFLICT) is handled: said as 'đã thay đổi ở nơi khác … chưa được lưu', the policy is re-read, the approval box stays, and the server still has NO approval", /đã thay đổi ở nơi khác/.test(await T(p, "release-error").innerText()) && /chưa được lưu/.test(await T(p, "release-error").innerText()) && (await T(p, "release-approve-box").count()) === 1 && (await policy(p)).publicDataApproved === false && (await calls(p, "getConfig")).length >= 3 && (await T(p, "release-note").count()) === 0);
  await p.evaluate(() => window.__rel.set({ errors: { putConfig: { status: 403, code: "FORBIDDEN" } } })); await T(p, "release-approve").click(); await p.waitForTimeout(400);
  check("F5. a refusal of the PUT (403) is said in words; nothing is reported as saved", /không có quyền lưu phê duyệt/i.test(await T(p, "release-error").innerText()) && (await policy(p)).publicDataApproved === false);
  await p.close(); }

// D. stale / conflicting policy => 409 handled
{ const p = await open("draft=public&policy=private");
  check("POL03 the stored policy is PRIVATE and the dialog says so before anything is sent", /riêng tư\./.test(await T(p, "release-policy").innerText()));
  await publishPublic(p);
  const er = T(p, "release-error");
  check("D. a PUBLIC request against a stored non-public policy → 409 PUBLISH_POLICY_MISMATCH: 'Chính sách xuất bản đã thay đổi hoặc xung đột', nothing published, a reload of the policy is offered, NO retry", (await er.getAttribute("data-kind")) === "policy-mismatch" && /Chính sách xuất bản đã thay đổi hoặc xung đột/.test(await er.innerText()) && (await T(p, "policy-reload-error").count()) === 1 && (await T(p, "release-retry").count()) === 0 && (await T(p, "deployment").count()) === 0);
  await p.waitForTimeout(1500);
  check("D2. the dialog does NOT silently retry with its cached policy: still ONE publish call after waiting", (await calls(p, "publish")).length === 1);
  await p.evaluate(() => window.__rel.set({ policy: { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, cacheSeconds: null, publicDataApproved: false, revision: 5 } }));   // an administrator changed the policy meanwhile
  await T(p, "policy-reload-error").click(); await p.waitForTimeout(400);
  check("D3. 'Tải lại chính sách' reads the CURRENT server policy (now public, revision 5, not approved) and shows it", /bản 5/.test(await T(p, "release-policy-line").innerText()) && /công khai/.test(await T(p, "release-policy-line").innerText()) && (await calls(p, "publish")).length === 1);
  await p.close(); }

// E. non-public publish unchanged; G. no stored policy = legacy
{ const p = await open("draft=public&policy=public-unapproved");
  await privateRadio(p).check(); await T(p, "publish-ack").check(); await T(p, "publish").click(); await p.waitForTimeout(450);
  check("E. a PRIVATE publish is unchanged: accepted although the stored policy is unapproved and the draft has public queries; no refusal, no publish-config write", (await calls(p, "publish")).length === 1 && (await T(p, "deployment").count()) === 1 && (await T(p, "release-error").count()) === 0 && (await calls(p, "putConfig")).length === 0);
  await p.close(); }
{ const p = await open("draft=plain&policy=private");
  await privateRadio(p).check(); await T(p, "publish").click(); await p.waitForTimeout(450);
  check("E2. a plain private page with a stored private policy publishes as before (no approval UI at all)", (await T(p, "public-queries-box").count()) === 0 && (await T(p, "deployment").count()) === 1);
  await p.close(); }
{ const p = await open("draft=public&policy=none");
  check("G0. with NO stored policy the dialog says the request decides (legacy), and offers no approval to persist", /Chưa có chính sách xuất bản được lưu/.test(await T(p, "release-policy").innerText()));
  await publishPublic(p);
  check("G. no stored policy → the server does not enforce an approval: the publish is accepted (unchanged behaviour), no 422 and no publish-config write", (await calls(p, "publish")).length === 1 && (await T(p, "deployment").count()) === 1 && (await T(p, "release-error").count()) === 0 && (await calls(p, "putConfig")).length === 0);
  await p.close(); }
{ const p = await open("draft=public&policy=public-unapproved&binds=0");
  await publishPublic(p);
  check("H. the SERVER inspects the immutable version, not the browser draft: a draft with public queries but a version that binds no data is accepted although the policy is unapproved (the dialog never decides the refusal)", (await calls(p, "publish")).length === 1 && (await T(p, "deployment").count()) === 1 && (await T(p, "release-error").count()) === 0);
  await p.close(); }
{ const p = await open("draft=plain&policy=public-unapproved&binds=1");
  await publicRadio(p).check(); await T(p, "publish").click(); await p.waitForTimeout(450);
  check("H2. …and the reverse: a draft the browser sees as having NO public data is still refused (422) when the version being published binds data; the approval box has its OWN acknowledgement (the dialog's checkbox does not exist for this draft)", (await T(p, "release-error").getAttribute("data-kind")) === "public-data-not-approved" && (await T(p, "publish-ack").count()) === 0 && (await T(p, "release-approve-ack").count()) === 1);
  await T(p, "release-approve-ack").check(); await T(p, "release-approve").click(); await p.waitForTimeout(450); await T(p, "publish").click(); await p.waitForTimeout(450);
  check("H3. approving through the box and publishing again works for that draft too", (await policy(p)).publicDataApproved === true && (await T(p, "deployment").count()) === 1);
  await p.close(); }

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close(); finish();
