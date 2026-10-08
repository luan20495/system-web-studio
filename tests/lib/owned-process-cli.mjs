// @class: unit
// Command-line face of tests/lib/owned-process.mjs, for shell scripts (docs/parallel/c5/e2e-stack.sh). Nothing here ever looks a process up by name or kills by port.
//
//   owned-process-cli.mjs start  --state F [--log F] [--cwd D] [--port P] [--name N] -- cmd args...   spawn detached + record identity; env is inherited. Port busy => exit 3, nothing started
//   owned-process-cli.mjs refresh --state F                                                            after readiness: also accept the command the process shows now
//   owned-process-cli.mjs adopt  --state F --port P --parent F --contains STR                          record the listener of P as owned ONLY if it is provably ours (see adoptListener)
//   owned-process-cli.mjs stop   --state F [--grace MS]                                                validated SIGTERM -> SIGKILL of the owned group only; verified gone
//   owned-process-cli.mjs signal --state F --sig STOP|CONT                                             validated signal
//   owned-process-cli.mjs status --state F                                                             prints OWNED|GONE|REUSED|UNKNOWN (exit 0 only for OWNED)
//   owned-process-cli.mjs port-free PORT                                                               exit 0 free / 3 busy (read-only holder on stderr)
//
// exit: 0 ok · 1 usage / generic · 3 port busy · 4 not ours (REFUSED / FOREIGN_PROCESS) · 5 stop FAILED · 6 not running
import { PortBusyError, adoptListener, assertPortFree, identify, readState, refreshIdentity, signalOwned, spawnOwned, statusOf, stopOwned } from "./owned-process.mjs";

const argv = process.argv.slice(2); const sub = argv.shift();
const dd = argv.indexOf("--"); const rest = dd >= 0 ? argv.splice(dd) .slice(1) : [];
const opt = (k, d = null) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : d; };
const need = (k) => { const v = opt(k); if (!v) { console.error(`owned-process-cli ${sub}: --${k} is required`); process.exit(1); } return v; };
const say = (m) => console.log(`[owned] ${m}`);

try {
  switch (sub) {
    case "start": {
      const state = need("state"); if (!rest.length) { console.error("start: command after --"); process.exit(1); }
      const prev = readState(state); if (prev && identify(prev).state === "OWNED") { say(`${opt("name", rest[0])} already running (owned pid ${prev.pid})`); break; }
      const o = await spawnOwned(rest[0], rest.slice(1), { stateFile: state, logFile: opt("log"), cwd: opt("cwd", process.cwd()), name: opt("name"), port: opt("port") ? Number(opt("port")) : null });
      say(`started ${opt("name", rest[0])} pid ${o.pid} pgid ${o.pgid} (state ${state})`); break;
    }
    case "refresh": { const r = refreshIdentity(need("state")); if (!r.ok) { console.error(`[owned] refresh refused: ${r.reason}`); process.exit(4); } break; }
    case "adopt": { const a = adoptListener({ port: Number(need("port")), parent: need("parent"), contains: need("contains"), stateFile: need("state"), name: opt("name") }); say(`adopted listener pid ${a.pid} on ${a.port}`); break; }
    case "stop": {
      const state = need("state"); if (!readState(state)) { say("nothing recorded, nothing to stop"); break; }
      const r = await stopOwned(state, { graceMs: opt("grace") ? Number(opt("grace")) : 8000 });
      if (r.state === "REFUSED") { console.error(`[owned] REFUSED: ${r.reason}. Nothing was signalled; the state file is KEPT (${state}) so the recorded process can still be found.`); process.exit(4); }
      if (r.state === "FAILED") { console.error(`[owned] FAILED: ${r.reason} (pid ${r.pid})`); process.exit(5); }
      say(`${r.state}${r.signal ? ` (${r.signal})` : ""}`); break;
    }
    case "signal": {
      const sig = `SIG${need("sig").replace(/^SIG/, "")}`; const r = signalOwned(need("state"), sig);
      if (!r.ok) { console.error(`[owned] ${r.state}: ${r.reason}. Nothing was signalled.`); process.exit(r.state === "GONE" ? 6 : 4); }
      break;
    }
    case "status": { const r = statusOf(need("state")); console.log(r.state); process.exit(r.state === "OWNED" ? 0 : 1); }
    case "port-free": { await assertPortFree(Number(argv[0])); break; }
    default: console.error("usage: owned-process-cli.mjs start|refresh|adopt|stop|signal|status|port-free ... (see the header)"); process.exit(1);
  }
} catch (e) {
  if (e instanceof PortBusyError) { console.error(`[owned] ERROR: ${e.message}`); process.exit(3); }
  if (e.code === "FOREIGN_PROCESS" || e.code === "NOT_OWNED") { console.error(`[owned] REFUSED: ${e.message}`); process.exit(4); }
  console.error(`[owned] ERROR: ${e.message ?? e}`); process.exit(1);
}
