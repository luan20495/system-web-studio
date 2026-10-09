// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import { claimStudioStorage, clearStudioStorage, STUDIO_OWNER_KEY } from "../../features/studio/studioStorage";

function mem(init: Record<string, string> = {}) {
  const m = new Map(Object.entries(init));
  return { m, getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), key: (i: number) => [...m.keys()][i] ?? null, get length() { return m.size; } };
}

test("M-091: logout clears studio-ws, studio-ai-model and every ws-mode-* session key, nothing else", () => {
  const l = mem({ "studio-ws": "w2", "studio-ai-model": "gpt", [STUDIO_OWNER_KEY]: "u1", other: "keep" });
  const s = mem({ "ws-mode-p1": "design", "ws-mode-p2": "ai", unrelated: "x" });
  clearStudioStorage(l as never, s as never);
  assert.deepEqual([...l.m.keys()], ["other"]);
  assert.deepEqual([...s.m.keys()], ["unrelated"]);
});

test("M-091: the next user of the browser does not inherit the previous user's workspace / model; the same user keeps them", () => {
  const l = mem({ "studio-ws": "w2", "studio-ai-model": "gpt", [STUDIO_OWNER_KEY]: "u1" }); const s = mem({ "ws-mode-p1": "design" });
  claimStudioStorage("u1", l as never, s as never);
  assert.equal(l.m.get("studio-ws"), "w2"); assert.equal(s.m.get("ws-mode-p1"), "design");
  claimStudioStorage("u2", l as never, s as never);
  assert.equal(l.m.get("studio-ws"), undefined); assert.equal(l.m.get("studio-ai-model"), undefined); assert.equal(s.m.size, 0);
  assert.equal(l.m.get(STUDIO_OWNER_KEY), "u2");
});

test("M-091: storage that throws (private mode / blocked) never breaks Studio", () => {
  const bad = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); }, removeItem() { throw new Error("blocked"); }, key() { throw new Error("blocked"); }, get length(): number { throw new Error("blocked"); } };
  assert.doesNotThrow(() => { claimStudioStorage("u1", bad as never, bad as never); clearStudioStorage(bad as never, bad as never); });
  assert.doesNotThrow(() => { claimStudioStorage("u1", undefined, undefined); });
});
