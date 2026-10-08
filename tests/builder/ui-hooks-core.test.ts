// @class: unit
// The framework-free cores of packages/ui/src/useAction.ts and useLoad.ts (single-flight runner, keyed stale-while-revalidate cache). The React wrappers are exercised in a real browser:
// tests/browser/hooks.spec.mjs. Relative imports only (see scripts/test-unit.mjs).
import test from "node:test";
import assert from "node:assert/strict";
import { createActionRunner } from "../../packages/ui/src/useAction";
import { createLoadCache } from "../../packages/ui/src/useLoad";

const tick = () => new Promise((r) => setTimeout(r, 0));
const deferred = <T = void>() => { let resolve!: (v?: T) => void, reject!: (e: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a as (v?: T) => void; reject = b; }); return { promise, resolve, reject }; };

// ------------------------------------------------------------------------------------------------------------------------------ useAction core
test("action: a double click in the same tick sends ONE call; the second answer is `skipped`", async () => {
  let calls = 0; const d = deferred<string>();
  const a = createActionRunner(async () => { calls += 1; return d.promise; });
  const first = a.run(); const second = a.run();                      // two events before any render or await
  assert.deepEqual(await second, { status: "skipped" });
  assert.equal(a.isBusy(), true); assert.equal(a.state().busy, true);
  d.resolve("done");
  assert.deepEqual(await first, { status: "ok", value: "done" });
  assert.equal(calls, 1); assert.equal(a.isBusy(), false);
});

test("action: a rejected call re-enables the control, stores the error, never rejects, and the next call goes through", async () => {
  let n = 0; const states: boolean[] = [];
  const a = createActionRunner(async () => { n += 1; if (n === 1) throw new Error("boom"); return "fine"; }, {}, (s) => states.push(s.busy));
  const r1 = await a.run();
  assert.equal(r1.status, "error"); assert.equal((r1 as { error: Error }).error.message, "boom");
  assert.equal(a.isBusy(), false); assert.equal((a.state().error as Error).message, "boom");
  assert.deepEqual(states, [true, false]);                              // busy on, busy off (error stored)
  const r2 = await a.run();
  assert.deepEqual(r2, { status: "ok", value: "fine" }); assert.equal(a.state().error, null);   // a new try clears the old error
  assert.equal(n, 2);
});

test("action: a synchronous throw inside the function is an error result, not an exception", async () => {
  const a = createActionRunner((): Promise<void> => { throw new Error("sync"); });
  assert.equal((await a.run()).status, "error"); assert.equal(a.isBusy(), false);
});

test("action: keyOf lets different targets run in parallel but not the same target twice", async () => {
  const calls: string[] = []; const gates = new Map<string, { resolve: () => void }>();
  const a = createActionRunner(async (_c, id: string) => { calls.push(id); const g = deferred(); gates.set(id, g); await g.promise; return id; }, { keyOf: (id) => id });
  const r1 = a.run("a"); const r2 = a.run("b"); const r3 = a.run("a");
  assert.deepEqual(await r3, { status: "skipped" });
  assert.deepEqual([...a.state().busyKeys].sort(), ["a", "b"]); assert.equal(a.isBusy("a"), true); assert.equal(a.isBusy("c"), false);
  gates.get("a")!.resolve(); gates.get("b")!.resolve(); await Promise.all([r1, r2]);
  assert.deepEqual(calls, ["a", "b"]); assert.equal(a.isBusy(), false);
});

test("action: the idempotency key is stable across retries of a FAILED call and replaced after a success", async () => {
  const seen: { key: string | undefined; attempt: number }[] = []; let n = 0; let seq = 0;
  const a = createActionRunner(async (ctx) => { seen.push({ key: ctx.idempotencyKey, attempt: ctx.attempt }); n += 1; if (n <= 2) throw new Error("net"); return "ok"; }, { idempotencyKey: () => `k${++seq}` });
  await a.run(); await a.run(); await a.run();                          // fail, fail, succeed: one operation, three attempts
  await a.run();                                                        // a NEW operation
  assert.deepEqual(seen, [{ key: "k1", attempt: 1 }, { key: "k1", attempt: 2 }, { key: "k1", attempt: 3 }, { key: "k2", attempt: 1 }]);
});

test("action: without the option no idempotency key is made", async () => {
  let key: string | undefined = "x"; const a = createActionRunner(async (ctx) => { key = ctx.idempotencyKey; });
  await a.run(); assert.equal(key, undefined);
  const b = createActionRunner(async (ctx) => ctx.idempotencyKey, { idempotencyKey: true });
  const r = await b.run(); assert.equal(r.status, "ok"); assert.match(String((r as { value: string }).value), /.{8,}/);
});

test("action: after detach (unmount) nothing is reported, the call still completes; attach resumes", async () => {
  let changes = 0; const d = deferred(); const a = createActionRunner(async () => d.promise, {}, () => { changes += 1; });
  const p = a.run(); assert.equal(changes, 1); a.detach(); d.resolve(); await p; assert.equal(changes, 1);
  a.attach(); await a.run().then(() => undefined, () => undefined); assert.ok(changes > 1);
});

// ------------------------------------------------------------------------------------------------------------------------------ useLoad cache core
test("cache: concurrent requests for one key make ONE network call", async () => {
  const c = createLoadCache(); let calls = 0; const d = deferred<string>(); const ctl = new AbortController();
  const load = async () => { calls += 1; return d.promise; };
  const a = c.fetch("k", load, ctl.signal); const b = c.fetch("k", load, ctl.signal);
  d.resolve("v"); assert.deepEqual(await Promise.all([a, b]), ["v", "v"]); assert.equal(calls, 1);
  assert.equal(c.peek<string>("k")?.data, "v");
});

test("cache: stale-while-revalidate: peek answers at once, a new fetch refreshes the entry", async () => {
  let t = 1000; const c = createLoadCache(10, () => t); let v = "old"; const ctl = new AbortController();
  assert.equal(await c.fetch("k", async () => v, ctl.signal), "old"); assert.equal(c.peek("k")?.at, 1000);
  v = "new"; t = 5000; assert.equal(c.peek<string>("k")?.data, "old");     // what a second screen shows first
  assert.equal(await c.fetch("k", async () => v, ctl.signal), "new"); assert.equal(c.peek<string>("k")?.data, "new"); assert.equal(c.peek("k")?.at, 5000);
});

test("cache: a failed refresh keeps the last good data and rejects the caller", async () => {
  const c = createLoadCache(); const ctl = new AbortController();
  await c.fetch("k", async () => "good", ctl.signal);
  await assert.rejects(c.fetch("k", async () => { throw new Error("down"); }, ctl.signal), /down/);
  assert.equal(c.peek<string>("k")?.data, "good");
});

test("cache: the shared request is aborted only when EVERY caller has aborted", async () => {
  const c = createLoadCache(); let aborted = false; const d = deferred<string>();
  const load = (ctx: { signal: AbortSignal }) => { ctx.signal.addEventListener("abort", () => { aborted = true; d.reject(new Error("net aborted")); }); return d.promise; };
  const a = new AbortController(), b = new AbortController();
  const pa = c.fetch("k", load, a.signal); const pb = c.fetch("k", load, b.signal);
  a.abort(); await assert.rejects(pa, (e: Error) => e.name === "AbortError");
  await tick(); assert.equal(aborted, false);                            // b still waits for it
  b.abort(); await assert.rejects(pb, (e: Error) => e.name === "AbortError");
  await tick(); assert.equal(aborted, true);                             // nobody left: the network request is cancelled
});

test("cache: a caller that is already aborted never starts a second request and rejects at once", async () => {
  const c = createLoadCache(); const ctl = new AbortController(); ctl.abort(); let calls = 0;
  await assert.rejects(c.fetch("k", async () => { calls += 1; return 1; }, ctl.signal), (e: Error) => e.name === "AbortError");
  await tick(); assert.equal(calls, 0); assert.equal(c.peek("k"), undefined);
});

test("cache: force replaces the request in flight and the old answer is not stored", async () => {
  const c = createLoadCache(); const ctl = new AbortController(); const d1 = deferred<string>(), d2 = deferred<string>();
  const p1 = c.fetch("k", () => d1.promise, ctl.signal);
  const p2 = c.fetch("k", () => d2.promise, ctl.signal, { force: true });
  d2.resolve("fresh"); assert.equal(await p2, "fresh");
  d1.resolve("stale"); assert.equal(await p1, "stale");                  // its own caller still gets it...
  assert.equal(c.peek<string>("k")?.data, "fresh");                     // ...but the cache keeps the newer one
});

test("cache: invalidate by key and by prefix; an answer that lands after an invalidate is not stored", async () => {
  const c = createLoadCache(); const ctl = new AbortController();
  c.set("projects:w1:p0", 1); c.set("projects:w1:p1", 2); c.set("templates:mine", 3);
  c.invalidate("projects:"); assert.equal(c.peek("projects:w1:p0"), undefined); assert.equal(c.peek("templates:mine")?.data, 3);
  const d = deferred<string>(); const p = c.fetch("templates:mine", () => d.promise, ctl.signal, { force: true });
  c.invalidate("templates:"); d.resolve("late"); assert.equal(await p, "late"); assert.equal(c.peek("templates:mine"), undefined);
  c.set("a", 1); c.invalidate(); assert.equal(c.size(), 0);
});

test("cache: least recently used entries are evicted beyond the limit; an entry in flight is kept", async () => {
  const c = createLoadCache(3); c.set("a", 1); c.set("b", 2); c.set("c", 3); c.peek("a"); c.set("d", 4);
  assert.ok(c.size() <= 3); assert.equal(c.peek("d")?.data, 4);
  const d = deferred<number>(); const ctl = new AbortController(); const p = c.fetch("busy", () => d.promise, ctl.signal);
  c.set("e", 5); c.set("f", 6); c.set("g", 7);
  d.resolve(9); assert.equal(await p, 9); assert.equal(c.peek("busy")?.data, 9);
});
