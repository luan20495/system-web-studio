#!/usr/bin/env node
// C6 FINAL RC — BUSINESS JOURNEY 01 "company onboarding", API half, against the c0rc stack. Real routes only, no SQL, no test hook.
// SYSTEM_ADMIN creates company X (+ first Tenant Admin) -> list -> activation -> TA login -> /auth/me + admin-portal data -> TA creates workspace -> TA provisions a workspace admin
// and a plain member -> workspace admin adds the member -> role assignment (tenant + workspace + project) -> audit trail of every privileged step -> tenant isolation vs company Y.
// Fixtures (kept in a mode-600 scratchpad file, reused by final-iam.mjs / final-adv.mjs): TA_X, M1 (workspace admin of W), LONELY (tenant member + workspace VIEWER), TA_Y. Activation budget: 4.
// Run: . docs/parallel/c6/harness/final-env.sh && node docs/parallel/c6/harness/final-onboarding.mjs      (FRESH=1 to ignore the saved fixtures)
import { S, superAdmin, recorder, st, tag } from "./final-lib.mjs";
import { loadState, saveState, ensureUser, provision, finish, counter, TA_CODES, WSADMIN_CODES, eqSet, hasAll, leakRe, relogin } from "./final-iam-fx.mjs";
const { rec, save } = recorder("final-onboarding");
const state = process.env.FRESH ? { runTag: tag, activations: 0, users: {}, tenants: {}, ws: {}, proj: {} } : loadState();
const RESUMED = !!state.tenants.X; const sa = await superAdmin(); const t = state.runTag;
const J = (r) => JSON.stringify(r.json ?? r.text ?? "");
const ok2 = (r) => r.status >= 200 && r.status < 300;

// ------------------------------------------------------------ A. SYSTEM_ADMIN creates the company
let X = state.tenants.X;
if (!X) {
  const r = await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-co-x-${t}`, name: `C6F company X ${t}`, firstAdmin: { username: `c6f-ob-ta-${t}`, displayName: `C6F tenant admin ${t}`, email: `c6f-ob-ta-${t}@example.test` } });
  rec("ON01", "onboarding", "SYSTEM_ADMIN creates a company with its first Tenant Admin (POST /admin/tenants)", "201, status ACTIVE, firstAdmin{userId,token,expiresAt}, no password echoed", `${st(r)} status=${r.json?.status} tokenPresent=${!!r.json?.firstAdmin?.token}`, r.status === 201 && r.json?.status === "ACTIVE" && !!r.json?.firstAdmin?.token && !/password/i.test(J(r).replace(/token/g, "")));
  if (r.status !== 201) { save(); process.exit(1); }
  X = state.tenants.X = { id: r.json.id, slug: r.json.slug, name: r.json.name, taId: r.json.firstAdmin.userId, taName: r.json.firstAdmin.username, taToken: r.json.firstAdmin.token }; saveState(state);
} else rec("ON01", "onboarding", "company X from a previous run of this script (resumed fixture; creation asserted in the first run)", "tenant exists", X.id, true, { note: "resume" });
const T = X.id;
let r = await sa.call("GET", "/api/v1/admin/tenants"); const row = (r.json ?? []).find((x) => x.id === T);
rec("ON02", "onboarding", "the new company appears in the platform tenant list", "200, row with slug/name/status ACTIVE", `${st(r)} found=${!!row} status=${row?.status}`, r.status === 200 && row?.status === "ACTIVE" && row?.slug === X.slug && row?.name === X.name);
r = await sa.call("POST", "/api/v1/admin/tenants", { slug: X.slug, name: "dup" }); rec("ON03", "onboarding", "duplicate slug is refused", "409 TENANT_SLUG_TAKEN", st(r), r.status === 409 && r.json?.code === "TENANT_SLUG_TAKEN");
r = await sa.call("POST", "/api/v1/admin/tenants", { slug: "Bad Slug!", name: "x" }); rec("ON04", "onboarding", "malformed slug is refused", "400 TENANT_SLUG_INVALID", st(r), r.status === 400 && r.json?.code === "TENANT_SLUG_INVALID");

// ------------------------------------------------------------ B. first admin activates, logs in
const TA = await ensureUser(state, "TA_X", async () => { const a = await finish(X.taToken, X.taId, X.taName, "TA_X"); return a; });
if (!TA.s) { rec("ON05", "onboarding", "first Tenant Admin activates and logs in", "login 200", `login ${TA.loginStatus}`, false); save(); process.exit(1); }
rec("ON05", "onboarding", "first Tenant Admin activates through the one-time link and logs in (no manual DB edit)", "activation 200 + login 200", "ok (session established)", true, { note: `activations so far this run: ${counter.activations}` });
r = await TA.s.call("GET", "/api/v1/auth/me"); const me = r.json;
const tx = (me?.tenants ?? []).find((x) => x.id === T);
rec("ON06", "onboarding", "/auth/me of the Tenant Admin: systemAdmin=false, platformScope=false, tenantId=X, tenantRole TENANT_ADMIN", "as stated", `${st(r)} sys=${me?.systemAdmin} plat=${me?.platformScope} tenantId=${me?.tenantId === T} role=${me?.tenantRole}`, r.status === 200 && me?.systemAdmin === false && me?.platformScope === false && me?.tenantId === T && me?.tenantRole === "TENANT_ADMIN");
rec("ON07", "onboarding", "root permissions are EXACTLY the 8 canonical TENANT_ADMIN codes (D-C0-54), no ORG_MANAGE", TA_CODES.join(","), (me?.permissions ?? []).join(","), eqSet(me?.permissions, TA_CODES) && !JSON.stringify(me).includes("ORG_MANAGE"));
rec("ON08", "onboarding", "tenants[] has exactly company X with the same 8 codes and status ACTIVE (Admin-portal data)", "1 entry, X, ACTIVE, 8 codes", `n=${me?.tenants?.length} status=${tx?.status} perms=${(tx?.permissions ?? []).length}`, me?.tenants?.length === 1 && tx?.status === "ACTIVE" && eqSet(tx?.permissions, TA_CODES));
rec("ON09", "onboarding", "a fresh Tenant Admin has no workspace and no project scope yet", "workspaces=[] projectScopes=[]", `ws=${me?.workspaces?.length} ps=${me?.projectScopes?.length}`, (me?.workspaces ?? []).length === 0 && (me?.projectScopes ?? []).length === 0);
r = await TA.s.call("GET", `/api/v1/admin/tenants/${T}`); rec("ON10", "admin-portal", "Admin portal data: company record (GET /admin/tenants/{X})", "200 {id,slug,name,status}", `${st(r)} ${r.json?.slug} ${r.json?.status}`, r.status === 200 && r.json?.id === T && r.json?.slug === X.slug && r.json?.status === "ACTIVE");
r = await TA.s.call("GET", `/api/v1/admin/tenants/${T}/members`); rec("ON11", "admin-portal", "Admin portal data: members list contains the Tenant Admin, active, role TENANT_ADMIN, no secret", "200", `${st(r)} n=${r.json?.length}`, r.status === 200 && (r.json ?? []).some((m) => m.userId === TA.id && m.role === "TENANT_ADMIN" && m.active) && !leakRe.test(J(r)));
r = await TA.s.call("GET", "/api/v1/admin/tenants"); rec("ON12", "admin-portal", "GET /admin/tenants (the PLATFORM list) is not available to a Tenant Admin (contract: forPlatform)", "403 ADMIN_REQUIRED", st(r), r.status === 403 && r.json?.code === "ADMIN_REQUIRED");
r = await TA.s.call("POST", "/api/v1/admin/tenants", { slug: `c6f-co-z-${t}`, name: "x" }); rec("ON13", "admin-portal", "Tenant Admin cannot create a company", "403", st(r), r.status === 403);

// ------------------------------------------------------------ C. workspace, provisioning, membership, roles
let W = state.ws.W;
if (!W) { r = await TA.s.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: `c6f ws ${t}` }); rec("ON14", "workspace", "Tenant Admin creates a workspace in its company", "201 {id,name,slug,tenantId=X}", `${st(r)} tenant=${r.json?.tenantId === T}`, r.status === 201 && r.json?.tenantId === T && !!r.json?.id); if (r.status !== 201) { save(); process.exit(1); } W = state.ws.W = r.json.id; saveState(state); }
else rec("ON14", "workspace", "workspace W from a previous run", "exists", W, true, { note: "resume" });
const M1 = await ensureUser(state, "M1", () => provision(TA.s, T, `c6f-ob-m1-${t}`, { workspaceId: W, workspaceRole: "WORKSPACE_ADMIN", displayName: "C6F workspace admin" }, "M1"));
rec("ON15", "provisioning", "Tenant Admin provisions a user with a workspace role (WORKSPACE_ADMIN) and the user activates", "201 + activation 200 + login 200", M1.s ? "ok" : `login ${M1.loginStatus}`, !!M1.s);
r = await M1.s.call("GET", "/api/v1/auth/me"); const m1 = r.json; const m1w = (m1?.workspaces ?? []).find((w) => w.id === W);
rec("ON16", "provisioning", "the new workspace admin sees workspace W with the WORKSPACE_ADMIN canonical set, tenantRole MEMBER, no tenant capability", "role WORKSPACE_ADMIN; 13 codes; root permissions []", `role=${m1w?.role} n=${m1w?.permissions?.length} rootPerms=${(m1?.permissions ?? []).length} tenantRole=${m1?.tenantRole}`, m1w?.role === "WORKSPACE_ADMIN" && eqSet(m1w?.permissions, WSADMIN_CODES) && (m1?.permissions ?? []).length === 0 && m1?.tenantRole === "MEMBER");
r = await TA.s.call("GET", `/api/v1/workspaces/${W}/projects`); rec("ON17", "provisioning", "the Tenant Admin gets NO implicit workspace/project access by being Tenant Admin (not a workspace member)", "403/404 (404 WORKSPACE_NOT_FOUND expected)", st(r), r.status === 404 || r.status === 403);
r = await TA.s.call("GET", `/api/v1/workspaces/${W}/audit-events`); rec("ON18", "provisioning", "the Tenant Admin cannot read the workspace audit (no AUDIT_READ)", "403/404", st(r), r.status === 404 || r.status === 403);
const LONELY = await ensureUser(state, "LONELY", () => provision(TA.s, T, `c6f-ob-mem-${t}`, { displayName: "C6F plain member" }, "LONELY"));
r = await LONELY.s.call("GET", "/api/v1/auth/me"); const l0 = r.json;
rec("ON19", "provisioning", "a user provisioned without workspace is a tenant MEMBER with NO permission and no workspace", "tenantRole MEMBER, permissions [], workspaces []", `role=${l0?.tenantRole} perms=${(l0?.permissions ?? []).length} ws=${(l0?.workspaces ?? []).length}`, l0?.tenantRole === "MEMBER" && (l0?.permissions ?? []).length === 0 && (l0?.workspaces ?? []).length === 0 || RESUMED, RESUMED ? { note: "resumed run: LONELY already joined W in the first run, check is only meaningful on the first run" } : {});
r = await M1.s.call("POST", `/api/v1/workspaces/${W}/members`, { username: LONELY.name, role: "VIEWER" }); rec("ON20", "membership", "workspace admin adds the member to the workspace (POST /workspaces/{W}/members)", RESUMED ? "201 (first run) / 409 ALREADY_MEMBER (resumed run)" : "201", st(r), r.status === 201 || (RESUMED && r.status === 409 && r.json?.code === "ALREADY_MEMBER"));
r = await LONELY.s.call("GET", "/api/v1/auth/me"); const l1 = (r.json?.workspaces ?? []).find((w) => w.id === W);
rec("ON21", "membership", "the added member now sees W with role VIEWER and NO canonical permission (workspace VIEWER grants none)", "listed, permissions []", `listed=${!!l1} role=${l1?.role} perms=${(l1?.permissions ?? []).length}`, !!l1 && l1.role === "VIEWER" && (l1.permissions ?? []).length === 0);
r = await LONELY.s.call("POST", `/api/v1/workspaces/${W}/projects`, { name: `c6f-ob-denied-${t}`, appType: "PAGE_SCHEMA" }); rec("ON22", "membership", "workspace VIEWER cannot create a project", "403", st(r), r.status === 403);

// role assignment (tenant level): promote M1 -> TENANT_ADMIN, next request shows it; demote -> next request loses it
r = await TA.s.call("PUT", `/api/v1/admin/tenants/${T}/members/${M1.id}`, { role: "TENANT_ADMIN" }); const promote = r;
let m = (await M1.s.call("GET", "/api/v1/auth/me")).json; const gotAdmin = await M1.s.call("GET", `/api/v1/admin/tenants/${T}/members`);
rec("ON23", "role-assign", "Tenant Admin promotes a member to TENANT_ADMIN: effective on the NEXT request of the target's existing session", "PUT 200; /auth/me 8 codes; members route 200", `${st(promote)} perms=${(m?.permissions ?? []).length} members=${gotAdmin.status}`, promote.status === 200 && eqSet(m?.permissions, TA_CODES) && gotAdmin.status === 200);
r = await TA.s.call("PUT", `/api/v1/admin/tenants/${T}/members/${M1.id}`, { role: "MEMBER" }); const demote = r;
m = (await M1.s.call("GET", "/api/v1/auth/me")).json; const lostAdmin = await M1.s.call("GET", `/api/v1/admin/tenants/${T}/members`);
rec("ON24", "role-assign", "demotion applies on the NEXT request of the same session (no re-login)", "PUT 200; /auth/me []; members route 403", `${st(demote)} perms=${(m?.permissions ?? []).length} members=${st(lostAdmin)}`, demote.status === 200 && (m?.permissions ?? []).length === 0 && lostAdmin.status === 403);
r = await TA.s.call("PUT", `/api/v1/admin/tenants/${T}/members/${TA.id}`, { role: "MEMBER" }); rec("ON25", "role-assign", "Tenant Admin cannot change its own role", "403 SELF_GRANT_FORBIDDEN", st(r), r.status === 403 && r.json?.code === "SELF_GRANT_FORBIDDEN");
// project level
let P = state.proj.P;
if (!P) { r = await M1.s.call("POST", `/api/v1/workspaces/${W}/projects`, { name: `c6f-ob-proj-${t}`, appType: "PAGE_SCHEMA" }); rec("ON26", "role-assign", "workspace admin creates a project in W", "201", st(r), r.status === 201); P = state.proj.P = r.json?.id; saveState(state); }
r = await M1.s.call("POST", `/api/v1/workspaces/${W}/projects/${P}/members`, { username: LONELY.name, role: "VIEWER" }); rec("ON27", "role-assign", "workspace admin assigns the project role VIEWER to the member", RESUMED ? "201 (first run) / 409 ALREADY_MEMBER (resumed run)" : "201", st(r), r.status === 201 || (RESUMED && r.status === 409 && r.json?.code === "ALREADY_MEMBER"));
r = await LONELY.s.call("GET", "/api/v1/auth/me"); const ps = (r.json?.projectScopes ?? []).find((x) => x.projectId === P);
rec("ON28", "role-assign", "the member's /auth/me projectScopes row for P is exactly APP_USE + APP_VIEW (not merged into workspace/root)", "[APP_USE,APP_VIEW]; root []", `${(ps?.permissions ?? []).join(",")} root=${(r.json?.permissions ?? []).length}`, eqSet(ps?.permissions, ["APP_USE", "APP_VIEW"]) && (r.json?.permissions ?? []).length === 0);
r = await M1.s.call("GET", `/api/v1/workspaces/${W}/audit-events?limit=100`); const wsAudit = r; rec("ON29", "audit", "workspace admin reads the workspace audit (AUDIT_READ)", "200", st(r), r.status === 200);

// ------------------------------------------------------------ D. audit trail (platform audit read by SYSTEM_ADMIN)
const rows = []; for (const action of ["TENANT_CREATED", "USER_CREATED", "ACTIVATION_LINK_CREATED", "ACCOUNT_ACTIVATED", "TENANT_MEMBER_SET", "WORKSPACE_CREATED", "ADD_MEMBER", "LOGIN_SUCCESS"]) {
  for (let pg = 0; pg < 6; pg++) { const a = await sa.call("GET", `/api/v1/admin/audit?action=${action}&size=100&page=${pg}`); const it = a.json?.items ?? []; rows.push(...it); if (it.length < 100) break; }
}
const SAid = (await sa.call("GET", "/api/v1/auth/me")).json?.id;
const find = (action, f) => rows.find((x) => x.action === action && f(x));
const nv = (x) => { try { return JSON.parse(x.newValue ?? "null") ?? {}; } catch { return {}; } };
const au = (id, desc, e, exp) => rec(id, "audit", desc, exp, e ? `found actor=${e.actor} (${e.actorId?.slice(0, 8)}) target=${e.resourceType}:${String(e.resourceId).slice(0, 8)}` : "NOT FOUND", !!e);
let e = find("TENANT_CREATED", (x) => x.resourceId === T && x.actorId === SAid); au("ON30", "audit: TENANT_CREATED by SYSTEM_ADMIN, target = company X", e, "actor=SYSTEM_ADMIN, resource TENANT:X");
e = find("USER_CREATED", (x) => x.resourceId === TA.id && x.actorId === SAid); au("ON31", "audit: USER_CREATED (first admin) by SYSTEM_ADMIN", e, "actor SA target TA_X");
e = find("TENANT_MEMBER_SET", (x) => x.resourceId === T && nv(x).userId === TA.id && nv(x).role === "TENANT_ADMIN" && x.actorId === SAid); au("ON32", "audit: TENANT_MEMBER_SET TENANT_ADMIN for the first admin, actor SYSTEM_ADMIN", e, "found");
e = find("ACTIVATION_LINK_CREATED", (x) => x.resourceId === TA.id); au("ON33", "audit: ACTIVATION_LINK_CREATED for the first admin", e, "found");
e = find("ACCOUNT_ACTIVATED", (x) => x.resourceId === TA.id); au("ON34", "audit: ACCOUNT_ACTIVATED for the first admin", e, "found");
e = find("LOGIN_SUCCESS", (x) => x.actorId === TA.id); au("ON35", "audit: LOGIN_SUCCESS of the Tenant Admin", e, "found");
e = find("WORKSPACE_CREATED", (x) => x.resourceId === W && x.actorId === TA.id); au("ON36", "audit: WORKSPACE_CREATED by the Tenant Admin (actor = TA, not SA)", e, "actor TA target workspace W");
e = find("USER_CREATED", (x) => x.resourceId === M1.id && x.actorId === TA.id); au("ON37", "audit: USER_CREATED (workspace admin) by the Tenant Admin", e, "actor TA");
e = find("ADD_MEMBER", (x) => nv(x).userId === LONELY.id && x.actorId === M1.id); au("ON38", "audit: ADD_MEMBER of the plain member by the workspace admin (actor M1, role VIEWER)", e, "actor M1");
const roleEv = rows.filter((x) => x.action === "TENANT_MEMBER_SET" && x.resourceId === T && nv(x).userId === M1.id && x.actorId === TA.id);
rec("ON39", "audit", "audit: both role changes of M1 (MEMBER->TENANT_ADMIN, TENANT_ADMIN->MEMBER) recorded by the Tenant Admin, with old and new value", ">=2 TENANT_MEMBER_SET incl. TENANT_ADMIN and MEMBER, each with old/new", `n=${roleEv.length} roles=${roleEv.map((x) => nv(x).role).join("/")} oldValue=${roleEv.every((x) => !!x.oldValue)}`, roleEv.some((x) => nv(x).role === "TENANT_ADMIN") && roleEv.some((x) => nv(x).role === "MEMBER") && roleEv.some((x) => !!x.oldValue));
const mine = rows.filter((x) => [TA.id, M1.id, LONELY.id, SAid].includes(x.actorId) && [T, W, TA.id, M1.id, LONELY.id].includes(x.resourceId));
const blob = JSON.stringify(mine) + JSON.stringify(wsAudit.json ?? "");
const secrets = [X.taToken, TA.p, M1.p, LONELY.p].filter(Boolean);
rec("ON40", "audit", `nothing secret in ${mine.length} audit rows of this journey (+ the workspace audit): no activation token, no password, no hash/secret/credential key`, "none", `tokenOrPasswordLeaked=${secrets.some((s) => blob.includes(s))} keyword=${leakRe.test(blob)}`, !secrets.some((s) => blob.includes(s)) && !leakRe.test(blob));
rec("ON41", "audit", "every audit row has an actor id, an action and a request id (append-only trail, nothing anonymous for privileged steps)", "all present", `rows=${mine.length} missing=${mine.filter((x) => !x.actorId || !x.action || !x.requestId).length}`, mine.length > 0 && mine.every((x) => !!x.actorId && !!x.action && !!x.requestId));
r = await TA.s.call("GET", "/api/v1/admin/audit"); rec("ON42", "audit", "the Tenant Admin cannot read the platform audit", "403 ADMIN_REQUIRED", st(r), r.status === 403);
r = await TA.s.call("DELETE", `/api/v1/workspaces/${W}/audit-events`); const r2 = await sa.call("DELETE", "/api/v1/admin/audit"); rec("ON43", "audit", "no delete route for audit (append-only): DELETE answers 4xx/405, never 2xx", "not 2xx", `${r.status} / ${r2.status}`, r.status >= 400 && r2.status >= 400);

// ------------------------------------------------------------ E. tenant isolation vs company Y + SYSTEM_ADMIN rules
let Y = state.tenants.Y;
if (!Y) { r = await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-co-y-${t}`, name: `C6F company Y ${t}`, firstAdmin: { username: `c6f-ob-tay-${t}`, displayName: `C6F tenant admin Y ${t}` } }); if (r.status !== 201) { rec("ON50", "isolation", "second company Y with its admin", "201", st(r), false); save(); process.exit(1); } Y = state.tenants.Y = { id: r.json.id, taId: r.json.firstAdmin.userId, taName: r.json.firstAdmin.username, taToken: r.json.firstAdmin.token }; saveState(state); }
const TAY = await ensureUser(state, "TA_Y", () => finish(Y.taToken, Y.taId, Y.taName, "TA_Y"));
if (!TAY.s) { rec("ON50", "isolation", "second company admin logs in", "200", `login ${TAY.loginStatus}`, false); save(); process.exit(1); }
r = await TAY.s.call("POST", `/api/v1/admin/tenants/${Y.id}/workspaces`, { name: `c6f ws y ${t}` }); const WY = r.json?.id; if (!state.ws.WY && WY) { state.ws.WY = WY; saveState(state); } const WYid = state.ws.WY;
rec("ON50", "isolation", "company Y admin creates its own workspace", "201", st(r), r.status === 201 || !!WYid);
const iso = async (id, who, desc, call, accept = [404]) => { const x = await call(); rec(id, "isolation", desc, accept.join("/"), st(x), accept.includes(x.status) && !J(x).includes(T) ); return x; };
await iso("ON51", TAY, "Tenant Admin Y reads company X", () => TAY.s.call("GET", `/api/v1/admin/tenants/${T}`));
await iso("ON52", TAY, "Tenant Admin Y lists company X members", () => TAY.s.call("GET", `/api/v1/admin/tenants/${T}/members`));
await iso("ON53", TAY, "Tenant Admin Y creates a workspace in X", () => TAY.s.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: "x" }));
await iso("ON54", TAY, "Tenant Admin Y provisions a user in X", () => TAY.s.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: `c6f-ob-bad-${t}`, displayName: "x" }));
await iso("ON55", TAY, "Tenant Admin Y promotes an X member (PUT members)", () => TAY.s.call("PUT", `/api/v1/admin/tenants/${T}/members/${M1.id}`, { role: "TENANT_ADMIN" }));
await iso("ON56", TAY, "Tenant Admin Y reads workspace W of X", () => TAY.s.call("GET", `/api/v1/workspaces/${W}/projects`));
await iso("ON57", TAY, "Tenant Admin Y reads project P of X through its own workspace id", () => TAY.s.call("GET", `/api/v1/workspaces/${WYid}/projects/${P}`));
await iso("ON58", TAY, "Tenant Admin Y reads W members / audit", async () => { const a = await TAY.s.call("GET", `/api/v1/workspaces/${W}/members`); const b = await TAY.s.call("GET", `/api/v1/workspaces/${W}/audit-events`); return { status: a.status === 404 && b.status === 404 ? 404 : 999, json: { a: a.status, b: b.status } }; });
await iso("ON59", TA, "Tenant Admin X reads company Y and its workspace", async () => { const a = await TA.s.call("GET", `/api/v1/admin/tenants/${Y.id}`); const b = await TA.s.call("GET", `/api/v1/workspaces/${WYid}/projects`); return { status: a.status === 404 && b.status === 404 ? 404 : 999, json: { a: a.status, b: b.status } }; });
await iso("ON60", TA, "Tenant Admin X reads a user of Y through the platform user route", () => TA.s.call("GET", `/api/v1/admin/users/${TAY.id}`), [403, 404]);
r = await TAY.s.call("GET", `/api/v1/admin/tenants/${Y.id}/member-candidates?q=c6f`); rec("ON61", "isolation", "member candidates of Y never list X accounts", "no X username", `${st(r)} leak=${J(r).includes("c6f-ob-m1") || J(r).includes("c6f-ob-mem")}`, r.status === 200 && !J(r).includes("c6f-ob-m1") && !J(r).includes("c6f-ob-mem") && !J(r).includes("c6f-ob-ta-"));
const mY = (await TAY.s.call("GET", "/api/v1/auth/me")).json; rec("ON62", "isolation", "/auth/me of Y's admin shows only company Y (no X id/slug/workspace/project anywhere)", "no X data", `tenants=${mY?.tenants?.length}`, mY?.tenants?.length === 1 && mY?.tenants?.[0]?.id === Y.id && !JSON.stringify(mY).includes(T) && !JSON.stringify(mY).includes(W));
// SYSTEM_ADMIN rules (contract: platform authority, no business data by default, never org data)
r = await sa.call("GET", `/api/v1/admin/tenants/${T}`); rec("ON63", "sysadmin", "SYSTEM_ADMIN reads any company (platform scope)", "200", st(r), r.status === 200);
r = await sa.call("GET", `/api/v1/admin/tenants/${T}/members`); rec("ON64", "sysadmin", "SYSTEM_ADMIN lists members of any company (TENANT_MEMBERS via platform scope)", "200", st(r), r.status === 200);
r = await sa.call("GET", `/api/v1/workspaces/${W}/projects`); const saProj = r;
rec("ON65", "sysadmin", "SYSTEM_ADMIN (not a member) project LIST of a company workspace: contract 3.2 says business routes give 403 for a platform-scope operator", "403 (contract text) or 404", st(r) + ` body=${J(r).slice(0, 20)}`, r.status === 403 || r.status === 404, { note: "FQ-IAM-01 P3: forWorkspace passes for platform scope; ProjectController.list answers 200 with an EMPTY list (no project data is exposed: see ON65b)" });
const saD = [await sa.call("GET", `/api/v1/workspaces/${W}/projects/${P}`), await sa.call("GET", `/api/v1/workspaces/${W}/projects/${P}/schema`), await sa.call("GET", `/api/v1/workspaces/${W}/projects/${P}/versions`), await sa.call("GET", `/api/v1/workspaces/${W}/projects/${P}/members`)];
rec("ON65b", "sysadmin", "SYSTEM_ADMIN (not a member) reads NO business data: list body is empty, project/schema/versions/members answer 404, data-sources 403", "[] ; 404 x4; 403", `list=${saProj.status} ${J(saProj)} ; ${saD.map((x) => x.status).join("/")} ; ds=${(await sa.call("GET", `/api/v1/workspaces/${W}/data-sources`)).status}`, saProj.status === 200 && Array.isArray(saProj.json) && saProj.json.length === 0 && saD.every((x) => x.status === 404));
r = await sa.call("GET", `/api/v1/admin/tenants/${T}/organization-units`); rec("ON66", "sysadmin", "SYSTEM_ADMIN reads organization data of a company: 403 (org data is tenant business data)", "403", st(r), r.status === 403);
r = await sa.call("GET", `/api/v1/workspaces/${W}/audit-events`); rec("ON67", "sysadmin", "SYSTEM_ADMIN (not a member) reads the workspace audit through the workspace route", "403/404 (platform audit is /admin/audit)", st(r), r.status === 403 || r.status === 404);

state.activations = (state.activations ?? 0) + counter.activations; saveState(state);
console.log(`activations this run: ${counter.activations} (429 waits: ${counter.retries429}, ${Math.round(counter.waitedMs / 1000)}s); cumulative ${state.activations}`);
rec("ON99", "budget", "activation budget respected (coordinator limit: <= 12 for the whole workstream)", "<= 4 in this script", String(counter.activations), counter.activations <= 4);
process.exit(save() ? 1 : 0);
