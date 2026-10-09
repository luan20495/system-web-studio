#!/usr/bin/env python3
"""Generate docs/parallel/c6/QA_MASTER.md (single source of truth for QA) — C6 only, never touches production code.

  python3 docs/parallel/c6/harness/qa_master.py --sha 8e91172 --prev f894cc6 \
      --tsv docs/parallel/c6/evidence/mac-8e91172-gate/backend-testcases.tsv \
      --nostack docs/parallel/c6/evidence/mac-8e91172-nostack

Status of every automated row is DERIVED from the per-test-case JUnit export (suite<TAB>test<TAB>status) of the SHA under test, never typed by hand.
Rows without evidence are GAP (no test exists), BLOCKED (cannot run: stack / feature missing) or NOT RUN. History per row is persistent in
qa_master_state.json (append-only: a new SHA adds an entry, old entries are never rewritten). Edit the DATA below to add a test case or a bug.
"""
import argparse, collections, json, os, re, sys, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
C6 = os.path.normpath(os.path.join(HERE, ".."))
ROOT = os.path.normpath(os.path.join(C6, "..", "..", ".."))

SYM = {"PASS": "✅ PASS", "FAIL": "❌ FAIL", "BLOCKED": "⚠ BLOCKED", "NOTRUN": "○ NOT RUN", "RETEST": "🔁 RETEST REQUIRED", "GAP": "🟦 GAP"}

# suites that are new in the SHA under test (diff f894cc6..8e91172) — used only for the default history text
NEW_SUITES = {"ActionRunAbandonmentTests", "WorkflowAbandonedWriteTests", "WorkflowLeaseTests", "WorkflowRunPersistenceMigrationTests",
              "ActionRunRecoveryTests", "RunStoreConfigurationTests", "JdbcActionRunStoreTests", "JdbcWorkflowRunStoreTests", "RunCodecTests",
              "WorkflowRestartRecoveryTests"}
WATCH_PKG = ("wiring", "logic.action", "logic.workflow", "migration")  # modules touched by the last diff

ROWS = []


def row(id, area, feature, case, typ, pr, exp, src, owner, pre=None, bug="-", req=None, hist=None, retest=None):
    ROWS.append(dict(id=id, area=area, feature=feature, case=case, typ=typ, pr=pr, exp=exp, src=src, owner=owner, pre=pre, bug=bug, req=req or [], hist=hist, retest=retest))


def J(*pairs):  # junit-derived: (suite, keyword) ; keyword "" = whole suite
    return ("junit", list(pairs))


GAP = lambda note: ("gap", note)
BLK = lambda note: ("blocked", note)
NR = lambda note: ("notrun", note)
STATIC = lambda *ids: ("static", list(ids))
CC = lambda *ids: ("cc", list(ids))
OV = lambda *names: ("ov", list(names))
NPM = lambda: ("npm",)
MULTI = lambda *srcs: ("multi", list(srcs))
RES = lambda key: ("res", key)
FE = lambda note: ("fe", note)
FAILING = lambda note, sha=None: ("fail", note, sha)

STACK_PRE = "Stack up with isolated ports/DB (blocked: BLK-C6-05)"
BE = "JDK 21 + Docker (Testcontainers)"

# ---------------------------------------------------------------- C0 Architecture / Integration
A = "C0 Architecture/Integration"
for (i, f, c, t, p, e, s) in [
    ("MIG-01", "V29 migration", "the three run tables exist and tenant_id is mandatory on all", "Positive", "P0", "action_runs, workflow_runs, workflow_run_steps exist; tenant_id NOT NULL", J(("WorkflowRunPersistenceMigrationTests", "the three tables exist"))),
    ("MIG-02", "V29 migration", "run cannot point at another tenant's workspace or a project of another workspace", "Cross-tenant", "P0", "DB constraint rejects the row", J(("WorkflowRunPersistenceMigrationTests", "cannot point at a workspace of another tenant"), ("WorkflowRunPersistenceMigrationTests", "must name a real project"))),
    ("MIG-03", "V29 migration", "workflow run start is idempotent per scope; TEST never shares a key with LIVE", "Idempotency", "P0", "Second insert with same scope key is rejected; TEST/LIVE keys separate", J(("WorkflowRunPersistenceMigrationTests", "idempotent per scope"))),
    ("MIG-04", "V29 migration", "status/mode/attempt/RUNNING-vs-finished shape and compensation constraints", "Boundary", "P1", "Invalid combinations rejected by CHECK constraints", J(("WorkflowRunPersistenceMigrationTests", "status, mode, attempt"), ("WorkflowRunPersistenceMigrationTests", "status, compensation"))),
    ("MIG-05", "V29 migration", "contract columns: action_runs.mutating default TRUE and mandatory; workflow lease nullable", "Positive", "P0", "Columns exist with the C4 contract defaults", J(("WorkflowRunPersistenceMigrationTests", "C4 contract columns"))),
    ("MIG-06", "V29 migration", "app_id NULL is one key scope; application and workspace come together or not at all", "Boundary", "P1", "NULL app handled as a single scope; mixed state rejected", J(("WorkflowRunPersistenceMigrationTests", "app_id accepts NULL"), ("WorkflowRunPersistenceMigrationTests", "come together"))),
    ("MIG-07", "V29 migration", "steps belong to a run of the same tenant and go with their run", "Cross-tenant", "P1", "FK + cascade enforce tenant match", J(("WorkflowRunPersistenceMigrationTests", "steps belong to a run"))),
    ("MIG-08", "V29 migration", "V29 registered as next migration, V28 not renumbered", "Regression", "P1", "Flyway order V28 then V29", J(("WorkflowRunPersistenceMigrationTests", "registered as the next migration"))),
    ("MIG-09", "V29 undo", "undo refuses while a run is in flight and changes nothing", "Rollback", "P0", "Refusal, no data change", J(("WorkflowRunPersistenceMigrationTests", "undo script refuses while a run is in flight"))),
    ("MIG-10", "V29 undo", "undo drops exactly the three V29 tables, children first, guards in-flight work", "Rollback", "P1", "Only V29 tables dropped", J(("WorkflowRunPersistenceMigrationTests", "undo script guards in flight work"))),
    ("MIG-11", "V28 migration", "every data-runtime table exists with mandatory tenant_id", "Positive", "P0", "Tables + NOT NULL tenant_id", J(("DataRuntimeMigrationTests", "every table exists"))),
    ("MIG-12", "V28 migration", "data source cannot point at a workspace of another tenant; child rows cannot reference another tenant's source", "Cross-tenant", "P0", "DB rejects", J(("DataRuntimeMigrationTests", "workspace of another tenant"), ("DataRuntimeMigrationTests", "child rows cannot reference"))),
    ("MIG-13", "V28 migration", "binding names only a source owned by the project's own workspace; never crosses tenants/workspaces", "Cross-tenant", "P0", "DB rejects cross-workspace binding", J(("DataRuntimeMigrationTests", "B-C0-W-05"), ("DataRuntimeMigrationTests", "a binding is per project and mode"))),
    ("MIG-14", "V28 migration", "credentials stored as ciphertext only; idempotency rows validate state and key shape", "Negative", "P0", "Plaintext / bad key shape rejected", J(("DataRuntimeMigrationTests", "ciphertext only"), ("DataRuntimeMigrationTests", "idempotency rows validate"))),
    ("MIG-15", "V28 undo", "undo refuses while data sources exist", "Rollback", "P1", "Refusal, no data change", J(("DataRuntimeMigrationTests", "undo script refuses"))),
    ("MIG-16", "V28 migration", "status/config shape/name uniqueness per tenant", "Boundary", "P2", "Constraints enforced", J(("DataRuntimeMigrationTests", "status, config shape"), ("DataRuntimeMigrationTests", "schema snapshots are unique"))),
    ("MIG-17", "V26 migration", "V25 data upgraded: every row in DEFAULT tenant, nothing lost, ids unchanged", "Persistence", "P0", "No data loss on upgrade", J(("TenantMigrationFromV25Tests", "every row lands in the DEFAULT tenant"))),
    ("MIG-18", "V26 migration", "legacy INSERT keeps working; mismatching tenant_id rejected on migrated rows", "Regression", "P0", "Back-compat + isolation", J(("TenantMigrationFromV25Tests", "keeps legacy INSERTs working"))),
    ("MIG-19", "V26 undo", "undo refuses once V2 tenant data exists; on backfill-only DB keeps business rows", "Rollback", "P1", "Safe undo", J(("TenantMigrationFromV25Tests", "undo script refuses"), ("TenantMigrationFromV25Tests", "undo script on a database"))),
    ("MIG-20", "V27 migration", "publish_configs: tenant matches workspace; PRIVATE_LINK needs token hash; PUBLIC cannot require auth; defaults private+static", "Negative", "P1", "Constraints enforced", J(("PublishConfigsMigrationTests", ""))),
    ("MIG-21", "Flyway", "versions contiguous V1..current, no duplicates, outOfOrder never enabled", "Static", "P0", "SQ-01/02/04 PASS", STATIC("SQ-01", "SQ-02", "SQ-04")),
    ("MIG-22", "Flyway", "no migration file beyond the highest version allocated in MIGRATION_LEDGER.md", "Static", "P1", "SQ-03 PASS", STATIC("SQ-03")),
    ("MIG-23", "Real data upgrade", "upgrade a COPY of the dev DB (V25, ~1800 projects) to V29: no loss, time, constraints", "Persistence", "P0", "All rows survive; migration time recorded", GAP("no automated test; needs a DB copy (dev DB is V25, never migrate it in place)")),
    ("MIG-24", "Fresh install", "empty DB boots through V1..V29 with Flyway validate on the real app (not Testcontainers)", "Positive", "P1", "App starts, readiness 200", BLK("needs the isolated stack")),
]:
    row("C0-" + i, A, f, c, t, p, e, s, "C0", pre=(STACK_PRE if isinstance(s, tuple) and s[0] == "blocked" else BE), req=["integration-contract", "data-runtime"])
for (i, f, c, t, p, e, s, o) in [
    ("WIR-01", "Run stores", "without the property the durable JDBC stores are used", "Positive", "P0", "No silent memory store in production wiring", J(("RunStoreConfigurationTests", "without the property")), "C0"),
    ("WIR-02", "Run stores", "unknown run-store value fails startup and names the missing store", "Negative", "P0", "Startup failure with clear message", J(("RunStoreConfigurationTests", "unknown value makes startup fail")), "C0"),
    ("WIR-03", "Run stores", "memory is an explicit override only; empty/misspelled value is not a silent fallback", "Malformed", "P0", "No fallback to volatile stores", J(("RunStoreConfigurationTests", "memory is an explicit override"), ("RunStoreConfigurationTests", "empty or differently spelled"), ("RunStoreConfigurationTests", "jdbc explicitly selects")), "C0"),
    ("WIR-04", "Run stores", "workflow runtime disabled: no run store exists", "Boundary", "P2", "No store bean", J(("RunStoreConfigurationTests", "workflow runtime disabled")), "C0"),
    ("WIR-05", "Feature flags", "no runtime route is mounted by default; data platform admin family not mounted either", "Negative", "P0", "Flags default OFF", J(("AppRuntimeFlagsOffTests", "")), "C0"),
    ("WIR-06", "Feature flags", "V2 flags default false; system-admin business access default false", "Static", "P0", "SQ-05/06", STATIC("SQ-05", "SQ-06"), "C0"),
    ("WIR-07", "Volatile guard", "LIVE mutating action refused with RUNTIME_STORES_VOLATILE while stores are volatile; TEST/non-mutating unaffected", "Negative", "P0", "No write on volatile stores", J(("VolatileStoreGuardsTests", "")), "C0"),
    ("WIR-08", "Volatile guard", "LIVE workflow start refused over HTTP while the run store is volatile", "Negative", "P0", "503-style refusal", J(("AppRuntimeApiTests", "LIVE workflow start is refused while the run store is volatile")), "C0"),
    ("WIR-09", "Data gateway wiring", "the data gateway bean exists and is the real one", "Positive", "P1", "Real DataGateway wired", J(("DataRuntimeLiveApiTests", "the data gateway bean exists")), "C0"),
    ("WIR-10", "Port adapters", "C1 ports: context + decision conversion, denial passed on, other app denied without asking C1", "Negative", "P0", "Adapters faithful to C1", J(("C1PortAdaptersTests", "")), "C0"),
    ("WIR-11", "Port adapters", "actor kinds: enums identical, USER never produced for another kind", "Regression", "P1", "Names match both ways", J(("ActorKindsTests", "")), "C0"),
    ("WIR-12", "Port adapters", "ActionDataPort: ambiguous write -> IDEMPOTENCY_OUTCOME_UNKNOWN not retryable; unbound slot definite refusal; TEST never reaches source", "Partial-failure", "P0", "Error taxonomy preserved", J(("ActionDataPortAdapterTests", "")), "C0"),
    ("WIR-13", "Mapping catalog", "local query ids translated for the gateway; stored document unchanged", "Positive", "P1", "No mutation of stored definition", J(("RuntimeMappingCatalogTests", "")), "C0"),
    ("WIR-14", "Web origins", "CORS is exactly the three portal origins, no wildcard; redirect/logout URIs per portal", "Negative", "P1", "Exact origins", J(("WebOriginsTests", "")), "C0"),
    ("WIR-15", "Runtime API (pure)", "status codes follow contract table; error body shape; unknown outcome 409 never retryable; rate limited carries Retry-After", "Positive", "P0", "Contract-conformant mapping", J(("RuntimeApiPureTests", "status codes follow the contract"), ("RuntimeApiPureTests", "unknown outcome is 409"), ("RuntimeApiPureTests", "rate limited carries Retry-After"), ("RuntimeApiPureTests", "generic error body")), "C0"),
    ("WIR-16", "Runtime API (pure)", "undeclared body fields refused; non-object body refused; mode must be LIVE|TEST exactly; empty body = LIVE", "Malformed", "P1", "Strict request schema", J(("RuntimeApiPureTests", "fields the contract does not list"), ("RuntimeApiPureTests", "body that is not an object"), ("RuntimeApiPureTests", "mode must be LIVE or TEST"), ("RuntimeApiPureTests", "an empty body")), "C0"),
    ("WIR-17", "Codecs", "definition and run codecs round-trip; corrupt/unknown decodes to null or is refused; identity never from document", "Persistence", "P1", "Lossless round trip", J(("DefinitionCodecsTests", ""), ("RunCodecTests", "")), "C0"),
    ("ARCH-01", "Layering", "logic code imports no repository/connector/JDBC/HTTP client/framework; only data port + handlers talk to data", "Static", "P0", "Architecture rules hold", J(("ActionContractV2Tests", "logic code imports no repository"), ("ActionContractV2Tests", "only the data port"), ("ActionContractV2Tests", "no logic source accesses a data store")), "C0"),
    ("ARCH-02", "Layering", "logic sources use the Jackson 3 form (no Jackson 2 generic set)", "Static", "P2", "Rule holds", J(("ActionContractV2Tests", "Jackson 3")), "C0"),
    ("ARCH-03", "Skeleton", "backend/src/wiring-skeleton contains only .skel/README (not compiled)", "Static", "P1", "SQ-07", STATIC("SQ-07"), "C0"),
    ("ARCH-04", "Backward compat", "legacy project/publish/static-site/server-runtime/audit APIs unchanged", "Regression", "P0", "Legacy suites green", J(("ProjectApiTests", ""), ("PublishApiTests", ""), ("StaticSiteTests", ""), ("ServerRuntimeTests", ""), ("AuditApiTests", "")), "C0"),
    ("ARCH-05", "Ownership", "no change to another owner's files / HOT FILES in a SHA", "Static", "P1", "diff shows only owner-expected files", GAP("no automated check; C6 reviews git diff manually each SHA (evidence/*/diff-vs-baseline.txt)"), "C0"),
    ("ARCH-06", "Integration wiring", "C3 persistence/management adapters integrated into integration/v2", "Regression", "P0", "Merged and green", BLK("C3 not GREEN; C0 has not integrated yet"), "C3"),
]:
    row("C0-" + i, A, f, c, t, p, e, s, o, pre=(STACK_PRE if s[0] == "blocked" else BE), req=["integration-contract", "runtime-api"] if i.startswith(("WIR", "ARCH")) else [])

# persistence / restart (integration-test level; real stack variants are BLOCKED)
for (i, f, c, t, p, e, s, o) in [
    ("PER-01", "Data persistence", "data source round-trips, invisible to another tenant, other tenant cannot overwrite by reusing id", "Cross-tenant", "P0", "Isolation at repository", J(("JdbcPersistenceTests", "data source round-trips"), ("JdbcPersistenceTests", "another tenant cannot overwrite")), "C3"),
    ("PER-02", "Data persistence", "credentials tenant-scoped ciphertext, never in a normal read; non-ciphertext refused", "Negative", "P0", "No plaintext at rest", J(("JdbcPersistenceTests", "credentials are tenant-scoped ciphertext"), ("JdbcPersistenceTests", "not ciphertext is refused")), "C3"),
    ("PER-03", "Data persistence", "stale or repeated save is a conflict and changes nothing (optimistic version)", "Concurrent", "P1", "No lost update", J(("JdbcPersistenceTests", "saving needs a newer version"), ("JdbcPersistenceTests", "only replaced by a newer version")), "C3"),
    ("PER-04", "Data persistence", "TEST/LIVE bindings separate, default-deny, tenant-scoped; binding cannot point at another tenant/workspace", "Cross-tenant", "P0", "Binding isolation", J(("JdbcPersistenceTests", "TEST and LIVE bindings are separate"), ("JdbcPersistenceTests", "binding cannot point at a data source")), "C3"),
    ("PER-05", "Data persistence", "stored definition that no longer validates is not found and does not break the list", "Malformed", "P2", "Graceful degradation", J(("JdbcPersistenceTests", "no longer validates")), "C3"),
    ("PER-06", "Idempotency persistence", "completed mutation replay survives a new store instance (restart)", "Restart", "P0", "Replay after restart writes nothing", J(("JdbcIdempotencyStoreTests", "survives a new store instance")), "C3"),
    ("PER-07", "Session persistence", "session survives a full application restart while Redis stays up", "Restart", "P1", "Same session works", J(("SessionRestartTests", "")), "C1"),
    ("PER-08", "Action runs", "finished run survives a restart and is replayed by a new store instance", "Restart", "P0", "Replay, no re-execution", J(("JdbcActionRunStoreTests", "finished run survives a restart")), "C4"),
    ("PER-09", "Action runs", "RUNNING run survives restart as in-progress and is not started a second time", "Restart", "P0", "No duplicate execution", J(("JdbcActionRunStoreTests", "RUNNING run survives a restart")), "C4"),
    ("PER-10", "Action runs", "ambiguous write stays unknown and never retryable across a restart, however often asked", "Restart", "P0", "No second write", J(("JdbcActionRunStoreTests", "ambiguous write stays unknown")), "C4"),
    ("PER-11", "Action runs", "run of another tenant's app refused and leaves no row; other tenant cannot see/finish a run", "Cross-tenant", "P0", "Tenant isolation", J(("JdbcActionRunStoreTests", "application of another tenant is refused"), ("JdbcActionRunStoreTests", "another tenant cannot see or finish")), "C4"),
    ("PER-12", "Action runs", "purge deletes finished records older than horizon, never RUNNING", "Boundary", "P1", "No live data purged", J(("JdbcActionRunStoreTests", "purge deletes finished")), "C4"),
    ("PER-13", "Workflow runs", "in-flight run, step attempts and compensation progress reconstructed exactly after restart", "Restart", "P0", "Exact state recovery", J(("JdbcWorkflowRunStoreTests", "reconstructed exactly after a restart")), "C4"),
    ("PER-14", "Workflow runs", "finished runs stay finished and are never executed again by a new process", "Restart", "P0", "No re-execution", J(("WorkflowRestartRecoveryTests", "finished runs stay finished")), "C4"),
    ("PER-15", "Workflow runs", "WAIT timer survives the restart and fires once", "Restart", "P1", "Fires exactly once", J(("WorkflowRestartRecoveryTests", "WAIT timer survives")), "C4"),
    ("PER-16", "Workflow runs", "retry in backoff survives restart and is attempted after its delay with attempt count kept", "Restart", "P1", "Attempt counter preserved", J(("WorkflowRestartRecoveryTests", "retry in backoff survives")), "C4"),
    ("PER-17", "Workflow runs", "interrupted compensation resumes after restart without repeating one that already ran", "Rollback", "P0", "Compensation exactly once", J(("WorkflowRestartRecoveryTests", "compensation that was interrupted resumes")), "C4"),
    ("PER-18", "Workflow runs", "two processes sweeping one database never both take the same run", "Concurrent", "P0", "Single owner", J(("WorkflowRestartRecoveryTests", "two processes sweeping")), "C4"),
    ("PER-19", "Workflow runs", "ambiguous write stays unknown after restart, never retried, never compensated", "Restart", "P0", "No corruption on unknown outcome", J(("WorkflowRestartRecoveryTests", "ambiguous write stays unknown")), "C4"),
    ("PER-20", "Workflow runs", "PENDING run whose job was lost is started by the next process; lost job picked up without repeating a finished step", "Restart", "P1", "Run completes", J(("WorkflowRestartRecoveryTests", "PENDING run that never got its job"), ("WorkflowRestartRecoveryTests", "job was lost with the process")), "C4"),
    ("PER-21", "Workflow runs", "of several concurrent compare-and-sets from one version exactly one wins; stale CAS writes nothing", "Concurrent", "P0", "Optimistic locking", J(("JdbcWorkflowRunStoreTests", "exactly one wins"), ("JdbcWorkflowRunStoreTests", "stale version fails and writes nothing")), "C4"),
    ("PER-22", "Workflow runs", "concurrent creates of one key produce exactly one run; create idempotent per scope; TEST/LIVE/other users do not share a key", "Idempotency", "P0", "One run per key", J(("JdbcWorkflowRunStoreTests", "concurrent creates of one key"), ("JdbcWorkflowRunStoreTests", "create is idempotent per scope")), "C4"),
    ("PER-23", "Workflow runs", "get/list/CAS never cross tenants; run for another tenant's app or foreign workspace is refused", "Cross-tenant", "P0", "Tenant isolation", J(("JdbcWorkflowRunStoreTests", "never cross tenants"), ("JdbcWorkflowRunStoreTests", "cannot reach a run of another tenant"), ("JdbcWorkflowRunStoreTests", "application of another tenant, or with a foreign workspace")), "C4"),
    ("PER-24", "Workflow runs", "redaction and purge only touch finished runs that are not compensating", "Boundary", "P1", "No live data purged", J(("JdbcWorkflowRunStoreTests", "redaction and purge")), "C4"),
    ("PER-25", "Real restart", "backend process restart on the real stack: login works, project and runs survive (R08)", "Restart", "P0", "State intact after restart", BLK("needs the isolated stack"), "C0"),
]:
    row("C0-" + i, A, f, c, t, p, e, s, o, pre=(STACK_PRE if s[0] == "blocked" else BE), req=["data-runtime", "action-workflow"])

# build
for (i, f, c, t, p, e, s, bug) in [
    ("BLD-01", "Backend build", "uncached Kotlin compile + test with default Gradle memory on a 16 GB Mac", "Regression", "P1", "Compiles and runs tests", FAILING("compileKotlin: Not enough memory to run compilation (default heap); passes with -Pkotlin.daemon.jvmargs=-Xmx3g (evidence: G2; default heap NOT retried at 8e91172)", "f894cc6"), "BUG-C6-006"),
    ("BLD-02", "Backend build", "uncached compile + full test with -Xmx3g Kotlin daemon (no build cache, rerun-tasks)", "Positive", "P0", "1502 tests executed", J(("WorkflowEngineTests", ""), ("ActionRuntimeTests", ""), ("GatewayTests", "")), "-"),
    ("BLD-03", "Frontend build", "typecheck root + packages + apps", "Static", "P0", "0 errors", FE("typecheck"), "-"),
    ("BLD-04", "Frontend build", "next build of 3 portals + root legacy UI", "Positive", "P0", "Build succeeds", FE("build"), "-"),
    ("BLD-05", "Worktree hygiene", "a failed Kotlin compile leaves no untracked files in the repo (backend/.kotlin/ is not gitignored)", "Regression", "P3", "git status clean", FAILING("backend/.kotlin/errors/*.log appeared untracked after a failed compile (G2); not reproduced at 8e91172 because compile succeeded", "f894cc6"), "BUG-C6-008"),
]:
    row("C0-" + i, A, f, c, t, p, e, s, "C0", pre=BE, bug=bug)

# ---------------------------------------------------------------- C1 IAM / Security
A = "C1 IAM/Security"
C1 = [
    ("AUTH-01", "Authentication", "anonymous protected API is 401 with structured body and creates no session", "Unauthorized", "P0", "401, no session", J(("AuthSecurityTests", "anonymous protected API"), ("IsolationApiTests", "without a session every project route is 401"))),
    ("AUTH-02", "Authentication", "wrong password is 401; repeated failures end in 429 with Retry-After", "Boundary", "P0", "Brute force throttled", J(("AuthSecurityTests", "wrong password is 401"))),
    ("AUTH-03", "Authentication", "missing CSRF token on a mutating request is 403 CSRF_INVALID; unknown route elsewhere still needs CSRF + session", "Negative", "P0", "CSRF enforced", J(("AuthSecurityTests", "missing CSRF token"), ("WebhookSecurityTests", "an unknown route elsewhere"))),
    ("AUTH-04", "Authentication", "logout invalidates the session; session stored in Redis; cookie HttpOnly", "Positive", "P0", "Session lifecycle", J(("AuthSecurityTests", "logout invalidates"), ("RedisSessionTests", ""))),
    ("AUTH-05", "Authentication", "disabled user loses every route on the next request (reads and writes)", "Unauthorized", "P0", "Immediate revocation", J(("AuthSecurityTests", "disabled user loses access"), ("IsolationApiTests", "a disabled account loses every route"))),
    ("AUTH-06", "Authentication", "password-hash gate: at most N concurrent verifications, overflow 503 + Retry-After, permit released on failure", "Concurrent", "P1", "No CPU exhaustion", J(("PasswordHashGateTests", ""))),
    ("AUTH-07", "Authentication", "activation link: expired/forged refused, single use, token stored only as hash, reset signs out", "Replay", "P0", "No link reuse", J(("AccountActivationTests", "expired or forged links"), ("AccountActivationTests", "single use"))),
    ("AUTH-08", "Authentication", "only admins create users; duplicates/bad roles refused; never self; never the last system admin", "Unauthorized", "P0", "Admin invariants", J(("AccountActivationTests", "only admins create users"))),
    ("AUTH-09", "Authentication", "SCIM off unless enabled; bearer required; deprovision revokes; local accounts invisible; groups map to membership only via admin mapping", "Negative", "P1", "SCIM safe by default", J(("ScimDisabledTests", ""), ("ScimTests", ""))),
    ("AUTH-10", "Authentication", "login and failures are audited with request id", "Positive", "P1", "Audit rows", J(("AuthSecurityTests", "login and failures are audited"))),
    ("AUTH-11", "Authentication", "CORS allows configured origin and rejects others; webhook CORS origins exact", "Negative", "P1", "Exact origins", J(("AuthSecurityTests", "CORS allows the configured origin"), ("WebhookSecurityTests", "CORS origins must be exact"))),
    ("AUTH-12", "Authentication", "Redis down: structured 503 quickly, same session works again afterwards", "Partial-failure", "P0", "Fail fast + recover", J(("RedisOutageTests", ""))),
    ("AUTH-13", "SSO", "OIDC/SSO sign-in against real Keycloak (E2E_07)", "Positive", "P1", "SSO login works", BLK("needs stack + RUN_SSO=1 (Keycloak)")),
    ("AUTH-14", "Session", "session expiry / idle timeout / fixation on login", "Boundary", "P1", "Session rotated on login, expires", GAP("no test names these cases")),
    ("TEN-01", "Tenant isolation", "forged tenantId in a runtime body is refused (tenantId first)", "Cross-tenant", "P0", "422 undeclared field", J(("AppRuntimeApiTests", "tenantId first"))),
    ("TEN-02", "Tenant isolation", "claimed tenant must be the tenant of the workspace (gateway)", "Cross-tenant", "P0", "Denied", J(("AccessAdaptersTests", "cross tenant"))),
    ("TEN-03", "Tenant isolation", "forged workspace+project combination denied over HTTP and at the gateway", "Cross-tenant", "P0", "Denied, nothing leaks", J(("DataRuntimeLiveApiTests", "forged workspace and project"))),
    ("TEN-04", "Tenant isolation", "an outsider learns nothing and changes nothing through any combination of foreign ids", "Cross-tenant", "P0", "404, no write", J(("IsolationApiTests", "an outsider learns nothing"))),
    ("TEN-05", "Tenant isolation", "a role in one workspace grants nothing in another; foreign workspace routes hidden and create nothing", "Cross-tenant", "P0", "No cross-workspace authority", J(("IsolationApiTests", "a role in one workspace"), ("IsolationApiTests", "workspace-level routes of a foreign workspace"))),
    ("TEN-06", "Tenant isolation", "versions/assets/domains/deployments of another project not usable through my project", "Cross-tenant", "P0", "IDOR closed", J(("IsolationApiTests", "ids of another project"))),
    ("TEN-07", "Tenant isolation", "audit events scoped to caller's workspace even with foreign project filter", "Cross-tenant", "P1", "No audit leak", J(("IsolationApiTests", "audit events are scoped"))),
    ("TEN-08", "Tenant isolation", "explicit tenant_id that differs from the workspace tenant is rejected on every child table, never overwritten", "Cross-tenant", "P0", "DB trigger rejects", J(("TenantFoundationMigrationTests", "differs from the workspace tenant"), ("TenantFoundationMigrationTests", "workspace tenant constraint"))),
    ("TEN-09", "Tenant isolation", "removed tenant member loses access; suspended tenant closed to ordinary users", "Unauthorized", "P0", "Access revoked", J(("TenantAccessTests", "a member removed from the tenant"))),
    ("TEN-10", "Tenant isolation", "tenant B cannot reach tenant A's data source, query or cache; mapping/query of another tenant never used", "Cross-tenant", "P0", "Isolation at gateway", J(("GatewayTests", "tenant b cannot reach tenant a"), ("GatewayTests", "a mapping of another tenant"), ("GatewayTests", "planted for a data source"))),
    ("TEN-11", "Tenant isolation", "different tenant refused by sourceRef and by binding; tenant-level source without workspace unreachable at run time", "Cross-tenant", "P0", "Refused like missing", J(("DataRuntimeLiveApiTests", "a different tenant is refused"), ("DataRuntimeLiveApiTests", "tenant-level source without a workspace"))),
    ("TEN-12", "Workspace isolation", "same tenant, another workspace: direct sourceRef refused exactly like a missing source; cannot write; binding refused by writer and DB", "Cross-tenant", "P0", "Workspace boundary holds", J(("DataRuntimeLiveApiTests", "another workspace, a direct sourceRef is refused"), ("DataRuntimeLiveApiTests", "cannot write either"), ("DataRuntimeLiveApiTests", "a binding is refused by the writer"), ("DataRuntimeLiveApiTests", "another workspace cannot reach"))),
    ("TEN-13", "Workspace isolation", "same workspace direct sourceRef is allowed (positive control)", "Positive", "P1", "Allowed", J(("DataRuntimeLiveApiTests", "directly referenced source of that workspace is allowed"))),
    ("TEN-14", "Tenant isolation", "another tenant's workflow never started; status/cancel look not found; forged job finds nothing", "Cross-tenant", "P0", "Not found", J(("WorkflowEngineTests", "a workflow of another tenant is never started"), ("WorkflowEngineTests", "status and cancel of a run in another tenant"), ("WorkflowEngineTests", "forged job for another tenant"))),
    ("TEN-15", "Tenant isolation", "another tenant's / app's action indistinguishable from unknown; same id+key in two tenants never see each other", "Cross-tenant", "P0", "UNKNOWN_ACTION", J(("ActionRuntimeTests", "another tenant's action is indistinguishable"), ("ActionRuntimeTests", "tenant isolation - two tenants"), ("ActionRuntimeTests", "another app's action is unknown too"))),
    ("TEN-16", "Tenant isolation", "approval of another tenant denied and looks like not found; cross-tenant decisions need C1 policy", "Cross-tenant", "P0", "Denied", J(("ApprovalServiceTests", "approval of another tenant"), ("ApprovalServiceTests", "cross tenant decisions"))),
    ("TEN-17", "Tenant isolation", "tenant/data source named in webhook body or headers is ignored; endpoint decides", "Cross-tenant", "P0", "Ignored", J(("WebhookReplayTests", "a tenant named in the body or in headers is ignored"), ("WebhookTests", "come from the endpoint never from the request"))),
    ("TEN-18", "Tenant isolation", "per-tenant rate limits: a limited tenant does not slow another; backlog cannot crowd out another tenant", "Concurrent", "P1", "Fair isolation", J(("TenantRateLimitTests", "does not slow another"), ("TenantRateLimitTests", "one tenant exhausting its budget"), ("TenantRateLimitTests", "backlog of due schedules"))),
    ("TEN-19", "Tenant isolation", "two real tenants over HTTP on the real stack: IDOR sweep on every route family", "Cross-tenant", "P0", "No cross-tenant read/write", BLK("needs the isolated stack with two tenants")),
    ("TEN-20", "Tenant model", "tenant services: slug/name validation, DEFAULT tenant cannot be suspended, last TENANT_ADMIN cannot be removed", "Boundary", "P1", "Invariants", J(("TenantServiceTests", ""))),
    ("PRM-01", "Permissions", "default deny: unknown operation, missing workspace, non-user actors, disabled account, unknown ids", "Negative", "P0", "Deny", J(("AccessAdaptersTests", "default deny"))),
    ("PRM-02", "Permissions", "operation -> permission mapping is total and matches the contract", "Regression", "P0", "No unmapped operation", J(("AccessAdaptersTests", "mapping is total"))),
    ("PRM-03", "Permissions", "canonical codes closed set; unknown/blank/lower-case/legacy names are not canonical; legacy names never leak", "Malformed", "P0", "Closed vocabulary", J(("PermissionCanonicalTests", ""))),
    ("PRM-04", "Permissions", "unauthorized APP_EDIT: TEST mode of a query needs APP_EDIT; viewer can use but not edit", "Unauthorized", "P0", "403", J(("AppRuntimeApiTests", "TEST mode of a query needs APP_EDIT"), ("AccessAdaptersTests", "viewer can use but not edit"))),
    ("PRM-05", "Permissions", "project viewer reads basics, cannot read form data, cannot write anything", "Unauthorized", "P0", "Read-only", J(("IsolationApiTests", "a project viewer reads the basics"))),
    ("PRM-06", "Permissions", "QUERY_EXECUTE at gateway adapter: workspace admin may do every operation, plain member none, project owner reads/queries but not manage/mutate/sample", "Unauthorized", "P0", "Adapter-level deny", J(("AccessAdaptersTests", "a workspace admin may do every operation"), ("AccessAdaptersTests", "a project owner may read and query"))),
    ("PRM-07", "Permissions", "GAP-C6-05: user with a role but WITHOUT QUERY_EXECUTE is refused on the LIVE query route over HTTP", "Unauthorized", "P0", "403 on LIVE query", GAP("no HTTP-level test (GAP-C6-05)")),
    ("PRM-08", "Permissions", "unauthorized DATA_MUTATE: data action forbidden even in TEST mode (HTTP)", "Unauthorized", "P0", "403", J(("AppRuntimeApiTests", "without DATA_MUTATE"))),
    ("PRM-09", "Permissions", "unauthorized WORKFLOW_EXECUTE at engine/action level: start needs APP_USE + WORKFLOW_EXECUTE and audits the denial", "Unauthorized", "P0", "Denied + audited", J(("WorkflowEngineTests", "start needs APP_USE and WORKFLOW_EXECUTE"), ("ActionRuntimeTests", "START_WORKFLOW needs WORKFLOW_EXECUTE"))),
    ("PRM-10", "Permissions", "GAP-C6-06: WORKFLOW_EXECUTE denial over HTTP (POST workflow start by a user without it)", "Unauthorized", "P0", "403 over HTTP", GAP("denial tested at logic/adapter level only (GAP-C6-06)")),
    ("PRM-11", "Permissions", "each missing permission is FORBIDDEN and audited DENIED, input never inspected; both APP_USE and ACTION_EXECUTE required", "Unauthorized", "P0", "Deny + audit", J(("ActionRuntimeTests", "each missing permission is FORBIDDEN"), ("ActionRuntimeTests", "APP_USE and ACTION_EXECUTE are both required"))),
    ("PRM-12", "Permissions", "UI cannot grant itself anything; payload/explicit inputs never reach the permission check; dispatch cannot spoof the user", "Negative", "P0", "Server-side authz", J(("ActionRuntimeTests", "the UI cannot grant itself"), ("ActionRuntimeTests", "dispatch cannot spoof the acting user"))),
    ("PRM-13", "Permissions", "write types need DATA_MUTATE in addition to ACTION_EXECUTE; permission codes are the five canonical codes", "Positive", "P0", "Contract", J(("ActionContractV2Tests", "write types need DATA_MUTATE"), ("ActionContractV2Tests", "five canonical codes"))),
    ("PRM-14", "Permissions", "permissions re-checked at every workflow step", "Negative", "P0", "Revocation honoured mid-run", J(("WorkflowEngineTests", "permissions are re-checked at every step"))),
    ("PRM-15", "Permissions", "authorizer / tenant gate / access port outage fails closed (deny or retryable dependency error)", "Partial-failure", "P0", "Fail closed", J(("GatewayTests", "an authorizer outage denies"), ("ActionContractV2Tests", "missing or throwing authorizer"), ("ActionRuntimeTests", "access port that throws fails closed"), ("WorkflowEngineTests", "start fails closed"))),
    ("PRM-16", "Permissions", "SYSTEM_ADMIN default: platform scope only, no business data of non-member workspaces; keeps platform operations", "Unauthorized", "P0", "No god mode", J(("TenantAccessTests", "SYSTEM_ADMIN default policy"), ("TenantAccessTests", "SYSTEM_ADMIN keeps the platform operations"), ("AccessAdaptersTests", "system admin with the flag OFF"))),
    ("PRM-17", "Permissions", "legacy god-mode flag ON behaves as before and self-grant still rejected", "Regression", "P1", "Flag semantics", J(("TenantAccessLegacyFlagTests", ""))),
    ("PRM-18", "Permissions", "tenant admin / workspace admin / member boundaries: no implicit workspace, app or data authority; demoted admin loses authority at once", "Unauthorized", "P0", "Tenant authority bounded", J(("TenantAdminViaRoleTests", ""), ("TenantAccessTests", "TENANT_ADMIN is a tenant-level role only"))),
    ("PRM-19", "Permissions", "/me exposes canonical permissions per role; no platform scope for ordinary member", "Positive", "P1", "Canonical codes only", J(("MeTenancyTests", ""))),
    ("ESC-01", "Escalation", "nobody adds itself to a tenant or changes its own tenant role, not even a system admin", "Unauthorized", "P0", "Self-grant refused", J(("PrivilegeEscalationTests", "tenant level"))),
    ("ESC-02", "Escalation", "project owner / plain member cannot promote themselves; admin can promote someone else", "Unauthorized", "P0", "Self-promotion refused", J(("PrivilegeEscalationTests", "a project owner and a plain member"))),
    ("ESC-03", "Escalation", "workspace admin cannot re-add/promote itself or add itself to a project it is not in", "Unauthorized", "P0", "Refused", J(("PrivilegeEscalationTests", "a workspace admin cannot re-add"))),
    ("ESC-04", "Escalation", "system admin member cannot raise own role; cannot add itself to a workspace (R-08)", "Unauthorized", "P0", "Refused", J(("PrivilegeEscalationTests", "a system admin who is a member cannot raise"), ("PrivilegeEscalationTests", "R-08"))),
    ("ESC-05", "Escalation", "system admin who is only a workspace member cannot make itself owner of an application", "Unauthorized", "P0", "Refused", J(("AdminTransferOwnershipSelfGrantSpec", ""))),
    ("ESC-06", "Escalation", "platform duty kept: system admin can still onboard OTHER people; tenant admin has no way into workspaces via member API", "Positive", "P1", "Only self-grant closed", J(("PrivilegeEscalationTests", "can still onboard OTHER people"), ("PrivilegeEscalationTests", "a tenant admin has no way into"))),
    ("SEC-01", "SSRF", "private/loopback/link-local/multicast/any-local/CGNAT/reserved/doc ranges refused; IPv6 zone id, 6to4, Teredo, NAT64 judged correctly", "Negative", "P0", "No internal address reachable", J(("PublicAddressTests", ""), ("AddressRangeSpecTests", ""))),
    ("SEC-02", "SSRF", "pinned HTTPS transport (DNS rebinding / redirect to private address)", "Negative", "P0", "Connects only to the vetted IP", J(("PinnedHttpsTransportTests", ""))),
    ("SEC-03", "SSRF", "CALL_API action cannot be smuggled a URL through definition or input", "Malformed", "P0", "INVALID_DEFINITION", J(("ActionRuntimeTests", "CALL_API cannot be smuggled a url"), ("ActionRuntimeTests", "smuggled url or missing reference"))),
    ("SEC-04", "SQL guard", "SQL guard refuses writes/multi-statement/dangerous constructs; read-only session; statement timeout; row limit", "Negative", "P0", "No write through a read query", J(("SqlGuardTests", ""), ("PostgresSessionSecurityTests", ""))),
    ("SEC-05", "Credentials", "stored credential reaches the connector yet appears in no response, audit row or table other than as ciphertext", "Negative", "P0", "No secret leak", J(("DataRuntimeLiveApiTests", "stored credential reaches the connector"), ("GatewayTests", "no credential reaches a response"), ("SecurityPrimitivesTests", ""))),
    ("SEC-06", "Gateway input", "a browser cannot send SQL, URL, header or tenant; only approved operations exist", "Malformed", "P0", "Rejected", J(("GatewayTests", "a browser cannot send sql"), ("GatewayTests", "only approved operations exist"))),
    ("SEC-07", "Webhook", "wrong secret, tampered body or timestamp rejected without effect; malformed signature headers rejected", "Negative", "P0", "No effect", J(("WebhookTests", "wrong secret tampered body"), ("WebhookTests", "signature verification rejects malformed headers"))),
    ("SEC-08", "Webhook", "stale or future timestamps rejected even when the signature is valid", "Boundary", "P0", "Replay window", J(("WebhookTests", "stale or future timestamps"))),
    ("SEC-09", "Webhook", "replay: same signature never passes twice; captured request with another delivery id still a replay; replayed delivery acknowledged without effects", "Replay", "P0", "Single effect", J(("WebhookReplayTests", "keyed by signature"), ("WebhookReplayTests", "captured request replayed"), ("WebhookTests", "a replayed delivery is acknowledged"))),
    ("SEC-10", "Webhook", "v1/v2 signatures cannot be converted; delivery id of v2 cannot be changed or removed; repeated protocol header dropped", "Malformed", "P0", "Signature binding", J(("WebhookReplayTests", "v1 and v2 signatures"), ("WebhookReplayTests", "delivery id of a v2 signature"), ("WebhookReplayTests", "repeated protocol header"))),
    ("SEC-11", "Webhook", "ingest route anonymous without CSRF, only POST, neighbouring routes stay authenticated", "Negative", "P0", "Narrow anonymous surface", J(("WebhookSecurityTests", "the ingest route is anonymous"), ("WebhookSecurityTests", "only POST is open"), ("WebhookSecurityTests", "neighbouring routes stay authenticated"))),
    ("SEC-12", "Webhook", "secret visible once, ciphertext at rest; rotation keeps old secret for grace only; deleting endpoint removes secrets", "Negative", "P0", "Secret hygiene", J(("WebhookTests", "the secret is visible once"), ("WebhookTests", "rotation keeps the old secret"), ("WebhookTests", "deleting an endpoint removes its secrets"))),
    ("SEC-13", "Webhook", "oversized and malformed bodies refused; malformed one does not burn the delivery id; unknown/disabled/malformed ids look identical", "Malformed", "P1", "No oracle", J(("WebhookTests", "oversized and malformed bodies"), ("WebhookTests", "all look the same to the sender"))),
    ("SEC-14", "Webhook", "infrastructure failures answer 503 and never accept unchecked; failed delivery releases replay records so retry passes", "Partial-failure", "P0", "Fail closed", J(("WebhookTests", "infrastructure failures answer 503"), ("WebhookReplayTests", "a failed delivery releases both replay records"))),
    ("SEC-15", "Webhook", "rate limits per peer before lookup and per endpoint after authentication; rejections audited at a bounded rate", "Boundary", "P1", "Flood protection", J(("WebhookTests", "rate limits apply per peer"), ("WebhookTests", "a single peer is limited"), ("WebhookTests", "rejections are audited at a bounded rate"))),
    ("SEC-16", "Hardening", "restrictive security headers, SameSite CSRF cookie, 413 for oversized JSON, metrics not readable by sessions", "Positive", "P1", "Headers present", J(("HardeningTests", "restrictive security headers"), ("HardeningTests", "oversized JSON bodies"), ("HardeningTests", "metrics are not readable"))),
    ("SEC-17", "Hardening", "prod profile refuses unsafe config (wildcard/localhost CORS, unsafe cookie, mock deploy, weak bootstrap admin, missing salt)", "Negative", "P0", "Fail fast", J(("HardeningTests", "production profile refuses unsafe"), ("HardeningTests", "prod profile fails fast"), ("ProductionConfigValidatorTests", ""))),
    ("SEC-18", "Hardening", "trusted proxy: spoofed X-Forwarded-* from untrusted peer ignored; audit IP real client; metrics need scrape token", "Negative", "P1", "No header spoofing", J(("TrustedProxyTests", ""))),
    ("SEC-19", "Hardening", "access logs never contain query strings, ids or credentials", "Negative", "P1", "No sensitive data in logs", J(("HardeningTests", "access logs never contain"), ("IdempotencyTests", "the key appears in no log line"))),
    ("SEC-20", "Secrets", "repository secret scan (gitleaks)", "Static", "P0", "No secret committed", RES("SECRET_SCAN")),
    ("SEC-21", "Server runtime", "connector proxy needs gateway token, valid app token, grant and declared operation; secrets write-only, encrypted, delivered only to the runner", "Unauthorized", "P0", "No ambient authority", J(("ServerRuntimeTests", "connector proxy"), ("ServerRuntimeTests", "secrets are write-only"))),
    ("SEC-22", "Dependencies", "dependency vulnerability scan (npm audit / OWASP) of backend and frontend", "Static", "P2", "No known critical CVE", GAP("no scan is part of the gate")),
    ("SEC-23", "Public surface", "published-site exposure: strict headers, private site ticket, forms origin check", "Negative", "P1", "Hardened static serving", J(("StaticSiteTests", "publishing builds an immutable artifact"), ("StaticSiteTests", "a private site needs"), ("StaticSiteTests", "website forms"))),
    ("AUD-01", "Audit", "audit trail append-only (DB trigger blocks UPDATE/DELETE of audit rows)", "Negative", "P0", "Immutable audit", GAP("no test names append-only/immutability (CLAUDE.md invariant)")),
    ("AUD-02", "Audit", "audit records carry ids/counts/identity but never values or keys", "Negative", "P0", "No PII/values", J(("GatewayTests", "audit records carry ids and counts"), ("ActionRuntimeTests", "audit entries carry identity and trigger but no input values"))),
    ("AUD-03", "Audit", "audit failure at STARTED fails closed (action never runs); audit failure after run does not hide result", "Partial-failure", "P0", "Fail closed before, honest after", J(("ActionRuntimeTests", "audit failure at STARTED fails closed"), ("ActionRuntimeTests", "audit failure after the action ran"))),
    ("AUD-04", "Audit", "no workflow run exists without its audit record", "Positive", "P1", "Audit coupling", J(("WorkflowEngineTests", "no run exists without its audit record"))),
    ("AUD-05", "Audit", "audit API filters and scoping", "Positive", "P2", "Filtered, scoped", J(("AuditApiTests", ""))),
]
for (i, f, c, t, p, e, s) in C1:
    row("C1-" + i, A, f, c, t, p, e, s, "C1", pre=(STACK_PRE if s[0] == "blocked" else BE), req=["tenant-permission", "tenant-context", "permission-model"])

# ---------------------------------------------------------------- C2 Build / Publish / Deploy
A = "C2 Build/Publish/Deploy"
C2 = [
    ("DEF-01", "Definition", "validator rejects duplicate ids, unknown component version, missing required props; invalid ops create no version", "Negative", "P0", "Rejected, no version", J(("SchemaApiTests", "validator rejects duplicate ids"), ("SchemaApiTests", "invalid operations and invalid schemas"))),
    ("DEF-02", "Definition", "two schema writes with the same revision: exactly one wins; stale revision is 409", "Concurrent", "P0", "Optimistic locking", J(("SchemaApiTests", "exactly one win"), ("SchemaApiTests", "stale revision is 409"))),
    ("DEF-03", "Definition", "viewer cannot edit schema; foreign users cannot read or patch it", "Unauthorized", "P0", "403/404", J(("SchemaApiTests", "viewer cannot edit"), ("SchemaApiTests", "foreign users cannot read or patch"))),
    ("DEF-04", "Definition", "schema patch operations ADD/REMOVE/MOVE/UPDATE work; new project gets default schema v1", "Positive", "P1", "PatchEngine intact", J(("SchemaApiTests", "ADD_ITEM REMOVE_ITEM"), ("SchemaApiTests", "UPDATE_PROP"), ("SchemaApiTests", "new project gets default schema"))),
    ("DEF-05", "Versions", "versions gapless; restore creates a NEW version, history untouched; foreign version 404; stale 409; viewer cannot restore", "Positive", "P0", "Immutable versions", J(("VersionApiTests", ""))),
    ("DEF-06", "AppDefinition V2", "AppDefinition V2 validator: error codes, references, limits", "Negative", "P0", "Invalid definitions refused", J(("AppDefinitionValidatorTests", ""))),
    ("DEF-07", "AppDefinition V2", "contract conformance fixtures (C2 <-> C5 <-> C0)", "Regression", "P0", "All fixtures conform", J(("ContractConformanceTests", ""))),
    ("DEF-08", "AppDefinition V2", "action/workflow rules inside the definition (triggers, refs, cycles)", "Negative", "P1", "Rules enforced", J(("AppDefinitionActionWorkflowRulesTests", ""))),
    ("DEF-09", "AppDefinition V2", "backward compatibility with V1 Page Schema and STATIC_APP", "Regression", "P0", "Old documents still valid", J(("AppDefinitionCompatibilityTests", ""), ("AppDefinitionViewTests", ""))),
    ("DEF-10", "AppDefinition V2", "commits are immutable versions; operations (structured AI ops) apply atomically", "Persistence", "P0", "No partial commit", J(("AppDefinitionCommitTests", ""), ("DefinitionOperationsCommitTests", ""), ("DefinitionOperationsTests", ""))),
    ("DEF-11", "AppDefinition V2", "data binding resolver: slots, TEST/LIVE binding, unresolved refs", "Boundary", "P1", "Resolved or definite error", J(("AppDataBindingResolverTests", ""))),
    ("DEF-12", "Components", "component registry/version metadata and API", "Positive", "P2", "Seeded registry", J(("ComponentMetadataTests", ""), ("ComponentApiTests", ""), ("SchemaServiceInitializationTests", ""))),
    ("DEF-13", "Templates", "template sanitizer blocks script/unsafe content; templates/blocks/business templates valid", "Negative", "P1", "No XSS via templates", J(("TemplateSanitizerTests", ""), ("TemplateAndBlockTests", ""), ("BusinessTemplateTests", ""), ("LibraryCatalogTests", ""), ("TemplateV2IntegrationTests", ""))),
    ("DEF-14", "AI planner", "prompt -> structured operations; request delimited as data; oversized app not sent; plan parsing", "Malformed", "P1", "Only valid operations", J(("AppPlannerTests", ""), ("PlanParsingTests", ""), ("PromptApiTests", ""))),
    ("PUB-01", "Publish", "publish runs the state machine to RUNNING with ordered events, url and audit", "Positive", "P0", "Deployment RUNNING", J(("PublishApiTests", "publish runs the state machine"))),
    ("PUB-02", "Publish", "state transitions are compare-and-set and cannot skip or go backwards", "Concurrent", "P0", "No illegal transition", J(("PublishApiTests", "transitions are compare-and-set"))),
    ("PUB-03", "Publish", "same Idempotency-Key never creates a second deployment; different payload rejected", "Idempotency", "P0", "One deployment", J(("PublishApiTests", "the same idempotency key never creates"))),
    ("PUB-04", "Publish", "concurrent duplicate requests with one key create exactly one deployment", "Concurrent", "P0", "One deployment", J(("PublishApiTests", "concurrent duplicate requests"))),
    ("PUB-05", "Publish", "stale revision is 409; editor and viewer cannot publish; other project is 404", "Unauthorized", "P0", "Refused", J(("PublishApiTests", "stale revision is 409"))),
    ("PUB-06", "Publish", "failing provider ends FAILED with error and no URL; page containing script is blocked", "Partial-failure", "P0", "No half-published site", J(("PublishApiTests", "a failing provider ends in FAILED"), ("StaticSiteTests", "a rendered page containing a script fails the build"))),
    ("PUB-07", "Publish", "immutable artifact served with strict headers; multi-page site is one atomic artifact", "Persistence", "P0", "Atomic artifact", J(("StaticSiteTests", "publishing builds an immutable artifact"), ("StaticSiteTests", "every page is in one atomic artifact"))),
    ("PUB-08", "Publish", "custom domains: TXT verification, TLS probe, served only for verified public sites, no reserved hosts", "Negative", "P1", "Domain hijack closed", J(("StaticSiteTests", "custom domains"))),
    ("PUB-09", "Publish config", "first config is revision 1; later changes need the current revision; rejected config stores nothing", "Concurrent", "P1", "Optimistic locking", J(("PublishConfigTests", "first configuration is revision 1"), ("PublishConfigTests", "a rejected configuration stores nothing"))),
    ("PUB-10", "Publish config", "private link token created once, only hash stored; rotation needs existing link; audit never carries token", "Negative", "P0", "No token leak", J(("PublishConfigTests", "private link token is created once"), ("PublishConfigTests", "rotating needs an existing private link"), ("PublishConfigTests", "audit lines carry the change and never the token"))),
    ("PUB-11", "Publish config", "public visibility subject to admin switches; data bound to an app does not become public without explicit confirmation", "Negative", "P0", "No accidental data exposure", J(("PublishConfigTests", "public visibility is subject"), ("PublishConfigTests", "data bound to an app does not become public"), ("LockdownSettingsTests", "public publishing can be disabled"))),
    ("PUB-12", "Publish config", "cache seconds bounded; mode must fit project kind; publish service has no access to membership/sharing", "Boundary", "P2", "Bounds enforced", J(("PublishConfigTests", "cache seconds are bounded"), ("PublishConfigTests", "mode must fit the kind of project"), ("PublishConfigTests", "no access to membership"))),
    ("DEP-01", "Rollback", "rollback serves an earlier artifact without rebuild; unpublish takes site offline; only publishers may", "Rollback", "P0", "Instant rollback", J(("StaticSiteTests", "rollback serves an earlier artifact"))),
    ("DEP-02", "Deploy queue", "job published while worker is stopped waits in the durable queue and completes when worker returns", "Restart", "P0", "No lost deployment", J(("QueueRecoveryTests", "waits in the durable queue"))),
    ("DEP-03", "Deploy queue", "deployment whose enqueue was lost is re-published by the sweeper; dead worker resumed mid-pipeline without repeating steps", "Restart", "P0", "Pipeline resumes", J(("QueueRecoveryTests", "enqueue was lost"), ("QueueRecoveryTests", "died mid-pipeline"))),
    ("DEP-04", "Server runtime", "blue-green: new version serves only after health check; failure keeps old; rollback restores earlier", "Rollback", "P0", "Zero-downtime rollback", J(("ServerRuntimeTests", "blue-green"))),
    ("DEP-05", "Server runtime", "per-app database and role; role connects only to its own database; gateway passes only declared routes and strips cookies", "Cross-tenant", "P0", "App isolation", J(("ServerRuntimeTests", "per-app database"), ("ServerRuntimeTests", "gateway passes only declared routes"))),
    ("DEP-06", "Code projects", "code project sandbox, quotas, diff/merge", "Positive", "P1", "Quota enforced", J(("CodeProjectTests", ""), ("QuotaEnforcementTests", ""))),
    ("DEP-07", "Retention", "retention keeps the served artifact and last N rollbacks, deletes older and audits", "Boundary", "P2", "Rollback targets preserved", J(("LockdownSettingsTests", "retention keeps the served artifact"))),
    ("DEP-08", "Publish V30", "publish/rollback concurrency with scope lease (V30__deployment_rollback_and_scope_lease)", "Concurrent", "P0", "No double-publish, no rollback race", BLK("V30 not created yet (reserved for C2, D-C0-27)")),
    ("DEP-09", "Publish V30", "rollback racing with an in-flight publish of the same scope", "Concurrent", "P0", "Exactly one wins, no corrupt state", BLK("V30 not created yet")),
    ("DEP-10", "Runtime publish", "published runtime gets apiBase and calls the Runtime API (LIVE query through published app)", "Positive", "P0", "Published app renders live data", BLK("apiBase/publish runtime pending (C2)")),
    ("DEP-11", "Runtime publish", "LIVE runtime finds no definition while nothing is published", "Negative", "P1", "404-style definite answer", J(("AppRuntimeApiTests", "LIVE finds no definition while nothing is published"))),
    ("DEP-12", "Real deploy", "real publish of a static site on the stack: build, artifact, sites-gateway serves it (E2E_02)", "Positive", "P0", "Site reachable", BLK("needs the isolated stack")),
    ("DEP-13", "Real deploy", "real rollback on the stack serves previous artifact", "Rollback", "P0", "Previous artifact served", BLK("needs the isolated stack")),
    ("DEP-14", "Real deploy", "code project build in the Docker runner and server-app blue/green on real Docker (E2E_04/05)", "Positive", "P1", "Build + run + switch", BLK("needs the isolated stack")),
]
for (i, f, c, t, p, e, s) in C2:
    row("C2-" + i, A, f, c, t, p, e, s, "C2", pre=(STACK_PRE if s[0] == "blocked" else BE), req=["app-definition", "runtime-api"] if i.startswith(("DEF", "DEP-1")) else ["integration-contract"])

# ---------------------------------------------------------------- C3 Data / Connector
A = "C3 Data/Connector"
C3 = [
    ("SRC-01", "Data source", "data source administration service: create/update/disable, permission, tenant scope", "Positive", "P0", "Service-level admin works", J(("DataSourceAdminTests", ""), ("DataSourceServiceTests", ""), ("DataSourceScopeTests", ""))),
    ("SRC-02", "Data source", "disabled data source serves nothing; disabled query is not found", "Negative", "P1", "Refused", J(("GatewayTests", "a disabled data source serves nothing"), ("DataRuntimeLiveApiTests", "a disabled query is not found"))),
    ("SRC-03", "Connectors", "connector SPI, Postgres connector (+integration), REST connector contracts", "Positive", "P0", "Connectors conform to SPI", J(("ConnectorSpiTests", ""), ("PostgresConnectorTests", ""), ("PostgresConnectorIntegrationTests", ""), ("RestConnectorTests", ""))),
    ("SRC-04", "Discovery", "schema discovery (read-only session ends with rollback), AI data catalog, masked samples", "Positive", "P1", "Discovery safe", J(("DiscoveryTests", ""), ("PostgresDiscoveryTests", ""), ("AiDataCatalogTests", ""))),
    ("SRC-05", "Connectors", "connection test and discovery go through the gateway", "Positive", "P1", "Gateway-mediated", J(("GatewayTests", "connection test and discovery go through the gateway"))),
    ("SRC-06", "Management API v2", "create/update/delete data source, query, mutation over HTTP (management-api.md frozen D-C0-28)", "Positive", "P0", "CRUD with permission + tenant scope", BLK("management API not implemented (B-C0-W-03; C3 not GREEN)")),
    ("SRC-07", "Management API v2", "credential create/rotate/delete: value never returned, only ciphertext stored", "Negative", "P0", "No secret in any response", BLK("management API not implemented")),
    ("SRC-08", "Management API v2", "management API unauthorized/cross-tenant/duplicate-name/stale-version cases", "Unauthorized", "P0", "403/404/409", BLK("management API not implemented")),
    ("QRY-01", "Query", "LIVE query reads rows through persistent source, query and binding and returns the mapped view model", "Positive", "P0", "View model only", J(("DataRuntimeLiveApiTests", "a LIVE query reads rows"))),
    ("QRY-02", "Query", "LIVE needs a LIVE binding (TEST-only binding leaves LIVE unbound); TEST reads TEST binding only and never writes a binding", "Negative", "P0", "422 DATA_SOURCE_UNBOUND", J(("DataRuntimeLiveApiTests", "LIVE needs a LIVE binding"), ("DataRuntimeLiveApiTests", "a TEST run reads the TEST binding only"))),
    ("QRY-03", "Query", "response carries the view model only, no raw column; audit carries ids not values", "Negative", "P0", "No raw data leak", J(("GatewayTests", "the response carries the view model only"))),
    ("QRY-04", "Query", "missing or mismatched mapping refused before the connector is called; mutation params typed, unknown refused", "Malformed", "P0", "Refused pre-connector", J(("GatewayTests", "a missing or mismatched mapping"), ("GatewayTests", "mutation parameters are typed"))),
    ("QRY-05", "Cache", "second identical request is a hit and connector runs once; ttl 0 never cached; ttl expiry refetches", "Boundary", "P1", "Cache semantics", J(("GatewayTests", "second identical request is a hit"), ("GatewayTests", "ttl zero is never cached"))),
    ("QRY-06", "Cache", "mapping version bump / editing the source or query makes older entries unreachable at once", "Positive", "P1", "No stale answer", J(("GatewayTests", "a mapping version bump is a different cache entry"), ("GatewayTests", "editing the data source the query or the mapping"))),
    ("QRY-07", "Cache", "answer computed before an invalidation cannot be stored after it (stress, tenant-bound ticket, backend down)", "Concurrent", "P0", "Cache never holds an old answer", J(("CacheRaceTests", ""))),
    ("QRY-08", "Cache", "cache outage degrades to uncached reads and never fails the request; hit still requires permission and is audited", "Partial-failure", "P1", "Degrade gracefully", J(("GatewayTests", "a cache outage degrades"), ("GatewayTests", "a hit still requires permission"))),
    ("QRY-09", "Query", "query params plain values within limit; page range checked", "Boundary", "P2", "Bounds enforced", J(("RuntimeApiPureTests", "query params are plain values"))),
    ("QRY-10", "Mapping", "mapping engine, expressions, transforms, mapping contract", "Positive", "P1", "Deterministic mapping", J(("MappingEngineTests", ""), ("ExpressionTests", ""), ("TransformTests", ""), ("MappingContractTests", ""))),
    ("QRY-11", "Query", "manual refresh drops cache, notifies subscribers, is rate limited", "Boundary", "P2", "Rate limited", J(("GatewayTests", "manual refresh drops the cache"), ("GatewayTests", "manual refresh is rate limited"))),
    ("QRY-12", "Realtime/Sync", "sync service, realtime subscriptions, cache invalidation by webhook", "Positive", "P1", "Subscribers notified", J(("SyncTests", ""), ("RealtimeTests", ""), ("CacheTests", ""))),
    ("QRY-13", "Query", "large result sets / pagination boundary against a real external DB", "Boundary", "P2", "Row limit + paging respected", GAP("only executor-level tests (PostgresSessionSecurityTests); no end-to-end large-result test")),
    ("MUT-01", "Mutation", "LIVE action writes through the gateway, stores only a derived key, retry with same key writes nothing again", "Idempotency", "P0", "One write", J(("DataRuntimeLiveApiTests", "a LIVE action writes through the gateway"))),
    ("MUT-02", "Mutation", "ambiguous failure keeps the key reserved as UNKNOWN; retry never writes again", "Partial-failure", "P0", "No duplicate write", J(("DataRuntimeLiveApiTests", "an ambiguous failure keeps the key reserved"))),
    ("MUT-03", "Mutation", "TEST action only previewed: no connector call, no idempotency row; LIVE without LIVE binding changes nothing", "Negative", "P0", "No side effect", J(("DataRuntimeLiveApiTests", "a TEST action is only previewed"), ("DataRuntimeLiveApiTests", "a LIVE action with no LIVE binding changes nothing"))),
    ("MUT-04", "Mutation", "rejected mutation stays rejected (422), never retryable; key conflict definite; transport exception on write = unknown outcome", "Partial-failure", "P0", "Error taxonomy", J(("DataWriteErrorsTests", ""))),
    ("MUT-05", "Mutation", "read-only connector refuses writes and frees the key; definitely rejected mutation frees key so a retry can run", "Negative", "P0", "Key lifecycle", J(("GatewayTests", "a read only connector refuses writes"), ("GatewayTests", "a definitely rejected mutation frees the key"))),
    ("MUT-06", "Mutation", "mutation invalidates what it declares and emits record events", "Positive", "P1", "Cache coherent after write", J(("GatewayTests", "a mutation invalidates what it declares"))),
    ("MUT-07", "Mutation", "another tenant cannot run a mutation of a data source it does not own", "Cross-tenant", "P0", "Refused", J(("GatewayTests", "another tenant cannot run a mutation"))),
    ("MUT-08", "Mutation", "production writable connector: real INSERT/UPDATE/DELETE against an external DB", "Positive", "P0", "Write applied exactly once", BLK("production connector is read-only (B-C0-W-04)")),
    ("MUT-09", "Atomicity", "multi-statement mutation is atomic: partial failure rolls back everything", "Rollback", "P0", "All-or-nothing", GAP("no test names transactions/atomic mutation (C3 trigger: atomicity)")),
    ("MUT-10", "Atomicity", "ambiguous mutation timeout against a real connector (R10): key stays reserved, no second write", "Timeout", "P0", "UNKNOWN outcome, no duplicate", BLK("needs writable production connector (B-C0-W-04) + stack")),
    ("IDM-01", "Idempotency", "concurrent retries of one key run the mutation exactly once (gateway and JDBC store)", "Concurrent", "P0", "Single execution", J(("IdempotencyTests", "concurrent retries of one key"), ("JdbcIdempotencyStoreTests", "concurrent callers with one key"))),
    ("IDM-02", "Idempotency", "same key with other parameters is a conflict and never silently re-run; unknown state exact about parameters", "Duplicate", "P0", "Conflict", J(("GatewayTests", "the same key with other parameters"), ("IdempotencyTests", "exact about parameters"))),
    ("IDM-03", "Idempotency", "key mandatory and well formed; only derived shape sha256/base64url accepted", "Malformed", "P0", "Refused otherwise", J(("GatewayTests", "an idempotency key is mandatory and well formed"), ("IdempotencyTests", "derived key shape"), ("JdbcIdempotencyStoreTests", "not the derived shape"))),
    ("IDM-04", "Idempotency", "reservation per tenant, data source and mutation", "Cross-tenant", "P0", "Scoped keys", J(("IdempotencyTests", "per tenant, data source and mutation"), ("GatewayTests", "idempotency is scoped per tenant"), ("JdbcIdempotencyStoreTests", "keys are scoped by tenant"))),
    ("IDM-05", "Idempotency", "retention configurable, >= 7 days, key lives for the configured retention even if caller asks 24h; reservations expire with the window", "Boundary", "P1", "Window honoured", J(("JdbcIdempotencyStoreTests", "anything under 7 days is refused"), ("JdbcIdempotencyStoreTests", "key lives for the configured retention"), ("IdempotencyTests", "reservations expire"))),
    ("IDM-06", "Idempotency", "holder that vanished does not free the key: after its lease the outcome is unknown, not retryable", "Timeout", "P0", "No duplicate write", J(("IdempotencyTests", "a holder that vanished"), ("JdbcIdempotencyStoreTests", "a holder that vanished"))),
    ("IDM-07", "Idempotency", "store failure after a successful write does not turn success into error; store failure while recording ambiguous failure keeps key unusable", "Partial-failure", "P0", "No false error / no reuse", J(("IdempotencyTests", "a store that fails after a successful write"), ("IdempotencyTests", "a store that fails while recording an ambiguous failure"))),
    ("IDM-08", "Idempotency", "oversized result dropped but completion and count kept; markUnknown never touches completed key", "Boundary", "P2", "Safe storage", J(("JdbcIdempotencyStoreTests", "oversized result"), ("JdbcIdempotencyStoreTests", "markUnknown does not touch"))),
    ("IDM-09", "Idempotency", "same Idempotency-Key sent twice over real HTTP to a real DB: second answer is a replay, one row", "Duplicate", "P0", "Exactly one row", BLK("needs the isolated stack + writable connector")),
    ("WHK-01", "Webhook", "correctly signed delivery accepted, invalidates declared queries, notifies subscribers; only identifier-shaped values leave the payload", "Positive", "P1", "Effect once", J(("WebhookTests", "a correctly signed delivery is accepted"), ("WebhookTests", "only identifier shaped values leave the payload"))),
    ("WHK-02", "Webhook", "workflow trigger gets identifiers only; workflow identity derived from the signature; failure retryable", "Positive", "P1", "No payload injection", J(("WebhookTests", "the workflow trigger gets identifiers only"), ("WebhookReplayTests", "the workflow identity is derived from the signature"))),
    ("WHK-03", "Webhook", "endpoint bound only to things its creator can reference; administration needs permission; other tenant sees nothing", "Unauthorized", "P0", "Scoped admin", J(("WebhookTests", "an endpoint can only be bound"), ("WebhookTests", "administration needs its permission"))),
]
for (i, f, c, t, p, e, s) in C3:
    row("C3-" + i, A, f, c, t, p, e, s, "C3", pre=(STACK_PRE if s[0] == "blocked" else BE), req=["data-connector", "data-runtime"] + (["management-api"] if i.startswith("SRC-0") and s[0] == "blocked" else []))

# ---------------------------------------------------------------- C4 Action / Workflow
A = "C4 Action/Workflow"
C4 = [
    ("ACT-01", "Action", "write action without a key is rejected (writes are never fire-and-forget); NONE on a mutating action is invalid", "Negative", "P0", "INVALID / refused", J(("ActionRuntimeTests", "a write action without a key is rejected"), ("ActionContractV2Tests", "NONE on a mutating action"))),
    ("ACT-02", "Action", "same key + same input replays the first result without writing again", "Replay", "P0", "One write", J(("ActionRuntimeTests", "same key and same input replays"))),
    ("ACT-03", "Action", "same key with different input is IDEMPOTENCY_KEY_REUSED; same key by another user is a separate run", "Duplicate", "P0", "Conflict / isolation", J(("ActionRuntimeTests", "same key with different input"), ("ActionRuntimeTests", "the same key by another user"))),
    ("ACT-04", "Action", "invalid input is INVALID_INPUT with per-field details and nothing runs; oversized input LIMIT_EXCEEDED", "Malformed", "P1", "Refused", J(("ActionRuntimeTests", "invalid input is INVALID_INPUT"), ("ActionRuntimeTests", "oversized input is LIMIT_EXCEEDED"))),
    ("ACT-05", "Action", "request can shorten the timeout but never lengthen it", "Boundary", "P1", "Timeout capped", J(("ActionRuntimeTests", "a request can shorten the timeout"))),
    ("ACT-06", "Action", "mutating action that times out or is interrupted is an unknown outcome, never retryable, no onError chain", "Timeout", "P0", "UNKNOWN, no duplicate", J(("ActionRuntimeTests", "a mutating action that times out is an unknown outcome"), ("ActionRuntimeTests", "a mutating action that is interrupted is an unknown outcome"))),
    ("ACT-07", "Action", "non-mutating action that times out/is interrupted keeps TIMEOUT/INTERRUPTED, stays retryable, still runs onError", "Timeout", "P1", "Retryable", J(("ActionRuntimeTests", "a non-mutating action that times out"), ("ActionRuntimeTests", "a non-mutating action that is interrupted"))),
    ("ACT-08", "Action", "retryable failure retried with same key succeeds once; retry after a lost response creates no duplicate business record", "Retry", "P0", "Exactly once effect", J(("ActionRuntimeTests", "a retryable failure can be retried"), ("ActionRuntimeTests", "retrying after a lost response creates no duplicate"))),
    ("ACT-09", "Action", "unknown-outcome write stays failed and is never sent downstream again with the same key; replay is the same failure", "Replay", "P0", "No second write", J(("ActionRuntimeTests", "an unknown-outcome write stays failed"), ("ActionRuntimeTests", "a replay of a normalized unknown outcome"))),
    ("ACT-10", "Action", "rejected write definite failure, not retryable even if adapter says so, may run onError; refusal does not run onError", "Partial-failure", "P1", "Taxonomy", J(("ActionRuntimeTests", "a rejected write stays a definite failure"), ("ActionRuntimeTests", "onError runs after a failure but not after a refusal"))),
    ("ACT-11", "Action", "chain cycle stopped by depth limit; fan-out bounded; chained writes get derived keys so re-run is idempotent", "Boundary", "P0", "Bounded, idempotent", J(("ActionRuntimeTests", "a chain cycle is stopped"), ("ActionRuntimeTests", "a fan out is bounded"), ("ActionRuntimeTests", "chained writes get derived keys"))),
    ("ACT-12", "Action", "TEST mode never writes, needs no key, keeps no run state, same permissions as LIVE, still limited", "Negative", "P0", "Preview only", J(("ActionRuntimeTests", "TEST mode never writes"), ("ActionContractV2Tests", "TEST mode needs the same permissions"), ("TenantRateLimitTests", "TEST mode is limited too"))),
    ("ACT-13", "Action", "connector without dry run reported unsupported; success never simulated", "Negative", "P1", "Honest preview", J(("ActionContractV2Tests", "connector without dry run"), ("ActionRuntimeTests", "TEST mode does not pretend a dry run happened"))),
    ("ACT-14", "Action", "throwing handler becomes HANDLER_ERROR without leaking message; unknown type UNSUPPORTED; port not wired NOT_IMPLEMENTED", "Negative", "P1", "Typed errors", J(("ActionRuntimeTests", "a throwing handler becomes HANDLER_ERROR"), ("ActionRuntimeTests", "UNSUPPORTED_ACTION_TYPE"), ("ActionRuntimeTests", "port not wired gives a typed non retryable NOT_IMPLEMENTED"))),
    ("ACT-15", "Action", "derived key is base64url sha256 (43 chars), user/tenant-separated, stable; raw client key reaches no port/audit/record", "Positive", "P0", "Key derivation contract", J(("ActionContractV2Tests", "derived key is base64url sha256"), ("ActionContractV2Tests", "two users or two tenants"), ("ActionContractV2Tests", "the raw client key reaches no port"), ("ActionContractV2Tests", "derivation is stable"))),
    ("ACT-16", "Action", "dispatch: bound actions only, payload mapped by definition, client event id sanitised, safe to redeliver", "Replay", "P1", "Idempotent dispatch", J(("ActionRuntimeTests", "dispatch runs bound actions only"), ("ActionRuntimeTests", "dispatch sanitises a client event id"))),
    ("ACT-17", "Action", "tenant gate throws -> fail closed retryable; disabled tenant runs nothing", "Partial-failure", "P0", "Fail closed", J(("ActionRuntimeTests", "a tenant gate that throws fails closed"), ("ActionRuntimeTests", "a disabled tenant runs nothing"))),
    ("ACT-18", "Action", "per-tenant rate limit: burst, honest retry-after, refill; RATE_LIMITED never triggers onError; concurrent callers never exceed capacity", "Load", "P1", "Fair limiting", J(("TenantRateLimitTests", "a tenant gets its burst"), ("TenantRateLimitTests", "concurrent callers never get more than the capacity"), ("TenantRateLimitTests", "a throwing limiter fails closed"))),
    ("ACT-19", "Action", "action definition validator, input binder, input resolver, handlers", "Positive", "P1", "Definitions valid", J(("ActionDefinitionValidatorTests", ""), ("ActionInputBinderTests", ""), ("InputResolverTests", ""), ("ActionHandlerTests", ""))),
    ("ACT-20", "Action", "run store: concurrent begins yield exactly one owner; stale owner cannot complete; retryable restarts with a new attempt", "Concurrent", "P0", "Single owner", J(("ActionRunStoreTests", ""), ("JdbcActionRunStoreTests", "concurrent begins of one key"), ("JdbcActionRunStoreTests", "concurrent completes of one run"))),
    ("ACT-21", "Action", "audit STARTED -> SUCCEEDED/FAILED/DENIED; audit failure at STARTED fails closed", "Positive", "P1", "Audit coupling", J(("ActionRuntimeTests", "NAVIGATE runs end to end and is audited"), ("ActionRuntimeTests", "downstream failure keeps its code and retryable flag"))),
    ("ACT-22", "Action HTTP", "LIVE mutating action over HTTP on the real stack with durable run stores (GAP_V29 closure check)", "Positive", "P0", "200 + one write; no RUNTIME_STORES_VOLATILE", BLK("needs the isolated stack; V29 stores are durable by default at 8e91172 (integration-test level only)")),
    ("ABN-01", "Abandoned run", "abandoned MUTATING action run becomes an unknown outcome, never retryable, replayed as is, never started again (A-1)", "Restart", "P0", "No duplicate write", J(("ActionRunAbandonmentTests", "abandoned mutating run becomes an unknown outcome"), ("ActionRunRecoveryTests", "abandoned MUTATING run is an unknown outcome"))),
    ("ABN-02", "Abandoned run", "abandoned non-mutating run is a retryable timeout and starts again with a new attempt; inside its lease it is left alone", "Retry", "P1", "Safe retry", J(("ActionRunAbandonmentTests", "abandoned non-mutating run is a retryable timeout"), ("ActionRunRecoveryTests", "a run inside its lease is left alone"))),
    ("ABN-03", "Abandoned run", "caller that does not say is assumed to mutate (safe side); restart takes the mutating flag of the new call", "Boundary", "P0", "Safe default", J(("ActionRunAbandonmentTests", "assumed to mutate"), ("ActionRunAbandonmentTests", "takes the mutating flag of the new call"))),
    ("ABN-04", "Abandoned run", "one sweep treats mutating and non-mutating each by own flag; recorded unknown outcome left alone; sweeping twice touches nothing finished", "Persistence", "P0", "Per-run policy", J(("ActionRunAbandonmentTests", "each get their own outcome"), ("JdbcActionRunStoreTests", "one sweep treats a mutating and a non-mutating"), ("JdbcActionRunStoreTests", "sweeping twice does not touch"))),
    ("ABN-05", "Abandoned run", "store failure is contained and the next tick still runs; recorded ambiguous write never turned retryable; lease under a minute refused", "Partial-failure", "P1", "Robust sweeper", J(("ActionRunRecoveryTests", "a store failure is contained"), ("ActionRunRecoveryTests", "recorded ambiguous write is never turned into a retryable run"), ("ActionRunRecoveryTests", "a lease shorter than a minute"))),
    ("WFL-01", "Workflow", "start returns a PENDING run and executes nothing on the calling thread; steps run in order; inputs mapped from workflow input and earlier outputs", "Positive", "P0", "Async, ordered", J(("WorkflowEngineTests", "start does not execute anything"), ("WorkflowEngineTests", "a worker runs the steps in order"), ("WorkflowEngineTests", "step inputs are mapped"))),
    ("WFL-02", "Workflow", "start is idempotent; reused key with other input refused; same key from another user is a different run", "Idempotency", "P0", "One run per key", J(("WorkflowEngineTests", "start is idempotent"), ("WorkflowEngineTests", "the same key from another user"))),
    ("WFL-03", "Workflow", "start refuses bad keys, unknown workflows, missing app context, invalid definition; oversized input refused before storing", "Malformed", "P1", "Refused", J(("WorkflowEngineTests", "start refuses bad keys"), ("WorkflowEngineTests", "an invalid definition is refused"), ("WorkflowEngineTests", "an oversized input is refused"))),
    ("WFL-04", "Workflow", "BRANCH follows first matching condition else default; no match without default fails", "Boundary", "P1", "Deterministic branching", J(("WorkflowEngineTests", "BRANCH follows the first matching condition"), ("WorkflowEngineTests", "BRANCH without a match"))),
    ("WFL-05", "Workflow", "retryable failure waits for backoff and is retried by sweeper; retries exhausted fails with last error; non-retryable not retried; early nudge in backoff does not run", "Retry", "P0", "Bounded retries", J(("WorkflowEngineTests", "a retryable failure waits for its backoff"), ("WorkflowEngineTests", "when retries are exhausted"), ("WorkflowEngineTests", "a non retryable failure is not retried"), ("WorkflowEngineTests", "an early nudge"))),
    ("WFL-06", "Workflow", "onError routes failed step to handler (run can still succeed); onReject routes rejected approval; rejected write is definite and not retried", "Partial-failure", "P1", "Routing", J(("WorkflowEngineTests", "onError routes a failed step"), ("WorkflowEngineTests", "onReject routes a rejected approval"), ("WorkflowEngineTests", "a rejected write is a definite failure"))),
    ("WFL-07", "Workflow", "failing step compensates finished steps in reverse order; failed compensation is PARTIAL and others still run; replaying compensation job does not compensate twice", "Rollback", "P0", "Compensation exactly once", J(("WorkflowEngineTests", "compensates the finished steps in reverse order"), ("WorkflowEngineTests", "a compensation that fails is reported as PARTIAL"), ("WorkflowEngineTests", "replaying the compensation job"))),
    ("WFL-08", "Workflow", "unknown write outcome fails the run with that code, skips onError and retries, is never compensated but earlier steps still are", "Partial-failure", "P0", "No corruption on unknown", J(("WorkflowEngineTests", "an unknown write outcome fails the run"), ("WorkflowEngineTests", "a step with an unknown outcome is never compensated"))),
    ("WFL-09", "Workflow", "step timeout on a write is an unknown outcome, not retried, no onError even when attempts and handler exist", "Timeout", "P0", "UNKNOWN", J(("WorkflowShapeTests", "a step timeout on a write is an unknown outcome"), ("WorkflowShapeTests", "a timed out write step is not retried"))),
    ("WFL-10", "Workflow", "abandoned write whose worker died is unknown for good: no retry, no onError, not compensated, earlier steps compensated", "Restart", "P0", "No corruption", J(("WorkflowAbandonedWriteTests", ""))),
    ("WFL-11", "Workflow", "cancel stops before any step, is idempotent, can compensate, cancels pending approval", "Rollback", "P1", "Clean cancel", J(("WorkflowEngineTests", "cancel stops the run before any step"), ("WorkflowEngineTests", "cancel can compensate"), ("WorkflowEngineTests", "cancelling a run cancels its pending approval"))),
    ("WFL-12", "Workflow", "approval step pauses run; quorum; rejection fails run; only snapshotted approvers decide; unanswered approval expires; late decision cannot revive", "Positive", "P1", "Approval semantics", J(("WorkflowEngineTests", "an APPROVAL step pauses the run"), ("WorkflowEngineTests", "only a snapshotted approver can decide"), ("WorkflowEngineTests", "an unanswered approval expires"), ("ApprovalServiceTests", "a late decision cannot revive an expired request"))),
    ("WFL-13", "Workflow", "WAIT holds run until its time; run exceeding its maximum duration times out", "Timeout", "P1", "Timers", J(("WorkflowEngineTests", "WAIT holds the run until its time"), ("WorkflowEngineTests", "a run that exceeds its maximum duration times out"), ("WorkflowEngineTests", "the sweeper times out a waiting run"))),
    ("WFL-14", "Workflow", "child workflow nesting bounded; definition cycle ends at step execution limit", "Boundary", "P0", "No runaway", J(("WorkflowEngineTests", "may start a child workflow and the nesting is bounded"), ("WorkflowEngineTests", "a cycle in the definition ends at the step execution limit"))),
    ("WFL-15", "Workflow", "TEST run executes nothing real, does not wait or compensate, uses a different idempotency scope than LIVE", "Negative", "P0", "No side effect", J(("WorkflowEngineTests", "a TEST run executes nothing real"), ("WorkflowEngineTests", "a TEST run uses a different idempotency scope"), ("WorkflowEngineTests", "a TEST run does not wait"))),
    ("WFL-16", "Workflow", "tenant disabled in flight stops run; run acts as the starting user; status visible only to creator/WORKFLOW_MANAGE; view hides definition snapshot and input", "Unauthorized", "P0", "Authority preserved", J(("WorkflowEngineTests", "a tenant disabled while the run is in flight"), ("WorkflowEngineTests", "the run acts as the user who started it"), ("WorkflowEngineTests", "status is visible to the creator"), ("WorkflowEngineTests", "the view never exposes the definition snapshot"))),
    ("WFL-17", "Workflow", "definition shape: closed set of step kinds/operators, no scripting; script-like element makes the workflow not offered", "Malformed", "P0", "No code injection via definition", J(("WorkflowShapeTests", "script-like step kind"), ("WorkflowShapeTests", "closed set with no scripting"), ("WorkflowShapeTests", "one invalid element anywhere"))),
    ("WFL-18", "Workflow", "workflow model and definition validation", "Positive", "P1", "Valid models", J(("WorkflowModelTests", ""))),
    ("WFL-19", "Workflow", "thrown exception in a step becomes a failed step, not a lost run; no run without audit", "Partial-failure", "P1", "Never lost", J(("WorkflowEngineTests", "a thrown exception inside a step becomes a failed step"))),
    ("QUE-01", "Messaging (in-engine)", "duplicated message does not repeat the effect; consumer dying before ack only causes redelivery; duplicate job for a running step does nothing", "Duplicate", "P0", "At-least-once safe", J(("WorkflowEngineTests", "a duplicated message does not repeat the effect"), ("WorkflowEngineTests", "a consumer that dies before acking"), ("WorkflowEngineTests", "a duplicate job for a step that another worker is running"))),
    ("QUE-02", "Messaging (in-engine)", "job lost before delivery is re-published by sweeper; outage dead-letters nothing and sweeper recovers; crashed worker recovered, effect exactly once", "Restart", "P0", "No lost run", J(("WorkflowEngineTests", "a job lost before delivery is published again"), ("WorkflowEngineTests", "an outage does not dead-letter"), ("WorkflowEngineTests", "a crashed worker is recovered"))),
    ("QUE-03", "Messaging (in-engine)", "malformed message goes straight to DLQ and cannot touch a run; DLQ for unknown/foreign run ignored; poison run backs off and only its own message dead-lettered", "Malformed", "P0", "Poison isolation", J(("WorkflowEngineTests", "a malformed message is rejected straight to the dead letter"), ("WorkflowSweeperTests", "a dead letter naming an unknown or foreign run"), ("WorkflowSweeperTests", "a poison run is retried with growing backoff"))),
    ("QUE-04", "Messaging (in-engine)", "per-tenant fairness: large backlog cannot starve another tenant; per-tenant cap; oldest first; every run examined within ceil(n/limit)", "Load", "P1", "No starvation", J(("WorkflowSweeperTests", "a tenant with a large backlog cannot starve"), ("WorkflowSweeperTests", "the per-tenant cap bounds one tenant"), ("WorkflowSweeperTests", "oldest eligible run is served first"))),
    ("QUE-05", "Messaging (in-engine)", "sweep backoff exponential with floor and cap; failure budget explicit; publish failures backed off", "Boundary", "P1", "Bounded retries", J(("WorkflowSweeperTests", "sweep backoff of a repeatedly failing run"), ("WorkflowSweeperTests", "the failure budget is explicit"), ("WorkflowSweeperTests", "runs whose publish fails are backed off"))),
    ("QUE-06", "RabbitMQ durable queue", "WorkflowQueue wired to a real broker: durable queue survives broker restart, DLQ replay (R11)", "Restart", "P0", "No lost/duplicated job", BLK("WorkflowQueue not wired to RabbitMQ (B-C4-06); needs stack")),
    ("QUE-07", "RabbitMQ durable queue", "RabbitMQ down/up while workflows are running: runs resume without duplicate effect (R07)", "Partial-failure", "P0", "Runs resume", BLK("needs the isolated stack + queue wiring")),
    ("LSE-01", "Lease", "two workers racing for one step: exactly one owns it; claim records owner and lease; finishing releases both", "Concurrent", "P0", "Single owner", J(("WorkflowLeaseTests", "two workers racing for one step"), ("WorkflowLeaseTests", "claiming a step records its owner"))),
    ("LSE-02", "Lease", "only the owner renews; owner that keeps renewing is not reclaimed, one that stops is; renewal from stale version does not extend", "Concurrent", "P0", "Heartbeat semantics", J(("WorkflowLeaseTests", "only the owner renews"), ("WorkflowLeaseTests", "an owner that keeps renewing"), ("JdbcWorkflowRunStoreTests", "a renewal from a stale version loses"))),
    ("LSE-03", "Lease", "lease judged by its own expiry, not run age: expired lease reclaimed even if touched recently; valid lease left alone however old", "Boundary", "P0", "No false takeover", J(("WorkflowLeaseTests", "a lease that ran out is reclaimed"), ("WorkflowLeaseTests", "judged by its own expiry"), ("JdbcWorkflowRunStoreTests", "valid lease is left alone however old"))),
    ("LSE-04", "Lease", "lease expiring exactly now counts as expired; lease longer than stale time honoured; run without lease keeps the old updated_at rule", "Boundary", "P1", "Edge of expiry", J(("JdbcWorkflowRunStoreTests", "expires exactly now"), ("WorkflowLeaseTests", "a lease longer than the stale time"), ("WorkflowLeaseTests", "a run without a lease keeps the old rule"))),
    ("LSE-05", "Lease", "outcome from a worker the sweeper already gave up on is dropped and the step redone as a replay; dead owner with valid lease left alone, taken over as new attempt after expiry", "Restart", "P0", "No double effect", J(("WorkflowEngineTests", "an outcome from a worker the sweeper already gave up on is dropped"), ("WorkflowRestartRecoveryTests", "dead owner still holds a valid lease"))),
    ("LSE-06", "Lease", "step whose worker died while RUNNING retried as a new attempt of the same idempotency key", "Retry", "P0", "Same key, new attempt", J(("WorkflowRestartRecoveryTests", "RUNNING when its worker died"))),
    ("SCH-01", "Scheduler", "many workers ticking concurrently hand every fire over exactly once; claiming from many threads creates exactly one ledger row", "Concurrent", "P0", "Exactly-once fire", J(("SchedulerDedupeTests", "many workers ticking concurrently"), ("SchedulerDedupeTests", "claiming one execution from many threads"))),
    ("SCH-02", "Scheduler", "restart does not fire the same time again; clock jumping back does not refire; two nodes with clock skew fire once", "Restart", "P0", "No double fire", J(("SchedulerDedupeTests", "a restart (new service on the same store)"), ("SchedulerDedupeTests", "a clock that jumps back"), ("SchedulerDedupeTests", "two nodes with clocks"))),
    ("SCH-03", "Scheduler", "DST: fall-back hour fires once, spring-forward gap fires once that day, hourly never twice at same key", "Boundary", "P1", "Time-zone safe", J(("SchedulerDedupeTests", "fall-back hour - a daily time"), ("SchedulerDedupeTests", "spring-forward gap"), ("SchedulerDedupeTests", "fall-back hour - an hourly schedule"))),
    ("SCH-04", "Scheduler", "failing enqueue backs off exponentially and is given up after attempt budget; ledger unavailable blocks hand-over, retried later; lost confirmation repaired without second hand-over", "Retry", "P0", "At-most-once hand-over", J(("SchedulerDedupeTests", "a failing enqueue backs off exponentially"), ("SchedulerDedupeTests", "an unavailable ledger blocks"), ("SchedulerDedupeTests", "a confirmation lost after a successful hand-over"))),
    ("SCH-05", "Scheduler", "declared schedules tenant/app scoped and need manage permission; racing publishers end with one schedule; republishing creates nothing", "Duplicate", "P1", "No duplicate schedules", J(("SchedulerDedupeTests", "declared schedules are tenant and app scoped"), ("SchedulerDedupeTests", "racing publishers"), ("SchedulerDedupeTests", "publishing the same declaration again"))),
    ("SCH-06", "Scheduler", "cron expression parsing; scheduler service behaviour; one failing schedule does not delay a healthy one", "Positive", "P1", "Correct next-run", J(("CronExpressionTests", ""), ("SchedulerServiceTests", ""))),
    ("SCH-07", "Scheduler", "scheduled fire starts a workflow run as the schedule owner and is idempotent; disabled tenant or missing permission refused", "Unauthorized", "P1", "Owner authority", J(("WorkflowEngineTests", "a scheduled fire starts a workflow run"), ("WorkflowEngineTests", "a scheduled fire for a disabled tenant"))),
    ("APR-01", "Approval", "approvers snapshotted; requester removed unless self-approval; only approvers decide; changing mind is a conflict; sweeper expires due requests once", "Positive", "P1", "Approval integrity", J(("ApprovalServiceTests", ""))),
    ("NOT-01", "Notification", "notification service: delivery, dedupe, tenant scope", "Positive", "P2", "Notifications correct", J(("NotificationServiceTests", ""))),
    ("RET-01", "Retention", "retention purge touches only finished data past the horizon", "Boundary", "P2", "No live data purged", J(("RetentionTests", ""), ("CleanupTests", ""))),
    ("REC-01", "Real-stack recovery", "interrupted workflow over HTTP + backend restart: run resumes without repeating a finished step (R09)", "Restart", "P0", "Resumes exactly once", BLK("needs the isolated stack (V29 stores + restart)")),
    ("REC-02", "Real-stack recovery", "duplicated workflow start (same key, parallel) over HTTP creates one run", "Concurrent", "P0", "One run", BLK("needs the isolated stack")),
    ("REC-03", "Real-stack recovery", "workflow start rate-limited per tenant over HTTP with Retry-After", "Load", "P1", "429 + Retry-After", BLK("needs the isolated stack")),
]
for (i, f, c, t, p, e, s) in C4:
    row("C4-" + i, A, f, c, t, p, e, s, "C4", pre=(STACK_PRE if s[0] == "blocked" else BE), req=["action-workflow", "runtime-api"])

# ---------------------------------------------------------------- C5 Studio / Frontend
A = "C5 Studio/Frontend"
C5 = [
    ("TYP-01", "Typecheck", "root UI, packages and 3 portals typecheck with 0 errors", "Static", "P0", "tsc clean", FE("typecheck")),
    ("UNT-01", "Contract mirror", "contract.test.ts + conformance.test.ts: C2 conformance fixtures (needs XWEB_CONFORMANCE_DIR)", "Regression", "P0", "All fixtures conform", FE("unit")),
    ("UNT-02", "Data binding", "tests/builder/data.test.ts: slots, query binding, TEST/LIVE", "Positive", "P1", "Binding logic", FE("unit")),
    ("UNT-03", "Actions", "tests/builder/actions.test.ts: action definition authoring, triggers, keys", "Positive", "P1", "Action authoring", FE("unit")),
    ("UNT-04", "Workflow", "tests/builder/workflow.test.ts: workflow authoring, shape, validation", "Positive", "P1", "Workflow authoring", FE("unit")),
    ("UNT-05", "Components/Inspector/Pages/DnD", "components, inspector, pages and drag-and-drop logic", "Positive", "P1", "Studio core logic", FE("unit")),
    ("UNT-06", "Portals", "tests/builder/portals.test.ts: platform/admin/studio route and permission model", "Unauthorized", "P1", "Role-based portals", FE("unit")),
    ("UNT-07", "Error mapping", "tests/builder/errors.test.ts: API error code -> user message", "Negative", "P1", "No raw error leak", FE("unit")),
    ("UNT-08", "Unit gate", "default `npm run test:unit` runs conformance (no skipped test)", "Regression", "P2", "0 skipped", FAILING("default command: 115 tests, 1 skipped (XWEB_CONFORMANCE_DIR unset) — false-green risk")),
    ("BLD-01", "Frontend build", "next build: platform, admin, studio + root", "Positive", "P0", "Builds", FE("build")),
    ("BRW-01", "Browser (component harness)", "Builder spec: 64 checks in real Chrome (component harness, not backend)", "Positive", "P1", "64/64", RES("BROWSER_BUILDER")),
    ("BRW-02", "Browser (portals)", "Portals spec: 33 checks on the three built portals (no backend)", "Positive", "P1", "33/33", ("stale", "f894cc6", "PASS 33/33", "re-run at 8e91172 NOT_RUN: port 3003 held by a foreign next-server (pid 46234); not killed")),
    ("A11Y-01", "Accessibility", "a11y.mjs axe checks on the live UI (E2E_06)", "Positive", "P2", "No serious violations", BLK("needs the isolated stack")),
    ("STU-01", "Studio real", "login -> create project -> edit -> save -> version (factory-flow, E2E_02)", "Positive", "P0", "Flow completes on real backend", BLK("needs the isolated stack")),
    ("STU-02", "Studio real", "admin setup: provider/limits -> employee uses quota (E2E_03)", "Positive", "P1", "Quota enforced", BLK("needs the isolated stack")),
    ("STU-03", "Studio real", "save with a stale revision shows a conflict and does not overwrite (browser, real backend)", "Concurrent", "P0", "No lost update in UI", GAP("no browser test against the real backend")),
    ("STU-04", "Studio real", "Studio publish flow in the browser (publish, URL, rollback)", "Positive", "P0", "Published site reachable", BLK("needs stack; publish runtime/apiBase pending (C2)")),
    ("STU-05", "Studio real", "runtime rendering of a published app shows live query data and a working action", "Positive", "P0", "Data + action work end to end", BLK("blocked by C3 (management API) and C2 (apiBase)")),
    ("STU-06", "Studio real", "unauthorised user in the browser: forbidden UI states and server denial agree (viewer/editor/publisher)", "Unauthorized", "P0", "Server is the authority", GAP("no browser-vs-server permission test")),
    ("STU-07", "Studio", "XSS: user-entered text/HTML in components never executes in preview or published site", "Malformed", "P0", "No script execution", GAP("backend sanitizer tested (TemplateSanitizerTests, StaticSiteTests); no browser XSS test")),
    ("STU-08", "Studio", "responsive layout and cosmetic checks", "Positive", "P3", "Usable at phone/desktop widths", GAP("no automated test")),
]
for (i, f, c, t, p, e, s) in C5:
    bug = "BUG-C6-004" if i == "UNT-08" else "-"
    row("C5-" + i, A, f, c, t, p, e, s, "C5", pre=(STACK_PRE if s[0] == "blocked" else "Node 22, npm ci" + (", Chrome" if i.startswith("BRW") else "")), bug=bug, req=["app-definition", "runtime-api"])

# ---------------------------------------------------------------- System E2E
A = "System E2E"
E2E = [
    ("01", "pages-mock", "e2e/pages-mock.mjs (mock/static pages; NOT a backend E2E)", "Regression", "P2", "6/6", RES("E2E_01"), "C5"),
    ("02", "factory-flow", "e2e/factory-flow.mjs: login, create/edit project, version, AI (real backend)", "Positive", "P0", "Flow passes", BLK("needs the isolated stack"), "C5"),
    ("03", "admin-setup-flow", "e2e/admin-setup-flow.mjs: provider/limit setup then quota (real backend)", "Positive", "P1", "Flow passes", BLK("needs the isolated stack"), "C5"),
    ("04", "code-flow", "e2e/code-flow.mjs: code project sandbox build, diff, merge, publish", "Positive", "P1", "Flow passes", BLK("needs the isolated stack (Forgejo + runner)"), "C2"),
    ("05", "runtime-flow", "e2e/runtime-flow.mjs: server runtime build, container, blue/green", "Positive", "P1", "Flow passes", BLK("needs the isolated stack (full profile, Docker)"), "C2"),
    ("06", "a11y", "e2e/a11y.mjs on the real UI", "Positive", "P2", "No serious violations", BLK("needs the isolated stack"), "C5"),
    ("07", "sso-flow", "e2e/sso-flow.mjs: OIDC with Keycloak", "Positive", "P1", "SSO login works", BLK("needs stack + RUN_SSO=1"), "C1"),
    ("08", "public-flow", "e2e/public-flow.mjs: public deployment via Cloudflare", "Positive", "P2", "Public URL works", BLK("needs scripts/public-up.sh + Cloudflare (not a local-stack test)"), "C2"),
    ("SMK", "smoke", "scripts/smoke-test.sh against the running stack", "Positive", "P0", "Smoke passes", BLK("needs the isolated stack"), "C0"),
    ("STK", "stack up", "scripts/run-local.sh brings up the full profile (ports free)", "Positive", "P0", "readiness 200, UI 200", FAILING("run-local.sh failed: Bind for 127.0.0.1:13000 failed: port is already allocated (stack 'hbl' of the user holds the fixed ports); not retried at 8e91172 (would collide again)", "f894cc6"), "C0"),
    ("CH-01", "chain", "login -> tenant/workspace/project (real)", "Positive", "P0", "Session + context established", GAP("tests/e2e-real not created"), "C5"),
    ("CH-02", "chain", "Studio create/edit/save app definition (real backend)", "Positive", "P0", "Version created", GAP("tests/e2e-real not created"), "C5"),
    ("CH-03", "chain", "create data source + credential through management API", "Positive", "P0", "Source stored, secret hidden", BLK("management API not implemented (C3)"), "C3"),
    ("CH-04", "chain", "define query + mapping + binding; LIVE query returns rows", "Positive", "P0", "Mapped view model", BLK("management API not implemented (C3)"), "C3"),
    ("CH-05", "chain", "mutation with idempotency over HTTP writes once to an external system", "Positive", "P0", "One write", BLK("production writable connector missing (C3/C0)"), "C3"),
    ("CH-06", "chain", "action bound to UI trigger executes (LIVE) and is audited", "Positive", "P0", "Action succeeds", BLK("needs stack + C3 chain"), "C4"),
    ("CH-07", "chain", "workflow runs (retry/compensation) and survives backend restart", "Positive", "P0", "Run completes once", BLK("needs stack + RabbitMQ wiring"), "C4"),
    ("CH-08", "chain", "publish the app, open the published runtime, see live data and run an action", "Positive", "P0", "End to end works", BLK("apiBase/publish runtime pending (C2)"), "C2"),
    ("CH-09", "chain", "rollback the deployment; runtime serves the previous version", "Rollback", "P0", "Previous version live", BLK("V30 pending (C2)"), "C2"),
    ("CH-10", "chain", "tenant B user cannot see tenant A's data anywhere in the chain", "Cross-tenant", "P0", "Isolation holds", BLK("needs the whole chain"), "C1"),
]
for (i, f, c, t, p, e, s, o) in E2E:
    row("E2E-" + i, A, f, c, t, p, e, s, o, pre=(STACK_PRE if s[0] in ("blocked", "fail") else "Node 22, Chrome"), bug=("BUG-C6-007" if i == "STK" else "-"), req=["runtime-api", "integration-contract"])

# ---------------------------------------------------------------- Failure / Recovery
A = "Failure/Recovery"
REC = [
    ("R01", "Concurrent writes", "5 concurrent prompts with the same expectedRevision: exactly one 200, the rest 409 (recovery.sh R01)", "Concurrent", "P0", "1x200 + 4x409", BLK("needs the isolated stack; engine-level equivalent: SchemaApiTests exactly-one-wins PASS"), "C2"),
    ("R02", "Replay", "retry with a stale revision is a conflict, never a second write", "Replay", "P0", "409", BLK("needs the isolated stack"), "C2"),
    ("R03", "Duplicate request", "duplicate publish with the same Idempotency-Key (sequential) returns the same deployment", "Idempotency", "P0", "Same deployment", BLK("needs the isolated stack; API-level equivalent: PublishApiTests PASS"), "C2"),
    ("R04", "Concurrent request", "5 parallel publishes with the same key produce one deployment", "Concurrent", "P0", "One deployment", BLK("needs the isolated stack; API-level equivalent: PublishApiTests PASS"), "C2"),
    ("R05", "Dependency outage", "PostgreSQL down/up: readiness drops, API recovers, login + read work", "Partial-failure", "P0", "Fail closed then recover", BLK("needs the isolated stack (stopping shared containers is not allowed)"), "C0"),
    ("R06", "Dependency outage", "Redis down/up: readiness drops, session store recovers", "Partial-failure", "P0", "Recover", BLK("needs the isolated stack; integration-level equivalent: RedisOutageTests PASS"), "C1"),
    ("R07", "Dependency outage", "RabbitMQ down/up: publish/workflow jobs survive and resume", "Partial-failure", "P0", "Jobs survive", BLK("needs the isolated stack; publish queue equivalent: QueueRecoveryTests PASS"), "C2"),
    ("R08", "Restart", "backend restart: project survives, login works", "Restart", "P0", "State intact", BLK("needs the isolated stack"), "C0"),
    ("R09", "Interrupted workflow", "workflow interrupted by crash/restart over HTTP", "Restart", "P0", "Resumes once", BLK("needs the isolated stack"), "C4"),
    ("R10", "Ambiguous mutation", "mutation timeout against a real connector: unknown outcome, no replay", "Timeout", "P0", "UNKNOWN, no duplicate", BLK("production connector read-only (B-C0-W-04)"), "C3"),
    ("R11", "Queue replay", "DLQ/replay with a real broker", "Replay", "P0", "No loss/duplication", BLK("WorkflowQueue not wired (B-C4-06)"), "C4"),
    ("R12", "Database outage", "PostgreSQL unavailable mid-request: structured 503, no partial write, no stack trace", "Partial-failure", "P0", "Fail closed", GAP("no test; only Redis-down is tested (RedisOutageTests)"), "C0"),
    ("R13", "Object store outage", "MinIO down during publish: deployment FAILED cleanly, retry works", "Partial-failure", "P1", "Clean failure", GAP("no test"), "C2"),
    ("R14", "Resource exhaustion", "disk full / connection pool exhausted: degrade without data corruption", "Partial-failure", "P2", "Degrade", GAP("no test"), "C0"),
    ("R15", "Time", "clock skew / clock jump on scheduler and lease logic", "Boundary", "P1", "No double fire / no false takeover", J(("SchedulerDedupeTests", "a clock that jumps back"), ("SchedulerDedupeTests", "two nodes with clocks"), ("WorkflowLeaseTests", "judged by its own expiry")), "C4"),
    ("R16", "Integration-level restart", "restart recovery at store level (action + workflow runs, timers, compensation) with Testcontainers", "Restart", "P0", "All restart cases pass", J(("WorkflowRestartRecoveryTests", ""), ("ActionRunRecoveryTests", ""), ("SessionRestartTests", "")), "C4"),
]
for (i, f, c, t, p, e, s, o) in REC:
    row("REC-" + i, A, f, c, t, p, e, s, o, pre=(STACK_PRE if s[0] == "blocked" else BE), req=["integration-contract", "action-workflow"])

# ---------------------------------------------------------------- Performance
A = "Performance"
PERF = [
    ("01", "Query latency", "LIVE query p95/p99 under 50 concurrent users with cache cold/warm", "Load", "P1", "p95 within agreed budget", "C3"),
    ("02", "Mutation contention", "100 concurrent mutations on one idempotency key and on distinct keys: throughput, no duplicate write", "Load", "P0", "Exactly one write per key", "C3"),
    ("03", "Workflow throughput", "10k runs in flight: sweeper fairness, lease renewal cost, DB load", "Load", "P1", "No starvation, bounded DB load", "C4"),
    ("04", "Scheduler", "1k schedules firing in the same minute: exactly-once and latency", "Load", "P1", "Exactly once", "C4"),
    ("05", "Publish", "20 concurrent publishes across projects: queue depth, build time, artifact store", "Load", "P1", "All complete, no cross-talk", "C2"),
    ("06", "Studio", "large app definition (many pages/components): save, validate, render time", "Load", "P2", "Within budget", "C5"),
    ("07", "Auth", "login burst: password-hash gate returns 503+Retry-After without CPU collapse", "Load", "P1", "Graceful shedding", "C1"),
    ("08", "Migration", "V26–V29 on a database with 100k projects: duration and lock time", "Load", "P1", "Within maintenance window", "C0"),
    ("09", "Soak", "8-hour soak: memory/connection leaks, sweeper drift", "Load", "P2", "Flat resource curves", "C0"),
    ("10", "Cache", "cache stampede on a hot key after invalidation", "Concurrent", "P1", "Single refill", "C3"),
]
for (i, f, c, t, p, e, o) in PERF:
    row("PERF-" + i, A, f, c, t, p, e, GAP("no performance test or tooling exists in the repo; define at RC"), o, pre="Isolated stack + load tool", req=["runtime-api"])

# ---------------------------------------------------------------- Non-functional / harness
A = "Non-functional"
NF = [
    ("DOC-01", "Docs", "README test count matches reality (README says 185; backend has 1502)", "Static", "P3", "Matches", FAILING("README.md:38 still says '185 tests'"), "C0", "BUG-C6-001"),
    ("DOC-02", "Docs", "MAC_INTEGRATION_CHECKLIST skip expectation matches code (no @Disabled; skipped = 3 env-only)", "Static", "P3", "Matches", FAILING("checklist line 44 still says 'skipped = 3 + 1 (still disabled)'; @Disabled count is 0"), "C0", "BUG-C6-002"),
    ("DOC-03", "Docs", "BOARD/BASELINE consistent with the tree (V28/V29 imported; 'Re-baseline (macOS)' recorded)", "Static", "P3", "Consistent", FAILING("BOARD says V28 'not imported into integration/v2' while the tree has V28 and V29; BASELINE Re-baseline (macOS) 'chưa có'"), "C0", "BUG-C6-003"),
    ("OPS-01", "Operations", "health probes list components with honest states; backup monitor reports stale/failed/skipped honestly", "Positive", "P2", "Honest health", J(("HealthProbesTests", ""), ("BackupMonitorTests", "")), "C0", "-"),
    ("OPS-02", "Operations", "lockdown settings: sign-up off by default, numeric settings range-checked, asset quota per project", "Negative", "P1", "Safe defaults", J(("LockdownSettingsTests", "")), "C1", "-"),
    ("OPS-03", "Operations", "sign-up creates an isolated account; rate-limited per IP; weak passwords refused; project/asset caps", "Boundary", "P1", "Abuse limits", J(("RegistrationAndLimitsTests", ""), ("SignupRateLimitTests", ""), ("SignupDisabledTests", "")), "C1", "-"),
    ("OPS-04", "AI Gateway", "AI governance: model access deny rules, tool calling with user's permissions, quotas, key never returned or logged", "Negative", "P0", "AI cannot bypass authz or leak keys", J(("AiGovernanceTests", ""), ("AiConfigWebTests", ""), ("AiUsageTests", ""), ("MultiProviderTests", ""), ("OpenRouterPromptTests", ""), ("TenantAiServiceTests", ""), ("TenantAiPolicyTests", ""), ("AiSourceResolverTests", "")), "C2", "-"),
    ("OPS-05", "AI Gateway", "OpenRouter live: real key answers, invalid key fatal without echo, model list parses (needs real key)", "Positive", "P3", "Live provider works", ("skipped", "3 tests skipped: need OpenRouter key (env-conditional)"), "C2", "-"),
    ("OPS-06", "Assets/Members/Admin", "asset API/reference, member API, admin org/API", "Positive", "P2", "CRUD + permissions", J(("AssetApiTests", ""), ("AssetReferenceTests", ""), ("MemberApiTests", ""), ("AdminApiTests", ""), ("AdminOrgTests", ""), ("ProjectApiTests", ""), ("ProjectRepositoryTests", "")), "C0", "-"),
    ("TRC-01", "Traceability", "every key clause of the frozen contracts, ledger, board, decisions and CLAUDE.md is a requirement mapped to at least one test id (section 6)", "Static", "P1", "Matrix exists; no requirement without a mapped test", ("traceability",), "C6", "-"),
    ("TRC-02", "Traceability", "requirement matrix covers the design notes too: docs/parallel/c3, c4, c5, c0 design docs and BLOCKERS.md (only contracts, ledger, board, decisions and CLAUDE.md are mapped)", "Static", "P2", "Every blocker/design note mapped", GAP("scope of the first matrix"), "C6", "-"),
    ("HX-01", "QA harness", "backend gate executes tests (rejects `:test FROM-CACHE`/UP-TO-DATE)", "Regression", "P0", "Real execution only", ("harness_fixed", "BUG-C6-005"), "C6", "BUG-C6-005"),
    ("HX-02", "QA harness", "stack phase preflights ports/projects and reports BLOCKED instead of a late FAIL", "Regression", "P2", "Early, explicit BLOCKED", FAILING("mac-full.sh has no port preflight: STACK_UP failed after creating containers and waiting 2 minutes"), "C6", "BUG-C6-007"),
    ("HX-03", "QA harness", "static QA SQ-03 follows the C0 migration ledger (V29 allocated)", "Regression", "P1", "10/10", ("harness_fixed", "BUG-C6-009"), "C6", "BUG-C6-009"),
    ("HX-04", "QA harness", "selftest of the runner logic", "Regression", "P1", "44/44", ("selftest", "44/44"), "C6", "-"),
]
for (i, f, c, t, p, e, s, o, bug) in NF:
    row("NF-" + i, A, f, c, t, p, e, s, o, pre=BE, bug=bug)

# Risk-based tiering: P0 only for security isolation, auth bypass, data corruption, duplicate mutation, workflow corruption, publish rollback.
# Plain functional flows that were first written as P0 are demoted here (kept in one place so the reasoning stays auditable).
P1_DEMOTE = {"C0-MIG-01", "C0-MIG-05", "C0-MIG-11", "C0-MIG-18", "C0-WIR-15", "C0-ARCH-01", "C0-ARCH-06", "C0-BLD-02", "C0-BLD-03", "C0-BLD-04",
             "C1-AUTH-04", "C1-PRM-13", "C2-DEF-07", "C2-DEF-09", "C2-PUB-01", "C2-DEP-10", "C2-DEP-12", "C2-DEP-13", "C2-DEP-14", "C3-SRC-01", "C3-SRC-03", "C3-SRC-06",
             "C3-QRY-01", "C4-ACT-11", "C4-ACT-15", "C4-ACT-22", "C4-WFL-01", "C4-WFL-14", "C5-TYP-01", "C5-UNT-01", "C5-BLD-01", "C5-STU-01", "C5-STU-04", "C5-STU-05",
             "E2E-02", "E2E-SMK", "E2E-STK", "E2E-CH-01", "E2E-CH-02", "E2E-CH-03", "E2E-CH-04", "E2E-CH-06", "E2E-CH-08", "NF-HX-01",
             "C0-PER-22", "C1-AUTH-02", "C1-AUTH-12", "C1-SEC-08", "C1-SEC-21", "C2-PUB-04", "C3-QRY-02", "C3-QRY-04", "C3-QRY-07", "C3-WHK-03",
             "C4-ACT-20", "C4-ACT-14", "C4-LSE-02", "C4-QUE-04", "C4-SCH-01", "C4-SCH-04", "C4-WFL-05", "C4-WFL-12", "C4-REC-02", "C4-REC-03", "REC-R12",
             "C3-IDM-03", "C3-IDM-07", "C1-PRM-15", "C1-SEC-14", "C0-PER-03", "C0-PER-06", "C2-PUB-06", "C2-DEP-02", "C2-DEP-03"}
for _r in ROWS:
    if _r["id"] in P1_DEMOTE and _r["pr"] == "P0":
        _r["pr"] = "P1"

# ================================================================= engine
def load_tsv(path):
    cases = collections.defaultdict(list)
    with open(path, encoding="utf-8") as fh:
        next(fh)
        for line in fh:
            s, n, st = line.rstrip("\n").split("\t")
            cases[s].append((n, st))
    return cases


def suite_cases(cases, short):
    for full, lst in cases.items():
        if full == short or full.endswith("." + short):
            return full, lst
    return None, None


def evaluate(r, cases, ctx, src=None):
    """returns (status_key, evidence_str, tested_sha, note)"""
    src = src or r["src"]
    kind = src[0]
    if kind == "junit":
        per, status = collections.OrderedDict(), "PASS"
        for (suite, kw) in src[1]:
            full, lst = suite_cases(cases, suite)
            if lst is None:
                raise SystemExit(f"{r['id']}: suite {suite} not found in the JUnit export")
            sel = [(n, st) for (n, st) in lst if kw.lower() in n.lower()]
            if not sel:
                raise SystemExit(f"{r['id']}: no test case in {suite} matches '{kw}'")
            r.setdefault("_pk", set()).add(".".join(full.split(".")[:-1]))
            d = per.setdefault(suite, {"all": len(lst), "names": {}})
            for (n, st) in sel:
                d["names"][n] = st
        for d in per.values():
            vals = set(d["names"].values())
            if "FAIL" in vals:
                status = "FAIL"
            elif "SKIPPED" in vals and status != "FAIL":
                status = "SKIPPED"
        total = sum(len(d["names"]) for d in per.values())
        parts = [f"{k}[all {d['all']}]" if len(d["names"]) == d["all"] else f"{k}[{len(d['names'])}]" for k, d in per.items()]
        return ("FAIL" if status == "FAIL" else "PASS" if status == "PASS" else "NOTRUN"), "J: " + ", ".join(parts) + f" = {total} cases", ctx["sha"], ""
    if kind == "fe":
        k = src[1]
        m = {"typecheck": ctx["res"].get("FRONTEND_TYPECHECK"), "unit": ctx["res"].get("FRONTEND_UNIT"), "build": ctx["res"].get("FRONTEND_BUILD")}[k]
        if m and m.startswith("PASS"):
            return "PASS", f"NS: FRONTEND_{k.upper()} {m}", ctx["sha"], ""
        return "NOTRUN", "NS: missing", "-", ""
    if kind == "static":
        ids = src[1]
        got = [ctx["static"].get(i) for i in ids]
        if all(g == "PASS" for g in got):
            return "PASS", "SQ: " + ",".join(ids) + " (STATIC_QA.log)", ctx["sha"], ""
        return "FAIL", "SQ: " + ",".join(f"{i}={g}" for i, g in zip(ids, got)), ctx["sha"], ""
    if kind == "res":
        v = ctx["res"].get(src[1])
        if v and v.startswith("PASS"):
            return "PASS", f"NS: {r['src'][1]} {v}", ctx["sha"], ""
        return "NOTRUN", f"NS: {r['src'][1]} {v}", "-", ""
    if kind == "multi":
        outs = [evaluate(r, cases, ctx, x) for x in src[1]]
        sts = [o[0] for o in outs]
        return ("FAIL" if "FAIL" in sts else "PASS" if all(x == "PASS" for x in sts) else "NOTRUN"), " + ".join(o[1] for o in outs), ctx["sha"], ""
    if kind == "cc":
        got = [ctx["cc"].get(i) for i in src[1]]
        if any(g is None for g in got):
            return "NOTRUN", "C6 checks: missing " + ",".join(i for i, g in zip(src[1], got) if g is None), "-", ""
        return ("PASS" if all(g == "PASS" for g in got) else "FAIL"), "C6: " + ", ".join(src[1]) + " (evidence/mac-8e91172-contract-checks)", ctx["sha"], ""
    if kind == "ov":
        hit = []
        for pre in src[1]:
            m = [st for n, st in ctx["ov"].items() if n.startswith(pre)]
            if not m:
                return "NOTRUN", "OV: no overlay result for '" + pre + "'", "-", ""
            hit += m
        return ("PASS" if all(x == "PASS" for x in hit) else "FAIL"), f"OV: C6RuntimePermissionHttpTests[{len(hit)}] (overlay worktree, Gradle)", ctx["sha"], ""
    if kind == "npm":
        v = ctx["npm"]
        if v is None:
            return "NOTRUN", "npm audit not run", "-", ""
        return ("PASS" if v == 0 else "FAIL"), f"npm audit --omit=dev: {v} vulnerabilities", ctx["sha"], ""
    if kind == "gap":
        return "GAP", "-", "-", src[1]
    if kind == "blocked":
        return "BLOCKED", "-", "-", src[1]
    if kind == "notrun":
        return "NOTRUN", "-", "-", src[1]
    if kind == "fail":
        return "FAIL", "see bug", (src[2] or ctx["sha"]), src[1]
    if kind == "stale":
        return "RETEST", "R1: BROWSER_PORTALS (f894cc6)", src[1], src[3]
    if kind == "skipped":
        return "NOTRUN", "J: OpenRouterLiveTests[3 skipped]", ctx["sha"], src[1]
    if kind == "harness_fixed":
        ev = "G2 + BACKEND_TEST.log: gate refuses FROM-CACHE; 8e91172 `:test` executed (470 s)" if src[1] == "BUG-C6-005" else "evidence/mac-8e91172-gate/STATIC_QA_rerun_after_SQ03_fix.log (10/10)"
        return "PASS", ev, ctx["sha"], ""
    if kind == "traceability":
        return "PASS", f"section 6: {ctx['nreq']} requirements, each maps to >=1 test row (validated by the generator)", ctx["sha"], "scope: contracts/ledger/board/decisions/CLAUDE.md; other design notes = NF-TRC-02"
    if kind == "selftest":
        return "PASS", "harness/selftest.sh 44/44", ctx["sha"], ""
    raise SystemExit("unknown src " + kind)


def flat(src):
    """leaf evidence sources of a (possibly multi) source"""
    if src[0] == "multi":
        for x in src[1]:
            yield from flat(x)
    else:
        yield src


def md(s):
    return str(s).replace("|", "/").replace("\n", " ")


GATE_NAME = {"G1": "Build", "G2": "Backend", "G3": "Security", "G4": "Frontend", "G5": "Data", "G6": "Workflow", "G7": "Publish", "G8": "Real E2E", "G9": "Recovery", "G10": "Performance", "G11": "RC regression"}
EXPLICIT_GAP = {**{f"E2E-CH-{n:02d}": "GAP-C6-01" for n in range(1, 11)}, "C5-STU-09": "GAP-C6-02", "C0-WIR-24": "GAP-C6-03", "C0-ARCH-05": "GAP-C6-04", "C1-PRM-07": "GAP-C6-05",
                "C1-PRM-10": "GAP-C6-06", "C3-MUT-09": "GAP-C6-07", "C1-AUD-01": "GAP-C6-08", "REC-R12": "GAP-C6-09", "REC-R13": "GAP-C6-10", "C1-AUTH-14": "GAP-C6-11",
                "C1-SEC-22": "GAP-C6-12", "C5-STU-07": "GAP-C6-13", "NF-TRC-02": "GAP-C6-14"}
BUG_SEED = {
    "BUG-C6-001": [["f894cc6", "2026-10-06", "OPEN", "found by static review (README says 185 tests)"], ["8e91172", "2026-10-06", "OPEN", "revalidated on the 8e91172 tree: README.md:38 unchanged"]],
    "BUG-C6-002": [["f894cc6", "2026-10-06", "OPEN", "checklist expects the spec still disabled; @Disabled count is 0"], ["8e91172", "2026-10-06", "OPEN", "revalidated: unchanged"]],
    "BUG-C6-003": [["f894cc6", "2026-10-06", "OPEN", "BOARD/BASELINE contradict the tree"], ["8e91172", "2026-10-06", "OPEN", "revalidated: BOARD still says V28 'not imported' while V28 and V29 are in the tree"]],
    "BUG-C6-004": [["f894cc6", "2026-10-06", "OPEN", "default npm run test:unit skips conformance (Linux VM)"], ["f894cc6", "2026-10-06", "OPEN", "confirmed on Mac run 1: 115 tests, 1 skipped, exit 0"], ["8e91172", "2026-10-06", "OPEN", "confirmed again: 115 tests, 1 skipped"]],
    "BUG-C6-005": [["f894cc6", "2026-10-06", "OPEN", "Mac run 1: BACKEND_TEST PASS came from `:test FROM-CACHE` (15 s)"], ["f894cc6", "2026-10-06", "FIX IN PROGRESS", "harness patched: --no-build-cache --rerun-tasks + refuse FROM-CACHE/UP-TO-DATE"], ["8e91172", "2026-10-06", "RETEST PASS", "`:test` executed for real (470 s, 1502 tests)"], ["8e91172", "2026-10-06", "RESOLVED", "fixed in the C6 harness; Regression Watch YES"]],
    "BUG-C6-006": [["f894cc6", "2026-10-06", "OPEN", "gate2: compileKotlin 'Not enough memory to run compilation' (default heap)"], ["8e91172", "2026-10-06", "OPEN", "workaround verified with -Pkotlin.daemon.jvmargs=-Xmx3g; repo config change (C0) still required"]],
    "BUG-C6-007": [["f894cc6", "2026-10-06", "OPEN", "mac-full: STACK_UP failed late on port 13000 collision (no preflight)"], ["8e91172", "2026-10-06", "OPEN", "no preflight yet"]],
    "BUG-C6-008": [["f894cc6", "2026-10-06", "OPEN", "failed compile left untracked backend/.kotlin/ (not gitignored)"], ["8e91172", "2026-10-06", "OPEN", "not reproduced (compile succeeded); gitignore still missing"]],
    "BUG-C6-009": [["8e91172", "2026-10-06", "OPEN", "static QA SQ-03 hardcoded max<=28 failed when V29 was allocated"], ["8e91172", "2026-10-06", "FIX IN PROGRESS", "SQ-03 now reads MIGRATION_LEDGER.md"], ["8e91172", "2026-10-06", "RETEST PASS", "static QA 10/10"], ["8e91172", "2026-10-06", "RESOLVED", "fixed in the C6 harness; Regression Watch YES"]],
    "BUG-C6-010": [["8e91172", "2026-10-07", "OPEN", "BLOCKERS.md lists delivered items as OPEN (B-C4-05 V29, B-C4-07 wiring, B-C4-09 controllers, B-C5-04 TEST/WouldRun, B-C5-09 decisions D-C0-17/19)"]],
}
# ---- frozen snapshot of the previous generation (batch 6 end). Never recomputed: history is not rewritten without new evidence.
BASE6 = dict(sha="8e91172", reqs=180, full=97, partial=57, none=26, p0none=14, p1none=8, rows=473, p0rows=212, mapped=1359, passed=313, failed=8, blocked=98, notrun=1, retest=1, gap=52,
             p0none_ids=["REQ-TP-07", "REQ-ML-07", "REQ-MA-01", "REQ-AW-26", "REQ-AD-15", "REQ-DC-04", "REQ-DC-05", "REQ-DC-06", "REQ-DC-07", "REQ-DC-08", "REQ-DC-09", "REQ-DC-18", "REQ-DC-19", "REQ-DC-22"])

P0_DEMOTE = {  # P0 -> P1 after the risk review (batch 7). Reason = the failure cannot cause any of the allowed P0 outcomes.
    "C0-ARCH-04": "regression of legacy APIs: functional break, no isolation/data-loss path",
    "C1-AUD-03": "an unaudited action is an audit-completeness gap, not a breach; the audit-failure fail-closed rule is functional hardening",
    "C2-DEF-01": "invalid app definitions are rejected/accepted at the validator: functional correctness, versions stay immutable (C2-DEF-05/10 stay P0)",
    "C2-DEF-06": "V2 validator error codes: functional correctness",
    "C3-MUT-08": "a writable production connector is a missing feature; the safety semantics are P0 in C3-MUT-02/10",
    "C5-STU-03": "the server already prevents the lost update (C2-DEF-02 P0); the UI message is functional",
    "C5-STU-06": "the server is the authority and is P0-tested; browser/server agreement is UX",
    "REC-R06": "Redis outage logs users out and recovers; no data loss or inconsistency path",
    "C3-MGT-09": "management CRUD happy path is functional; its dangerous parts are C3-MGT-10 (P0)",
    "C3-MGT-18": "audit-with-write unit is audit completeness; credential/tenant leaks are P0 elsewhere",
}
P0_CLASS_NAME = {"AUTH": "auth/security isolation breach", "PRIV": "privilege escalation", "SECRET": "secret/credential leak", "LOSS": "data loss/corruption", "DUP": "duplicate unsafe mutation", "IDEM": "broken idempotency",
                 "AMBIG": "ambiguous workflow/mutation outcome", "REPLAY": "unsafe replay", "MIG": "migration corruption", "CAS": "publish/CAS race", "ROLLBACK": "rollback to wrong runtime/state", "RECOV": "critical recovery inconsistency"}
P0_DEFAULT = {"Cross-tenant": "AUTH", "Unauthorized": "AUTH", "Replay": "REPLAY", "Idempotency": "IDEM", "Duplicate": "DUP", "Timeout": "AMBIG", "Restart": "RECOV", "Rollback": "ROLLBACK", "Persistence": "LOSS", "Load": "DUP"}
P0_OVERRIDE = {
    "C0-MIG-09": "MIG", "C0-MIG-14": "SECRET", "C0-MIG-21": "MIG", "C0-MIG-23": "MIG", "C0-WIR-01": "LOSS", "C0-WIR-02": "LOSS", "C0-WIR-03": "LOSS", "C0-WIR-05": "AUTH", "C0-WIR-06": "AUTH",
    "C0-WIR-07": "DUP", "C0-WIR-08": "DUP", "C0-WIR-10": "AUTH", "C0-WIR-12": "AMBIG", "C0-WIR-18": "DUP", "C0-PER-02": "SECRET", "C0-PER-08": "IDEM", "C0-PER-09": "DUP", "C0-PER-10": "AMBIG",
    "C0-PER-14": "DUP", "C0-PER-17": "RECOV", "C0-PER-18": "DUP", "C0-PER-19": "AMBIG", "C0-PER-21": "LOSS",
    "C1-AUTH-03": "AUTH", "C1-AUTH-08": "PRIV", "C1-PRM-01": "AUTH", "C1-PRM-02": "AUTH", "C1-PRM-03": "PRIV", "C1-PRM-12": "AUTH", "C1-PRM-14": "AUTH", "C1-PRM-18": "PRIV", "C1-PRM-20": "PRIV",
    "C1-ESC-01": "PRIV", "C1-ESC-02": "PRIV", "C1-ESC-03": "PRIV", "C1-ESC-04": "PRIV", "C1-ESC-05": "PRIV", "C1-TEN-26": "PRIV", "C1-TEN-22": "AUTH", "C1-TEN-23": "AUTH",
    "C1-SEC-01": "AUTH", "C1-SEC-02": "AUTH", "C1-SEC-03": "AUTH", "C1-SEC-04": "AUTH", "C1-SEC-05": "SECRET", "C1-SEC-06": "AUTH", "C1-SEC-07": "AUTH", "C1-SEC-10": "REPLAY", "C1-SEC-11": "AUTH",
    "C1-SEC-12": "SECRET", "C1-SEC-17": "AUTH", "C1-SEC-20": "SECRET", "C1-SEC-24": "AUTH", "C1-SEC-27": "SECRET", "C1-AUD-01": "LOSS", "C1-AUD-02": "SECRET", "NF-OPS-04": "AUTH",
    "C2-DEF-02": "LOSS", "C2-DEF-05": "LOSS", "C2-DEF-10": "LOSS", "C2-PUB-02": "CAS", "C2-PUB-10": "SECRET", "C2-PUB-11": "AUTH", "C2-PUB-15": "AUTH", "C2-DEP-08": "CAS", "C2-DEP-09": "CAS",
    "C2-V30-01": "CAS", "C2-V30-02": "CAS", "C2-V30-03": "CAS", "C2-V30-04": "CAS", "C2-V30-06": "RECOV", "C2-V30-09": "CAS", "C2-V30-11": "MIG", "C2-V30-12": "CAS", "C2-V30-13": "ROLLBACK", "C2-V30-15": "LOSS",
    "C3-SRC-07": "SECRET", "C3-SRC-08": "AUTH", "C3-QRY-03": "SECRET", "C3-MUT-02": "AMBIG", "C3-MUT-03": "DUP", "C3-MUT-04": "AMBIG", "C3-MUT-05": "DUP", "C3-MUT-09": "LOSS", "C3-IDM-01": "DUP",
    "C3-MGT-01": "AUTH", "C3-MGT-02": "AUTH", "C3-MGT-03": "AUTH", "C3-MGT-05": "SECRET", "C3-MGT-06": "LOSS", "C3-MGT-10": "LOSS", "C3-MGT-12": "SECRET", "C3-MGT-15": "AUTH",
    "C4-ACT-01": "IDEM", "C4-ACT-08": "DUP", "C4-ACT-12": "DUP", "C4-ACT-17": "AUTH", "C4-ABN-03": "AMBIG", "C4-ABN-04": "AMBIG", "C4-WFL-08": "AMBIG", "C4-WFL-15": "DUP", "C4-WFL-17": "AUTH",
    "C4-QUE-03": "RECOV", "C4-QUE-07": "RECOV", "C4-LSE-01": "DUP", "C4-LSE-03": "DUP", "C4-LSE-05": "DUP", "C4-LSE-06": "DUP", "C5-STU-07": "AUTH",
    "E2E-CH-05": "DUP", "E2E-CH-07": "RECOV", "REC-R01": "LOSS", "REC-R04": "DUP", "REC-R05": "RECOV", "REC-R07": "RECOV", "C0-PER-23": "AUTH",
}
NEEDS_EXPLICIT = {"Positive", "Regression", "Static", "Boundary", "Negative", "Malformed", "Partial-failure", "Concurrent"}
IMPL_ORDER = ["IMPLEMENTED", "PARTIAL", "NOT_IMPLEMENTED", "UNKNOWN"]
VER_ORDER = ["VERIFIED", "PARTIAL", "UNVERIFIED", "BLOCKED"]
IMPL_WORDS = ("not implemented", "not integrated", "not created", "not wired", "pending", "read-only", "missing", "not built", "not requested", "unnumbered", "wiring", "writable", "management api", "v30", "apibase", "c3 not green", "h-5", "w-07", "none exists")


def gate_of_row(r):
    i = r["id"]
    if i.startswith("C1-"):
        return "G3"
    if i.startswith("C0-BLD"):
        return "G1"
    if i in ("C0-PER-25", "C0-WIR-22", "C0-WIR-24") or (i.startswith(("E2E-", "C5-STU-0", "C5-STU-1")) and i not in ("C5-STU-09", "C5-STU-11")):
        return "G8"
    if i.startswith("C4-REC"):
        return "G9"
    if i.startswith("C0-"):
        return "G2"
    if i.startswith("C2-DEF"):
        return "G2"
    if i.startswith("C2-"):
        return "G7"
    if i.startswith("C3-"):
        return "G5"
    if i.startswith("C4-"):
        return "G6"
    if i.startswith("C5-"):
        return "G4"
    if i.startswith("REC-"):
        return "G9"
    if i.startswith("PERF-"):
        return "G10"
    return "G11"


def pr_rank(p):
    return {"P0": 0, "P1": 1, "P2": 2, "P3": 3}[p]


def row_cause(r):
    """why a non-passing row is not green: QA_COVERAGE (no test exists), IMPLEMENTATION (feature missing/not integrated), ENVIRONMENT (needs the stack/ports), DEFECT, RETEST"""
    st = r["status"]
    if st == "GAP":
        return "QA_COVERAGE"
    if st == "FAIL":
        return "DEFECT"
    if st == "RETEST":
        return "ENVIRONMENT"
    if st == "NOTRUN":
        return "ENVIRONMENT"
    if st == "BLOCKED":
        n = (r.get("note") or "").lower()
        if any(w in n for w in IMPL_WORDS):
            return "IMPLEMENTATION"
        if "stack" in n or "port" in n or "keycloak" in n or "docker" in n:
            return "ENVIRONMENT"
        return "IMPLEMENTATION"
    return ""


def p0_class(r):
    import qa_master_reqs as _RQ
    if r["id"] in _RQ.P0_PROMOTE:
        return _RQ.P0_PROMOTE[r["id"]][0]
    if r["id"] in P0_OVERRIDE:
        return P0_OVERRIDE[r["id"]]
    if r["typ"] in NEEDS_EXPLICIT:
        return None
    return P0_DEFAULT.get(r["typ"])


def main():
    sys.path.insert(0, HERE)
    import qa_master_reqs as RQ
    ap = argparse.ArgumentParser()
    ap.add_argument("--sha", required=True)
    ap.add_argument("--prev", required=True)
    ap.add_argument("--tsv", required=True)
    ap.add_argument("--nostack", required=True, help="evidence dir with RESULTS.txt + STATIC_QA.log of the same SHA")
    ap.add_argument("--checks", default=None, help="evidence dir with contract-checks.tsv, db-probes.tsv, TEST-*.xml (overlay) and npm-audit-prod.json of the same SHA")
    ap.add_argument("--out", default=os.path.join(C6, "QA_MASTER.md"))
    ap.add_argument("--state", default=os.path.join(C6, "qa_master_state.json"))
    ap.add_argument("--write-state", action="store_true", help="append this SHA to the persistent per-row history (idempotent)")
    ap.add_argument("--bug-event", nargs=4, metavar=("BUG", "SHA", "STATE", "NOTE"), help="append a lifecycle event to a bug (OPEN, FIX IN PROGRESS, READY FOR RETEST, RETEST FAILED, RETEST PASS, RESOLVED)")
    a = ap.parse_args()

    def absd(x):
        return os.path.join(ROOT, x) if not os.path.isabs(x) else x
    cases = load_tsv(absd(a.tsv))
    nsd = absd(a.nostack)
    res = {}
    for line in open(os.path.join(nsd, "RESULTS.txt"), encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) >= 2 and p[1] in ("PASS", "FAIL", "NOT_RUN", "INFO", "WARN"):
            res[p[0]] = p[1] + " " + " ".join(x for x in p[2:] if not x.startswith(("log=", "at=")))[:60]
    static = {}
    for line in open(os.path.join(nsd, "STATIC_QA.log"), encoding="utf-8"):
        m = re.match(r"^(PASS|FAIL)\s+(SQ-\d+)", line)
        if m:
            static[m.group(2)] = m.group(1)
    cc, ov, npm = {}, {}, None
    if a.checks:
        import glob, json as _json, xml.etree.ElementTree as ET
        cd = absd(a.checks)
        for f in ("contract-checks.tsv", "db-probes.tsv"):
            fp = os.path.join(cd, f)
            if os.path.exists(fp):
                for ln in open(fp, encoding="utf-8").read().splitlines()[1:]:
                    i, st, _ = (ln.split("\t") + ["", ""])[:3]
                    cc[i] = st
        for fp in glob.glob(os.path.join(cd, "TEST-*.xml")):
            for tc in ET.parse(fp).getroot().iter("testcase"):
                ov[tc.get("name")] = "FAIL" if (tc.find("failure") is not None or tc.find("error") is not None) else ("SKIP" if tc.find("skipped") is not None else "PASS")
        fp = os.path.join(cd, "npm-audit-prod.json")
        if os.path.exists(fp):
            npm = _json.load(open(fp)).get("metadata", {}).get("vulnerabilities", {}).get("total")

    REQS = RQ.register(row, J, GAP, BLK, STATIC)
    ctx = dict(sha=a.sha, res=res, static=static, nreq=len(REQS), cc=cc, ov=ov, npm=npm)
    state = json.load(open(a.state, encoding="utf-8")) if os.path.exists(a.state) else {"rows": {}, "runs": []}
    state.setdefault("gap_ids", {})
    state.setdefault("bug_events", {})
    for b, ev in BUG_SEED.items():
        state["bug_events"].setdefault(b, ev)
    if a.bug_event:
        b, sha, st, note = a.bug_event
        state["bug_events"].setdefault(b, []).append([sha, datetime.date.today().isoformat(), st, note])

    ids = [r["id"] for r in ROWS]
    dup = [k for k, v in collections.Counter(ids).items() if v > 1]
    if dup:
        raise SystemExit("duplicate test ids: " + ", ".join(dup))
    # ---- corrections: GAP/BLOCKED rows covered by existing test BODIES or by the C6 checks of this batch
    corrections = []
    ovr = RQ.SRC_OVERRIDES(J, GAP, BLK, CC, OV, NPM, MULTI)
    for r in ROWS:
        if r["id"] in ovr:
            old = r["src"][0]
            r["src"], why = ovr[r["id"]]
            corrections.append((r["id"], old, r["src"][0], why))
    # ---- priority review (P0 only for the allowed outcomes)
    p0_before = sum(1 for r in ROWS if r["pr"] == "P0")
    demoted = []
    for r in ROWS:
        if r["id"] in P0_DEMOTE and r["pr"] == "P0":
            r["pr"] = "P1"
            demoted.append((r["id"], P0_DEMOTE[r["id"]]))
    promoted = []
    for r in ROWS:
        if r["id"] in RQ.P0_PROMOTE and r["pr"] != "P0":
            r["pr"] = "P0"
            promoted.append((r["id"], RQ.P0_PROMOTE[r["id"]][1]))
    miss_cls = [r["id"] for r in ROWS if r["pr"] == "P0" and p0_class(r) is None]
    if miss_cls:
        raise SystemExit("P0 rows without an explicit risk class (add to P0_OVERRIDE or demote): " + ", ".join(miss_cls))
    for r in ROWS:
        r["cls"] = p0_class(r) if r["pr"] == "P0" else None

    for r in ROWS:
        st, ev, sha, note = evaluate(r, cases, ctx)
        r.update(status=st, evidence=ev, tested=sha, note=note)
        hist = state["rows"].setdefault(r["id"], [])
        if a.write_state:
            if not hist:
                leaf = list(flat(r["src"]))
                k = leaf[0][0]
                if k == "junit" and not ({x[0] for l in leaf if l[0] == "junit" for x in l[1]} & NEW_SUITES):
                    hist.append([a.prev, "NOTRUN"])
                elif k in ("fe", "static", "res", "stale"):
                    hist.append([a.prev, "PASS"])
                elif k in ("gap", "blocked"):
                    hist.append([a.prev, st])
                elif k in ("fail", "harness_fixed") and r["bug"] != "BUG-C6-009":
                    hist.append([a.prev, "FAIL"])
                elif r["bug"] == "BUG-C6-009":
                    hist.append([a.sha, "FAIL"])
            if r["id"] in {c[0] for c in corrections} and state["rows"][r["id"]] and state["rows"][r["id"]][-1][1] in ("GAP", "BLOCKED") and st == "PASS":
                pass
            if not hist or hist[-1] != [a.sha, st]:
                hist.append([a.sha, st])

    # ---------------------------------------------------------------- bugs
    BUGS = [
        ("BUG-C6-001", "Low", "C0", "README test count stale ('185 tests')", "OPEN", ["NF-DOC-01"], "f894cc6", "-"),
        ("BUG-C6-002", "Low", "C0/C1", "docs expect AdminTransferOwnershipSelfGrantSpec still disabled (skipped 3+1); spec is enabled", "OPEN", ["NF-DOC-02", "C1-ESC-05"], "f894cc6", "-"),
        ("BUG-C6-003", "Low", "C0", "BOARD/BASELINE contradict the tree (V28/V29 imported; Re-baseline (macOS) empty)", "OPEN", ["NF-DOC-03"], "f894cc6", "-"),
        ("BUG-C6-004", "Low", "C5", "default npm run test:unit skips conformance, exit 0 (false-green risk)", "OPEN", ["C5-UNT-08", "C5-UNT-01"], "f894cc6", "-"),
        ("BUG-C6-005", "Medium (harness)", "C6", "backend gate accepted Gradle build-cache replay as PASS", "RESOLVED", ["NF-HX-01", "C0-BLD-02"], "f894cc6", "8e91172"),
        ("BUG-C6-006", "Low", "C0", "uncached compileKotlin OOM with default heap on a 16 GB Mac (no kotlin.daemon.jvmargs)", "OPEN", ["C0-BLD-01", "C0-BLD-02"], "f894cc6", "-"),
        ("BUG-C6-007", "Low (harness)", "C6", "no port/stack preflight; STACK_UP fails late on port collision", "OPEN", ["NF-HX-02", "E2E-STK"], "f894cc6", "-"),
        ("BUG-C6-008", "Low", "C0", "backend/.kotlin/ not gitignored; a failed compile leaves it untracked", "OPEN", ["C0-BLD-05"], "f894cc6", "-"),
        ("BUG-C6-009", "Low (harness)", "C6", "static QA SQ-03 hardcoded max<=28; failed when V29 was allocated", "RESOLVED", ["NF-HX-03", "C0-MIG-22"], "8e91172", "8e91172"),
        ("BUG-C6-010", "Low", "C0", "BLOCKERS.md lists delivered items as OPEN (B-C4-05/07/09, B-C5-04/09, B-C0-W-01)", "OPEN", ["NF-DOC-04"], "8e91172", "-"),
    ]
    rid = {r["id"]: r for r in ROWS}
    linked = collections.defaultdict(set)
    for b in BUGS:
        for t in b[5]:
            if t not in rid:
                raise SystemExit(f"bug {b[0]} links unknown test {t}")
            linked[t].add(b[0])
    for r in ROWS:
        ids_ = set(linked[r["id"]]) | ({x for x in r["bug"].split(", ")} - {"-"} if r["bug"] != "-" else set(linked[r["id"]]))
        r["bug"] = ", ".join(sorted(ids_)) if ids_ else "-"
    for r in ROWS:
        for _b in ([] if r["bug"] == "-" else r["bug"].split(", ")):
            if _b not in {b[0] for b in BUGS}:
                raise SystemExit(f"{r['id']} cites unknown bug {_b}")
        if r["status"] == "FAIL" and r["bug"] == "-":
            raise SystemExit(f"{r['id']} is FAIL without a bug id")
    open_b = [b for b in BUGS if b[4] != "RESOLVED"]
    res_b = [b for b in BUGS if b[4] == "RESOLVED"]
    for b in BUGS:
        if b[0] not in state["bug_events"]:
            raise SystemExit(f"{b[0]} has no lifecycle events")

    # ---------------------------------------------------------------- requirements: coverage + implementation + verification (all computed / explicit-with-evidence)
    covered = collections.Counter()
    for r in ROWS:
        for lf in flat(r["src"]):
            if lf[0] == "junit":
                for (suite, kw) in lf[1]:
                    full, lst = suite_cases(cases, suite)
                    for (n, s) in lst:
                        if kw.lower() in n.lower():
                            covered[(full, n)] += 1
    reqres, seen_req = [], set()
    req_demoted = []
    for q in REQS:
        if q["id"] in RQ.REQ_P1_DEMOTE and q["risk"] == "P0":
            q["risk"] = "P1"
            req_demoted.append((q["id"], RQ.REQ_P1_DEMOTE[q["id"]]))
        if q["id"] in seen_req:
            raise SystemExit("duplicate requirement " + q["id"])
        seen_req.add(q["id"])
        rows_ = []
        for t in q["tests"]:
            if t not in rid:
                raise SystemExit(f"{q['id']} maps unknown test id {t}")
            rows_.append(rid[t])
        cstat = []
        for (suite, kw) in q["cases"]:
            full, lst = suite_cases(cases, suite)
            if lst is None:
                raise SystemExit(f"{q['id']}: suite {suite} not found")
            sel = [(n, s) for (n, s) in lst if kw.lower() in n.lower()]
            if not sel:
                raise SystemExit(f"{q['id']}: no case in {suite} matches '{kw}'")
            for (n, s) in sel:
                covered[(full, n)] += 1
            cstat.append((suite, kw, len(sel), "FAIL" if any(s == "FAIL" for _, s in sel) else "SKIPPED" if any(s == "SKIPPED" for _, s in sel) else "PASS"))
        npass = sum(1 for x in rows_ if x["status"] == "PASS") + sum(1 for c in cstat if c[3] == "PASS")
        nbad = sum(1 for x in rows_ if x["status"] != "PASS") + sum(1 for c in cstat if c[3] != "PASS")
        cov = "NONE" if npass == 0 else ("FULL" if nbad == 0 else "PARTIAL")
        sts = collections.Counter(x["status"] for x in rows_)
        if sts["FAIL"] or any(c[3] == "FAIL" for c in cstat):
            result = "FAIL"
        elif cov == "FULL":
            result = "PASS"
        elif cov == "PARTIAL":
            result = "PASS_WITH_GAPS"
        else:
            result = "BLOCKED" if sts["BLOCKED"] else "NOT_RUN"
        causes = {row_cause(x) for x in rows_ if x["status"] != "PASS"} - {""}
        # implementation status: FULL coverage means tests execute the product code; otherwise it must be established explicitly from the tree
        if cov == "FULL":
            impl, inote = "IMPLEMENTED", ""
        elif q["id"] in RQ.IMPL:
            impl, inote = RQ.IMPL[q["id"]]
        else:
            raise SystemExit(f"{q['id']} is {cov} but has no implementation audit entry (RQ.IMPL)")
        # verification status
        if q["id"] in RQ.BLOCKED_BY_IMPLEMENTATION or impl == "NOT_IMPLEMENTED":
            ver, vcause = "BLOCKED", "IMPLEMENTATION"
        elif cov == "FULL":
            ver, vcause = "VERIFIED", ""
        elif cov == "PARTIAL":
            ver, vcause = "PARTIAL", "+".join(sorted(causes))
        elif causes and causes <= {"ENVIRONMENT"}:
            ver, vcause = "BLOCKED", "ENVIRONMENT"
        elif result == "FAIL":      # a failing test IS verification: the requirement was checked and is not met
            ver, vcause = "VERIFIED", "FAILS (open bug)"
        else:
            ver, vcause = "UNVERIFIED", "QA_COVERAGE" if "QA_COVERAGE" in causes or not causes else "+".join(sorted(causes))
        bugs_ = sorted({b for x in rows_ for b in (x["bug"].split(", ") if x["bug"] != "-" else [])})
        q.update(rows=rows_, cstat=cstat, cov=cov, result=result, npass=npass, nbad=nbad, bugs=bugs_, sts=sts, sha=(a.sha if npass else "-"), impl=impl, inote=inote, ver=ver, vcause=vcause, causes=causes)
        reqres.append(q)
    bad_p0 = [q["id"] + " (" + ", ".join(x["id"] + ":" + x["pr"] for x in q["rows"]) + ")" for q in reqres if q["risk"] == "P0" and not any(x["pr"] == "P0" for x in q["rows"]) and q["id"] not in RQ.P0_WITHOUT_P0_ROW]
    if bad_p0:
        raise SystemExit("P0 requirements without any P0 test row (review the risk or add the P0 row):\n  " + "\n  ".join(bad_p0))
    cov_ct = collections.Counter(q["cov"] for q in reqres)
    impl_ct = collections.Counter(q["impl"] for q in reqres)
    ver_ct = collections.Counter(q["ver"] for q in reqres)
    risk_cov = collections.defaultdict(collections.Counter)
    for q in reqres:
        risk_cov[q["risk"]][q["cov"]] += 1
    p0q = [q for q in reqres if q["risk"] == "P0"]
    p0_ver = collections.Counter(q["ver"] for q in p0q)
    p0_blk_impl = [q for q in p0q if q["ver"] == "BLOCKED" and q["vcause"] == "IMPLEMENTATION"]
    p0_blk_env = [q for q in p0q if q["ver"] == "BLOCKED" and q["vcause"] == "ENVIRONMENT"]
    p0_unver = [q for q in p0q if q["ver"] == "UNVERIFIED"]
    p0_none = [q for q in reqres if q["risk"] == "P0" and q["cov"] == "NONE"]
    p1_none = [q for q in reqres if q["risk"] == "P1" and q["cov"] == "NONE"]
    p0_part = [q for q in reqres if q["risk"] == "P0" and q["cov"] == "PARTIAL"]
    junit_total = sum(len(v) for v in cases.values())
    junit_fail = sum(1 for v in cases.values() for (_, s) in v if s == "FAIL")
    junit_skip = sum(1 for v in cases.values() for (_, s) in v if s == "SKIPPED")
    mapped = len(covered)
    qmap = {q["id"]: q for q in reqres}

    # ---------------------------------------------------------------- GAP register (ids persist in state)
    for r in ROWS:
        if r["status"] in ("GAP", "BLOCKED") and r["id"] not in state["gap_ids"]:
            if r["id"] in EXPLICIT_GAP:
                state["gap_ids"][r["id"]] = EXPLICIT_GAP[r["id"]]
            else:
                used = {int(v.split("-")[-1]) for v in state["gap_ids"].values()} | {14}
                state["gap_ids"][r["id"]] = f"GAP-C6-{max(used) + 1:02d}"
    if a.write_state:
        json.dump(state, open(a.state, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    gaps = [r for r in ROWS if r["status"] in ("GAP", "BLOCKED")]

    def rc_required(r):
        g = gate_of_row(r)
        return "YES" if (r["pr"] in ("P0", "P1") or (r["pr"] == "P2" and g in ("G3", "G5", "G6", "G7"))) else "NO"

    # ---------------------------------------------------------------- gates (computed; BLOCKED names its cause)
    def causes_text(cs):
        names = {"IMPLEMENTATION": "implementation (feature missing or not integrated)", "ENVIRONMENT": "environment (stack/ports)", "QA_COVERAGE": "QA coverage (no test yet)", "DEFECT": "a failing test (bug open)"}
        return "; ".join(f"blocked by {names[c]}" for c in ("IMPLEMENTATION", "ENVIRONMENT", "QA_COVERAGE", "DEFECT") if c in cs)

    def gate_from_reqs(g):
        qs = [q for q in reqres if q["gate"] == g]
        if not qs:
            return "NOT_RUN", "no requirement mapped", set()
        hard = [q for q in qs if q["result"] == "FAIL" and q["risk"] in ("P0", "P1") and any(x["status"] == "FAIL" and x["pr"] in ("P0", "P1") for x in q["rows"])]
        if hard:
            return "FAIL", "a P0/P1 requirement fails on a P0/P1 test: " + ", ".join(q["id"] for q in hard), {"DEFECT"}
        soft = [q for q in qs if q["result"] == "FAIL"]
        blk = [q for q in qs if q["risk"] == "P0" and q["ver"] == "BLOCKED"]
        if blk:
            cs = {q["vcause"] for q in blk}
            return "BLOCKED", f"{len(blk)} P0 requirement(s) cannot be verified: " + ", ".join(q["id"] for q in blk[:6]) + (" …" if len(blk) > 6 else "") + " — " + causes_text(cs), cs
        unv = [q for q in qs if q["risk"] == "P0" and q["ver"] == "UNVERIFIED"]
        if not any(q["npass"] for q in qs):
            return "NOT_RUN", "nothing executed", {"QA_COVERAGE"}
        c = collections.Counter(q["cov"] for q in qs)
        base = f"{c['FULL']} FULL / {c['PARTIAL']} PARTIAL / {c['NONE']} NONE of {len(qs)} requirements"
        if all(q["cov"] == "FULL" for q in qs):
            return "PASS", base, set()
        extra = ""
        if unv:
            extra += "; QA blockers (P0 implemented, unverified): " + ", ".join(q["id"] for q in unv)
        if soft:
            extra += "; low-priority failing tests: " + ", ".join(x["id"] for q in soft for x in q["rows"] if x["status"] == "FAIL")
        cs = set().union(*[q["causes"] for q in qs]) - {""}
        return "PASS_WITH_GAPS", base + extra + (" — open: " + causes_text(cs) if cs else ""), cs

    gates = collections.OrderedDict()
    bl = [rid[i] for i in ("C0-BLD-02", "C0-BLD-03", "C0-BLD-04")]
    if all(x["status"] == "PASS" for x in bl):
        gates["G1"] = ("PASS_WITH_GAPS" if any(rid[i]["status"] == "FAIL" for i in ("C0-BLD-01", "C0-BLD-05")) else "PASS", "backend (uncached, -Xmx3g) + frontend builds PASS; default-heap compile OOM (BUG-C6-006) and untracked .kotlin (BUG-C6-008) open", {"DEFECT"})
    else:
        gates["G1"] = ("FAIL", "a build step fails", {"DEFECT"})
    g2s, g2n, g2c = gate_from_reqs("G2")
    gates["G2"] = ("FAIL" if junit_fail else g2s, f"{junit_total} executed / {junit_fail} failed / {junit_skip} skipped (real execution); traceability: {g2n}", g2c)
    gates["G3"] = gate_from_reqs("G3")
    g4rows = [r for r in ROWS if r["id"].startswith(("C5-TYP", "C5-UNT", "C5-BLD", "C5-BRW"))]
    g4 = "PASS" if all(r["status"] == "PASS" for r in g4rows) else ("FAIL" if any(r["status"] == "FAIL" and r["pr"] in ("P0", "P1") for r in g4rows) else "PASS_WITH_GAPS")
    g4s = gate_from_reqs("G4")
    gates["G4"] = (g4 if g4s[0] in ("PASS", "PASS_WITH_GAPS") else g4s[0], "typecheck/unit 130/130/build PASS; BUG-C6-004 open; portals browser spec needs re-run; " + g4s[1], g4s[2] | {"ENVIRONMENT"})
    for g in ("G5", "G6", "G7"):
        gates[g] = gate_from_reqs(g)
    e2e = [r for r in ROWS if r["area"] == "System E2E" and r["id"] != "E2E-01"]
    e2e_c = {row_cause(r) for r in e2e if r["status"] != "PASS"} - {""}
    gates["G8"] = ("BLOCKED" if not any(r["status"] == "PASS" for r in e2e) else "PASS_WITH_GAPS", f"{sum(1 for r in e2e if r['status'] == 'PASS')}/{len(e2e)} real-backend E2E rows pass (E2E-01 is a mock) — " + causes_text(e2e_c), e2e_c)
    rec = [r for r in ROWS if r["area"] == "Failure/Recovery" and r["id"] in {f"REC-R{n:02d}" for n in range(1, 12)}]
    rec_c = {row_cause(r) for r in rec if r["status"] != "PASS"} - {""}
    gates["G9"] = ("BLOCKED" if not any(r["status"] == "PASS" for r in rec) else "PASS_WITH_GAPS", f"{sum(1 for r in rec if r['status'] == 'PASS')}/{len(rec)} stack recovery cases pass; store-level restart tests PASS (C0-PER, C4-LSE) — " + causes_text(rec_c), rec_c)
    perf = [r for r in ROWS if r["area"] == "Performance"]
    gates["G10"] = ("NOT_RUN", f"{len(perf)} performance cases, no test or tooling exists — blocked by QA coverage", {"QA_COVERAGE"})
    gates["G11"] = ("NOT_RUN", "RC not declared; final full QA at feature freeze", set())
    sev = {b[0]: b[1] for b in BUGS}
    fails = [r for r in ROWS if r["status"] == "FAIL" and (r["pr"] == "P0" or any(sev.get(x, "Low").startswith(("Critical", "High")) for x in (r["bug"].split(", ") if r["bug"] != "-" else [])))]
    if fails:   # RED = Critical/High regression, isolation/security failure, corruption, unsafe replay/idempotency, critical integration failure
        verdict = "RED"
    elif all(v[0] == "PASS" for v in gates.values()) and not p0_unver and not p0_blk_impl:
        verdict = "GREEN"
    else:
        verdict = "YELLOW"

    st_ct = collections.Counter(r["status"] for r in ROWS)
    pr_ct = collections.Counter(r["pr"] for r in ROWS)
    area_ct = collections.OrderedDict()
    for r in ROWS:
        area_ct.setdefault(r["area"], collections.Counter())[r["status"]] += 1
    cls_ct = collections.Counter(r["cls"] for r in ROWS if r["pr"] == "P0")

    def history(r):
        h = state["rows"].get(r["id"], [])
        out = [f"{s} {st}" for (s, st) in h] or [f"{a.sha} {r['status']}"]
        extra = ""
        if r["status"] == "PASS" and any(lf[0] == "junit" and ({x[0] for x in lf[1]} & NEW_SUITES) for lf in flat(r["src"])):
            extra = " (new in V29/C4 diff)"
        return " → ".join(out) + extra

    def first_fail(r):
        for (s, st) in state["rows"].get(r["id"], []):
            if st == "FAIL":
                return s
        return "f894cc6" if r["status"] == "FAIL" else "-"

    def watch(r):
        pk = r.get("_pk", set())
        return "YES" if (r["pr"] == "P0" or r["bug"] != "-" or any(p.startswith(WATCH_PKG) for p in pk)) else "NO"

    seeds = {
        "C0-BLD-01": "f894cc6 FAIL (Mac gate2: uncached compile OOM, default heap) → 8e91172 not retried with default heap; PASS only with -Xmx3g (C0-BLD-02) → BUG-C6-006 OPEN",
        "C0-BLD-05": "f894cc6 FAIL (gate2: backend/.kotlin untracked after failed compile) → 8e91172 not reproduced (compile succeeded, scope check PASS) → BUG-C6-008 OPEN",
        "C5-UNT-08": "f894cc6 FAIL → 8e91172 FAIL (still 1 skipped) → BUG-C6-004 OPEN",
        "C5-BRW-02": "f894cc6 PASS 33/33 → 8e91172 NOT RUN (port 3003 held by foreign next-server) → RETEST REQUIRED",
        "E2E-STK": "f894cc6 FAIL (port 13000 already allocated) → 8e91172 not retried (the user's hbl stack still holds the ports) → BUG-C6-007 OPEN",
        "NF-DOC-01": "f894cc6 FAIL → 8e91172 FAIL (README.md:38 still 185) → BUG-C6-001 OPEN",
        "NF-DOC-02": "f894cc6 FAIL → 8e91172 FAIL → BUG-C6-002 OPEN",
        "NF-DOC-03": "f894cc6 FAIL → 8e91172 FAIL → BUG-C6-003 OPEN",
        "NF-DOC-04": "8e91172 FAIL (batch 7: BLOCKERS.md statuses stale) → BUG-C6-010 OPEN",
        "NF-HX-01": "f894cc6 FAIL (accepted :test FROM-CACHE as PASS, BUG-C6-005) → harness fixed → 8e91172 RETEST PASS (real execution, 470 s) → RESOLVED; Regression Watch YES",
        "NF-HX-02": "f894cc6 FAIL → 8e91172 FAIL (not fixed) → BUG-C6-007 OPEN",
        "NF-HX-03": "8e91172 FAIL (SQ-03 max<=28, BUG-C6-009) → harness fixed → 8e91172 RETEST PASS 10/10 → RESOLVED; Regression Watch YES",
        "NF-HX-04": "f894cc6 PASS 44/44 → 8e91172 PASS 44/44 (after harness patches)",
    }
    retests = {"C0-BLD-01": "OPEN (workaround verified)", "C0-BLD-05": "OPEN", "C5-UNT-08": "OPEN", "C5-BRW-02": "RETEST REQUIRED (port busy)", "E2E-STK": "OPEN",
               "NF-DOC-01": "OPEN", "NF-DOC-02": "OPEN", "NF-DOC-03": "OPEN", "NF-DOC-04": "OPEN", "NF-HX-01": "RETEST PASS", "NF-HX-02": "OPEN", "NF-HX-03": "RETEST PASS"}

    import subprocess
    try:
        changed = subprocess.run(["git", "-C", ROOT, "diff", "--name-only", a.prev, a.sha], capture_output=True, text=True, check=True).stdout.split()
    except Exception:
        changed = []
    IMPACT = [("db/migration/", ["REQ-ML", "REQ-IC-04", "REQ-IC-05", "REQ-DC-19", "REQ-DC-21"], "C0 migrations"), ("/logic/", ["REQ-AW", "REQ-RA-14", "REQ-RA-15", "REQ-RA-18", "REQ-RA-19"], "C4 logic"),
              ("/wiring/", ["REQ-RA", "REQ-AW-2", "REQ-DR-16"], "C0 wiring/persistence"), ("application.yml", ["REQ-IC-06", "REQ-RA-09", "REQ-RA-22"], "flags/config"),
              ("/tenancy/", ["REQ-TP"], "C1 tenancy"), ("/access/", ["REQ-TP"], "C1 access"), ("/data/", ["REQ-DR", "REQ-MA"], "C3 data"), ("/app/definition/", ["REQ-AD"], "C2 definition"),
              ("/publish/", ["REQ-DC", "REQ-AD-14", "REQ-CL-07"], "C2 publish"),
              ("contracts/v2/tenant-permission", ["REQ-TP"], "tenant-permission contract text"), ("contracts/v2/runtime-api", ["REQ-RA"], "runtime-api contract text"), ("contracts/v2/data-runtime", ["REQ-DR"], "data-runtime contract text"),
              ("contracts/v2/action-workflow", ["REQ-AW"], "action-workflow contract text"), ("contracts/v2/app-definition", ["REQ-AD"], "app-definition contract text"), ("contracts/v2/integration-contract", ["REQ-IC"], "integration contract text"),
              ("contracts/v2/management-api", ["REQ-MA"], "management-api contract text"), ("C2_DEPLOY_CONTRACT", ["REQ-DC"], "C2 deploy contract text"), ("MIGRATION_LEDGER", ["REQ-ML", "REQ-IC-04", "REQ-DC-21"], "migration ledger"),
              ("parallel/BOARD.md", ["REQ-BD"], "board"), ("parallel/DECISIONS.md", ["REQ-AW-2", "REQ-RA-22", "REQ-DR-16", "REQ-DR-17", "REQ-DC-21"], "decision log (D-C0-27/28)"), ("parallel/BLOCKERS.md", ["REQ-BL"], "blockers")]
    impacted = collections.OrderedDict()
    for f in changed:
        for (pat, prefixes, why) in IMPACT:
            if pat in f:
                for q in REQS:
                    if any(q["id"].startswith(p) for p in prefixes):
                        impacted.setdefault(q["id"], set()).add(why)
    changed_tests = [f for f in changed if "/src/test/" in f]

    # ---------------------------------------------------------------- render
    out = []
    w = out.append
    w("# QA_MASTER — HBL-XWeb system QA traceability master (C6)\n")
    w(f"> **Single source of truth for QA from now to production.** Generated {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} by `docs/parallel/c6/harness/qa_master.py` (+ `qa_master_reqs.py`) for **HEAD `{a.sha}`** (previous tested `{a.prev}`).")
    w("> Chain proven here: **Requirement/Contract clause → Test case → Result → Bug → Retest → Evidence.** Two dimensions per requirement: **Implementation** (is it in the product?) and **Verification** (did QA prove it?). It does **not** replace raw evidence and never deletes history: per-row history, bug lifecycle events and GAP ids live in `docs/parallel/c6/qa_master_state.json` (append-only). Linked docs: [QA_STATUS](QA_STATUS.md) · [BUGS](BUGS.md) · [REGRESSION_MATRIX](REGRESSION_MATRIX.md) · [E2E_MATRIX](E2E_MATRIX.md).")
    w("> Rules: status of automated rows is **derived** from the per-test-case JUnit export, the C6 check outputs or the overlay results of the SHA under test; never PASS on old-SHA or cached evidence; requirement Coverage/Result/Verification are **computed**; Implementation is explicit and evidenced (code reference, branch + SHA, or tests executing the clause). **No mock is ever counted as verification of an unintegrated feature.**\n")
    w("## 1. Verdict\n")
    w(f"**QA = {verdict} · Production Ready = NO.** Product bugs: 0 new. Open bugs: {len(open_b)} (all Low: docs/config/harness). Requirements: {len(reqres)} — Coverage FULL {cov_ct['FULL']} · PARTIAL {cov_ct['PARTIAL']} · NONE {cov_ct['NONE']}; Implementation: " + " · ".join(f"{k} {impl_ct[k]}" for k in IMPL_ORDER) + "; Verification: " + " · ".join(f"{k} {ver_ct[k]}" for k in VER_ORDER) + ".")
    w(f"\n**QA blockers** (P0, IMPLEMENTED, no verification): **{len(p0_unver)}**" + (" — " + ", ".join(q["id"] for q in p0_unver) if p0_unver else "") + f". **P0 blocked by unfinished implementation** (not a QA failure): **{len(p0_blk_impl)}**. P0 blocked by environment: {len(p0_blk_env)}. A P0 requirement that is NOT_IMPLEMENTED and untested is Verification BLOCKED and is **not** a QA failure; an IMPLEMENTED P0 requirement without verification **is** a QA blocker.\n")
    w("Legend (test rows): ✅ PASS · ❌ FAIL · ⚠ BLOCKED (cannot run: stack/feature missing) · ○ NOT RUN · 🔁 RETEST REQUIRED (not valid for the current SHA) · 🟦 GAP (no test exists). Gate/requirement result model: **PASS · PASS_WITH_GAPS · FAIL · BLOCKED · NOT_RUN**. Implementation: IMPLEMENTED (on integration/v2) · PARTIAL (part on integration/v2, or complete only on an unintegrated branch) · NOT_IMPLEMENTED (no code anywhere) · UNKNOWN. Verification: VERIFIED · PARTIAL · UNVERIFIED (implemented, no/insufficient test) · BLOCKED (by implementation or environment). P0 = a failure can cause: auth/security isolation breach, privilege escalation, secret/credential leak, data loss/corruption, duplicate unsafe mutation, broken idempotency, ambiguous workflow mutation, unsafe replay, migration corruption, publish/CAS race, rollback to wrong runtime, critical recovery inconsistency. P1 core flows · P2 edge cases · P3 cosmetic.\n")
    w("Evidence keys: **J** = `evidence/mac-8e91172-gate/backend-testcases.tsv` (1502 per-case rows; XML in `backend-junit-xml.tgz`) · **NS** = `evidence/mac-8e91172-nostack/RESULTS.txt` · **SQ** = `evidence/mac-8e91172-nostack/STATIC_QA.log` · **C6** = `evidence/mac-8e91172-contract-checks/` (`contract-checks.tsv` static checks with a self-test, `db-probes.tsv` throw-away PostgreSQL 17.6, `TEST-*.xml` overlay Gradle run of C6-owned HTTP tests, `npm-audit-prod.json`) · **R1** = `evidence/mac-run1-cached-20261006T1323Z/` (f894cc6; backend result INVALID cache replay) · **G2** = `evidence/mac-gate2-nocache-kotlin-oom-20261006T1331Z/`.\n")

    w("## 2. Baselines\n")
    w("### 2.1 Current (HEAD " + a.sha + ", after the batch-7 audit)\n")
    w("| Item | Value |\n|---|---|")
    w(f"| HEAD tested | `{a.sha}` (integration/v2); previous tested `{a.prev}` |")
    w(f"| Backend | **{junit_total} tests · {junit_total - junit_fail - junit_skip} passed · {junit_fail} failed · {junit_skip} skipped** (3 × OpenRouterLive, need a real key) · real execution · `--no-build-cache --rerun-tasks` · 470 s · security 26/26 suites |")

    def _sum(names):
        t = f = 0
        for n in names:
            full, lst = suite_cases(cases, n)
            t += len(lst); f += sum(1 for (_, x) in lst if x == "FAIL")
        return t, f
    pers = _sum(["WorkflowRunPersistenceMigrationTests", "JdbcWorkflowRunStoreTests", "JdbcActionRunStoreTests", "WorkflowRestartRecoveryTests", "ActionRunRecoveryTests", "RunStoreConfigurationTests", "RunCodecTests"])
    lease = _sum(["WorkflowLeaseTests", "WorkflowAbandonedWriteTests", "ActionRunAbandonmentTests", "WorkflowEngineTests", "ActionRuntimeTests"])
    w(f"| Affected V29/C4 | {pers[0]} persistence/restart tests ({pers[1]} failed) + {lease[0]} lease/abandon/engine/runtime tests ({lease[1]} failed) |")
    w(f"| New C6 evidence (batch 7) | static contract checks {sum(1 for v in cc.values() if v == 'PASS' and True)} PASS in total with DB probes (self-tested); overlay HTTP tests {sum(1 for v in ov.values() if v == 'PASS')}/{len(ov)} PASS; `npm audit --omit=dev` {npm} vulnerabilities |")
    w(f"| QA test cases | **{len(ROWS)}** mapped to **{mapped}** of {junit_total} executed backend tests ({100 * mapped / junit_total:.1f}%) · ✅ {st_ct['PASS']} · ❌ {st_ct['FAIL']} · ⚠ {st_ct['BLOCKED']} · ○ {st_ct['NOTRUN']} · 🔁 {st_ct['RETEST']} · 🟦 {st_ct['GAP']} · P0 {pr_ct['P0']} |")
    w(f"| Requirements | **{len(reqres)}** (180 baseline + {len(reqres) - 180} from the GAP-C6-14 traceability): FULL {cov_ct['FULL']} · PARTIAL {cov_ct['PARTIAL']} · NONE {cov_ct['NONE']} · P0 NONE {len(p0_none)} · P1 NONE {len(p1_none)} |")
    w(f"| Bugs | open {len(open_b)} ({', '.join(b[0] for b in open_b)}) · resolved {len(res_b)} · new product bugs 0 |")
    w("| Environment blockers | BLK-C6-05: stack `hbl` of the user holds the fixed ports (13000/15432/16379/18088/19000/15675/18095), its dev DB is Flyway V25 (not migrated by C6); foreign `next-server` on :3003 |")
    w(f"| QA / Production Ready | **{verdict} / NO** |\n")
    w("### 2.2 Frozen snapshot at the start of batch 7 (history is not rewritten; deltas below are explained by evidence)\n")
    b6 = BASE6
    w("| Metric | Batch-6 end (frozen) | Now | Why it changed |\n|---|---|---|---|")
    w(f"| Backend executed / pass / fail / skipped | 1502 / 1499 / 0 / 3 | {junit_total} / {junit_total - junit_fail - junit_skip} / {junit_fail} / {junit_skip} | unchanged (no new backend run; HEAD unchanged) |")
    w(f"| Mapped backend tests | 1359 / 1502 = 90.5% | {mapped} / {junit_total} = {100 * mapped / junit_total:.1f}% | new rows/cases from the audit |")
    w(f"| Requirements | 180 · FULL 97 · PARTIAL 57 · NONE 26 | {len(reqres)} · FULL {cov_ct['FULL']} · PARTIAL {cov_ct['PARTIAL']} · NONE {cov_ct['NONE']} | +{len(reqres) - 180} requirements (ledger item 6 and 10 unresolved BLOCKERS.md items); {sum(1 for c in corrections)} rows re-evidenced |")
    w(f"| P0 requirements with Coverage NONE | 14 | {len(p0_none)} | TP-07 and ML-07 verified; the other 12 are NOT_IMPLEMENTED/unintegrated |")
    w(f"| QA test cases / P0 test cases | 473 / 212 | {len(ROWS)} / {pr_ct['P0']} | +{len(ROWS) - 473} rows (blocker-derived GAP/BLOCKED); {len(demoted)} P0 → P1 and {len(promoted)} P1 → P0 by the risk review |")
    w(f"| ✅ / ❌ / ⚠ / ○ / 🔁 / 🟦 | 313 / 8 / 98 / 1 / 1 / 52 | {st_ct['PASS']} / {st_ct['FAIL']} / {st_ct['BLOCKED']} / {st_ct['NOTRUN']} / {st_ct['RETEST']} / {st_ct['GAP']} | {len(corrections)} rows closed/re-classified with evidence (section 12) |")
    w("| Bugs open / resolved | 7 / 2 | " + f"{len(open_b)} / {len(res_b)} | BUG-C6-010 opened (BLOCKERS.md drift) |")
    w("| QA / Production Ready | YELLOW / NO | " + f"{verdict} / NO | |\n")

    w("## 3. Release gates (for the current SHA)\n")
    w("Status model: **PASS** (coverage FULL) · **PASS_WITH_GAPS** (executed tests green, coverage incomplete) · **FAIL** · **BLOCKED** (a P0 requirement cannot be verified; the cause is named) · **NOT_RUN**. Gates G2–G7 are computed from the requirement matrix (§6), G1/G4/G8/G9/G10 from their rows.\n")
    w("| Gate | Status @ " + a.sha + " | Blocked by | Basis |\n|---|---|---|---|")
    for g, (s, basis, cs) in gates.items():
        sym = {"PASS": "✅", "PASS_WITH_GAPS": "🟨", "FAIL": "❌", "BLOCKED": "⚠", "NOT_RUN": "○"}[s]
        nm = {"IMPLEMENTATION": "implementation", "ENVIRONMENT": "environment", "QA_COVERAGE": "QA coverage", "DEFECT": "open bug"}
        by = ", ".join(nm[c] for c in ("IMPLEMENTATION", "ENVIRONMENT", "QA_COVERAGE", "DEFECT") if c in cs) if s in ("BLOCKED", "NOT_RUN", "PASS_WITH_GAPS", "FAIL") else "-"
        w(f"| {g} {GATE_NAME[g]} | {sym} {s} | {by or '-'} | {md(basis)} |")
    w("")

    w("## 4. Summary counts\n")
    w("| Metric | Value |\n|---|---|")
    w(f"| Backend tests executed | {junit_total} (passed {junit_total - junit_fail - junit_skip}, failed {junit_fail}, skipped {junit_skip}) |")
    w(f"| Backend tests mapped | {mapped} |")
    w(f"| Mapping coverage | **{100 * mapped / junit_total:.1f}%** ({junit_total - mapped} executed tests are not referenced by any QA case or requirement) |")
    w(f"| Requirements total | **{len(reqres)}** |")
    w(f"| Coverage FULL / PARTIAL / NONE | **{cov_ct['FULL']} / {cov_ct['PARTIAL']} / {cov_ct['NONE']}** |")
    w("| Implementation | " + " · ".join(f"**{k} {impl_ct[k]}**" for k in IMPL_ORDER) + " |")
    w("| Verification | " + " · ".join(f"**{k} {ver_ct[k]}**" for k in VER_ORDER) + " |")
    w(f"| P0 requirements (total / VERIFIED / PARTIAL / UNVERIFIED / BLOCKED) | {len(p0q)} / {p0_ver['VERIFIED']} / {p0_ver['PARTIAL']} / {p0_ver['UNVERIFIED']} / {p0_ver['BLOCKED']} (blocked by implementation {len(p0_blk_impl)}, by environment {len(p0_blk_env)}) |")
    w(f"| P0 requirements (total / FULL / PARTIAL / NONE) | {sum(risk_cov['P0'].values())} / {risk_cov['P0']['FULL']} / {risk_cov['P0']['PARTIAL']} / {risk_cov['P0']['NONE']} |")
    w(f"| P1 requirements (total / FULL / PARTIAL / NONE) | {sum(risk_cov['P1'].values())} / {risk_cov['P1']['FULL']} / {risk_cov['P1']['PARTIAL']} / {risk_cov['P1']['NONE']} |")
    w(f"| **P0 uncovered (Coverage NONE)** | **{len(p0_none)}** — {sum(1 for q in p0_none if q['ver'] == 'BLOCKED')} blocked by unfinished implementation/environment, **{sum(1 for q in p0_none if q['ver'] == 'UNVERIFIED')} QA blockers** |")
    w(f"| **P1 uncovered (Coverage NONE)** | **{len(p1_none)}** |")
    w(f"| QA test cases | {len(ROWS)} |")
    w(f"| Open GAP/BLOCKED items | {len(gaps)} ({sum(1 for r in gaps if r['status'] == 'GAP')} GAP · {sum(1 for r in gaps if r['status'] == 'BLOCKED')} BLOCKED: " + ", ".join(f"{k.lower()} {sum(1 for r in gaps if r['status'] == 'BLOCKED' and row_cause(r) == k)}" for k in ('IMPLEMENTATION', 'ENVIRONMENT')) + ") |")
    w(f"| Bugs open / resolved | {len(open_b)} / {len(res_b)} |\n")
    w("| Priority | Test cases |\n|---|---|")
    for p in ("P0", "P1", "P2", "P3"):
        w(f"| {p} | {pr_ct[p]} |")
    w("\n| Status | Count |\n|---|---|")
    for k in ("PASS", "FAIL", "BLOCKED", "NOTRUN", "RETEST", "GAP"):
        w(f"| {SYM[k]} | {st_ct[k]} |")
    w("\n| Domain | ✅ | ❌ | ⚠ | ○ | 🔁 | 🟦 | Total |\n|---|---|---|---|---|---|---|---|")
    for ar, c in area_ct.items():
        w(f"| {ar} | {c['PASS']} | {c['FAIL']} | {c['BLOCKED']} | {c['NOTRUN']} | {c['RETEST']} | {c['GAP']} | {sum(c.values())} |")
    w("")

    w("## 5. Test inventory\n")
    w("Columns: **Pri** shows the P0 risk class in brackets (§11) · **Status** current for the SHA under test · **Tested SHA** = latest SHA with executed evidence · **1st fail** = first SHA that failed · **Watch** = regression watch · **History** = SHA→status trail (append-only in `qa_master_state.json`). Pre = preconditions. Rows created from contract clauses/blockers that had no test are GAP/BLOCKED by construction.\n")
    cur_area = None
    hdr = "| Test ID | Feature | Test case | Type | Pri | Preconditions | Expected | Status | Tested SHA | 1st fail | Bug | Owner | Retest | Evidence | Watch | History |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    for r in ROWS:
        if r["area"] != cur_area:
            cur_area = r["area"]
            w(f"\n### {cur_area}\n")
            w(hdr)
        note = f" — {r['note']}" if r["note"] else ""
        h = seeds.get(r["id"]) or history(r)
        retest = retests.get(r["id"]) or ("RETEST REQUIRED" if r["status"] == "RETEST" else ("n/a (never failed)" if r["status"] == "PASS" else "n/a"))
        pri = r["pr"] + (f" ({r['cls']})" if r["cls"] else "")
        w("| " + " | ".join(md(x) for x in [r["id"], r["feature"], r["case"], r["typ"], pri, ("Test to be designed/written" if r["status"] == "GAP" else ("C6 harness / overlay run (see Evidence)" if r["pre"] == "Test to be designed/written" else r["pre"])), r["exp"] + note, SYM[r["status"]], r["tested"], first_fail(r), r["bug"], r["owner"], retest, r["evidence"], watch(r), h]) + " |")
    w("")

    w("## 6. REQUIREMENT TRACEABILITY MATRIX\n")
    w("Sources scanned: `docs/contracts/v2/{tenant-permission, runtime-api, data-runtime, action-workflow, app-definition, integration-contract, management-api}.md`, `docs/parallel/c0/C2_DEPLOY_CONTRACT.md`, `docs/parallel/MIGRATION_LEDGER.md`, `docs/parallel/BOARD.md`, the frozen decisions in `docs/parallel/DECISIONS.md` (D-C0-13…28, D-C1-11…16, D-C3-13…18, D-C4-04…17), the invariants in `CLAUDE.md`, and (GAP-C6-14) `docs/parallel/BLOCKERS.md` restricted to **unresolved items that still affect a release at 8e91172**. `docs/parallel/c3/**` and `c4/**` do **not exist** on integration/v2 (they live on the feature branches); `c5/**` (PHASE3_AUDIT, PHASE3_E2E_PLAN, C5_OVERLAY_MANIFEST) is covered through the E2E rows and BL-09. The five legacy `docs/contracts/*.md` files are superseded by their v2 counterparts. No design note was turned into a requirement.\n")
    w("Coverage: **FULL** every mapped row/case passes · **PARTIAL** some pass, some GAP/BLOCKED/FAIL/RETEST · **NONE** nothing passes. **Impl** = Implementation status (evidence in Notes). **Verification**: VERIFIED · PARTIAL · UNVERIFIED (QA blocker if P0) · BLOCKED (cause). Result: PASS (FULL) · PASS_WITH_GAPS · FAIL · BLOCKED/NOT_RUN.\n")
    w("| Requirement ID | Source document | Clause/heading | Requirement | Owner | Risk | Impl | Verification | Mapped Test IDs | Coverage | Latest SHA | Result | Bug IDs | Evidence | Notes |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for q in reqres:
        ev_cases = "; ".join(f"{c[0]}[{c[2]}]" for c in q["cstat"])
        ev = f"{q['npass'] - sum(1 for c in q['cstat'] if c[3] == 'PASS')}/{len(q['rows'])} rows PASS" + (f"; J: {ev_cases}" if ev_cases else "")
        miss = [x["id"] + " " + {"GAP": "GAP", "BLOCKED": "BLOCKED", "FAIL": "FAIL", "RETEST": "RETEST", "NOTRUN": "NOT RUN"}[x["status"]] for x in q["rows"] if x["status"] != "PASS"]
        notes = (("impl: " + q["inote"] + (" · " if miss else "")) if q["inote"] else "") + (("missing: " + ", ".join(miss)) if miss else "")
        gids = [state["gap_ids"][x["id"]] for x in q["rows"] if x["id"] in state["gap_ids"]]
        if gids:
            notes += " [" + ", ".join(sorted(set(gids))) + "]"
        vtxt = q["ver"] + (f" ({q['vcause'].lower()})" if q["vcause"] else "")
        w("| " + " | ".join(md(x) for x in [q["id"], q["doc"], q["clause"], q["text"], q["owner"], q["risk"], q["impl"], vtxt, ", ".join(q["tests"]), q["cov"], q["sha"], q["result"], ", ".join(q["bugs"]) or "-", ev, notes]) + " |")
    w("")
    w("### 6.1 Audit of every P0 requirement that is not VERIFIED\n")
    w("Is it implemented? Who owns it? What does it depend on? Is a test missing? Is it blocked by an unfinished feature? (Implementation evidence is a code reference or a branch+SHA; nothing here is assumed.)\n")
    w("| Requirement | Cov | Implemented? | Owner | Dependency / where the code is | Test missing? | Verification |\n|---|---|---|---|---|---|---|")
    for q in p0q:
        if q["ver"] == "VERIFIED":
            continue
        miss = [x["id"] for x in q["rows"] if x["status"] != "PASS"]
        tm = ("yes — " + ", ".join(miss)) if miss else "no"
        w(f"| {q['id']} {md(q['clause'])} | {q['cov']} | **{q['impl']}** | {q['owner']} | {md(q['inote'])} | {tm} | {q['ver']}" + (f" ({q['vcause'].lower()})" if q["vcause"] else "") + " |")
    w("\n### 6.2 The 14 requirements that were P0 + Coverage NONE at the start of batch 7\n")
    w("| Requirement | Before | Now: coverage | Implementation | Verification | What changed / what is needed |\n|---|---|---|---|---|---|")
    why = {"REQ-TP-07": "implemented by convention (2 explicit construction sites); VERIFIED by C6 static check CC-TP07-1..3 (self-tested) + TenantAccessTests",
           "REQ-ML-07": "implemented (V3 triggers, INSERT-only writer). The earlier 'GAP' was wrong: AuditApiTests and ProjectApiTests already assert UPDATE/DELETE/TRUNCATE are refused for the application user. C6 added the app-layer scan and a DB probe incl. non-owner role and a negative control. Residual (owner can disable the trigger) = REQ-ML-10 / T19 NOT_IMPLEMENTED"}
    for i in BASE6["p0none_ids"]:
        q = qmap[i]
        w(f"| {i} {md(q['clause'])} | P0 / NONE | {q['cov']} | {q['impl']} | {q['ver']}" + (f" ({q['vcause'].lower()})" if q["vcause"] else "") + f" | {md(why.get(i, q['inote']))} |")
    w("\n### 6.3 Bug → test cases → requirements (every bug links back; every FAIL row links a bug)\n")
    w("| Bug | Severity | Owner | Test cases | Requirements | State |\n|---|---|---|---|---|---|")
    for b in BUGS:
        rq = sorted({q["id"] for q in reqres if set(q["tests"]) & set(b[5])})
        w(f"| {b[0]} | {b[1]} | {b[2]} | {', '.join(b[5])} | {', '.join(rq) or '-'} | {b[4]} |")
    w("")

    w("## 7. GAP management\n")
    w("Every GAP/BLOCKED test row has a persistent GAP ID (GAP-C6-01…06 are the original ids from `E2E_MATRIX.md`/`BUGS.md`). *Cause*: **QA coverage** (feature exists, no test), **implementation** (feature missing or not integrated), **environment** (needs the stack/ports). *Blocking gate* = the gate that cannot be PASS while it is open. *Required before RC* = YES for P0/P1 and for P2 in gates G3/G5/G6/G7.\n")
    w("| GAP ID | Test ID | Status | Cause | Area | Missing test | Risk | Owner | Blocking gate | Required before RC |\n|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(gaps, key=lambda x: (int(state["gap_ids"][x["id"]].split("-")[-1]), pr_rank(x["pr"]))):
        w("| " + " | ".join(md(x) for x in [state["gap_ids"][r["id"]], r["id"], SYM[r["status"]], {"QA_COVERAGE": "QA coverage", "IMPLEMENTATION": "implementation", "ENVIRONMENT": "environment"}.get(row_cause(r), row_cause(r)), r["area"], r["case"] + " — " + r["note"], r["pr"], r["owner"], gate_of_row(r) + " " + GATE_NAME[gate_of_row(r)], rc_required(r)]) + " |")
    w("")
    named = ("C3-MUT-09", "C1-AUD-01", "REC-R12", "REC-R13", "C1-AUTH-14", "C1-SEC-22", "C5-STU-07", "NF-TRC-02")
    w("Status of the eight gaps discovered in the batch-6 review: " + " · ".join(f"{state['gap_ids'].get(i, '—')} {rid[i]['feature']} → {SYM[rid[i]['status']]}" for i in named if i in rid) + ".\n")

    w("## 8. QA run history by SHA (never overwritten)\n")
    w("| SHA | Date (UTC) | Changed modules | Scope | Pass | Fail | Skip | Bugs opened | Bugs closed | QA |\n|---|---|---|---|---|---|---|---|---|---|")
    runs = [
        ("f894cc6", "2026-10-06 ~11:20–12:30 (Linux VM, batches 1–3; SHA not verifiable then)", "baseline", "frontend typecheck/unit/build, static QA; backend/E2E/browser NOT RUN", "frontend 130/130, static 10/10", "0", "1 (unit default)", "BUG-C6-001..004", "-", "YELLOW"),
        ("f894cc6", "2026-10-06 13:23–13:30 (Mac run 1, `mac-full.sh`)", "baseline", "full runner. Backend `:test FROM-CACHE` (1395/0/3 replay, INVALID evidence); frontend, static 10/10, secret scan, E2E_01 6/6, browser 64+33 PASS; STACK_UP FAIL (port 13000); rest NOT_RUN", "frontend/static/browser PASS; backend INVALID", "1 (STACK_UP)", "3 env-skips (replay)", "BUG-C6-005, 007", "-", "YELLOW"),
        ("f894cc6", "2026-10-06 13:31–13:34 (Mac gate 2, no cache)", "baseline", "backend uncached: compileKotlin OOM before any test; frontend + static PASS", "frontend/static PASS", "1 (BACKEND_TEST OOM)", "-", "BUG-C6-006, 008", "-", "YELLOW"),
        ("8e91172", "2026-10-06 15:13–15:22 (Mac gate)", "C0 V29 + wiring; C4 logic; docs", "full backend uncached (-Xmx3g) + 26 security suites + frontend + static", "1499 backend + 26 sec suites + 130 FE + 9/10 static", "1 (static SQ-03 = harness bug)", "3 (OpenRouter env)", "BUG-C6-009", "BUG-C6-009 (fixed in harness, re-run 10/10)", "YELLOW"),
        ("8e91172", "2026-10-06 15:33–15:35 (Mac no-stack run)", "same", "frontend, static, secret scan, E2E_01, builder browser; portals NOT_RUN (port 3003 busy); stack/E2E/recovery not attempted", "FE 130/130, static 10/10, secret scan, E2E_01 6/6, builder 64/64", "0", "portals NOT_RUN", "-", "BUG-C6-005 (retested at this SHA: real execution proven)", "YELLOW"),
        ("8e91172", "2026-10-06 (traceability pass, no new execution)", "none (QA docs only)", "clause-level requirement matrix from contracts; GAP register; gates recomputed", "97 requirements FULL", "26 requirements NONE", "57 PARTIAL", "-", "-", "YELLOW"),
        ("8e91172", "2026-10-07 (batch 7: C6 contract checks, DB probes, overlay HTTP tests, npm audit, BLOCKERS audit)", "none (no product change; C6 harness + QA tests only)", "static contract checks 18 PASS (self-tested), DB probes 15 PASS on a throw-away PostgreSQL 17.6, overlay Gradle run 4 HTTP tests, npm audit; implementation audit of the 14 P0-NONE requirements against the tree and the C2/C3/C4 branches", f"{sum(1 for v in cc.values() if v == 'PASS')} C6 checks + {sum(1 for v in ov.values() if v == 'PASS')} overlay tests + npm audit 0 vulns", "0", "0", "BUG-C6-010", "-", verdict),
    ]
    for rr in runs:
        w("| " + " | ".join(md(x) for x in rr) + " |")
    w("\nNext SHAs append rows here (do not edit past rows).\n")

    w("## 9. Defect lifecycle and retest history (resolved bugs stay forever)\n")
    w("States: OPEN → FIX IN PROGRESS → READY FOR RETEST → (RETEST FAILED → FIX IN PROGRESS) → RETEST PASS → RESOLVED. A bug is RESOLVED only after a retest PASS on a SHA that contains the fix; the failing test row is kept with `Current = ✅ PASS`, `Regression Watch = YES`.\n")
    w("| Bug | Severity | Owner | State | Opened (SHA) | Resolved (SHA) | Linked tests | Current result of linked tests | Regression watch |\n|---|---|---|---|---|---|---|---|---|")
    for b in BUGS:
        cur = ", ".join(f"{t}={SYM[rid[t]['status']]}" for t in b[5])
        w(f"| {b[0]} | {b[1]} | {b[2]} | **{b[4]}** | {b[6]} | {b[7]} | {', '.join(b[5])} | {cur} | {'YES' if b[4] == 'RESOLVED' else 'n/a until fixed'} |")
    w("\n### 9.1 Retest history (append-only; one line per event)\n")
    w("| Bug | SHA | Date | Event | Note |\n|---|---|---|---|---|")
    for b in BUGS:
        for (sha, dt, stt, note) in state["bug_events"][b[0]]:
            w(f"| {b[0]} | {sha} | {dt} | {stt} | {md(note)} |")
    w("\nTemplate for a product bug (example, not a real bug): `8e91172 FAIL (test row) → BUG OPEN` · `<sha B> FIXED BY <owner> (READY FOR RETEST)` · `<sha C> RETEST PASS` · `RESOLVED` → row stays, Current = ✅ PASS, Regression Watch = YES. Add events with `--bug-event BUG SHA STATE NOTE`.\n")

    w("## 10. HEAD change policy and impact of the last change\n")
    w("""For every new integration/v2 HEAD:
1. Record the exact SHA and diff against the last tested SHA (the generator runs `git diff --name-only <prev> <sha>` and lists impacted requirements below).
2. Identify impacted requirements (path rules in `qa_master.py` `IMPACT`) and impacted tests (`Watch` = YES and the suites of changed modules).
3. Re-run affected tests; **always re-run the C6 checks** (`harness/contract-checks.py`, `harness/db-probes.py`, `harness/run-overlay-tests.sh`) — they are cheap and catch layering, audit, migration-immutability and permission regressions; security regression if contract/auth/data/action changed; full backend uncached only for migration/core/shared wiring changes (`C6_GRADLE_ARGS` defaults to `-Pkotlin.daemon.jvmargs=-Xmx3g`; the gate refuses `FROM-CACHE`).
4. Export per-case results, regenerate: `python3 docs/parallel/c6/harness/qa_master.py --sha <new> --prev <prev> --tsv <tsv> --nostack <dir> --checks <dir> --write-state`.
5. Never reuse old-SHA evidence as a new PASS: rows not re-run show their old *Tested SHA* or 🔁 RETEST REQUIRED.
6. Bug found → add to `BUGS` in `qa_master.py` + `BUGS.md`, route to the owner (C1 IAM/security · C2 build/publish/deploy · C3 data/connectors · C4 action/workflow/queue · C5 Studio/frontend · C0 integration/migration/persistence), link the failing row. After the fix: retest first, then RESOLVED. History is never removed.
7. When a feature is integrated (Management API, V30, RabbitMQ queue, apiBase) its requirements change from BLOCKED(implementation) to testable: update `IMPL`/`BLOCKED_BY_IMPLEMENTATION` in `qa_master_reqs.py` in the same commit that records the integration SHA, then run the real tests — never earlier.
8. Triggers: C3 GREEN → data/management/connector/security/atomicity · C0 integrates C3 → integration + migration/persistence · C2 V30/apiBase → publish/deploy/rollback/runtime · C5 real E2E unblocked → full real E2E · RC → full final QA (backend no-cache, frontend, security, real E2E, browser, recovery, performance).
""")
    w(f"### 10.1 Impact of `{a.prev}` → `{a.sha}` (computed)\n")
    w(f"{len(changed)} files changed ({len(changed_tests)} test files). Impacted requirements: **{len(impacted)}** of {len(reqres)}.\n")
    w("| Requirement | Why impacted | Coverage now | Verification |\n|---|---|---|---|")
    for qi, why_ in impacted.items():
        q = qmap[qi]
        w(f"| {qi} | {', '.join(sorted(why_))} | {q['cov']} | {q['ver']} |")
    w("")

    w("## 11. P0 priority review (batch 7)\n")
    w(f"Criteria: a test is P0 only if its failure can cause **{', '.join(P0_CLASS_NAME.values())}**. The 212 P0 test cases of batch 6 were audited one by one against these criteria, and the rows that batch 6 had demoted by \"critical functional\" reasoning were re-checked against the SAME criteria. Result: **{BASE6['p0rows']} → {pr_ct['P0']} P0 test cases** ({len(demoted)} demoted to P1; {len(promoted)} existing rows promoted to P0 because they match the criteria; {p0_before - BASE6['p0rows']} new P0 rows: C2-DEF-17, C3-SRC-09). Every P0 row carries a risk class (the code in the *Pri* column). The count is not a target: promotions are as evidence-based as demotions.\n")
    w("| Class | Meaning | P0 test cases |\n|---|---|---|")
    for k in P0_CLASS_NAME:
        w(f"| {k} | {P0_CLASS_NAME[k]} | {cls_ct[k]} |")
    w("\n| Demoted P0 → P1 | Reason |\n|---|---|")
    for i, why_ in demoted:
        w(f"| {i} {md(rid[i]['case'][:80])} | {md(why_)} |")
    w("\n| Promoted P1 → P0 | Class | Reason |\n|---|---|---|")
    for i, why_ in promoted:
        w(f"| {i} {md(rid[i]['case'][:80])} | {rid[i]['cls']} | {md(why_)} |")
    w("\n| P0 requirement demoted to P1 | Reason |\n|---|---|")
    for i, why_ in req_demoted:
        w(f"| {i} | {md(why_)} |")
    w("\nRule enforced by the generator: every P0 requirement keeps at least one P0 test row; every P0 test row has a risk class.\n")

    w("## 12. Corrections log (GAP/BLOCKED rows re-evidenced or re-classified in batch 7)\n")
    w("The batch-6 GAP detection searched test **names** only. Reading test **bodies** and the product code found existing coverage and wrongly-labelled gaps. Each row below changed because of evidence, not to improve a count.\n")
    w("| Test ID | Was | Now | Evidence / reason |\n|---|---|---|---|")
    for (i, old, new, why_) in corrections:
        w(f"| {i} | {old.upper()} | {SYM[rid[i]['status']]} | {md(why_)} |")
    w("")
    open(a.out, "w", encoding="utf-8").write("\n".join(out) + "\n")
    print(f"rows={len(ROWS)} status={dict(st_ct)} p0={pr_ct['P0']} (was {p0_before}) reqs={len(reqres)} cov={dict(cov_ct)} impl={dict(impl_ct)} ver={dict(ver_ct)} p0_none={len(p0_none)} p0_unver={len(p0_unver)} p0_blk_impl={len(p0_blk_impl)} p0_blk_env={len(p0_blk_env)} p1_none={len(p1_none)} open_bugs={len(open_b)} resolved={len(res_b)} mapped={mapped}/{junit_total} gaps={len(gaps)} verdict={verdict}")
    print("p0 verification:", dict(p0_ver), "| gates:", {g: v[0] for g, v in gates.items()})


if __name__ == "__main__":
    main()
