#!/usr/bin/env node
// @class: unit
// CLI of the owned-process helper (scripts/lib/owned-process.mjs, D-C0-48). Shell scripts, harnesses and agents use THIS instead of pkill / killall / kill-by-port.
//   node scripts/owned-process.mjs start --owner NAME --name NAME --state FILE [--cwd DIR] [--port P] [--mode M] [--ready-port P | --ready-url URL] [--timeout SEC] [--no-group] -- CMD ARGS...
//   node scripts/owned-process.mjs stop --state FILE [--grace SEC]                    stop exactly what `start` recorded (TERM, KILL after grace), verify, remove the state file
//   node scripts/owned-process.mjs status --state FILE                                exit 0 OWNED (running) · 1 GONE · 2 REUSED (the pid is someone else's) · 64 unusable metadata
//   node scripts/owned-process.mjs stop-port --port P [--state FILE] [--cwd-under DIR] [--grace SEC]
//                                                                                     stop the listener of P ONLY if it is proven ours (recorded process, or cwd inside DIR); a foreign
//                                                                                     listener => exit 3 FOREIGN_PROCESS and nothing is signalled
// Output: one JSON line. Exit codes: 0 ok · 1 failed · 2 reused / stale · 3 FOREIGN_PROCESS · 4 PORT_BUSY · 64 usage.
import { startOwned, stopOwned, stopOwnedPort, statusOf } from "./lib/owned-process.mjs";

const argv = process.argv.slice(2); const cmd = argv[0]; const dd = argv.indexOf("--"); const flags = dd >= 0 ? argv.slice(1, dd) : argv.slice(1); const rest = dd >= 0 ? argv.slice(dd + 1) : [];
const opt = (n, d = null) => { const i = flags.indexOf(n); return i >= 0 ? flags[i + 1] : d; }; const has = (n) => flags.includes(n);
const out = (o, code = 0) => { console.log(JSON.stringify(o)); process.exit(code); };
const usage = (m) => out({ ok: false, error: "USAGE", message: m }, 64);
try {
  if (cmd === "start") {
    if (!opt("--owner") || !opt("--name") || !opt("--state") || !rest.length) usage("start needs --owner --name --state and a command after --");
    const port = opt("--port") ? Number(opt("--port")) : null; const rp = opt("--ready-port") ? Number(opt("--ready-port")) : port; const ru = opt("--ready-url");
    const meta = await startOwned({ owner: opt("--owner"), name: opt("--name"), stateFile: opt("--state"), cwd: opt("--cwd") ?? process.cwd(), cmd: rest, port, mode: opt("--mode"), group: !has("--no-group"),
      ready: ru ? { url: ru, timeoutMs: Number(opt("--timeout", 60)) * 1000 } : rp != null ? { port: rp, timeoutMs: Number(opt("--timeout", 60)) * 1000 } : null });
    out({ ok: true, state: "STARTED", pid: meta.pid, pgid: meta.pgid, startTime: meta.startTime, port: meta.port, stateFile: meta.stateFile });
  } else if (cmd === "stop") {
    if (!opt("--state")) usage("stop needs --state"); const r = await stopOwned(opt("--state"), { graceMs: Number(opt("--grace", 8)) * 1000 });
    out({ ok: ["STOPPED", "ALREADY_GONE", "STALE_FOREIGN"].includes(r.state), ...r }, r.state === "STALE_FOREIGN" ? 2 : ["STOPPED", "ALREADY_GONE"].includes(r.state) ? 0 : 1);
  } else if (cmd === "status") {
    if (!opt("--state")) usage("status needs --state"); const s = statusOf(opt("--state"));
    out({ ok: s.state === "OWNED", state: s.state, reason: s.reason, pid: s.meta?.pid ?? null }, s.state === "OWNED" ? 0 : s.state === "GONE" ? 1 : s.state === "REUSED" ? 2 : 64);
  } else if (cmd === "stop-port") {
    if (!opt("--port")) usage("stop-port needs --port"); if (!opt("--state") && !opt("--cwd-under")) usage("stop-port needs --state and/or --cwd-under: a port alone proves nothing");
    const r = await stopOwnedPort(Number(opt("--port")), { stateFile: opt("--state"), cwdUnder: opt("--cwd-under"), graceMs: Number(opt("--grace", 8)) * 1000 });
    out(r, r.ok ? 0 : r.error === "FOREIGN_PROCESS" ? 3 : 1);
  } else usage("commands: start | stop | status | stop-port");
} catch (e) { out({ ok: false, error: e.code ?? "ERROR", message: String(e.message).slice(0, 300) }, e.code === "PORT_BUSY" ? 4 : e.code === "USAGE" ? 64 : 1); }
