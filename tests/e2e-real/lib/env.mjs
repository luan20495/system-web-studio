// @class: real-backend — shared config of the real-backend suite. Reads ONLY the environment; nothing here is a secret and nothing is read from a file.
export const EXIT = { OK: 0, FAIL: 1, NOT_RUN: 2 };

/** Required to run at all. Missing → the whole suite is NOT RUN (exit 2), never "passed". */
export const REQUIRED = [
  ["E2E_STUDIO_URL", "origin of a RUNNING Studio app whose same-origin /api reaches the backend under test, e.g. http://127.0.0.1:3003"],
  ["E2E_ADMIN_USER", "username of a SYSTEM ADMIN account in the stack under test (used to create the per-run fixtures)"],
  ["E2E_ADMIN_PASSWORD", "its password, from the stack's own secret source (never from the repo)"],
];

/** Operator-supplied REACHABLE data source for the "connection test succeeds" checks (E2E-06). All three come from the environment only, never from a file in the repo.
 *  A malformed value is recorded as `error` (a short reason, never the value) and the success checks are then skipped with that reason. */
export function loadDataSourceFacts(env = process.env) {
  const type = env.E2E_DS_TYPE?.trim() || "";
  if (!type) return { provided: false, error: null };
  const parse = (name) => { const raw = env[name]; if (!raw) return { v: undefined }; try { const v = JSON.parse(raw); return v && typeof v === "object" && !Array.isArray(v) ? { v } : { error: `${name} must be a JSON object` }; } catch { return { error: `${name} is not valid JSON` }; } };
  const c = parse("E2E_DS_CONFIG_JSON"), k = parse("E2E_DS_CREDENTIAL_JSON");
  const error = c.error ?? k.error ?? (!c.v ? "E2E_DS_CONFIG_JSON is required with E2E_DS_TYPE" : null);
  return { provided: true, type, config: c.v, credential: k.v, error };
}

export function loadConfig(env = process.env) {
  const missing = REQUIRED.filter(([k]) => !env[k]);
  const studio = (env.E2E_STUDIO_URL ?? "").replace(/\/+$/, "");
  return {
    missing, studio, adminUser: env.E2E_ADMIN_USER ?? "", adminPassword: env.E2E_ADMIN_PASSWORD ?? "",
    /** the Studio portal prefix (@xweb/permissions PORTAL_PREFIX) */
    studioPrefix: env.E2E_STUDIO_PREFIX ?? "/studio",
    chrome: env.E2E_CHROME ?? env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    headless: env.E2E_HEADED !== "1",
    outDir: env.E2E_OUT_DIR ?? ".run/e2e-real",
    only: (env.E2E_ONLY ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    runId: env.E2E_RUN_ID ?? Date.now().toString(36),
    // optional operator facts / hooks. Absent → the flows that need them are BLOCKED with the exact reason, never faked.
    preseeded: {
      workspaceId: env.E2E_DATA_WORKSPACE_ID, projectId: env.E2E_DATA_PROJECT_ID, queryId: env.E2E_DATA_QUERY_ID,
      user: env.E2E_DATA_USER, password: env.E2E_DATA_PASSWORD,
    },
    publicBase: env.E2E_PUBLIC_BASE,
    dataSource: loadDataSourceFacts(env),
    /** E2E-PD01 operator facts: a REAL approved READ operation of the real data source above (C3 query definition id, or E2E_PD_SQL = a read-only SELECT the flow registers as a C3 query definition through the Management API) and the text its first row returns. Absent → the flow is BLOCKED with the exact reason; nothing is invented. */
    publicData: { operationKey: env.E2E_PD_OPERATION_KEY?.trim() || "", sql: env.E2E_PD_SQL?.trim() || "", expectText: env.E2E_PD_EXPECT_TEXT ?? "", writeOperationKey: env.E2E_PD_WRITE_OPERATION_KEY?.trim() || "" },
    restartBackendCmd: env.E2E_RESTART_BACKEND_CMD,
    pauseStoreCmd: env.E2E_PAUSE_STORE_CMD, resumeStoreCmd: env.E2E_RESUME_STORE_CMD, pauseRenderCmd: env.E2E_PAUSE_RENDER_CMD, resumeRenderCmd: env.E2E_RESUME_RENDER_CMD,
    pauseBackendCmd: env.E2E_PAUSE_BACKEND_CMD, resumeBackendCmd: env.E2E_RESUME_BACKEND_CMD,
    stopBackendCmd: env.E2E_STOP_BACKEND_CMD, startBackendCmd: env.E2E_START_BACKEND_CMD,
    stopRabbitCmd: env.E2E_STOP_RABBIT_CMD, startRabbitCmd: env.E2E_START_RABBIT_CMD,
    /** stability runs: a number shuffles the order of the selected flows (deterministic per seed) to expose order dependencies and state leaks between flows */
    shuffleSeed: /^\d+$/.test(env.E2E_SHUFFLE_SEED ?? "") ? Number(env.E2E_SHUFFLE_SEED) : null,
    durableRunStores: env.E2E_DURABLE_RUN_STORES === "1",
    rabbitWired: env.E2E_RABBITMQ_WIRED === "1",
  };
}

/** deterministic shuffle (mulberry32): the same seed always gives the same order */
export function shuffled(items, seed) {
  const a = [...items]; let t = seed >>> 0;
  const rnd = () => { t += 0x6d2b79f5; let x = Math.imul(t ^ (t >>> 15), 1 | t); x ^= x + Math.imul(x ^ (x >>> 7), 61 | x); return ((x ^ (x >>> 14)) >>> 0) / 4294967296; };
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}
