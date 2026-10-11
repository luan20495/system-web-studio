# Project status

The single, current statement of what is finished and what is not. Status words are used **only** as defined here: **DONE** (implemented, integrated on `integration/v2`, covered by passing automated tests, no open finding that changes the answer), **PARTIAL** (usable, with a named gap), **BLOCKED** (cannot proceed without a decision or an external input), **DEFERRED** (an explicit decision to postpone; the product is consistent without it). Evidence for a status is the test or document named in the row; open items have an id in `docs/KNOWN_LIMITATIONS.md`.

| Name | Value |
|---|---|
| `CURRENT_INTEGRATION_SHA` | `{{INTEGRATION_SHA}}` (branch `integration/v2`, the source of truth) |
| `CURRENT_FINAL_RC_SHA` | `{{FINAL_RC_SHA}}` |
| `CURRENT_PUBLIC_FRONTEND_SHA` | `{{PUBLIC_FRONTEND_SHA}}` (release `bc5c47f292d0-3f59e8d1`) |
| `CURRENT_PUBLIC_API_SHA` | `{{PUBLIC_API_SHA}}` (release `1006cbf441f6-8985413d`) |
| Written | 2026-10-11 |

Latest evidence on the code of `integration/v2` (details and paths: `docs/QA_FINAL.md`): backend full run, frontend gate, Flyway V1..V33 on a clean database, secret scan, and the golden company acceptance on the release candidate. The public deployment runs older pinned releases and is **not** the subject of this table.

## Status per area

| Area | Status | What is true | Open items |
|---|---|---|---|
| IAM | PARTIAL | Authentication, sessions, CSRF, canonical permission matrix (21 codes), default deny, no role-name authorization, the final hardening (tenant freeze, last-admin locks, credential erasure) are integrated and tested (`docs/SECURITY_AND_PERMISSION.md`). | Four findings rated P1 by C1 are open and unowned (merge-policy bypass, admin-console payload exposure, activation link returned to its creator, sign-up toggle at runtime): `KL-IAM-05`. A code-level existence oracle on `GET /api/v1/projects/{id}`: `KL-IAM-04`. P2 findings: `KL-IAM-06`. |
| Tenant | DONE | Company lifecycle (ACTIVE / SUSPENDED / DELETED) with the frozen-write semantics, tenant derived server-side, provisioning of the first Tenant Admin (`TenantServiceTests`, `HardeningLifecycleTests`, golden steps 01-04). | none that changes the answer |
| Workspace | DONE | Roles WORKSPACE_ADMIN / EDITOR / PUBLISHER / VIEWER, member lifecycle, last-admin rules, isolation between workspaces (`MemberApiTests`, `IsolationApiTests`, golden steps 04, 06, 07). | none |
| Project | DONE | Project = App; Page Schema documents with typed operations, immutable versions, compare-and-swap commits (`SchemaApiTests`, `VersionApiTests`, golden steps 05, 11, 12). | The `GET /projects/{id}` error-code oracle is tracked under IAM. |
| Dynamic Org | DONE | Unit types, tree, employees, memberships, positions, grades; organization data is never authorization; persistence is migration V32 (`docs/DYNAMIC_ORG.md`, `E2E-ORG01` 31/31 recorded on the previous candidate, golden steps 08-09). | The persistence switch `ORGANIZATION_PERSISTENCE_ENABLED` defaults to OFF (`KL-ORG-01`); turning it on is a recorded decision. |
| Data | DONE | Data sources, credentials (write-only, encrypted), TLS verify-full to PostgreSQL targets with the JVM trust store, host:port allow-list, schema discovery, Management API behind `DATA_PLATFORM_ENABLED` (`docs/DATA_RUNTIME.md`). | Only PostgreSQL and REST connectors exist; real external sources were never exercised (`KL-DATA-*`). |
| Query | DONE | Approved SQL query definitions with bound parameters, caps, SqlGuard, TEST and LIVE runs through the Data Gateway (`DataRuntimeLiveApiTests`, golden step 14). | The public route shares the gateway result cache (`KL-PUB-*`). |
| Mutation | DONE | Writable PostgreSQL connector (INSERT / UPDATE / DELETE with bound values, declared key, `maxAffectedRows`), writes only through actions, unknown outcome never retried (`DataWritableE2ETests`, `RecordKeyActionE2ETests`, golden steps 15 and 20). | A mutation cannot write NULL; a connection loss is treated as an ambiguous outcome. |
| Action | DONE | Nine canonical action types, input binding, TEST mode (`WOULD_RUN`), idempotency, per-step authorization with the run creator's authority (`ActionRuntimeTests`, golden steps 16-17). | NOTIFY returns `NOT_IMPLEMENTED`. |
| Workflow | DONE | Durable run state in PostgreSQL (V29), RabbitMQ queue with manual ack and redelivery, leases and fencing, sweepers, restart recovery, interrupted-worker guard (`WorkflowG3*` 21 tests, `AmqpWorkflowQueueTests`, golden steps 17-18, 26-27). | Schedules, event fan-out and retention jobs are not wired. The in-memory queue is the default outside the prod profile (refused in prod). |
| Approval | PARTIAL | Durable approvals (V33), decision route, approver snapshot, duplicate / opposite / final decision semantics, restart-safe (`ApprovalJourneyE2ETests`, `JdbcApprovalStoreTests`, `WorkflowApprovalG3Tests`, golden steps 18-19). | A decider must be a named approver **and** a WORKSPACE_ADMIN (`WORKFLOW_MANAGE`); there is no `APPROVAL_DECIDE`, no inbox, no notification and no browser UI (`KL-IAM-02`, `KL-WF-*`). |
| Publish | DONE | Publish pipeline with lease and fencing, verify-before-serve, rollback, unpublish, policy and public-data approval, `ROLLING_BACK` (V30) (`PublishApiTests`, `ReleaseScope*`, golden steps 21, 24, 25). | Stored policy fields `requiresAuth` and `cacheSeconds` are not enforced when serving; the HTTP release probe is off by default (`KL-PUB-*`). |
| Public Runtime | PARTIAL | The anonymous `PUBLIC_SITE` query route returns real rows through the sites gateway; the allow-list comes from the immutable release and the stored approval (`PublicDataEndpointTests`, golden steps 22-23). | The rule "a writable source is never public" is not enforced in code; the route is behind two flags that default OFF; the public API deployment predates it (`KL-PUB-*`, `D-C0-57`). |
| Frontend | PARTIAL | Three portals and the Builder; Admin / Studio flows real against the backend; shared UI with the final accessibility fixes; `gate:frontend` green (`docs/ARCHITECTURE.md` section 5). | No browser UI to start a LIVE action or workflow or to decide an approval (API-only); Firefox is BLOCKED_TOOLING and Safari NOT_TESTED (`KL-UI-*`). |
| Brand | DONE | One token foundation, logo family, backgrounds and banners in `packages/ui` (`docs/BRAND_GUIDELINE.md`). | none |
| QA | PARTIAL | Layered gates exist and are documented; the final acceptance of the golden company flow is recorded in `docs/QA_FINAL.md` section 8. | The last independent QA verdict (2026-10-10) was READY_FOR_RELEASE = NO with two P1 whose fixes are integrated; they are re-proven in the acceptance record, not assumed (`docs/QA_FINAL.md` section 5). |
| Operations | PARTIAL | Reproducible isolated stacks with SHA proof, pinned public deployment, process safety, backup and restore drills (`docs/OPERATIONS.md`). | Production readiness is NO: no Linux runtime host with kernel isolation, no off-host backup, no real provider key, single node (`KL-OPS-*`). |

## Release gate

| Severity | Open | Notes |
|---|---|---|
| P0 | 0 | none recorded |
| P1 | see `docs/QA_FINAL.md` section 8.4 | the four IAM findings above are rated P1 by their author and have no accepted-risk decision: **a decision of the release owner is required** before "release" can be claimed |
| P2 / P3 | listed per item in `docs/KNOWN_LIMITATIONS.md` | each with owner and workaround |

## What changes this table

A status changes only with evidence: a merged change, a passing test with its SHA and path, or a recorded decision (`docs/DECISIONS.md`). Do not edit a status to reflect intent.
