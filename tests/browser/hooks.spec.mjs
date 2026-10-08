// @class: harness — real Chromium on the REAL useAction / useLoad hooks with in-page fake calls (tests/browser/hooks-harness.tsx); proves what the HOOKS send and show, NOT a backend
// Run: node tests/browser/build-harness.mjs && node tests/browser/harness-server.mjs run -- node tests/browser/hooks.spec.mjs
import { harnessPage, launch, makeChecks, watchConsole } from "./lib/spec.mjs";
const { check, finish } = makeChecks();
const browser = await launch();
const errors = [];
const T = (p, id) => p.getByTestId(id);
async function open(q = "") {
  const p = await browser.newPage({ viewport: { width: 900, height: 700 } }); p.setDefaultTimeout(5000); watchConsole(p, errors);
  await p.goto(`${harnessPage("hooks.html")}${q}`); await p.waitForSelector('[data-testid="act-a"]'); await p.waitForTimeout(500); return p;
}
const calls = (p) => p.evaluate(() => window.__hk);
const settle = (p, ms = 450) => p.waitForTimeout(ms);

// ===================================================================================================================== useAction
{ const p = await open();
  await T(p, "act-a").dblclick(); await p.waitForTimeout(50);
  check("ACT01 a real double click sends ONE call; the control is busy and disabled meanwhile", (await calls(p)).act.length === 1 && (await T(p, "act-busy").innerText()) === "busy" && (await T(p, "act-a").isDisabled()));
  await settle(p);
  check("ACT01b …and is enabled again afterwards with the result delivered once", (await T(p, "act-busy").innerText()) === "idle" && !(await T(p, "act-a").isDisabled()) && (await T(p, "act-ok").innerText()) === "a" && (await calls(p)).done.length === 1);
  await p.close(); }

{ const p = await open();
  await T(p, "act-fire").click(); await settle(p);
  check("ACT02 five calls in the SAME tick (no render in between, so a `busy` state alone could not stop them) send ONE", (await calls(p)).act.length === 1, JSON.stringify((await calls(p)).act));
  await p.close(); }

{ const p = await open("?s=failfirst");
  await T(p, "act-a").click(); await settle(p);
  const e1 = await T(p, "act-error").innerText();
  check("ACT03 a rejected call stores the error, clears busy and RE-ENABLES the control (no unhandled rejection)", e1 === "boom@a" && (await T(p, "act-busy").innerText()) === "idle" && !(await T(p, "act-a").isDisabled()) && (await T(p, "act-ok").innerText()) === "");
  await T(p, "act-a").click(); await settle(p);
  const idem = await p.evaluate(() => window.__hkIdem);
  check("ACT03b the retry works, clears the old error and REUSES the idempotency key of the failed try", (await T(p, "act-ok").innerText()) === "a" && (await T(p, "act-error").innerText()) === "none" && idem.length === 2 && !!idem[0] && idem[0] === idem[1], JSON.stringify(idem));
  await T(p, "act-a").click(); await settle(p);
  const idem2 = await p.evaluate(() => window.__hkIdem);
  check("ACT03c after a SUCCESS the next call is a new operation with a NEW idempotency key", idem2.length === 3 && idem2[2] !== idem2[1]);
  await p.close(); }

{ const p = await open();
  await T(p, "act-a").click(); await T(p, "act-b").click(); await p.waitForTimeout(40);
  check("ACT04 different keys run in parallel: both rows busy, two calls", (await calls(p)).act.length === 2 && (await T(p, "act-a").isDisabled()) && (await T(p, "act-b").isDisabled()));
  await settle(p);
  check("ACT04b both finish and both controls come back", !(await T(p, "act-a").isDisabled()) && !(await T(p, "act-b").isDisabled()) && (await T(p, "act-ok").innerText()) === "a,b");
  await p.close(); }

{ const p = await open("?strict=1");
  await T(p, "act-a").click(); await p.waitForTimeout(40);
  const busy = await T(p, "act-busy").innerText(); await settle(p);
  check("ACT05 under React.StrictMode (effects mount, unmount, mount) the busy state is still reported and the control comes back", busy === "busy" && (await T(p, "act-busy").innerText()) === "idle" && (await calls(p)).act.length === 1);
  await p.close(); }

// ===================================================================================================================== useLoad
{ const p = await open("?a=0");
  const loaded = await T(p, "plain").innerText(); const n1 = (await calls(p)).load.filter((c) => c.startsWith("plain")).length;
  check("LOAD01 no key (the OLD contract): the data arrives after ONE call", /^loaded\|\{"param":2\}\|ok/.test(loaded) && n1 === 1, loaded);
  await T(p, "plain-reload").click(); const during = await T(p, "plain").innerText(); await settle(p, 300);
  check("LOAD01b reload() asks again, shows loading while the OLD data stays on screen, then the new answer", /^loading\|\{"param":2\}\|ok/.test(during) && (await calls(p)).load.filter((c) => c.startsWith("plain")).length === 2, during);
  await p.close(); }

{ const p = await open("?a=0");
  await T(p, "param-1").click(); await T(p, "param-2").click(); await settle(p, 700);
  check("LOAD02 a slow answer of an OLD request never overwrites the newer one (stale responses ignored)", /\{"param":2\}/.test(await T(p, "plain").innerText()), await T(p, "plain").innerText());
  await p.close(); }

{ const p = await open();
  await settle(p, 300); await T(p, "toggle-b").click(); await p.waitForTimeout(10);
  const first = await p.evaluate(() => window.__hkFirst.b);
  check("LOAD03 keyed cache: a SECOND screen with the same key has the cached data on its FIRST render (no empty flash) and is flagged fromCache", first && first.data && first.data.param === 2 && first.fromCache === true, JSON.stringify(first));
  await settle(p, 300);
  const txt = await T(p, "keyed-b").innerText();
  check("LOAD03b …and it revalidates: the network is asked again and the screen ends fresh (net, idle)", /^loaded\|idle\|net\|/.test(txt) && (await calls(p)).load.filter((c) => c === "list:2").length === 2, txt);
  await p.close(); }

{ const p = await open("?b=1");
  await settle(p, 400);
  check("LOAD04 two screens that mount together with one key make ONE request (de-duplicated)", (await calls(p)).load.filter((c) => c === "list:2").length === 1 && /\{"param":2/.test(await T(p, "keyed-a").innerText()) && /\{"param":2/.test(await T(p, "keyed-b").innerText()));
  await p.close(); }

{ const p = await open();
  await settle(p, 300); await T(p, "param-1").click(); await p.waitForTimeout(40); await T(p, "param-2").click(); await settle(p, 600);
  const ab = await p.evaluate(() => window.__hkAborts);
  check("LOAD05 a superseded request is ABORTED through the signal the loader receives (and the newer data is shown)", ab.includes("list:1") && /\{"param":2/.test(await T(p, "keyed-a").innerText()), JSON.stringify(ab));
  await p.close(); }

{ const p = await open("?a=0");
  await T(p, "param-1").click(); await T(p, "toggle-b").click(); await p.waitForTimeout(40);
  await T(p, "toggle-b").click(); await settle(p, 600);
  const ab = await p.evaluate(() => window.__hkAborts);
  check("LOAD06 unmounting the only screen waiting for a request aborts it (nothing is left running)", ab.includes("list:1"), JSON.stringify(ab));
  await p.close(); }

{ const p = await open();
  await settle(p, 300); await T(p, "clear-cache").click(); await T(p, "toggle-b").click(); await p.waitForTimeout(10);
  const first = await p.evaluate(() => window.__hkFirst.b);
  check("LOAD07 clearLoadCache() (sign-out / tenant switch) forgets everything: the next screen starts empty", first && first.data === null && first.fromCache === false, JSON.stringify(first));
  await p.close(); }

{ const p = await open();
  await settle(p, 300); await T(p, "toggle-fail").click(); await T(p, "keyed-a-reload").click(); await settle(p, 300);
  const t = await T(p, "keyed-a").innerText();
  check("LOAD08 a failed revalidation keeps the last good data on screen and reports the error", /down/.test(t) && /\{"param":2/.test(t), t);
  await p.close(); }

check("no console error / warning / uncaught exception on any page (incl. React warnings)", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
finish();
