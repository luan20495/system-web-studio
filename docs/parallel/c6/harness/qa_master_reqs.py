"""Requirement / clause data for QA_MASTER (C6). Imported by qa_master.py.

Every requirement is a clause of a frozen contract or decision. `tests` lists TEST IDs (rows of QA_MASTER; rows that are GAP/BLOCKED stand for the
aspects that have no passing test), `cases` lists JUnit selectors (suite, keyword) that specifically evidence the clause. Coverage, result and
gate status are COMPUTED by qa_master.py from the status of those rows/cases at the SHA under test:
  FULL    every mapped row and case passes (and at least one exists)
  PARTIAL at least one passes, at least one is GAP/BLOCKED/FAIL/RETEST/NOT RUN
  NONE    nothing passes
"""

A0, A1, A2, A3, A4, A5 = ("C0 Architecture/Integration", "C1 IAM/Security", "C2 Build/Publish/Deploy", "C3 Data/Connector", "C4 Action/Workflow", "C5 Studio/Frontend")
STK = "Stack up with isolated ports/DB (blocked: BLK-C6-05)"


def register(row, J, GAP, BLK, STATIC):
    """create the clause-derived test rows (the ones that did not exist yet) and return the requirement list"""
    def N(id, area, feat, case, typ, pr, exp, src, owner, pre=None):
        row(id, area, feat, case, typ, pr, exp, src, owner, pre=pre or (STK if src[0] == "blocked" else ("Test to be designed/written" if src[0] == "gap" else "JDK 21 + Docker (Testcontainers)")), req=[])

    # ---- C1
    N("C1-TEN-21", A1, "Tenant compat removal", "no new INSERT into the four tenant tables omits tenant_id; the pending list only shrinks", "Static", "P2", "Grep test green", J(("TenantInsertPathsGrepTest", "")), "C1")
    N("C1-TEN-22", A1, "Tenant status", "suspended tenant answers 403 TENANT_SUSPENDED over HTTP (not only 'closed')", "Negative", "P0", "403 TENANT_SUSPENDED", GAP("tests assert access is closed, not the HTTP code"), "C1")
    N("C1-TEN-23", A1, "AccessContext", "new code never relies on the fail-open default tenant/tenantContext parameters of AccessContext", "Static", "P0", "Architecture/grep test", GAP("no test forbids use of the defaulted trailing parameters"), "C1")
    N("C1-TEN-25", A1, "Tenant model", "user without a tenant_members row is treated as MEMBER (accepted fail-open R-07) — explicit test", "Boundary", "P2", "MEMBER, no admin", GAP("only implied by migration tests"), "C1")
    N("C1-TEN-26", A1, "Tenant model", "workspace member active => tenant MEMBER (never TENANT_ADMIN); a deactivated tenant membership is never re-activated automatically", "Persistence", "P0", "Trigger invariant holds", J(("TenantFoundationMigrationTests", "becoming a workspace member makes the user a MEMBER"), ("TenantFoundationMigrationTests", "re-activating a workspace member")), "C1")
    N("C1-PRM-20", A1, "Role matrix", "project EDITOR/OWNER and WORKSPACE_ADMIN hold APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE; manage/mutate/workflow only WORKSPACE_ADMIN; VIEWER only view+use", "Unauthorized", "P0", "Matrix as D-C1-14", J(("PermissionCanonicalTests", "default deny - managing, mutating and workflow codes"), ("AccessAdaptersTests", "a project owner may read and query but not manage"), ("AccessAdaptersTests", "a workspace admin may do every operation")), "C1")
    N("C1-PRM-21", A1, "Role matrix", "VIEWER holds QUERY_EXECUTE only when the app is in published state (T15)", "Boundary", "P2", "Published-state rule", GAP("contract defers to T15; no test"), "C1")
    N("C1-PRM-24", A1, "SYSTEM_ADMIN", "flag off: a system admin who is not a workspace member gets 404 on its projects and an empty project list over HTTP", "Negative", "P1", "404 / empty list", GAP("only the adapter-level policy is tested"), "C1")
    N("C1-ESC-07", A1, "Self-grant", "self-leave of a workspace/project/tenant is still allowed (only self-grant is closed)", "Positive", "P2", "Leave works", GAP("no test names self-leave"), "C1")
    N("C1-SEC-24", A1, "Webhook route", "SecurityConfiguration declares DATA_WEBHOOK_INGEST and the /api/v1/webhooks/data/ path (anonymous + CSRF-exempt only that route)", "Static", "P0", "SQ-08", STATIC("SQ-08"), "C0")
    N("C1-SEC-25", A1, "Tenant AI", "tenant-AI outbound call re-resolves and checks the address with PublicAddress at call time (DNS rebinding), not only at PUT time", "Negative", "P1", "Rebinding blocked before the flag is enabled", GAP("flag app.tenant-ai.enabled is OFF; condition from integration-contract §6 has no test"), "C2")
    N("C1-SEC-26", A1, "Sync", "a sync job whose owner lost the permission pauses and pulls nothing; the run acts as the job owner", "Unauthorized", "P0", "Owner permission re-checked", J(("SyncTests", "a job whose owner lost the permission pauses"), ("SyncTests", "a run acts as the job owner")), "C3")
    N("C1-SEC-27", A1, "Postgres TLS/target", "TLS is verify-full with host-name verification, never require; denied host stays denied; undeterminable denied host blocks", "Negative", "P0", "Fail closed", J(("PostgresSessionSecurityTests", "TLS is verify-full"), ("PostgresSessionSecurityTests", "configuration accepts only verify-full"), ("PostgresSessionSecurityTests", "a denied host whose address cannot be determined blocks the connection"), ("PostgresConnectorTests", "a denied host stays denied")), "C3")
    # ---- C0
    N("C0-WIR-18", A0, "Runtime API", "with flags ON, no browser-callable mutation route exists: POST /api/v1/data/mutate and /api/v1/data/** are not mounted", "Negative", "P0", "404 for every route outside the 4 approved segments", GAP("AppRuntimeFlagsOffTests covers flags OFF only"), "C0")
    N("C0-WIR-19", A0, "Runtime API R1", "R1 over HTTP: cache field HIT|MISS|BYPASS and 422 MAPPING_REF_REQUIRED when zero/several mappings", "Positive", "P2", "Contract response shape", GAP("pure-function tests only"), "C0")
    N("C0-WIR-20", A0, "Runtime API R1", "R1 over HTTP: source failures map to 502/504 with safe messages; no DataGateway bean -> 503 DATA_RUNTIME_UNAVAILABLE", "Partial-failure", "P2", "Mapped statuses", GAP("status table tested at unit level, not over HTTP with a failing source"), "C0")
    N("C0-WIR-22", A0, "Runtime API R3", "R3 over HTTP on the real stack: POST runs 202 + run view, GET run (creator or WORKFLOW_MANAGE else 404), cancel idempotent", "Positive", "P1", "Run view without appId/creator/approval/snapshot", BLK("needs the isolated stack"), "C0")
    N("C0-WIR-24", A0, "OIDC/CORS", "OIDC redirect/logout URIs and CORS for the three portal origins on the real stack", "Positive", "P2", "Three origins work", BLK("needs stack + Keycloak (GAP-C6-03)"), "C0")
    N("C0-ARCH-07", A0, "Layering", "architecture tests for data.*, app.definition and access/tenancy import rules (only logic.* is scanned)", "Static", "P1", "Import rules enforced", GAP("ActionContractV2Tests scans logic.* only"), "C0")
    N("C0-ARCH-08", A0, "Conformance", "C3 mapping/viewmodel parsers and C4 catalogs load the shared conformance fixtures", "Regression", "P2", "Shared fixtures used", GAP("only C2 validator and C5 type-check load them (contract says C3/C4 add loads after import)"), "C0")
    N("C0-MIG-25", A0, "Migration immutability", "applied migration files V1..V29 are never edited (hash pin / diff check)", "Static", "P1", "No edited migration", GAP("only Flyway checksum at startup on a database that already applied them; no pinned-hash guard"), "C0")
    N("C0-MIG-26", A0, "Migration conventions", "each new table has (tenant_id, created_at) index where rows grow and a retention statement", "Static", "P2", "Convention holds", GAP("convention in MIGRATION_LEDGER, no check"), "C0")
    N("C0-MIG-27", A0, "V27 undo", "U27 undo script is guarded (refuses while publish_configs rows exist)", "Rollback", "P1", "Guarded undo", GAP("PublishConfigsMigrationTests has no undo test"), "C2")
    # ---- C2
    N("C2-DEF-15", A2, "Definition", "extensions{} keys must be namespaced xweb.*", "Boundary", "P2", "Un-namespaced key rejected", GAP("only URL/credential/code-like keys are tested"), "C2")
    N("C2-PUB-13", A2, "Publish model", "rollback never reads publish_configs; deployment visibility is fixed at deploy time", "Negative", "P1", "Served state is immutable", GAP("no test"), "C2")
    N("C2-PUB-14", A2, "Publish model", "adopt-draft copies the AppDefinition publishConfig draft into the persistent policy (CAS revision)", "Positive", "P2", "Policy updated", GAP("only 'draft changes nothing by itself' is tested"), "C2")
    N("C2-PUB-15", A2, "Publish model", "public_data_approved is re-checked against the CURRENT dataBindings at publish time (R-14)", "Negative", "P0", "Publish refused if bindings changed after approval", GAP("approval-on-confirmation is tested; re-check at publish time is not"), "C0")
    N("C2-PUB-16", A2, "Publish model", "deployments_visibility_check extended for TENANT / PRIVATE_LINK (separate C0 migration tied to publish wiring)", "Positive", "P2", "Visibility accepted", BLK("migration not requested/numbered (MIGRATION_LEDGER 'not requested')"), "C0")
    V30 = [
        ("01", "two publishes of the same app race: exactly one holds the scope lease, the other waits (SCOPE_BUSY event) then activates in order or ends STALE_PUBLISH; pointer never older than newest intent", "Concurrent", "P0"),
        ("02", "stale publisher: older activation_seq finishing after a newer one ends FAILED/STALE_PUBLISH, pointer unchanged (regression test for F-1 last-writer-wins)", "Concurrent", "P0"),
        ("03", "publish racing a manual rollback in both orders; rollback with wrong expectedActiveDeploymentId -> 409 ROLLBACK_STALE", "Concurrent", "P0"),
        ("04", "lease expiry: a worker stops heartbeating, another takes over, the dead worker's pointer CAS affects 0 rows (fencing)", "Timeout", "P0"),
        ("05", "idempotency: same Idempotency-Key twice -> one effect; other body -> IDEMPOTENCY_KEY_REUSED; queue redelivery continues under its own lease", "Idempotency", "P0"),
        ("06", "crash inside ROLLING_BACK: sweeper resumes, ends FAILED with pointer = previous or NULL, never RUNNING and never rolls forward", "Restart", "P0"),
        ("07", "failed restore: events ROLLBACK_FAILED then ROLLBACK_OFFLINE, pointer NULL (fail-closed), deployment FAILED", "Rollback", "P0"),
        ("08", "manual rollback failure changes no deployment status; ROLLED_BACK only when moving to an older release", "Rollback", "P0"),
        ("09", "unpublish vs publish race (unpublish takes the same lease)", "Concurrent", "P0"),
        ("10", "Batch-1 tests (fix/c2-v3) stay green unchanged after Batch 2", "Regression", "P1"),
        ("11", "V30 schema: constraint accepts ROLLING_BACK and nothing else; lease all-or-none; activation_seq backfill; undo U30 refuses while ROLLING_BACK or a lease is held", "Persistence", "P0"),
        ("12", "DeploymentStatus legal edges only: DEPLOYING->ROLLING_BACK, ROLLING_BACK->FAILED is the only exit, RUNNING->ROLLED_BACK only to an older release", "Boundary", "P0"),
        ("13", "the sites gateway never serves a ROLLING_BACK deployment (live() serves DEPLOYING and RUNNING only)", "Negative", "P0"),
        ("14", "release restorability is derived: RUNNING + artifact present + verifier OK; ROLLED_BACK/FAILED/damaged artifact are not restorable", "Negative", "P1"),
        ("15", "artifact retention never deletes the artifact of the ACTIVE or a RESTORABLE release nor the previous release of a deployment in progress", "Persistence", "P0"),
        ("16", "F-6: auto-rollback of a site also rolls the server runtime back (or stops the new one) idempotently", "Rollback", "P1"),
        ("17", "previous_deployment_id is read and stored under the lease (no regex over SWITCH event text)", "Persistence", "P1"),
        ("18", "no global lock: other projects, other tenants and reads (GET /site, serving) are never blocked", "Concurrent", "P1"),
        ("19", "API additive: rollback/unpublish accept expectedActiveDeploymentId + Idempotency-Key; SCOPE_BUSY 409 + Retry-After; SiteInfo.operation and pointerVersion", "Positive", "P1"),
    ]
    for (i, c, t, p) in V30:
        N("C2-V30-" + i, A2, "Deploy contract V30 (C2 Batch 2)", c, t, p, "As specified in C2_DEPLOY_CONTRACT section 7", BLK("V30 not created; C2 Batch 2 not integrated (H-2 handoff: these are the QA scenarios)"), "C2")
    # ---- C3 management API (contract frozen, NOT integrated)
    MGT = [
        ("01", "every route: client never sends tenantId; any authority key (tenantId, workspaceId, projectId, credentialRef, id, createdBy, userId, version outside expectedVersion) or unknown key -> 400 INVALID_PARAMS", "Malformed", "P0"),
        ("02", "session + CSRF on every state-changing call: 401 AUTHENTICATION_REQUIRED, 403 CSRF_INVALID", "Unauthorized", "P0"),
        ("03", "all routes mounted only with app.data-platform.enabled=true (disabled = 404)", "Negative", "P0"),
        ("04", "default deny and disclosure: non-member, unknown, other-workspace and other-tenant resources all answer the same 404; 403 only for a member lacking the permission", "Cross-tenant", "P0"),
        ("05", "no secret anywhere: no response, error, log, audit payload, header or URL carries password/token/ciphertext/credentialRef/connection string", "Negative", "P0"),
        ("06", "optimistic concurrency: PATCH/PUT with a stale expectedVersion -> 409 CONFLICT and nothing changes", "Concurrent", "P0"),
        ("07", "changing calls throttled per tenant (60 changes/60 s): 429 RATE_LIMITED + Retry-After; reads not throttled", "Boundary", "P1"),
        ("08", "permission table per route group (VIEW for reads, MANAGE for changes, samples need MANAGE+QUERY_EXECUTE, bindings need APP_EDIT + data-source permission); definition summaries expose no SQL/template", "Unauthorized", "P0"),
        ("09", "data source create 201 / PATCH all-or-nothing (one transaction, one version bump) / DELETE 204", "Positive", "P0"),
        ("10", "DELETE data source: 409 while any TEST/LIVE binding uses it or a mutation idempotency row is RESERVED/UNKNOWN; otherwise definitions, snapshots, finished rows and credential removed in one transaction", "Partial-failure", "P0"),
        ("11", "name rule + case-insensitive uniqueness per tenant (409); secret-looking config key/value -> 400 INVALID_CONFIG", "Negative", "P1"),
        ("12", "credential routes: GET returns key names only, PUT is write-only 1–8 keys that fit the connector, DELETE 204 even if nothing; body never logged or audited", "Negative", "P0"),
        ("13", "connection test: 200 TestResult always when run (codes listed), 409 DISABLED, 429; never the credential or raw driver message", "Negative", "P1"),
        ("14", "schema discover (samples masked, 429/REFRESH_TOO_SOON) and GET latest snapshot (404 when none)", "Positive", "P1"),
        ("15", "query/mutation definitions scoped by data source (not by project): id regex, validated by the same Kotlin types as the gateway, READ_ONLY_VIOLATION 422, parameterised templates only", "Malformed", "P0"),
        ("16", "TEST/LIVE bindings: mode exactly TEST|LIVE, source must be same tenant AND workspace (404 otherwise), never fall back to each other, APP_EDIT always required", "Cross-tenant", "P0"),
        ("17", "error envelope {code,message,requestId,retryable,details} on every non-2xx; retryable true only for RATE_LIMITED/REFRESH_TOO_SOON/TIMEOUT-class; details never carries a submitted value", "Positive", "P1"),
        ("18", "audit: every change writes an audit row (ids/versions/hash, never body/credential/config value) and the write + audit are one unit — an unauditable change is not applied", "Partial-failure", "P0"),
    ]
    for (i, c, t, p) in MGT:
        N("C3-MGT-" + i, A3, "Management API v2", c, t, p, "As management-api.md", BLK("Management API NOT INTEGRATED (D-C0-28; C3 branch not GREEN; B-C0-W-03)"), "C3")
    N("C3-WHK-04", A3, "Webhook ingest", "webhook ingest handler mounted on /api/v1/webhooks/data/{endpointId} end to end (handler W-07)", "Positive", "P1", "Signed delivery accepted over HTTP", BLK("handler W-07 not wired; route answers 404 today (BOARD F-11)"), "C0")
    # ---- C4
    N("C4-WFL-20", A4, "Approvals", "LIVE workflow with an APPROVAL step is refused until the approvals tables exist (D5)", "Negative", "P1", "Definite refusal", GAP("D-C0-21 D5 states the rule; no test"), "C0")
    N("C4-RET-02", A4, "Retention", "RetentionService is scheduled in production wiring (stores implement purge, nothing schedules it)", "Positive", "P2", "Purge runs on schedule", GAP("D-C0-23 item 5: not scheduled yet"), "C0")
    N("C4-QUE-08", A4, "RabbitMQ durable queue", "queue adapter carries only the signal v1|jobId|tenantId|runId|stepId (no payload/actor), never requeues immediately, delivery-limit as safety net, on a real broker", "Restart", "P0", "Signal-only durable messages", BLK("WorkflowQueue not wired to RabbitMQ (B-C4-06; H-5 not imported); needs stack"), "C4")
    # ---- C5
    N("C5-STU-09", A5, "Portals", "TENANT_ADMIN access to the Admin portal (Q-1 undecided; Admin stays platform-only D-C0-19)", "Unauthorized", "P2", "Decided + tested rule", GAP("GAP-C6-02: no decision, no test"), "C5")
    N("C5-STU-10", A5, "Deploy UI", "UI treats ROLLING_BACK as in progress (TERMINAL stays RUNNING|FAILED|ROLLED_BACK), handles SCOPE_BUSY/ROLLBACK_STALE, shows SiteInfo.operation (H-1)", "Positive", "P1", "No spinner loop, correct messages", BLK("H-1 not implemented; V30 pending"), "C5")
    N("C5-STU-11", A5, "Honesty", "panels for data/action/workflow show NOT READY instead of faking while the backend is not integrated", "Negative", "P1", "No fake functionality", GAP("no browser/unit test names this rule (working rule: do not fake)"), "C5")

    N("C2-DEF-17", A2, "Permission vocabulary", "PermissionDef.permission accepts only the 14 canonical codes (membership test); a non-canonical code is rejected by the validator and by the initial/template document check", "Negative", "P0", "Rejected with a path", J(("ContractConformanceTests", "only canonical permission codes are accepted"), ("SchemaServiceInitializationTests", "an initial document with a non canonical permission code is rejected"), ("AppDefinitionActionWorkflowRulesTests", "a permission resource must exist")), "C2")
    N("C3-SRC-09", A3, "Schema discovery PII", "discovery samples are off by default and masked by name and value shape; the AI catalog exposes only masked metadata granted to the caller (no credential/host/config/SQL/raw sample)", "Negative", "P0", "No raw sample or secret leaves the server", J(("DiscoveryTests", ""), ("AiDataCatalogTests", ""), ("PostgresDiscoveryTests", "")), "C3")
    # ---- rows created by the batch-7 audit of blockers / T19 / multi-node items (all are real, unresolved at 8e91172; none is a mock)
    N("C1-AUD-06", A1, "Audit hardening (T19)", "audit_events gets a tenant column and DB privileges so even the table owner cannot disable the append-only trigger", "Negative", "P1", "Owner cannot tamper", BLK("not implemented: MIGRATION_LEDGER item 6 (T19) is unnumbered; db-probes DB-AUD-8 shows the owner can disable the trigger"), "C0")
    N("C1-SEC-28", A1, "Dependency scan", "backend dependency vulnerability scan (OWASP/Gradle) — only the frontend has one (scripts/check.sh npm audit)", "Static", "P2", "No known critical CVE in backend deps", GAP("no backend scan exists in scripts/ or build.gradle.kts"), "C0")
    N("C2-DEF-16", A2, "AI planner wiring", "AiDataCatalogAdapter (W-06) wires C3's granted catalog into the C2 planner", "Positive", "P1", "Planner only sees granted sources", BLK("not implemented: no AiDataCatalogAdapter in wiring/** (B-C0-W-02); app.ai-planner.enabled stays OFF"), "C0")
    N("C3-QRY-14", A3, "Realtime/cache bus", "realtime events and cache bypass-on-invalidate work across several API nodes (shared bus, B-C3-09)", "Concurrent", "P1", "Consistent invalidation on every node", BLK("not implemented: DataEventBus is in-memory per node; shared Redis bus not built (B-C3-09)"), "C3")
    N("C3-SYN-01", A3, "Sync scheduling", "SyncRunner due jobs are scheduled by the application (B-C3-10)", "Positive", "P2", "Sync jobs run on a schedule", BLK("not implemented: no @Scheduled/runDue wiring for SyncRunner in wiring/**"), "C0")
    N("C4-ACT-23", A4, "Rate limit (cluster)", "tenant rate limit shared across nodes (B-C4-08); today a per-node in-memory bucket", "Load", "P1", "Limit enforced cluster-wide", BLK("not implemented: only InMemory TenantRateLimiter exists (D-C4-14, B-C4-08 PARTIAL)"), "C0")
    N("NF-DOC-04", "Non-functional", "Docs", "BLOCKERS.md status column is current (B-C4-05, B-C4-07, B-C4-09, B-C5-04, B-C5-09, B-C0-W-01 are listed OPEN although V29, the wiring, the controllers, TEST/WouldRun and decisions D-C0-17/19 exist)", "Static", "P3", "Statuses match the tree", ("fail", "BLOCKERS.md still lists delivered items as OPEN (checked: V29 file, AppRuntimeConfiguration.kt, AppRuntime*Controller.kt, ActionResult.WouldRun, DECISIONS D-C0-17/19)", "8e91172"), "C0")

    # ---------------------------------------------------------------- requirements
    R = []

    def req(id, doc, clause, text, owner, risk, gate, tests, cases=None):
        R.append(dict(id=id, doc=doc, clause=clause, text=text, owner=owner, risk=risk, gate=gate, tests=tests, cases=cases or []))

    TP = "contracts/v2/tenant-permission.md"
    req("REQ-TP-01", TP, "§1 Tenant model", "A workspace belongs to exactly one tenant; workspace_members, projects, project_members carry tenant_id equal to their workspace's (composite FKs enforce it)", "C1", "P0", "G3", ["C1-TEN-08", "C0-MIG-18"], [("TenantFoundationMigrationTests", "workspace tenant constraint"), ("TenantFoundationMigrationTests", "no row of the foundation tables is left without a tenant")])
    req("REQ-TP-02", TP, "§1 + D-C1-12", "tenant_members is the single source of truth for TENANT_ADMIN; no row => MEMBER (accepted fail-open)", "C1", "P0", "G3", ["C1-PRM-18", "C1-TEN-25", "C1-TEN-26"], [("TenantAdminViaRoleTests", "tenant admin authority")])
    req("REQ-TP-03", TP, "§1 DEFAULT tenant", "TenantIds.DEFAULT exists after V26 and all existing data lands in it", "C1", "P1", "G3", ["C0-MIG-17"], [("TenantFoundationMigrationTests", "DEFAULT tenant exists")])
    req("REQ-TP-04", TP, "§2 TenantContext", "TenantContext is resolved server-side from the workspace in the path; clients never send a tenant id; unknown tenant/workspace or no membership -> 404", "C1", "P0", "G3", ["C1-TEN-01", "C1-TEN-02", "C1-TEN-04"], [("TenantAccessTests", "AccessContext carries the tenant resolved from the workspace")])
    req("REQ-TP-05", TP, "§2 suspended", "suspended tenant -> 403 TENANT_SUSPENDED", "C1", "P0", "G3", ["C1-TEN-09", "C1-TEN-22"], [("AccessAdaptersTests", "resolver - a suspended tenant addresses nobody")])
    req("REQ-TP-06", TP, "§2 + D-C0-14", "single ActorKind/TenantContext definition; C3/C4 placeholders deleted; adapters convert ActorKind by name and an unknown name is denied (never USER)", "C1", "P1", "G2", ["C0-WIR-11", "C0-ARCH-01"], [("ActorKindsTests", ""), ("ActionContractV2Tests", "ActorKind keeps the four names")])
    req("REQ-TP-07", TP, "§3 AccessContext", "new code never relies on the defaulted trailing AccessContext parameters (they fail open to the DEFAULT tenant)", "C1", "P0", "G3", ["C1-TEN-23"])
    req("REQ-TP-08", TP, "§4 + D-C1-11", "SYSTEM_ADMIN is platform scope: no business-data access unless app.tenancy.system-admin-business-access=true (default false, declared)", "C1", "P0", "G3", ["C1-PRM-16", "C1-PRM-17", "C0-WIR-06"], [("TenantAccessTests", "SYSTEM_ADMIN default policy"), ("TenantAccessTests", "SYSTEM_ADMIN that is a member gets exactly its member permissions")])
    req("REQ-TP-09", TP, "§4 consequence", "flag off: a non-member system admin gets 404 on workspace projects and an empty project list", "C1", "P1", "G3", ["C1-PRM-16", "C1-PRM-24"])
    req("REQ-TP-10", TP, "§4 TENANT_ADMIN", "TENANT_ADMIN holds TENANT_MANAGE/TENANT_MEMBERS on its own tenant only and no implicit workspace/app/data access", "C1", "P0", "G3", ["C1-PRM-18"], [("TenantAccessTests", "TENANT_ADMIN is a tenant-level role only"), ("TenantAccessTests", "tenant admin API - platform operations need SYSTEM_ADMIN")])
    req("REQ-TP-11", TP, "§4 R-08 + D-C1-15", "nobody grants itself access: self-add or self-role-change is refused 403 SELF_GRANT_FORBIDDEN for every caller incl. SYSTEM_ADMIN/TENANT_ADMIN/WORKSPACE_ADMIN; others can still be granted; self-leave allowed", "C1", "P0", "G3", ["C1-ESC-01", "C1-ESC-02", "C1-ESC-03", "C1-ESC-04", "C1-ESC-05", "C1-ESC-06", "C1-ESC-07"], [("PrivilegeEscalationTests", ""), ("TenantAccessLegacyFlagTests", "flag on - self grant is still rejected")])
    req("REQ-TP-12", TP, "§5 vocabulary", "exactly 14 canonical permission codes (incl. TENANT_MANAGE/TENANT_MEMBERS); PermissionCodes.CANONICAL equals the contract table; new constants use the exact canonical name", "C1", "P0", "G3", ["C1-PRM-03", "C2-DEF-07"], [("PermissionCanonicalTests", "canonical set is closed"), ("ContractConformanceTests", "permission codes equal the table of the frozen contract")])
    req("REQ-TP-13", TP, "§5 rule 1", "a code outside the table is not allowed in PermissionDef.permission (membership test, not regex)", "C2", "P0", "G3", ["C2-DEF-17", "C2-DEF-06"], [("ContractConformanceTests", "only canonical permission codes are accepted"), ("SchemaServiceInitializationTests", "non canonical permission code is rejected")])
    req("REQ-TP-14", TP, "§5 rule 2 + D-C1-14", "deny by default: a new code is granted to no role until the matrix says so; APP_USE/DATA_SOURCE_VIEW/QUERY_EXECUTE/ACTION_EXECUTE for EDITOR/OWNER/WS_ADMIN; manage/mutate/workflow only WS_ADMIN; VIEWER/PUBLISHER APP_USE only", "C1", "P0", "G3", ["C1-PRM-01", "C1-PRM-20", "C1-PRM-21"], [("PermissionCanonicalTests", "default deny")])
    req("REQ-TP-15", TP, "§5 legacy constants", "legacy storage constants stay enforced server-side and are never listed to clients nor accepted in PermissionDef", "C1", "P1", "G3", ["C1-PRM-03"], [("PermissionCanonicalTests", "canonicalCodesOf exposes canonical codes only"), ("PermissionCanonicalTests", "legacy permission mapping")])
    req("REQ-TP-16", TP, "§5 rule 4", "hiding a button in the UI is not authorisation (the server decides)", "C1", "P0", "G3", ["C1-PRM-12", "C5-STU-06"], [("ActionRuntimeTests", "the UI cannot grant itself anything")])
    req("REQ-TP-17", TP, "§6 GatewayAuthorizer", "GatewayOperation -> canonical code mapping is total (DATASOURCE_READ, MANAGE, QUERY_EXECUTE, MUTATION_EXECUTE, SCHEMA_*, CACHE_REFRESH, EVENTS_SUBSCRIBE, SYNC/WEBHOOK_MANAGE); also authorises projectId/appVersionId", "C1", "P0", "G3", ["C1-PRM-02", "C1-TEN-02"], [("AccessAdaptersTests", "operation to permission mapping is total"), ("AccessAdaptersTests", "an app version must belong to the project"), ("AccessAdaptersTests", "a user of another workspace cannot use a foreign project id")])
    req("REQ-TP-18", TP, "§6 AccessPort", "AccessRequest.permission uses canonical codes; mode=TEST may require APP_EDIT", "C1", "P0", "G3", ["C1-PRM-04"], [("AccessAdaptersTests", "port - canonical codes only")])
    req("REQ-TP-19", TP, "§6 tenant check", "ctx.tenantId is verified against the app's real tenant (not a tautology)", "C1", "P0", "G3", ["C1-TEN-02"], [("AccessAdaptersTests", "port - tenant gate, claimed tenant and actor kinds")])
    req("REQ-TP-20", TP, "§6 MeResponse", "/auth/me exposes tenant memberships/roles and canonical permissions so portals can gate", "C1", "P1", "G3", ["C1-PRM-19"])
    req("REQ-TP-21", TP, "§7 + D-C1-13", "V26 compat: BEFORE-fill trigger fills only NULL tenant_id and RAISES on a non-NULL mismatch (never overwrites)", "C1", "P0", "G3", ["C1-TEN-08", "C0-MIG-18"], [("TenantFoundationMigrationTests", "legacy INSERT without tenant_id still works on every table"), ("TenantFoundationMigrationTests", "an explicit tenant_id that matches the workspace tenant is accepted")])
    req("REQ-TP-22", TP, "§7 removal plan", "compat scaffolding has a removal plan; TenantInsertPathsGrepTest pending list only shrinks", "C1", "P2", "G2", ["C1-TEN-21"])
    req("REQ-TP-23", TP, "D-C1-16", "C1 adapters are pure policy; default deny; only USER actors allowed (SYSTEM/SERVICE/APP_TOKEN denied)", "C1", "P0", "G3", ["C1-PRM-01", "C0-WIR-11", "C0-WIR-10"], [("AccessAdaptersTests", "default deny - unknown operation, missing workspace, non-user actors")])

    RA = "contracts/v2/runtime-api.md"
    req("REQ-RA-01", RA, "§0/§1 route family + D-C0-18", "only queries/actions/workflows/workflow-runs exist under /app-runtime; /api/v1/data/** (incl. browser-callable /mutate) is NOT mounted; no other write route", "C0", "P0", "G3", ["C0-WIR-05", "C0-WIR-18"], [("AppRuntimeFlagsOffTests", "the data platform administration family is not mounted either")])
    req("REQ-RA-02", RA, "§1 authentication", "normal session cookie + CSRF; SecurityConfiguration unchanged; no permitAll on runtime routes", "C0", "P0", "G3", ["C1-AUTH-01", "C1-AUTH-03"], [("AppRuntimeApiTests", "an anonymous caller is not let in")])
    req("REQ-RA-03", RA, "§1 tenantId", "tenantId is never read from the request; unknown body fields (tenantId, userId, dataSourceId, sql, url…) rejected 400 INVALID_REQUEST (strict parsing)", "C0", "P0", "G3", ["C1-TEN-01", "C0-WIR-16"], [("AppRuntimeApiTests", "a body field the contract does not list is refused, tenantId first"), ("RuntimeApiPureTests", "fields the contract does not list are refused")])
    req("REQ-RA-04", RA, "§1 ownership", "workspace/project ownership by AccessService.forProject: 404 PROJECT_NOT_FOUND for non-member, other workspace or other tenant; existence never disclosed", "C0", "P0", "G3", ["C1-TEN-04", "C1-TEN-05"], [("AppRuntimeApiTests", "a user of another workspace sees the project as missing on every route")])
    req("REQ-RA-05", RA, "§1 actor", "the caller is always ActorKind.USER; C1 denies every other kind", "C0", "P0", "G3", ["C0-WIR-11", "C1-PRM-01"])
    req("REQ-RA-06", RA, "§1 mode", "mode LIVE (default, published version) or TEST (draft; needs APP_EDIT; no side effect; WouldRun); exactly LIVE|TEST", "C0", "P0", "G6", ["C1-PRM-04", "C4-ACT-12", "C0-WIR-16"], [("AppRuntimeApiTests", "a manager gets a would-run answer in TEST mode"), ("RuntimeApiPureTests", "mode must be LIVE or TEST exactly")])
    req("REQ-RA-07", RA, "§1 default deny", "unknown permission code/mode/actor kind/missing wiring answers with an error, never data", "C0", "P0", "G3", ["C1-PRM-01", "C1-PRM-15"])
    req("REQ-RA-08", RA, "§1 error body", "non-2xx body {code,message,requestId,retryable,details}; retryable false unless C4/C3 say otherwise", "C0", "P1", "G2", ["C0-WIR-15"], [("RuntimeApiPureTests", "the generic error body has the ApiError shape")])
    req("REQ-RA-09", RA, "§1 flags", "app.data-platform.enabled and app.workflow.enabled default false; a disabled flag means the controller bean does not exist (404)", "C0", "P0", "G3", ["C0-WIR-05", "C0-WIR-06"])
    req("REQ-RA-10", RA, "§1 runtime ids", "no runtime ids are persisted or returned (derived UUIDs live inside one call)", "C0", "P1", "G5", ["C2-DEF-11"], [("AppDataBindingResolverTests", "resolving never changes the definition and no runtime id appears in it"), ("AppDataBindingResolverTests", "a query resolves to its source and approved operation, never to a stored runtime id")])
    req("REQ-RA-11", RA, "§2 R1 pipeline", "R1 run query: forProject -> mode gate -> APP_USE(LIVE)/APP_EDIT(TEST) + QUERY_EXECUTE -> definition (tenant-checked) -> resolver -> DataGateway (authorises QUERY_EXECUTE again)", "C0", "P0", "G5", ["C3-QRY-01", "C1-PRM-04", "C1-PRM-06", "C1-PRM-07", "C0-WIR-09"], [("DataRuntimeLiveApiTests", "a LIVE query reads rows through the persistent source, query and binding")])
    req("REQ-RA-12", RA, "§2 R1 request/response", "params <= 40 plain values; page limit 1..10000, offset 0..1000000; mappingRef optional (single mapping else 422 MAPPING_REF_REQUIRED); 200 {queryId,mode,cache,result}", "C0", "P1", "G5", ["C3-QRY-09", "C0-WIR-19"], [("RuntimeApiPureTests", "query params are plain values within the limit"), ("RuntimeApiPureTests", "the mapping of a query is the single mapping that names it")])
    req("REQ-RA-13", RA, "§2 R1 failures", "failure table: 400/403/404, 422 WRONG_MODE|DATA_SOURCE_UNBOUND|INVALID_PARAMS, 429, 502/504 safe messages, 503 DATA_RUNTIME_UNAVAILABLE", "C0", "P1", "G5", ["C3-QRY-02", "C0-WIR-20"], [("AppRuntimeApiTests", "TEST mode of a query needs APP_EDIT and, with nothing bound, the data runtime answers 422 DATA_SOURCE_UNBOUND"), ("AppRuntimeApiTests", "LIVE finds no definition while nothing is published")])
    req("REQ-RA-14", RA, "§3 R2 envelope", "R2 execute action: OK/FAILED/WOULD_RUN envelope with followUps; HTTP status table (400/403/404/409/422/429+Retry-After/501/503/504/500)", "C0", "P0", "G6", ["C0-WIR-15", "C4-ACT-14"], [("RuntimeApiPureTests", "status codes follow the contract table"), ("RuntimeApiPureTests", "ok and would-run are 200 and a would-run says nothing was executed")])
    req("REQ-RA-15", RA, "§3 retryable", "retryable is never true for IDEMPOTENCY_OUTCOME_UNKNOWN and MUTATION_REJECTED; the UI runs no onError after UNKNOWN", "C0", "P0", "G6", ["C0-WIR-15", "C4-ACT-06", "C4-ACT-09"], [("RuntimeApiPureTests", "an unknown outcome is 409 and never retryable even if a caller claimed it was"), ("RuntimeApiPureTests", "a rejected mutation is 422 and may carry its onError follow-ups")])
    req("REQ-RA-16", RA, "§3 request", "idempotencyKey ^[A-Za-z0-9._:-]{1,128}$ (required for mutating LIVE); trigger kind is always UI_EVENT; context-mapped inputs cannot be supplied by the client", "C0", "P0", "G6", ["C4-ACT-01", "C1-PRM-12"], [("ActionRuntimeTests", "keys are validated"), ("ActionRuntimeTests", "dispatch cannot spoof the acting user through the payload")])
    req("REQ-RA-17", RA, "§3 + D-C0-18", "a data mutation is only an action of type SUBMIT_FORM|CREATE|UPDATE|DELETE_RECORD|CALL_API; the C4 pipeline (permissions, derived key, audit, UNKNOWN semantics) is in front of every write", "C0", "P0", "G6", ["C0-WIR-18", "C3-MUT-01", "C4-ACT-01"])
    req("REQ-RA-18", RA, "§4 R3", "workflows: POST runs 202 + run view (key required); GET run (creator or WORKFLOW_MANAGE else 404); cancel idempotent; run view hides appId/creator/approval/snapshot", "C0", "P0", "G6", ["C4-WFL-01", "C4-WFL-11", "C4-WFL-16", "C0-WIR-22"], [("AppRuntimeApiTests", "a workflow start without a key is a bad request and an unknown run is not found"), ("WorkflowEngineTests", "the view never exposes the definition snapshot or the input")])
    req("REQ-RA-19", RA, "§4 + D-C4-17", "a workflow step that ends IDEMPOTENCY_OUTCOME_UNKNOWN fails the run with that code, is not routed to onError and is not compensated", "C4", "P0", "G6", ["C4-WFL-08", "C4-WFL-09"])
    req("REQ-RA-20", RA, "§5 permission mapping", "R1/R2/R3 controller gates and second enforcement (C3 GatewayAuthorizer QUERY_EXECUTE/MUTATION_EXECUTE; C4 APP_USE, ACTION_EXECUTE, DATA_MUTATE, declared permission, WORKFLOW_EXECUTE)", "C0", "P0", "G3", ["C1-PRM-04", "C1-PRM-07", "C1-PRM-08", "C1-PRM-09", "C1-PRM-10", "C1-PRM-11"])
    req("REQ-RA-21", RA, "§6 + D-C0-15", "C3 -> C4 write error mapping in ActionDataPortAdapter: UNKNOWN/REJECTED non-retryable; IN_PROGRESS/RATE_LIMITED retryable; NOT_EXECUTED codes non-retryable; every other failure on a WRITE -> UNKNOWN; resolution errors definite", "C0", "P0", "G5", ["C0-WIR-12", "C3-MUT-04"], [("ActionDataPortAdapterTests", "an ambiguous write failure is IDEMPOTENCY_OUTCOME_UNKNOWN"), ("ActionDataPortAdapterTests", "definite and busy failures keep their code"), ("ActionDataPortAdapterTests", "a failure result can never be made retryable for the two terminal codes"), ("DataWriteErrorsTests", "a transport style exception on a write is an unknown outcome"), ("DataWriteErrorsTests", "no failure of a write is ever a bare generic code")])
    req("REQ-RA-22", RA, "D-C0-20/23 volatile guard", "LIVE mutating actions and workflow starts are refused 503 RUNTIME_STORES_VOLATILE while stores are volatile; durable JDBC stores are the default; no silent fallback", "C0", "P0", "G6", ["C0-WIR-07", "C0-WIR-08", "C0-WIR-01", "C0-WIR-03"])
    req("REQ-RA-23", RA, "D-C0-27 item 6", "no anonymous (published) DataGateway route exists; published runtime channel apiBase is null today", "C0", "P0", "G7", ["C1-SEC-11", "C2-DEP-10"])

    DR = "contracts/v2/data-runtime.md"
    req("REQ-DR-01", DR, "§1 flow", "UI -> AppDefinition/ViewModel -> Query|Action -> Auth -> Permission -> DataGateway -> Connector; no raw URL, SQL or credential crosses the gateway", "C3", "P0", "G5", ["C1-SEC-05", "C1-SEC-06", "C3-QRY-03"])
    req("REQ-DR-02", DR, "§2 shapes", "canonical runtime shapes: GatewayQuery/GatewayMutation replace the draft; connector-internal types stay internal", "C3", "P2", "G5", ["C3-SRC-03", "C0-WIR-13"], [("ConnectorSpiTests", "")])
    req("REQ-DR-03", DR, "§2 + D-C3-13 mapping", "fields[].transforms[] is canonical (<= 8, ordered); legacy transform read-only; both present rejected", "C3", "P1", "G5", ["C3-QRY-10", "C2-DEF-07"], [("MappingContractTests", "a field with both keys is ambiguous and rejected"), ("MappingContractTests", "the writer emits only transforms")])
    req("REQ-DR-04", DR, "§2 SchemaSnapshot + §5 PII", "schema snapshots versioned, fingerprinted, immutable and tenant-scoped; samples off by default and masked; AI sees only AiSafeSchema", "C3", "P0", "G5", ["C3-SRC-04", "C3-SRC-09", "C2-DEF-14"], [("JdbcPersistenceTests", "schema snapshots are immutable per version"), ("AiDataCatalogTests", "")])
    req("REQ-DR-05", DR, "§3 resolution", "AppDataBindingResolver translates local ids to runtime ids (queryRef -> source + operationKey; mapping queryRef translated to the operation key); both UI query path and ActionDataPort use it", "C3", "P0", "G5", ["C2-DEF-11", "C0-WIR-13"], [("AppDataBindingResolverTests", "a mapping is translated from the local query id to the operation key C3 knows"), ("AppDataBindingResolverTests", "a data binding reads through its view model, query and mapping")])
    req("REQ-DR-06", DR, "§4 + D-C4-11 key", "mutation idempotency key mandatory ^[A-Za-z0-9_-]{8,128}$; reaching C3 it is derived base64url(sha256(tenant|app|user|actionOrQuery|clientKey)) (43 chars); raw client keys never forwarded", "C3", "P0", "G5", ["C3-IDM-03", "C4-ACT-15"], [("ActionContractV2Tests", "derived key is base64url sha256, 43 chars"), ("IdempotencyTests", "the derived key shape sha256 base64url is accepted")])
    req("REQ-DR-07", DR, "§4 scope + reservation", "C3 scopes keys by (tenant, dataSource, mutation, key); an ambiguous failure keeps the key reserved as UNKNOWN and it is never run again", "C3", "P0", "G5", ["C3-IDM-04", "C3-IDM-06", "C3-MUT-02", "C3-IDM-01"])
    req("REQ-DR-08", DR, "§4b outcomes", "write outcome table: 200 replayed; 409 UNKNOWN (never retry with same key); 422 MUTATION_REJECTED (key released); 409 IN_PROGRESS/CONFLICT; 422 unsupported/read-only; 400/403/404/429; first-attempt 502/504/500 then 409 UNKNOWN", "C3", "P0", "G5", ["C3-MUT-04", "C3-MUT-05", "C3-IDM-02", "C3-MUT-10", "C3-IDM-09"], [("DataWriteErrorsTests", ""), ("GatewayTests", "every failure code maps to a status and a body without detail")])
    req("REQ-DR-09", DR, "§4b rules", "only a failure on the NOT_EXECUTED allow-list releases a key; everything else marks it UNKNOWN", "C3", "P0", "G5", ["C3-MUT-05", "C3-IDM-07"], [("GatewayTests", "a definitely rejected mutation frees the key so a retry can run"), ("IdempotencyTests", "a definite not-executed failure frees the key")])
    req("REQ-DR-10", DR, "§4b disclosure", "a client never sees SQL, URL, headers, credentials or a cause in any error body (fixed text + stable code + requestId)", "C3", "P0", "G3", ["C1-SEC-05", "C3-QRY-03"], [("GatewayTests", "every failure code maps to a status and a body without detail"), ("GatewayTests", "no credential reaches a response an audit record or a log even when the connector blows up with it")])
    req("REQ-DR-11", DR, "§5 address policy", "one network address policy (PublicAddress); resolve once and pin (REST PinnedHttpsTransport, PG pinned socket); no redirects; caps on size/time/rows", "C3", "P0", "G3", ["C1-SEC-01", "C1-SEC-02"], [("PinnedHttpsTransportTests", "a redirect is returned and never followed")])
    req("REQ-DR-12", DR, "§5 SQL + D-C3-17/18", "SqlGuard single SELECT/WITH, rejects U&\"…\", comments, multi-statement, writing keywords/functions; per-session privilege preflight; deniedHosts fail closed; TLS verify-full only", "C3", "P0", "G3", ["C1-SEC-04", "C1-SEC-27"], [("SqlGuardTests", ""), ("PostgresSessionSecurityTests", "")])
    req("REQ-DR-13", DR, "§5 webhooks/cache/sync", "webhook replay guard keyed on the signature; cache stale-write race closed (ticket); sync re-checks the permission of the job owner", "C3", "P0", "G3", ["C1-SEC-09", "C3-QRY-07", "C1-SEC-26"], [("WebhookReplayTests", "the replay record is keyed by signature"), ("CacheRaceTests", "an answer computed before an invalidation cannot be stored after it - query level")])
    req("REQ-DR-14", DR, "§6 + D-C3-16", "webhook ingest path /api/v1/webhooks/data/{endpointId}; permitAll and CSRF-exempt only this route; v2 signature covers the delivery id; body <= 256 KiB; every auth failure answers alike", "C3", "P0", "G3", ["C1-SEC-24", "C1-SEC-11", "C1-SEC-10", "C1-SEC-13", "C3-WHK-04"], [("WebhookTests", "unknown disabled and malformed endpoint ids all look the same to the sender")])
    req("REQ-DR-15", DR, "§7 persistence", "every data table has tenant_id NOT NULL REFERENCES tenants(id); workspace-scoped tables use the composite FK", "C3", "P0", "G2", ["C0-MIG-11", "C0-MIG-12", "C0-MIG-13"])
    req("REQ-DR-16", DR, "D-C0-22 workspace ownership", "data sources are owned by a workspace at run time; cross-workspace/tenant source answers like a missing one; DB composite FKs enforce project/binding/source agreement", "C0", "P0", "G3", ["C1-TEN-11", "C1-TEN-12", "C1-TEN-13", "C0-MIG-13", "C0-PER-04"], [("DataRuntimeLiveApiTests", "same tenant, another workspace, a binding is refused by the writer and by the database")])
    req("REQ-DR-17", DR, "D-C0-21 D3/D4", "one bindings table with explicit TEST|LIVE mode (runtime only reads; TEST never writes a binding); data idempotency retention 30 d default, min 7 d", "C0", "P1", "G5", ["C3-QRY-02", "C3-IDM-05"], [("DataRuntimeLiveApiTests", "a TEST run reads the TEST binding only and never writes a binding")])

    AW = "contracts/v2/action-workflow.md"
    req("REQ-AW-01", AW, "§1 ActionType", "nine closed ActionTypes; REFRESH_QUERY canonical, RUN_QUERY/WRITE_DATA… rejected; NAVIGATE/REFRESH_QUERY are client instructions, all others need an idempotency key", "C4", "P1", "G6", ["C4-ACT-14", "C2-DEF-08"], [("ContractConformanceTests", "REFRESH_QUERY is an action type and the old RUN_QUERY alias is not")])
    req("REQ-AW-02", AW, "§2 + D-C4-10 trigger", "trigger optional on the definition; required only for UI-event runs; only a matching trigger can run UI-bound; malformed trigger fails closed", "C4", "P1", "G6", ["C4-ACT-19"], [("ActionContractV2Tests", "a UI-bound run of an action without a trigger is unknown"), ("ActionContractV2Tests", "a UI event that does not match the declared trigger is unknown"), ("ActionContractV2Tests", "workflow steps, schedules and chains do not need a trigger")])
    req("REQ-AW-03", AW, "§2 forbidden config", "sql, script, url, headers, token, password forbidden anywhere in config; references match ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", "C4", "P0", "G3", ["C1-SEC-03", "C2-DEF-06"], [("AppDefinitionValidatorTests", "an action cannot carry a URL SQL or code"), ("ActionRuntimeTests", "definition with a smuggled url or missing reference is INVALID_DEFINITION")])
    req("REQ-AW-04", AW, "§2 ActionDef", "C2 stores ActionDef/WorkflowDef losslessly (typed round-trip) and delegates deep validation to the C4 validators", "C2", "P1", "G2", ["C2-DEF-07", "C2-DEF-08"], [("ContractConformanceTests", "an ActionDef keeps every canonical member"), ("ContractConformanceTests", "a WorkflowDef keeps every canonical member")])
    req("REQ-AW-05", AW, "§3 workflow shape", "closed set of step kinds/operators/value sources (no scripting); a script-like element makes the workflow not offered; CAS on WorkflowRun.version; queue message carries no payload/actor", "C4", "P0", "G6", ["C4-WFL-17", "C4-WFL-18", "C0-PER-21"], [("WorkflowShapeTests", "the step kinds, value sources, operators and approver kinds are a closed set with no scripting")])
    req("REQ-AW-06", AW, "§4 + D-C4-12 data path", "the only data path Action -> ActionDataPort -> adapter -> DataGateway; no handler touches JDBC/HTTP", "C4", "P0", "G6", ["C0-ARCH-01", "C0-ARCH-07", "C0-WIR-12"], [("ActionContractV2Tests", "only the data port and the handlers' package talk to data")])
    req("REQ-AW-07", AW, "§4 adapter rules", "adapter derives the key, resolves queryRef, rejects a mutating action with a null key; WriteRequest/OperationRequest carry mode and appId", "C4", "P0", "G6", ["C4-ACT-01", "C4-ACT-15", "C0-WIR-12"], [("ActionContractV2Tests", "a data request cannot be built with a raw client key"), ("ActionContractV2Tests", "the data port receives app and mode"), ("ActionDataPortAdapterTests", "a write resolves local ids and sends the derived key unchanged to C3")])
    req("REQ-AW-08", AW, "§5 permissions", "port permission strings are the canonical codes: APP_USE, ACTION_EXECUTE, DATA_MUTATE (write actions), WORKFLOW_EXECUTE, WORKFLOW_MANAGE", "C4", "P0", "G3", ["C1-PRM-09", "C1-PRM-11", "C1-PRM-13"])
    req("REQ-AW-09", AW, "§5 tenancy", "ActionContext tenant verified against the app's tenant; workers rebuild context from the stored run creator; a disabled tenant fails the run TENANT_DISABLED", "C4", "P0", "G6", ["C4-WFL-16", "C4-ACT-17", "C1-TEN-14"], [("WorkflowEngineTests", "a tenant disabled while the run is in flight stops it")])
    req("REQ-AW-10", AW, "§5 audit", "audit entries carry the actor explicitly; no input values", "C4", "P1", "G3", ["C1-AUD-02", "C1-AUD-04"], [("ActionRuntimeTests", "audit entries carry identity and trigger but no input values")])
    req("REQ-AW-11", AW, "§6 + D-C4-13/14/15/16", "sweeper fairness, no immediate requeue loop, backoff floor, per-tenant rate limit, retention of run data, schedule registration dedupe", "C4", "P1", "G6", ["C4-QUE-04", "C4-QUE-05", "C4-ACT-18", "C4-RET-01", "C4-SCH-05", "C4-RET-02"])
    req("REQ-AW-12", AW, "D-C4-04", "workflow state lives in the DB (CAS on the run row); the broker only carries a signal; timers/retries/lost jobs/dead workers/lost callbacks handled by the sweeper; run executes as its creator with permissions re-checked at every step; definition snapshotted; start does not block HTTP", "C4", "P0", "G6", ["C4-WFL-01", "C1-PRM-14", "C4-WFL-16", "C4-QUE-02"])
    req("REQ-AW-13", AW, "D-C4-05", "idempotency scopes: action (tenant,app,action,user,key); workflow start (tenant,app,workflow,creator,mode,key); TEST and LIVE separate; step key wf:<runId>:<stepId>", "C4", "P0", "G6", ["C4-WFL-02", "C4-ACT-03", "C0-MIG-03", "C0-PER-22"], [("JdbcActionRunStoreTests", "tenants, users, actions and apps are separate key scopes")])
    req("REQ-AW-14", AW, "D-C4-06 TEST", "TEST mode: no mutation, no notification, no workflow/approval, no run state; WouldRun level honest; plan carries names not values", "C4", "P0", "G6", ["C4-ACT-12", "C4-ACT-13", "C4-WFL-15", "C3-MUT-03"])
    req("REQ-AW-15", AW, "D-C4-07 approval", "approvers snapshotted; requester excluded by default; quorum; one rejection rejects; cross-tenant DENY_ALL by default", "C4", "P1", "G6", ["C4-APR-01", "C4-WFL-12", "C1-TEN-16"])
    req("REQ-AW-16", AW, "D-C4-08/15 scheduler", "scheduler only enqueues; unique (tenant, schedule, fireAt) ledger + key sched:<id>:<epoch>; misfire SKIP/FIRE_ONCE never backfills; run as schedule owner; republish creates no duplicate", "C4", "P1", "G6", ["C4-SCH-01", "C4-SCH-02", "C4-SCH-03", "C4-SCH-04", "C4-SCH-05", "C4-SCH-06", "C4-SCH-07"])
    req("REQ-AW-17", AW, "D-C4-13 poison/outage", "worker never requeues immediately; malformed -> DLQ; poison counts as a failure of its run (cap 5 -> DEAD_LETTERED, only its message to DLQ); broker dead letter is one failure", "C4", "P0", "G6", ["C4-QUE-02", "C4-QUE-03", "C4-QUE-05"])
    req("REQ-AW-18", AW, "D-C4-17 + D-C0-13", "mutating TIMEOUT/INTERRUPTED is normalised to IDEMPOTENCY_OUTCOME_UNKNOWN (non-retryable); UNKNOWN runs no UI onError chain and no workflow onError; MUTATION_REJECTED keeps onError", "C4", "P0", "G6", ["C4-ACT-06", "C4-ACT-07", "C4-ACT-10", "C4-WFL-09"])
    req("REQ-AW-19", AW, "D-C4-16 retention", "retention deletes only finished rows; PENDING/RUNNING/WAITING, compensating runs, PENDING approvals, CLAIMED fires are never touched; minimum 7 days", "C4", "P1", "G6", ["C4-RET-01", "C0-PER-12", "C0-PER-24"])
    req("REQ-AW-20", AW, "D-C0-23 V29 store selection", "app.workflow.run-store jdbc (default) or memory; any other value fails startup; memory is an explicit override that never mixes with durable stores", "C0", "P0", "G6", ["C0-WIR-01", "C0-WIR-02", "C0-WIR-03", "C0-WIR-04"])
    req("REQ-AW-21", AW, "D-C0-23 isolation", "run isolation by schema AND adapter: tenant_id NOT NULL, composite FKs, run for another tenant's app refused, workspace derived from the project", "C0", "P0", "G6", ["C0-MIG-02", "C0-MIG-07", "C0-PER-11", "C0-PER-23"])
    req("REQ-AW-22", AW, "D-C0-23 ambiguity as data", "stored action result keeps code+retryable so UNKNOWN/REJECTED replay exactly after restart; a non-retryable FAILED is never restarted; nothing in V29 re-executes a mutation", "C0", "P0", "G6", ["C0-PER-08", "C0-PER-10", "C0-PER-19"])
    req("REQ-AW-23", AW, "D-C0-25 A-1", "action_runs.mutating NOT NULL DEFAULT TRUE; an abandoned MUTATING run becomes IDEMPOTENCY_OUTCOME_UNKNOWN non-retryable, a non-mutating one a retryable TIMEOUT; unspecified = mutating", "C4", "P0", "G6", ["C0-MIG-05", "C4-ABN-01", "C4-ABN-02", "C4-ABN-03", "C4-ABN-04", "C4-ABN-05"])
    req("REQ-AW-24", AW, "D-C0-25 H-3 lease", "workflow_runs.lease_owner/lease_until (both or none); a valid lease is never swept however old updated_at; expired lease reclaimed even if recently touched; owner-only renewal; stale owner fenced", "C4", "P0", "G6", ["C0-MIG-05", "C4-LSE-01", "C4-LSE-02", "C4-LSE-03", "C4-LSE-04", "C4-LSE-05", "C4-LSE-06"])
    req("REQ-AW-25", AW, "D-C0-23 recovery", "restart recovery: sweeper claims runs atomically (two nodes never take the same run); PENDING republished; stale RUNNING step -> new attempt of the same key; timers fire once; stuck compensation resumes without repeating; terminal runs untouched", "C0", "P0", "G6", ["C0-PER-13", "C0-PER-14", "C0-PER-15", "C0-PER-16", "C0-PER-17", "C0-PER-18", "C0-PER-20", "C4-REC-01"])
    req("REQ-AW-26", AW, "D-C4-04/13 broker", "RabbitMQ durable queue: signal-only messages v1|jobId|tenantId|runId|stepId survive broker restart; DLQ replay; delivery-limit safety net", "C4", "P0", "G6", ["C4-QUE-06", "C4-QUE-07", "C4-QUE-08", "REC-R11"])
    req("REQ-AW-27", AW, "D-C0-21 D5", "approvals/schedules/notifications are not persisted in V29; LIVE workflows with an APPROVAL step are refused until their tables exist", "C0", "P1", "G6", ["C4-WFL-20"])

    AD = "contracts/v2/app-definition.md"
    req("REQ-AD-01", AD, "§1 principles", "AppDefinitionV2 is a superset of Page Schema; legacy documents stay valid and round-trip unchanged; PAGE_SCHEMA and STATIC_APP untouched", "C2", "P0", "G2", ["C2-DEF-09", "C0-ARCH-04"], [("AppDefinitionCompatibilityTests", "legacy documents are not subject to any V2 rule"), ("AppDefinitionCompatibilityTests", "a multi-page legacy schema with site settings and unknown keys is unchanged by the adapter")])
    req("REQ-AD-02", AD, "§1 declarative only", "ids and typed references only, never SQL, URLs, credentials or code", "C2", "P0", "G3", ["C1-SEC-03", "C2-DEF-06"], [("AppDefinitionValidatorTests", "raw URLs are rejected in every text field"), ("AppDefinitionValidatorTests", "the extension area rejects URLs credentials SQL and code-like keys at any depth"), ("DefinitionOperationsTests", "an operation cannot smuggle SQL a URL a credential or code")])
    req("REQ-AD-03", AD, "§1 write path", "every change: typed operation -> patch -> validators -> SchemaCommitService (CAS revision) -> immutable version -> audit; AI uses the same path", "C2", "P0", "G7", ["C2-DEF-10", "C2-DEF-02", "C2-DEF-05"], [("SchemaApiTests", "two schema writes with the same revision let exactly one win"), ("DefinitionOperationsCommitTests", "")])
    req("REQ-AD-04", AD, "§2 stored document", "persisted keys are exactly the contract keys; derived views are never written; strict (unknown top-level keys rejected) only if schemaVersion is declared", "C2", "P1", "G2", ["C2-DEF-06", "C2-DEF-07"], [("ContractConformanceTests", "the persisted keys of a V2 document are exactly the contract keys"), ("ContractConformanceTests", "derived views are never written as keys of the document"), ("AppDefinitionValidatorTests", "a V2 document that declares schemaVersion may not carry unknown top-level keys")])
    req("REQ-AD-05", AD, "§3 dataSources", "dataSources[].sourceRef is a UUID or null, never a URL/connection string", "C2", "P0", "G5", ["C2-DEF-11", "C1-SEC-03"], [("AppDataBindingResolverTests", "an invalid sourceRef is reported by code, not by a parse exception")])
    req("REQ-AD-06", AD, "§3 queries", "queries[]: ParamType incl. DATE; ParamDef.required defaults true; mode READ|WRITE bound; reads never run as writes", "C2", "P1", "G5", ["C2-DEF-06"], [("ContractConformanceTests", "DATE is a parameter type"), ("ContractConformanceTests", "a parameter that omits required is required"), ("AppDefinitionValidatorTests", "reads are bound to data and never run as writes")])
    req("REQ-AD-07", AD, "§3 mappings", "mappings: transforms[] canonical (<= 8, ordered); legacy transform read-only; both rejected; lossless round trip of transforms/default/nullable/validation/errorPolicy; validated by the C3 parser", "C2", "P1", "G5", ["C3-QRY-10", "C2-DEF-07"], [("ContractConformanceTests", "mapping transforms are the canonical transforms array"), ("ContractConformanceTests", "transforms are bounded and shaped"), ("ContractConformanceTests", "the legacy transform key is read, normalised to transforms and never written")])
    req("REQ-AD-08", AD, "§3 viewModels", "FieldType and mirrored enums equal the owner's values name for name", "C2", "P1", "G2", ["C0-WIR-11", "C2-DEF-07"], [("ContractConformanceTests", "mirrored enums carry exactly the contract values")])
    req("REQ-AD-09", AD, "§3 dataBindings", "dataBindings[] is the only binding concept and is checked against sections and component props", "C2", "P1", "G5", ["C2-DEF-11", "C2-DEF-06"], [("AppDefinitionValidatorTests", "data bindings are checked against the sections and the component props")])
    req("REQ-AD-10", AD, "§3 permissions[]", "permissions[].permission must be one of the 14 canonical codes; a permission resource must exist", "C2", "P0", "G3", ["C2-DEF-17", "C2-DEF-06"], [("ContractConformanceTests", "only canonical permission codes are accepted"), ("AppDefinitionActionWorkflowRulesTests", "a permission resource must exist")])
    req("REQ-AD-11", AD, "§3 extensions", "extensions{} keys must be namespaced xweb.*", "C2", "P2", "G2", ["C2-DEF-15"])
    req("REQ-AD-12", AD, "§3 local ids", "all cross-references are local ids; runtime translation only by AppDataBindingResolver; local ids never look like runtime UUIDs", "C2", "P0", "G5", ["C2-DEF-11"], [("AppDefinitionActionWorkflowRulesTests", "local ids never look like runtime UUIDs"), ("AppDataBindingResolverTests", "a runtime id used where a local id belongs is never resolved and never accepted")])
    req("REQ-AD-13", AD, "§4 operations", "legacy 12 + 23 typed ops; no op creates a DataSource; REMOVE_* does not cascade and dangling references are caught at commit; commit is the only write path; ensureInitialized also validates V2 (R-12)", "C2", "P0", "G7", ["C2-DEF-10", "C2-DEF-01"], [("DefinitionOperationsTests", "the operation vocabulary keeps the legacy set and adds the typed V2 set next to it"), ("DefinitionOperationsTests", "an operation that leaves a dangling reference is rejected by the validator with the path"), ("SchemaServiceInitializationTests", "an initial document with a broken V2 reference is rejected and nothing is written")])
    req("REQ-AD-14", AD, "§5 publish model", "draft publishConfig vs persistent policy (CAS revision, hashed private-link token, public_data_approved) vs immutable deployments (visibility fixed at deploy; rollback never reads publish_configs)", "C2", "P0", "G7", ["C2-PUB-09", "C2-PUB-10", "C2-PUB-11", "C2-PUB-13", "C2-PUB-14"])
    req("REQ-AD-15", AD, "§5 R-14", "public_data_approved is re-checked against the current dataBindings at publish time", "C0", "P0", "G7", ["C2-PUB-15"])
    req("REQ-AD-16", AD, "§5 visibility", "visibility TENANT/PRIVATE_LINK need a separate C0 migration (base deployments_visibility_check forbids them)", "C0", "P2", "G7", ["C2-PUB-16"])
    req("REQ-AD-17", AD, "§6 AI", "planner never writes, never edits dataSources/publishConfig, never weakens permissions, uses only AiDataCatalog entries; flags app.ai-planner/tenant-ai/publish-configs OFF", "C2", "P0", "G3", ["C2-DEF-14", "C0-WIR-05", "NF-OPS-04"], [("AppPlannerTests", "publishing and permissions are never changed by AI"), ("AppPlannerTests", "the result check catches changes to data sources, publishing and permissions however they were made"), ("AppPlannerTests", "operations and data sources that are not granted are refused"), ("AppPlannerTests", "AI plans and Design UI edits use the same operations and give the same document")])
    req("REQ-AD-18", AD, "§7 component metadata", "ComponentMetadataV2 read-only API; event names are the canonical EventType wire names", "C2", "P2", "G2", ["C2-DEF-12"], [("ComponentMetadataTests", "validator rejects an event the component does not emit")])

    IC = "contracts/v2/integration-contract.md"
    req("REQ-IC-01", IC, "§1 layering", "package layering: logic imports base only; data imports base+tenancy; app imports base+data shapes+logic validator; wiring is the only cross-package layer", "C0", "P0", "G2", ["C0-ARCH-01", "C0-ARCH-07"])
    req("REQ-IC-02", IC, "§2 one definition", "one definition per concept; duplicated enums only with a name-for-name conformance test", "C0", "P1", "G2", ["C0-WIR-11", "C2-DEF-07"], [("ContractConformanceTests", "mirrored enums carry exactly the contract values"), ("ActorKindsTests", "the two enums have exactly the same names")])
    req("REQ-IC-03", IC, "§3 fixtures", "one set of conformance fixtures (C2's) loaded by every agent's reader/validator and by C5's type check; legacy spellings stay invalid", "C0", "P1", "G2", ["C2-DEF-07", "C5-UNT-01", "C0-ARCH-08"], [("ContractConformanceTests", "every fixture of the manifest behaves as declared"), ("ContractConformanceTests", "the manifest lists every conformance case the contract round asks for")])
    req("REQ-IC-04", IC, "§5 migration order", "Flyway strictly ascending, no outOfOrder; V26 then V27 then later numbers one at a time", "C0", "P0", "G2", ["C0-MIG-21", "C0-MIG-22", "C0-MIG-08"])
    req("REQ-IC-05", IC, "§5 tables", "every new table references tenants(id); workspace-scoped tables use the composite FK", "C0", "P0", "G2", ["C0-MIG-01", "C0-MIG-02", "C0-MIG-11", "C0-MIG-12", "C0-MIG-20"])
    req("REQ-IC-06", IC, "§6 switches", "ai-planner, tenant-ai, publish-configs, system-admin-business-access and data-platform/workflow flags are declared default false", "C0", "P0", "G3", ["C0-WIR-05", "C0-WIR-06"])
    req("REQ-IC-07", IC, "§6 Tenant AI", "tenant AI stays OFF until the outbound call re-checks the address with PublicAddress at call time (DNS rebinding)", "C1", "P1", "G3", ["C1-SEC-25", "C1-SEC-02"])
    req("REQ-IC-08", IC, "§7 verification gate", "nothing is passing until gradlew test (JDK 21, Docker) and npm gates ran on a capable machine with the output recorded in BASELINE.md", "C0", "P1", "G1", ["C0-BLD-02", "C0-BLD-03", "C0-BLD-04", "C0-BLD-01", "NF-DOC-03"])
    req("REQ-IC-09", IC, "§8 frontend rules", "Platform = SYSTEM_ADMIN, Admin = TENANT_ADMIN (D-C0-19: platform-only until a tenant-admin contract), Studio = tenant member; packages/types is the only mirror", "C5", "P2", "G4", ["C5-UNT-06", "C5-UNT-01", "C5-STU-09"])

    MA = "contracts/v2/management-api.md"
    for (rid, clause, text, risk, rows) in [
        ("01", "§1.1-2 scope", "base paths; tenant server-derived; authority keys and unknown keys -> 400 INVALID_PARAMS", "P0", ["C3-MGT-01"]),
        ("02", "§1.3 session", "authenticated session + CSRF on every state-changing call; 401/403 codes written by the platform", "P0", ["C3-MGT-02", "C1-AUTH-03"]),
        ("03", "§1.4 flag", "routes mounted only with app.data-platform.enabled=true", "P0", ["C3-MGT-03", "C0-WIR-05"]),
        ("04", "§1.5 disclosure", "non-member/unknown/other-workspace/other-tenant answer the same 404; 403 only for a member lacking the permission", "P0", ["C3-MGT-04", "C1-TEN-10"]),
        ("05", "§1.7 no secret", "no response, error, log, audit, header or URL can carry a secret, ciphertext or credentialRef", "P0", ["C3-MGT-05", "C1-SEC-05"]),
        ("06", "§1.8 concurrency", "mutable resources have integer version; expectedVersion mismatch -> 409 CONFLICT and nothing changes", "P0", ["C3-MGT-06", "C0-PER-03"]),
        ("07", "§1.9 throttle", "changing calls limited per tenant (60/60 s) with Retry-After", "P1", ["C3-MGT-07"]),
        ("08", "§1.10 no raw mutation", "no browser-callable mutation execution route; POST /api/v1/data/mutate must not be mounted", "P0", ["C0-WIR-18", "C0-WIR-05"]),
        ("09", "§2 permissions", "per-route permission table with canonical codes only; no QUERY_MANAGE/MUTATION_MANAGE", "P0", ["C3-MGT-08", "C1-PRM-02"]),
        ("10", "§3.1 data source", "create 201, PATCH all-or-nothing, DELETE 204 with the 409 rules (bound / RESERVED|UNKNOWN idempotency rows)", "P0", ["C3-MGT-09", "C3-MGT-10", "C3-SRC-01"]),
        ("11", "§3.1 name/config", "name rule, case-insensitive uniqueness per tenant, secret-looking config refused", "P1", ["C3-MGT-11", "C0-PER-01"]),
        ("12", "§3.2 credential", "credential metadata only; write-only PUT; DELETE; never returned/logged/audited", "P0", ["C3-MGT-12", "C0-PER-02"]),
        ("13", "§3.3 test", "connection test result codes, 409 DISABLED, 429, no raw driver message", "P1", ["C3-MGT-13", "C3-SRC-05"]),
        ("14", "§3.4 schema", "schema discover with masked samples; GET latest snapshot", "P1", ["C3-MGT-14", "C3-SRC-04"]),
        ("15", "§3.5 definitions", "query/mutation definitions scoped by data source; validated by gateway types; parameterised only; project-scoped routes not approved", "P0", ["C3-MGT-15", "C1-SEC-04"]),
        ("16", "§3.6 bindings", "TEST/LIVE bindings: exact mode, same tenant+workspace, no fallback, APP_EDIT required", "P0", ["C3-MGT-16", "C0-PER-04", "C3-QRY-02", "C1-TEN-12"]),
        ("17", "§4 envelope", "error envelope and code table", "P1", ["C3-MGT-17"]),
        ("18", "§5 audit", "audit row per change, no body/credential/config value; write and audit are one unit; audit append-only", "P0", ["C3-MGT-18", "C1-AUD-01", "C1-AUD-02"]),
    ]:
        req("REQ-MA-" + rid, MA, clause, text, "C3", risk, "G5", rows)

    DC = "parallel/c0/C2_DEPLOY_CONTRACT.md"
    for (rid, clause, text, risk, rows, cases) in [
        ("01", "§1.1 status model", "deployment status vocabulary and the only legal transitions (ROLLING_BACK new; terminal set unchanged; compare-and-set)", "P0", ["C2-PUB-02", "C2-V30-12"], [("PublishApiTests", "transitions are compare-and-set and cannot skip or go backwards")]),
        ("02", "§1.2 release", "release status derived not stored; restorable = RUNNING + artifact present + verifier OK; ROLLED_BACK not restorable", "P0", ["C2-DEP-01", "C2-V30-14"], []),
        ("03", "§1.3 events", "event vocabulary (SWITCH, ROLLBACK_OK/FAILED/OFFLINE, SCOPE_BUSY, STALE_PUBLISH) is history, never state", "P2", ["C2-V30-07", "C2-V30-01"], []),
        ("04", "§2.1 mechanism", "scope serialisation by lease row + optimistic pointer_version + activation_seq (no advisory/global lock)", "P0", ["C2-V30-01", "C2-V30-04", "C2-V30-11"], []),
        ("05", "§2.3 R1", "single owner: publish DEPLOYING step, auto rollback, manual rollback and unpublish all need the lease", "P0", ["C2-V30-09", "C2-V30-01"], []),
        ("06", "§2.3 R2", "publish ordering: activation_seq <= active_seq is stale -> FAILED/STALE_PUBLISH, pointer unchanged", "P0", ["C2-V30-02"], []),
        ("07", "§2.3 R3", "rollback/unpublish expectedActiveDeploymentId mismatch -> 409 ROLLBACK_STALE", "P0", ["C2-V30-03", "C2-V30-19"], []),
        ("08", "§2.3 R4", "busy is explicit: publish waits (SCOPE_BUSY) then FAILED after scope-wait; rollback/unpublish 409 SCOPE_BUSY + Retry-After", "P0", ["C2-V30-01", "C2-V30-19"], []),
        ("09", "§2.3 R5", "no race between publish and rollback; whichever acquires first completes its pointer write", "P0", ["C2-V30-03", "C2-V30-09"], []),
        ("10", "§2.3 R6", "idempotent retry: stable operation id, Idempotency-Key header, KEY_REUSED, re-entrant lease", "P0", ["C2-PUB-03", "C2-PUB-04", "C2-V30-05"], [("PublishApiTests", "the same idempotency key never creates a second deployment")]),
        ("11", "§2.3 R7", "recoverable: lease expiry, dead worker fenced, DEPLOYING re-run, ROLLING_BACK straight to undo (never re-verify/roll forward)", "P0", ["C2-DEP-03", "C2-V30-04", "C2-V30-06"], [("QueueRecoveryTests", "died mid-pipeline")]),
        ("12", "§2.3 R8", "previous release read and stored under the lease in a typed column (not regex over event text)", "P1", ["C2-V30-17"], []),
        ("13", "§2.3 R9", "no global lock; other scopes and reads never blocked", "P1", ["C2-V30-18"], []),
        ("14", "§3.1 auto rollback", "automatic rollback: DEPLOYING->ROLLING_BACK durable; restore previous or NULL; failed restore fail-closed (pointer NULL); never a failed release", "P0", ["C2-V30-06", "C2-V30-07", "C2-V30-13", "C2-PUB-06"], [("PublishApiTests", "a failing provider ends in FAILED with an error and no url")]),
        ("15", "§3.2 manual rollback", "manual rollback: AlreadyActive no-op; verify artifact; pointer CAS; ROLLED_BACK only to an older release; failure changes no status; 409 ROLLBACK_FAILED", "P0", ["C2-DEP-01", "C2-V30-08"], [("StaticSiteTests", "rollback serves an earlier artifact without a rebuild")]),
        ("16", "§3.3 server apps + F-6", "server apps keep their own blue/green; auto-rollback of a site must also roll the server runtime back (F-6)", "P1", ["C2-DEP-04", "C2-V30-16"], [("ServerRuntimeTests", "blue-green")]),
        ("17", "§4 API additive", "additive API: expectedActiveDeploymentId, Idempotency-Key, SCOPE_BUSY/ROLLBACK_STALE, SiteInfo.operation/pointerVersion, ROLLING_BACK status for clients", "P1", ["C2-V30-19", "C5-STU-10"], []),
        ("18", "§5 F-1", "F-1: pointer written by blind UPDATE (last writer wins) — a slow older publish must not overwrite a newer release", "P0", ["C2-V30-02", "C2-V30-01"], []),
        ("19", "§6 V30 schema", "V30 content: status CHECK + ROLLING_BACK, activation_seq sequence/backfill, previous_deployment_id, sites lease columns all-or-none, index, undo U30 guarded", "P0", ["C2-V30-11"], []),
        ("20", "§8 retention", "retention never deletes the artifact of the ACTIVE or a RESTORABLE release nor the previous release of a deployment in progress", "P0", ["C2-DEP-07", "C2-V30-15"], [("LockdownSettingsTests", "retention keeps the served artifact and the last N rollbacks")]),
        ("21", "§9 order gate", "V30 only after V29 (out-of-order off); V29 immutable", "P0", ["C0-MIG-21", "C0-MIG-08", "C0-MIG-25"], []),
        ("22", "§7 test scenarios (H-2 to C6)", "the nine mandatory race/crash scenarios exist as real-PostgreSQL tests", "P0", ["C2-V30-01", "C2-V30-02", "C2-V30-03", "C2-V30-04", "C2-V30-05", "C2-V30-06", "C2-V30-07", "C2-V30-08", "C2-V30-09", "C2-V30-10"], []),
    ]:
        req("REQ-DC-" + rid, DC, clause, text, "C2", risk, "G7", rows, cases)

    ML = "parallel/MIGRATION_LEDGER.md"
    req("REQ-ML-01", ML, "Rules", "only C0 allocates; one number one task; strictly ascending; spring.flyway.out-of-order stays off", "C0", "P0", "G2", ["C0-MIG-21", "C0-MIG-22"])
    req("REQ-ML-02", ML, "§1 allocated V26/V27", "V26 tenant_foundation and V27 publish_configs: constraints, backfill, tenant FKs, undo scripts", "C0", "P0", "G2", ["C0-MIG-17", "C0-MIG-18", "C0-MIG-19", "C0-MIG-20", "C0-MIG-27"])
    req("REQ-ML-03", ML, "§1 V28", "V28 data runtime: tables, tenant_id mandatory, composite FKs, ciphertext-only credentials, idempotency row validation, undo guarded", "C0", "P0", "G2", ["C0-MIG-11", "C0-MIG-12", "C0-MIG-13", "C0-MIG-14", "C0-MIG-15", "C0-MIG-16"])
    req("REQ-ML-04", ML, "§1 V29", "V29 run persistence: three tables, C4 contract columns, idempotent scope keys, status/shape checks, undo guarded; V29 immutable from now", "C0", "P0", "G2", ["C0-MIG-01", "C0-MIG-02", "C0-MIG-03", "C0-MIG-04", "C0-MIG-05", "C0-MIG-06", "C0-MIG-07", "C0-MIG-09", "C0-MIG-10", "C0-MIG-25"])
    req("REQ-ML-05", ML, "§1 V30", "V30 reserved for C2: file not created; nobody else may use it; V31 not allocated", "C0", "P1", "G7", ["C0-MIG-22", "C2-V30-11"])
    req("REQ-ML-06", ML, "§2/§3 conventions", "every table: tenant_id NOT NULL REFERENCES tenants(id), composite FK, (tenant_id, created_at) index where rows grow, retention statement before a number is given", "C0", "P2", "G2", ["C0-MIG-26", "C0-MIG-01", "C0-MIG-11"])
    req("REQ-ML-07", ML, "§2 item 6 (append-only part) + CLAUDE.md", "audit_events is append-only: DB trigger refuses UPDATE/DELETE/TRUNCATE and the application only ever INSERTs (the tenant column and privilege hardening are REQ-ML-10)", "C0", "P0", "G3", ["C1-AUD-01"])
    req("REQ-ML-08", ML, "§2 item 7 compat removal", "compat removal only when the TenantInsertPathsGrepTest pending list is empty", "C1", "P2", "G2", ["C1-TEN-21"])
    req("REQ-ML-09", ML, "operational", "the migrations upgrade a real V25 database without loss (shared dev DB is at V25; C6 does not migrate it in place)", "C0", "P0", "G2", ["C0-MIG-17", "C0-MIG-23", "C0-MIG-24"])

    BD = "parallel/BOARD.md"
    req("REQ-BD-01", BD, "task rows", "BOARD statuses match the tree (V28/V29 imported, Re-baseline (macOS) recorded)", "C0", "P3", "G11", ["NF-DOC-03"])
    req("REQ-BD-02", BD, "Management API row", "Management API is NOT READY and must not be advertised to C5; UI must not fake it", "C5", "P1", "G4", ["C5-STU-11", "C3-MGT-03"])
    req("REQ-BD-03", BD, "C5 import row", "frontend gates green before declaring the C5 import GREEN (typecheck, unit+conformance 130/130, builds, browser harness)", "C5", "P1", "G4", ["C5-TYP-01", "C5-UNT-01", "C5-BLD-01", "C5-BRW-01", "C5-BRW-02", "C5-UNT-08"])

    CL = "CLAUDE.md invariants"
    req("REQ-CL-01", CL, "Page Schema / PatchEngine / validator", "Page Schema + SchemaPatchEngine + PageSchemaValidator behave as before", "C2", "P0", "G2", ["C2-DEF-01", "C2-DEF-02", "C2-DEF-03", "C2-DEF-04"])
    req("REQ-CL-02", CL, "component registry/version", "component registry and versions intact", "C2", "P1", "G2", ["C2-DEF-12"])
    req("REQ-CL-03", CL, "immutable versions", "project versions are immutable; restore creates a new version", "C2", "P0", "G2", ["C2-DEF-05"])
    req("REQ-CL-04", CL, "AI Gateway", "AI Gateway governance intact (model access, tools run with user's permissions, keys never returned)", "C2", "P0", "G3", ["NF-OPS-04"])
    req("REQ-CL-05", CL, "Connector Proxy + SSRF + credentials", "Connector Proxy + SSRF guard; credentials only on the server", "C1", "P0", "G3", ["C1-SEC-01", "C1-SEC-02", "C1-SEC-05", "C1-SEC-21", "C1-SEC-20"])
    req("REQ-CL-06", CL, "Audit append-only", "audit is append-only", "C1", "P0", "G3", ["C1-AUD-01", "C1-AUD-02"])
    req("REQ-CL-07", CL, "publish pipeline", "publish pipeline and render/build/runtime planes", "C2", "P0", "G7", ["C2-PUB-01", "C2-PUB-02", "C2-PUB-03", "C2-PUB-07", "C2-DEP-04", "C2-DEP-06"])
    req("REQ-CL-08", CL, "backward compatibility", "existing API, projects table, Page Schema, STATIC_APP stay compatible; no old feature removed", "C0", "P0", "G2", ["C0-ARCH-04", "C2-DEF-09"])
    req("REQ-CL-09", CL, "no cross-owner edits", "do not edit another owner's files / HOT FILES", "C0", "P1", "G11", ["C0-ARCH-05"])
    req("REQ-CL-10", CL, "flyway discipline", "only C0 numbers migrations; do not edit existing migrations; outOfOrder off", "C0", "P0", "G2", ["C0-MIG-21", "C0-MIG-25"])
    req("REQ-CL-11", CL, "AI data flow", "AI: Prompt -> Structured Operation -> AppDefinition -> Validator -> Version (AI has no data model of its own)", "C2", "P0", "G3", ["C2-DEF-14", "C2-DEF-10"], [("AppPlannerTests", "AI plans and Design UI edits use the same operations and give the same document")])

    # ---- batch 7: ledger item 6 and the unresolved BLOCKERS.md items that still affect a release (verified against the tree, not copied)
    req("REQ-ML-10", ML, "§2 item 6 (T19) tenant column + privileges", "audit_events carries a tenant column and the application role cannot disable the append-only guard (privileges)", "C0", "P1", "G3", ["C1-AUD-06"])
    BL = "parallel/BLOCKERS.md"
    req("REQ-BL-01", BL, "B-C0-WEB-01 + B-C5-08", "OIDC redirect/callback, CORS/cookie and CSP work for the three portal origins (single registered callback today)", "C0", "P1", "G8", ["C0-WIR-14", "C0-WIR-24", "C1-AUTH-13"], [("WebOriginsTests", "")])
    req("REQ-BL-02", BL, "B-C0-W-02", "W-06 AiDataCatalogAdapter and W-07 DataWebhookController are wired", "C0", "P1", "G5", ["C2-DEF-16", "C3-WHK-04"])
    req("REQ-BL-03", BL, "B-C0-W-04", "a production connector can WRITE (today postgres/rest are read-only: mutator()==null) and keeps the §4b semantics on a real source", "C3", "P1", "G5", ["C3-MUT-08", "C3-MUT-09", "C3-MUT-10"])
    req("REQ-BL-04", BL, "B-C3-09", "realtime/cache invalidation is consistent across several API nodes (shared bus)", "C3", "P1", "G5", ["C3-QRY-14", "C3-QRY-07"], [("CacheRaceTests", "stress")])
    req("REQ-BL-05", BL, "B-C3-10", "sync jobs are scheduled by the application (SyncRunner.runDue is wired)", "C3", "P2", "G5", ["C3-SYN-01", "C3-QRY-12"])
    req("REQ-BL-06", BL, "B-C4-08", "tenant rate limits are enforced cluster-wide (shared state), not per node", "C4", "P1", "G6", ["C4-ACT-18", "C4-ACT-23"])
    req("REQ-BL-07", BL, "B-C5-07", "COMPANY templates/blocks never carry tenant-owned dataBindings/actions; restoring a version pointing at a deleted query is refused", "C2", "P1", "G3", ["C2-DEF-13"], [("TemplateSanitizerTests", "")])
    req("REQ-BL-08", BL, "B-C1-17", "default-deny TEMPORARY V2 policy: only USER actors; tenant-level data sources without a workspace have no holder", "C1", "P1", "G3", ["C1-PRM-01", "C0-WIR-11", "C1-TEN-11"])
    req("REQ-BL-09", BL, "B-C5-09 (Q-1/Q-2)", "decisions that blocked Phase 3 are recorded and tested: browser routes = /app-runtime (D-C0-17), Admin portal platform-only (D-C0-19)", "C5", "P2", "G4", ["C5-STU-09", "C5-UNT-06", "C0-WIR-05"])
    req("REQ-BL-10", BL, "all rows", "BLOCKERS.md statuses are current (no delivered item left OPEN)", "C0", "P3", "G11", ["NF-DOC-04"])

    return R


# ====================================================================================================================================
# Source corrections: rows that were GAP/BLOCKED in the previous generation but are covered by existing test BODIES (found by reading the
# tests, not only their names) or by the C6 checks of this batch. Applied before evaluation; the change is logged in QA_MASTER section 11.
def SRC_OVERRIDES(J, GAP, BLK, CC, OV, NPM, MULTI):
    return {
        "C1-AUD-01": (MULTI(J(("AuditApiTests", "audit rows cannot be updated or deleted"), ("ProjectApiTests", "audit rows cannot be updated or deleted")), CC("CC-ML07-1", "CC-ML07-2", "CC-ML07-3", "CC-ML07-4", "DB-AUD-2", "DB-AUD-3", "DB-AUD-4", "DB-AUD-6", "DB-AUD-7", "DB-AUD-9")),
                      "existing tests assert UPDATE/DELETE/TRUNCATE are refused for the application user (AuditApiTests, ProjectApiTests); C6 adds the app-layer scan and a DB probe incl. a non-owner role and a negative control. Residual (owner can disable the trigger) = C1-AUD-06 / T19"),
        "C1-TEN-22": (J(("TenantAccessTests", "a member removed from the tenant loses access to its workspaces, and a suspended tenant is closed to ordinary users")), "the test asserts 403 TENANT_SUSPENDED (service) and HTTP 403 for a suspended tenant, then 200 when re-activated"),
        "C1-PRM-24": (J(("TenantAccessTests", "SYSTEM_ADMIN default policy - platform scope only")), "the test asserts 404 on project detail/schema/versions and an EMPTY project list for a non-member system admin"),
        "C2-PUB-14": (J(("PublishConfigTests", "the draft in the document is only a request and changes nothing by itself")), "the test adopts a draft over an existing configuration with the current revision (fromDraft/CAS) and refuses a PUBLIC draft without confirmation"),
        "C1-TEN-23": (MULTI(CC("CC-TP07-1", "CC-TP07-2", "CC-TP07-3"), J(("TenantAccessTests", "AccessContext carries the tenant resolved from the workspace"))), "C6 static check (self-tested): every AccessContext construction passes tenantId+tenantContext explicitly; DEFAULT tenant only in 4 audited files; tenant derived from the resolver"),
        "C0-WIR-18": (MULTI(CC("CC-WIR18-1", "CC-WIR18-2", "CC-WIR18-3"), J(("AppRuntimeFlagsOffTests", "no runtime route is mounted by default"))), "C6 static check: no controller maps /api/v1/data/** or any *mutate* route; app-runtime family is exactly the two controllers; flags-OFF test already returns 404/405"),
        "C0-ARCH-07": (MULTI(CC("CC-ARCH-logic", "CC-ARCH-data", "CC-ARCH-tenancy", "CC-ARCH-access", "CC-ARCH-app_definition"), J(("ActionContractV2Tests", "logic code imports no repository"))), "C6 static layering check of integration-contract section 1 over all main imports (self-tested with injected violations)"),
        "C0-MIG-25": (CC("CC-MIG-1", "CC-MIG-2", "CC-MIG-3"), "C6: V1..V28 byte-identical between f894cc6 and 8e91172, all 29 hashes pinned in docs/parallel/c6/migration-hashes.sha256"),
        "C0-MIG-27": (CC("DB-MIG-APPLY", "DB-U27-1", "DB-U27-2", "DB-U27-3", "DB-U27-4"), "C6 DB probe on a throw-away PostgreSQL 17.6: U27 proceeds on backfill-only rows, refuses edited/approved rows, drops when empty"),
        "C1-PRM-07": (OV("GAP-C6-05 a project viewer with APP_USE", "GAP-C6-05 the same viewer"), "C6 overlay HTTP test: a project VIEWER (APP_USE only) gets 403 FORBIDDEN naming QUERY_EXECUTE on the LIVE route and on TEST; a workspace admin is not refused"),
        "C1-PRM-10": (OV("GAP-C6-06 an editor holding", "GAP-C6-06 a viewer is refused"), "C6 overlay HTTP test: an EDITOR (APP_USE+ACTION_EXECUTE, no WORKFLOW_EXECUTE) gets 403 FORBIDDEN on workflow start, no run row is created; admin control is not refused"),
        "C1-SEC-22": (NPM(), "C6 ran `npm audit --omit=dev --json` (the repo's scripts/check.sh step): 0 vulnerabilities. The BACKEND has no scan: see C1-SEC-28"),
        "C3-MUT-09": (BLK("production connectors are read-only (DataConnector.mutator() default null; postgres/rest do not override): atomic multi-statement mutation cannot exist until a writable connector is implemented (B-C0-W-04)"), "re-classified GAP -> BLOCKED(implementation): there is no production write path to test"),
        "C1-SEC-25": (BLK("not implemented: ai/tenant has no PublicAddress re-check at call time; app.tenant-ai.enabled stays OFF (integration-contract section 6)"), "re-classified GAP -> BLOCKED(implementation): grep ai/tenant finds no PublicAddress use"),
        "C2-PUB-15": (BLK("not implemented: PublishController/DeploymentProcessor never read publish_configs / public_data_approved (0 hits in publish/**, also on fix/c2-v3); R-14 owner C0, depends on publish wiring"), "re-classified GAP -> BLOCKED(implementation)"),
        "C4-RET-02": (BLK("not implemented: RetentionService is not scheduled (no @Scheduled/RetentionService in wiring/**, D-C0-23 item 5)"), "re-classified GAP -> BLOCKED(implementation)"),
    }


# Implementation status of every requirement that is not FULL (FULL requirements are IMPLEMENTED: tests execute the product code).
# IMPLEMENTED = on integration/v2 · PARTIAL = part of it on integration/v2 or complete only on a feature branch (NOT integrated) · NOT_IMPLEMENTED = no code anywhere ·
# UNKNOWN = not determinable from the tree. Each entry: (status, evidence / dependency).
BR_C2 = "fix/c2-v3 @1ab26f9 (18 commits ahead of 8e91172: V30__deployment_rollback_and_scope_lease.sql exists, commit log says lease/CAS + concurrency tests implemented; C6 read the file and the log, not every clause) — NOT integrated; C0 imports after its Mac gate"
BR_C3 = "wire/c3-management-import @b557a0d (12 commits ahead: DataManagementControllers.kt, ManagementTransport.kt + 6 test classes exist; clauses not audited by C6) — NOT integrated; C3 branch agent/c3-data-prod @671d30d"
BR_C4 = "agent/c4-workflow @b2386d8: AmqpWorkflowQueue + WorkflowQueueConfiguration (H-5) — NOT integrated (C0 H-5 import pending); integration/v2 wires InMemoryWorkflowQueue only (AppRuntimeConfiguration.kt:111)"
IMPL = {
    "REQ-TP-02": ("IMPLEMENTED", "AccessService resolves tenantRole from tenant_members; missing row => MEMBER (D-C1-12)"),
    "REQ-TP-07": ("IMPLEMENTED", "AccessContext keeps its defaulted tenant params by design; the only 2 production constructions (AccessService.kt:104,116) pass them explicitly"),
    "REQ-TP-11": ("PARTIAL", "self-grant is closed (D-C1-15, PrivilegeEscalationTests); there is no dedicated self-leave route: member removal needs MEMBER_MANAGE (MemberController.removeWorkspace)"),
    "REQ-TP-14": ("PARTIAL", "matrix enforced in Permission.kt; 'VIEWER gets QUERY_EXECUTE only on a published app' is deferred to T15"),
    "REQ-TP-16": ("IMPLEMENTED", "every runtime/admin route authorises on the server (forProject + require); UI behaviour is C5"),
    "REQ-RA-01": ("IMPLEMENTED", "no controller maps /api/v1/data/**; DataApi/DataRoutes are an unmounted route table"),
    "REQ-RA-11": ("IMPLEMENTED", "AppRuntimeDataController.kt:64-67: forProject, APP_USE|PROJECT_EDIT, then QUERY_EXECUTE"),
    "REQ-RA-12": ("IMPLEMENTED", "RuntimeRequests/AppRuntimeDataController parse params/page/mappingRef"),
    "REQ-RA-13": ("IMPLEMENTED", "RuntimeResponses maps the failure table"),
    "REQ-RA-17": ("IMPLEMENTED", "mutation reachable only through AppRuntimeActionController.execute"),
    "REQ-RA-18": ("IMPLEMENTED", "AppRuntimeActionController start/status/cancel; WORKFLOW_EXECUTE enforced in WorkflowEngine.start"),
    "REQ-RA-20": ("IMPLEMENTED", "controller gates + C3/C4 second enforcement"),
    "REQ-RA-23": ("PARTIAL", "no anonymous data route exists; the published-runtime channel (data-api-base) exists only on " + BR_C2),
    "REQ-DR-08": ("PARTIAL", "gateway outcome semantics implemented and tested with a test connector; production connectors postgres/rest are read-only (B-C0-W-04)"),
    "REQ-DR-14": ("PARTIAL", "webhook auth/replay/limits implemented in C3 and tested; the ingest controller W-07 is not wired (B-C0-W-02)"),
    "REQ-AW-06": ("IMPLEMENTED", "logic.* has no data/connector imports; only data port + handlers talk to data"),
    "REQ-AW-11": ("PARTIAL", "sweeper fairness, backoff floor, per-node limiter, purge logic implemented; RetentionService is not scheduled and the limiter is not cluster-wide (B-C4-08)"),
    "REQ-AW-25": ("IMPLEMENTED", "WorkflowEngine.sweep + JdbcWorkflowRunStore claim/lease (V29)"),
    "REQ-AW-26": ("PARTIAL", BR_C4),
    "REQ-AW-27": ("PARTIAL", "no approvals bean/tables: an APPROVAL step fails closed with NOT_IMPLEMENTED 'Approvals are not wired' (WorkflowEngine.kt:360); start is not refused up front"),
    "REQ-AD-11": ("PARTIAL", "AppDefinitionReader.extensions enforces EXTENSION_KEY (vendor or vendor.feature); the contract says xweb.* — wording ambiguous, C2 to confirm"),
    "REQ-AD-14": ("IMPLEMENTED", "PublishConfigService/Api (flag app.publish-configs.enabled OFF); publish pipeline does not read publish_configs"),
    "REQ-AD-15": ("NOT_IMPLEMENTED", "publish/** never reads publish_configs/public_data_approved on integration/v2 nor on fix/c2-v3, wire/c3-management-import, agent/c4-workflow; owner C0 (R-14), depends on publish wiring"),
    "REQ-AD-16": ("NOT_IMPLEMENTED", "no migration requested or numbered for deployments_visibility_check (MIGRATION_LEDGER 'not requested')"),
    "REQ-IC-01": ("IMPLEMENTED", "layering holds for logic/data/tenancy/access/app.definition (CC-ARCH)"),
    "REQ-IC-03": ("PARTIAL", "C2 validator, C3 MappingContractTests and the C5 type check load the shared fixtures; C4 catalogs do not"),
    "REQ-IC-07": ("NOT_IMPLEMENTED", "ai/tenant has no PublicAddress call-time re-check; flag app.tenant-ai.enabled OFF"),
    "REQ-IC-08": ("IMPLEMENTED", "gates run on the Mac; BASELINE.md record missing (BUG-C6-003)"),
    "REQ-IC-09": ("IMPLEMENTED", "Admin portal platform-only by decision D-C0-19; Q-1 closed as a decision"),
    "REQ-ML-02": ("IMPLEMENTED", "V26/V27 migrations + U26/U27 undo scripts exist"),
    "REQ-ML-04": ("IMPLEMENTED", "V29 on integration/v2; undo U29 present"),
    "REQ-ML-05": ("PARTIAL", "V30 file exists only on " + BR_C2),
    "REQ-ML-06": ("PARTIAL", "V28/V29 carry tenant_id indexes (data_sources_tenant_idx, workflow_runs_list_idx…); the convention is not mechanically verified for every table"),
    "REQ-ML-07": ("IMPLEMENTED", "V3 row+statement triggers; AuditService is the only writer and INSERT-only"),
    "REQ-ML-09": ("IMPLEMENTED", "migrations V1..V29 apply to an empty DB and V26 upgrades synthetic V25 data; no run on a real V25 copy"),
    "REQ-ML-10": ("NOT_IMPLEMENTED", "MIGRATION_LEDGER item 6 (T19) unnumbered: audit_events has no tenant column; the table owner can disable the trigger (DB-AUD-8)"),
    "REQ-BD-01": ("PARTIAL", "BOARD/BASELINE drift documented as BUG-C6-003"),
    "REQ-BD-02": ("IMPLEMENTED", "DataWizard/TestPanel render NOT_READY from errors.test.ts-covered outcome mapping; no browser test of the panels"),
    "REQ-BD-03": ("IMPLEMENTED", "frontend gates exist and ran"),
    "REQ-CL-06": ("IMPLEMENTED", "see REQ-ML-07"),
    "REQ-CL-09": ("UNKNOWN", "process rule: no machine-checkable owner map; C6 reviews git diff per SHA"),
    "REQ-CL-10": ("IMPLEMENTED", "outOfOrder off; applied migrations unchanged (CC-MIG)"),
    "REQ-BL-01": ("PARTIAL", "WebOrigins derives a redirect/logout URI per portal (tested); one registered OIDC callback; real multi-origin SSO not verifiable without the stack/Keycloak"),
    "REQ-BL-02": ("NOT_IMPLEMENTED", "no AiDataCatalogAdapter and no DataWebhookController in wiring/** (B-C0-W-02)"),
    "REQ-BL-03": ("NOT_IMPLEMENTED", "DataConnector.mutator() defaults to null; postgres/rest do not override it (B-C0-W-04)"),
    "REQ-BL-04": ("NOT_IMPLEMENTED", "DataEventBus is per-node in memory (B-C3-09); no shared bus"),
    "REQ-BL-05": ("NOT_IMPLEMENTED", "SyncRunner exists but nothing schedules runDue (B-C3-10)"),
    "REQ-BL-06": ("PARTIAL", "InMemory TenantRateLimiter per node implemented (D-C4-14); shared limiter missing (B-C4-08 PARTIAL)"),
    "REQ-BL-07": ("IMPLEMENTED", "TemplateSanitizer removes tenant-owned data refs from COMPANY templates"),
    "REQ-BL-08": ("IMPLEMENTED", "temporary V2 policy implemented in C1 adapters and wiring (deny every non-USER actor)"),
    "REQ-BL-09": ("IMPLEMENTED", "decided in D-C0-17 and D-C0-19"),
    "REQ-BL-10": ("PARTIAL", "documentation drift: NF-DOC-04 / BUG-C6-010"),
}
for _i in ("01", "02", "03", "04", "05", "06", "07", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18"):
    IMPL["REQ-MA-" + _i] = ("PARTIAL", BR_C3)
IMPL["REQ-MA-08"] = ("IMPLEMENTED", "no controller maps a mutation execution route (CC-WIR18)")
for _i in ("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "22"):
    IMPL["REQ-DC-" + _i] = ("PARTIAL", BR_C2)
IMPL["REQ-DC-21"] = ("IMPLEMENTED", "V30 is absent from integration/v2 and V1..V29 are pinned/immutable (CC-MIG)")

# Requirements whose verification is blocked because the feature is not integrated (user rule 4: never mock it and call it VERIFIED)
BLOCKED_BY_IMPLEMENTATION = {f"REQ-MA-{i}" for i in ("01", "02", "03", "04", "05", "06", "07", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18")} \
    | {f"REQ-DC-{i}" for i in ("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "22")} \
    | {"REQ-AW-26", "REQ-AD-15", "REQ-AD-16", "REQ-IC-07", "REQ-ML-05", "REQ-BL-02", "REQ-BL-03", "REQ-BL-04", "REQ-BL-05", "REQ-ML-10"}


# P0 requirements that may keep P0 although none of their mapped rows is P0 (explain each)
P0_WITHOUT_P0_ROW = set()


# Batch-7 priority corrections.
# P1 -> P0 : rows that batch 6 demoted by "critical functional" reasoning but whose failure CAN cause an allowed P0 outcome (evidence: the case wording / test body).
P0_PROMOTE = {
    "C0-MIG-01": ("AUTH", "tenant_id NOT NULL on every run table is the database-level isolation key"),
    "C0-MIG-05": ("AMBIG", "mutating defaults TRUE (A-1): the safe default for an unspecified run decides whether an ambiguous write is ever retried"),
    "C0-MIG-11": ("AUTH", "tenant_id NOT NULL on every data-runtime table"),
    "C0-MIG-18": ("AUTH", "a mismatching tenant_id on migrated rows must be rejected (isolation), not only legacy INSERT compatibility"),
    "C0-WIR-15": ("AMBIG", "unknown outcome is 409 and never retryable, rejected mutation 422: the HTTP face of the ambiguity rules"),
    "C0-PER-06": ("IDEM", "a completed mutation's idempotency replay survives a new store instance (restart): a retry after a restart writes nothing"),
    "C0-PER-22": ("IDEM", "concurrent creates of one key produce exactly one workflow run"),
    "C1-AUTH-02": ("AUTH", "failed-login throttling is the control against credential guessing"),
    "C1-AUTH-04": ("AUTH", "logout must invalidate the server-side session"),
    "C1-PRM-13": ("PRIV", "write action types need DATA_MUTATE on top of ACTION_EXECUTE"),
    "C1-PRM-15": ("AUTH", "an authorizer/tenant-gate outage must deny, never allow"),
    "C1-SEC-08": ("REPLAY", "stale or future timestamps are rejected even with a valid signature (replay window)"),
    "C1-SEC-14": ("AUTH", "webhook infrastructure failure must answer 503 and never accept an unchecked delivery"),
    "C1-SEC-21": ("AUTH", "connector proxy needs gateway token, app token, grant and a declared operation; secrets write-only"),
    "C2-PUB-04": ("CAS", "concurrent duplicate publish requests with one key create exactly one deployment"),
    "C2-DEF-09": ("LOSS", "legacy Page Schema documents must read and round-trip unchanged (no silent rewrite of stored documents)"),
    "C2-DEP-13": ("ROLLBACK", "real rollback on the stack must serve the previous artifact"),
    "C3-IDM-03": ("IDEM", "the idempotency key is mandatory and only the derived shape is accepted"),
    "C3-IDM-07": ("AMBIG", "a store failure after a successful write must not become an error; one while recording an ambiguous failure must keep the key unusable"),
    "C3-QRY-07": ("AUTH", "cache tickets are tenant-bound: another tenant's entry can never be filled; no stale answer after invalidation"),
    "C3-WHK-03": ("AUTH", "webhook endpoints bind only to things their creator can reference; administration needs its permission"),
    "C4-ACT-11": ("IDEM", "chained writes get derived keys so re-running a chain is idempotent"),
    "C4-ACT-15": ("IDEM", "derived idempotency key (sha256/base64url, user/tenant separated) and raw keys never forwarded"),
    "C4-ACT-20": ("DUP", "concurrent begins of one key yield exactly one owner; a stale owner cannot complete"),
    "C4-LSE-02": ("DUP", "only the owner renews a lease; a stale renewal does not extend it (fencing)"),
    "C4-SCH-01": ("DUP", "concurrent ticks hand every fire over exactly once"),
    "C4-SCH-04": ("DUP", "failed enqueue, unavailable ledger and lost confirmation never produce a second hand-over"),
    "C4-WFL-12": ("PRIV", "only snapshotted approvers decide, any rejection rejects, a late decision cannot revive an expired request"),
    "C4-REC-02": ("IDEM", "duplicated workflow start with one key over HTTP creates one run"),
    "C2-DEF-17": ("PRIV", "a non-canonical permission code in an app definition must never be accepted"),
    "C3-SRC-09": ("SECRET", "discovery samples masked/off by default; AI catalog exposes no raw sample or secret"),
}
# P0 requirements demoted to P1 (the clause cannot cause an allowed P0 outcome by itself; its dangerous parts are separate P0 requirements)
REQ_P1_DEMOTE = {
    "REQ-RA-14": "HTTP envelope/status table is functional; the dangerous mappings (unknown outcome, rejected mutation) are REQ-RA-15 (P0)",
    "REQ-DR-05": "local->runtime id resolution is functional correctness; isolation is enforced afterwards by the gateway (REQ-DR-16, P0)",
    "REQ-AD-12": "local ids/translation rule is structural; isolation is REQ-DR-16",
    "REQ-IC-01": "package layering is architecture hygiene, not an allowed P0 outcome",
}
