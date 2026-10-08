// @class: harness — the REAL useAction and useLoad hooks (packages/ui/src) mounted with in-page fake calls; NOT a backend, nothing here talks to a server
/**
 * TEST-ONLY. `?s=` picks the scenario of the fake action (`ok`, `failfirst`: the first call rejects, `fail`: every call rejects); `?strict=1` mounts under React.StrictMode. Every fake call is counted in
 * window.__hk and every abort of a load in window.__hkAborts, so tests/browser/hooks.spec.mjs can assert what the hooks SENT, not only what they showed.
 */
import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { useAction } from "../../packages/ui/src/useAction";
import { clearLoadCache, useLoad } from "../../packages/ui/src/useLoad";

declare global { interface Window { __hk: { act: string[]; load: string[]; done: string[] }; __hkAborts: string[]; __hkIdem: (string | undefined)[]; __hkFirst: Record<string, unknown> } }
window.__hk = { act: [], load: [], done: [] }; window.__hkAborts = []; window.__hkIdem = []; window.__hkFirst = {};
const P = new URLSearchParams(location.search); const S = P.get("s") ?? "ok"; const STRICT = P.has("strict");
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------------------------------------------------------------------ useAction
function ActionDemo() {
  const [ok, setOk] = useState<string[]>([]);
  const act = useAction(async (ctx, label: string) => {
    window.__hk.act.push(`${label}#${ctx.attempt}`); window.__hkIdem.push(ctx.idempotencyKey);
    await wait(150);
    if (S === "fail" || (S === "failfirst" && window.__hk.act.filter((c) => c.startsWith(label)).length === 1)) throw new Error("boom");
    window.__hk.done.push(label); return label;
  }, { keyOf: (label) => label, idempotencyKey: true });
  const go = async (label: string) => { const r = await act.run(label); if (r.status === "ok") setOk((o) => [...o, r.value]); };
  return (
    <section>
      <h2>action</h2>
      <button data-testid="act-a" disabled={act.isBusy("a")} onClick={() => void go("a")}>run a</button>
      <button data-testid="act-b" disabled={act.isBusy("b")} onClick={() => void go("b")}>run b</button>
      <button data-testid="act-fire" onClick={() => { for (let i = 0; i < 5; i++) void act.run("a"); }}>fire a 5 times in one tick</button>
      <p data-testid="act-busy">{act.busy ? "busy" : "idle"}</p>
      <p data-testid="act-error">{act.error ? `${(act.error as Error).message}@${act.errorKey}` : "none"}</p>
      <p data-testid="act-ok">{ok.join(",")}</p>
      <button data-testid="act-reset" onClick={act.reset}>reset</button>
    </section>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------ useLoad
const api = {
  list: async (param: number, signal?: AbortSignal) => {
    window.__hk.load.push(`list:${param}`);
    const ms = param === 1 ? 400 : 100;
    await new Promise<void>((res, rej) => { const t = setTimeout(res, ms); signal?.addEventListener("abort", () => { clearTimeout(t); window.__hkAborts.push(`list:${param}`); rej(Object.assign(new Error("aborted"), { name: "AbortError" })); }); });
    return { param, n: window.__hk.load.length };
  },
};
function Plain({ param }: { param: number }) {                                  // no key: the old contract, the loader ignores the signal
  const l = useLoad(async () => { window.__hk.load.push(`plain:${param}`); await wait(param === 1 ? 400 : 60); return { param }; }, [param]);
  return <p data-testid="plain">{l.loading ? "loading" : "loaded"}|{l.data ? JSON.stringify(l.data) : "null"}|{l.error ? "error" : "ok"}|<button data-testid="plain-reload" onClick={() => l.reload()}>reload</button></p>;
}
function Keyed({ name, param, fail }: { name: string; param: number; fail?: boolean }) {
  const l = useLoad(async ({ signal }) => { if (fail) { window.__hk.load.push(`fail:${param}`); await wait(60); throw new Error("down"); } return api.list(param, signal); }, [param, fail], { key: `demo:${param}` });
  if (!(name in window.__hkFirst)) window.__hkFirst[name] = { data: l.data, loading: l.loading, fromCache: l.fromCache };
  return (
    <p data-testid={`keyed-${name}`}>{l.loading ? "loading" : "loaded"}|{l.validating ? "validating" : "idle"}|{l.fromCache ? "cache" : "net"}|{l.data ? JSON.stringify(l.data) : "null"}|{l.error ? (l.error as Error).message : "ok"}
      <button data-testid={`keyed-${name}-reload`} onClick={() => l.reload()}>reload</button></p>
  );
}
function LoadDemo() {
  const [param, setParam] = useState(2); const [a, setA] = useState(P.get("a") !== "0"); const [b, setB] = useState(P.get("b") === "1"); const [fail, setFail] = useState(false);
  return (
    <section>
      <h2>load</h2>
      <button data-testid="param-1" onClick={() => setParam(1)}>param 1 (slow)</button>
      <button data-testid="param-2" onClick={() => setParam(2)}>param 2 (fast)</button>
      <button data-testid="toggle-a" onClick={() => setA((v) => !v)}>toggle A</button>
      <button data-testid="toggle-b" onClick={() => setB((v) => !v)}>toggle B</button>
      <button data-testid="clear-cache" onClick={() => clearLoadCache()}>clear cache</button>
      <button data-testid="toggle-fail" onClick={() => setFail((v) => !v)}>toggle failing loader</button>
      <p data-testid="param">{param}</p>
      <Plain param={param}/>
      {a ? <Keyed name="a" param={param} fail={fail}/> : null}
      {b ? <Keyed name="b" param={param} fail={fail}/> : null}
    </section>
  );
}
const app = <div style={{ padding: 16, font: "14px system-ui" }}><ActionDemo/><LoadDemo/></div>;
createRoot(document.getElementById("root")!).render(STRICT ? <StrictMode>{app}</StrictMode> : app);
