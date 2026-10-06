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
    restartBackendCmd: env.E2E_RESTART_BACKEND_CMD,
    stopRabbitCmd: env.E2E_STOP_RABBIT_CMD, startRabbitCmd: env.E2E_START_RABBIT_CMD,
    durableRunStores: env.E2E_DURABLE_RUN_STORES === "1",
    rabbitWired: env.E2E_RABBITMQ_WIRED === "1",
  };
}
