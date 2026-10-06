# C0 — Persistence design for real LIVE E2E (removes B-C0-W-01)

Status: **ACCEPTED 2026-10-06 (D-C0-21, decisions D1–D7 in §7).** V28 and V29 are allocated in `MIGRATION_LEDGER.md`. V28 and the C3 adapters are implemented on branch `wire/c3-persistence`. **V28 status (2026-10-06): implementation complete; real Mac gate GREEN @ `3a6f084`** (compileKotlin, compileTestKotlin, targeted `DataRuntimeLiveApiTests`, full `clean test --no-daemon --rerun-tasks` 6m07s). Branch only, not imported into `integration/v2`; not production-ready (no management API B-C0-W-03, production connectors read-only B-C0-W-04). **V29 status (2026-10-06): implementation written on `wire/v29-run-persistence` (base `integration/v2 @ f894cc6`), NOT yet verified on the Mac** — B-C0-W-01 stays OPEN until that gate is green (see §4a).
Base: `integration/v2 @ e7307fd`.

## 1. Audit result

| Fact | Evidence |
|---|---|
| Highest migration is **V27** (`V27__publish_configs.sql`); V1…V27 contiguous; no V28+ file on `integration/v2`, `agent/c3-data` or `agent/c4-workflow` | `db/migration/`, `MIGRATION_LEDGER.md` §1 ("V27 is the last number allocated") |
| No table exists for data sources, credentials, queries, mutations, data idempotency, source schemas, sync, webhooks, action runs, workflow runs/steps, approvals, schedules, notifications | table list of V1…V27 |
| Look-alikes that must NOT be reused | `connectors`/`project_connectors` (V22: platform-wide approved HTTP connectors for generated server apps, no tenant column), `idempotency_keys` (V4: `scope_key`/`request_hash`/`resource_id`, no state, no lease, no result), `build_jobs` (V14), `app_runtimes` (V22) |
| Prerequisites present | V26: `tenants`, `workspaces UNIQUE (id, tenant_id)`; V27 relies on `projects UNIQUE (workspace_id, id)`; Redis + AMQP starters, `RabbitTemplate`, `integration/queue/JobQueue.kt`, Testcontainers Postgres 17.6 / Redis / RabbitMQ in `IntegrationTestBase` |
| Ports with only in-memory implementations | C3: `DataSourceRepository`, `CredentialStore`, `QueryCatalog`, `MutationCatalog`, `IdempotencyStore`, `SourceSchemaStore`, `SyncJobStore`, `WebhookEndpointStore`. C4: `ActionRunStore`, `WorkflowRunStore`, `WorkflowQueue`, `ApprovalStore`, `ScheduleStore`, `DeliveryStore` |
| Existing infra usable without new components | `RedisCacheBackend` (query cache), `InMemoryDataEventBus` (realtime; ephemeral by nature), `RedisRateLimitGate`, `SecretsCrypto` |
| `DefaultDataGateway` constructor needs | `GatewayGuard`✔, `DataSourceService` (repository, vault, registry, limits, audit), `QueryCatalog`, `MutationCatalog`, `MappingCatalog`✔ (wired), `DiscoveryService` (→ `SourceSchemaStore`), `QueryCache`, `IdempotencyStore`, `DataChangeListener`, `DataAuditSink`, `RateLimitGate` |
| C3 idempotency TTL | `IDEMPOTENCY_TTL_SECONDS = 24 h` (gateway constant) vs C4 `action_runs` retention 30 d: safe because C4 replays the recorded result before C3 is reached, but both numbers must be asserted in a test |
| Ledger order | `MIGRATION_LEDGER.md` §2 orders: tenant resources → data foundation (C3) → sharing/groups → C4 persistence → RLS … Items 1 and 3 are NOT prerequisites of the minimal tables below (see §6) |

## 2. Minimal durable design

| Need | Durable state (PostgreSQL) | Not durable (by design) |
|---|---|---|
| LIVE query | `data_sources`, `data_credentials`, `data_queries`, `data_source_bindings` | query cache (Redis, already there), realtime bus (in memory, re-subscribe on restart) |
| LIVE mutation via actions | + `data_mutations`, `data_idempotency` (RESERVED/DONE/UNKNOWN + lease) | — |
| Action runs / idempotency | `action_runs` (derived 43-char key) | — |
| Workflow runs / steps / restart safety | `workflow_runs` (+ sweeper/DLQ/retention columns), `workflow_run_steps` | — |
| Workflow queue | **the run row is the durable queue state** (PENDING / timer / stale ⇒ the sweeper re-publishes). Broker = RabbitMQ per D-C4-13 | message bodies carry no payload (job id, tenant, run, step) |

Correctness never depends on the broker: a lost message leaves a PENDING/stale run that `claimForSweep` finds again. Therefore a persistent store with the existing in-memory queue is already restart-safe for a single node (latency = stale threshold). RabbitMQ is needed for multi-node fan-out, prompt delivery and the DLQ, and is Phase 2.

Deliberately deferred (each needs its own accepted request): `sync_jobs`/`sync_state`, `webhook_endpoints`/`webhook_replay` (W-07), `approvals`, `schedules`, `schedule_executions`, `notification_deliveries`, `in_app_notifications`, RLS.

## 3. V28 — data runtime foundation (C3 minimal + `source_schemas` + C0 bindings) — the authoritative DDL is `backend/src/main/resources/db/migration/V28__data_runtime.sql`; this section is the design summary (D2, D3, D4 applied)

All tables: `tenant_id uuid NOT NULL REFERENCES tenants(id)`; workspace-scoped rows use the composite FK `(workspace_id, tenant_id) → workspaces(id, tenant_id)`; children use `(data_source_id, tenant_id) → data_sources(id, tenant_id)`; additive only; no data migration; no backfill.

```sql
CREATE TABLE data_sources (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    workspace_id     UUID,
    type             VARCHAR(32)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    config_nonsecret JSONB        NOT NULL DEFAULT '{}'::jsonb,
    credential_ref   VARCHAR(80),
    created_by       UUID REFERENCES users (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version          BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT data_sources_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_sources_config_object CHECK (jsonb_typeof(config_nonsecret) = 'object'),
    CONSTRAINT data_sources_tenant_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT data_sources_id_tenant_unique UNIQUE (id, tenant_id),
    CONSTRAINT data_sources_id_tenant_workspace_unique UNIQUE (id, tenant_id, workspace_id),
    CONSTRAINT data_sources_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id)
);
CREATE INDEX data_sources_tenant_idx ON data_sources (tenant_id, created_at);

CREATE TABLE data_credentials (            -- ciphertext only (SecretsCrypto "v1:"); never selected by list queries
    tenant_id  UUID NOT NULL REFERENCES tenants (id),
    ref        VARCHAR(80) NOT NULL,
    ciphertext TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, ref),
    CONSTRAINT data_credentials_ciphertext_check CHECK (ciphertext LIKE 'v1:%')
);
-- data_sources.credential_ref is deliberately not a FK: webhook secrets share this table and rotation discards the old ref after the source row moved on.

CREATE TABLE source_schemas (            -- D2: masked discovery snapshots; immutable rows, version grows per data source
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    data_source_id      UUID NOT NULL,
    version             INTEGER NOT NULL,
    discovered_at       TIMESTAMPTZ NOT NULL,
    fingerprint         VARCHAR(64) NOT NULL,
    data_source_version BIGINT NOT NULL,
    requested_by        UUID REFERENCES users (id),
    includes_samples    BOOLEAN NOT NULL,
    snapshot            JSONB NOT NULL,                 -- masked; no credentials, no connection data
    CONSTRAINT source_schemas_version_unique UNIQUE (tenant_id, data_source_id, version),
    CONSTRAINT source_schemas_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

CREATE TABLE data_queries (
    tenant_id      UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    query_id       VARCHAR(64) NOT NULL,
    kind           VARCHAR(16) NOT NULL,            -- sql | rest (closed set, enforced by the codec)
    definition     JSONB NOT NULL,                  -- re-validated by QueryDefinition init{} on every load
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, query_id),
    CONSTRAINT data_queries_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_queries_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

CREATE TABLE data_mutations (
    tenant_id      UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    mutation_id    VARCHAR(64) NOT NULL,
    kind           VARCHAR(16) NOT NULL,            -- CREATE | UPDATE | DELETE | CALL
    definition     JSONB NOT NULL,                  -- target, params, entity, invalidates
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, mutation_id),
    CONSTRAINT data_mutations_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_mutations_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

CREATE TABLE data_idempotency (            -- key = C4's derived key (43-char base64url); the client's raw key is never stored
    tenant_id      UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    mutation_id    VARCHAR(64) NOT NULL,
    idem_key       VARCHAR(128) NOT NULL,
    fingerprint    VARCHAR(128) NOT NULL,
    state          VARCHAR(16) NOT NULL,
    affected       BIGINT,
    output_json    TEXT,                            -- only when DONE; bounded by the gateway; no secrets, no request parameters (text: it is the gateway's own encoded document)
    completed_at   TIMESTAMPTZ,
    lease_until    TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, mutation_id, idem_key),
    CONSTRAINT data_idempotency_state_check CHECK (state IN ('RESERVED', 'DONE', 'UNKNOWN')),
    CONSTRAINT data_idempotency_key_check CHECK (idem_key ~ '^[A-Za-z0-9_-]{8,128}$'),
    CONSTRAINT data_idempotency_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
CREATE INDEX data_idempotency_expiry_idx ON data_idempotency (expires_at);

CREATE TABLE data_source_bindings (        -- C0: AppDefinition local data-source id -> registered source. Runtime ids are never written into an AppDefinition.
    tenant_id      UUID NOT NULL REFERENCES tenants (id),
    workspace_id   UUID NOT NULL,
    project_id     UUID NOT NULL,
    mode           VARCHAR(8)  NOT NULL,
    slot_id        VARCHAR(64) NOT NULL,
    data_source_id UUID NOT NULL,
    created_by     UUID REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, mode, slot_id),
    CONSTRAINT data_source_bindings_mode_check CHECK (mode IN ('LIVE', 'TEST')),
    CONSTRAINT data_source_bindings_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id),
    CONSTRAINT data_source_bindings_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id),
    CONSTRAINT data_source_bindings_source_fk FOREIGN KEY (data_source_id, tenant_id, workspace_id) REFERENCES data_sources (id, tenant_id, workspace_id)
);
CREATE INDEX data_source_bindings_source_idx ON data_source_bindings (tenant_id, data_source_id);
```

Rules the adapters enforce (not expressible as FK): a workspace-scoped source may only be bound inside its own workspace; DISABLED queries/mutations/sources resolve to "not found"; every statement filters on `tenant_id`.
Retention (D4): `data_idempotency.expires_at` = max(gateway request, configured retention), default 30 days, minimum 7 days (`app.data-platform.idempotency-retention`); rows are purged only when `expires_at < now()` by a bounded scheduled purge; RESERVED/UNKNOWN rows are never purged earlier. Nothing else expires. `data_source_bindings` is read-only for the runtime (D3).
Undo story: `docs/parallel/c0/undo/U28__data_runtime_foundation.sql` (guarded `DROP TABLE IF EXISTS` in reverse order), same pattern as U26/U27. Tests: `DataRuntimeMigrationTests` (tables, constraints, cross-tenant FK rejection, ciphertext check).

## 4. Proposed V29 — action / workflow run stores (C4 minimal)

Taken from `audit/FINAL-C4-runtime-design.md` §10 **unchanged except**: (a) `workspace_id` gets the composite tenant FK, (b) `action_runs.run_id` stays `uuid` and the adapter maps the port's string form, (c) approvals/schedules/notifications are NOT included. Tables: `action_runs`, `workflow_runs`, `workflow_run_steps` with the unique keys and the partial indexes listed there (`action_runs_stale`, `action_runs_retention`, `workflow_runs_timers`, `workflow_runs_approval`, `workflow_runs_sweep`, `workflow_runs_retention`, `workflow_runs_redact`, `workflow_run_steps_tenant`). `app_id` = the project id, **no FK** (history must outlive a deleted project; tenant integrity comes from the workspace FK).
D6: `RunKey.appId` is `UUID?` in the port, so `action_runs.app_id` is **nullable** (not stricter than the domain contract). The natural unique key `(tenant_id, app_id, action_id, user_id, idempotency_key)` would let two NULL-app rows coexist, so V29 uses a null-safe unique index (`COALESCE(app_id, '00000000-0000-0000-0000-000000000000')`) and a partial index for rows without an app; the adapter treats a null `appId` as its own scope.
Retention: unchanged (D-C4-16), driven by `RetentionService.runOnce()` on a timer that C0 adds.

## 4a. V29 as implemented (D-C0-23) — supersedes the proposal above where they differ

* **Schema** (`V29__workflow_run_persistence.sql`): see `MIGRATION_LEDGER.md` for the exact tables, keys and FKs. Differences from §4: `app_id` has a composite FK to `projects` (repository pattern; projects are not hard-deleted) instead of "no FK"; `workflow_run_steps.step_order` instead of `position`; the pinned `definition` is stored as lossless JSON on the run; `cur_step_status / cur_wake_at / cur_approval_id` are denormalised so the sweeper never reads steps.
* **Adapters** (`wiring/persistence`): `JdbcActionRunStore` (one transaction per `begin`: `INSERT ... ON CONFLICT DO NOTHING`, then `SELECT ... FOR UPDATE`; `complete` is guarded by run id + key + `status = RUNNING`), `JdbcWorkflowRunStore` (CAS on `version`; the run row and the changed step rows are written in ONE transaction, a CAS loser writes nothing; `claimForSweep` = candidate query + `FairSelection` + version-guarded stamp), `ActionResultCodec`, `WorkflowDefinitionCodec`, `RunStoreSupport`.
* **Wiring**: `app.workflow.run-store` (`jdbc` default, `memory` explicit), `app.workflow.stale-after`, `app.workflow.action-run-stale-after`, `app.workflow.action-run-sweep-delay-ms`; `ActionRunRecovery` scheduled component.
* **Restart algorithm** (nothing is replayed, only re-driven): (1) the process starts with an empty in-memory queue; (2) the first `engine.sweep()` after `staleAfter` claims every unfinished run whose heartbeat is older than the lease, plus every due timer / retry; (3) PENDING -> job republished; RUNNING step -> RETRY_WAIT (attempt kept) + job; WAITING / RETRY_WAIT -> left to their timer; stuck compensation -> COMPENSATE job; (4) a re-driven step re-enters the action runtime with the SAME `wf:<runId>:<stepId>[:vN]` key; V28 answers Replay / OutcomeUnknown instead of writing twice; (5) terminal runs, `compensated` steps and ambiguous (UNKNOWN) steps are never executed again.
* **Still volatile / not done**: `WorkflowQueue` (RabbitMQ phase), `RetentionService` scheduling, approvals / schedules / notifications, management API (B-C0-W-03), writable production connectors (B-C0-W-04).

## 5. Adapters to implement (all in `wiring/` or the owning module's `persistence` package, JDBC via `NamedParameterJdbcTemplate`, every query tenant-filtered)

1. `JdbcDataSourceRepository`, `JdbcCredentialStore`, `JdbcQueryCatalog`, `JdbcMutationCatalog` (+ a strict `QueryDefinitionCodec` / `MutationDefinitionCodec`; unknown `kind` ⇒ not found).
2. `JdbcIdempotencyStore` — `begin` is one transaction: `INSERT … ON CONFLICT DO NOTHING`, then `SELECT … FOR UPDATE` of the winner and the in-memory store's decision table (expired ⇒ new RESERVED; fingerprint mismatch ⇒ Conflict; DONE ⇒ Replay; UNKNOWN ⇒ OutcomeUnknown; lease passed ⇒ mark UNKNOWN; else InProgress). `release` only deletes a RESERVED row.
3. `JdbcDataSourceSlotBindings` (implements the existing `DataSourceSlotBindings`).
4. `DataRuntimeConfiguration` completion: `DataGateway` bean (`DefaultDataGateway`) + `DataSourceService` + `DataConnectorRegistry` (postgres, rest connectors already imported) + `DiscoveryService` over an in-memory `SourceSchemaStore` + `QueryCache(RedisCacheBackend)` + `DataChangeNotifier` + `RedisRateLimitGate` + `AuditServiceSink`.
5. `JdbcActionRunStore`, `JdbcWorkflowRunStore` (CAS on `version`, `FOR UPDATE SKIP LOCKED` claim with per-tenant fairness, retention stage 1/2), `ActionResultCodec` (JSON of `ActionResult` incl. `Failed.details`).
6. Wiring changes: `actionRunStore`/`workflowRunStore` beans become JDBC; `app.workflow.allow-volatile-stores` remains but only for the queue (see §6); timers for `engine.sweep()`, `ActionRunStore.sweepStale`, `RetentionService.runOnce`, `data_idempotency` purge; a Redis-less test profile keeps the in-memory fallbacks.
7. Management path for E2E: **no new HTTP route.** Fixtures seed sources/queries/mutations/bindings through the repositories / `DataSourceAdminService` in test code. A production management API needs its own contract (B-C0-W-02a, Q-1 stays: admin portal platform-only).

## 6. RabbitMQ work (Phase 2, after V29 stores are green)

* `RabbitWorkflowQueue : WorkflowQueue` in `integration/queue/` (shared file, C0): quorum queue `xweb.workflow.jobs` (`x-delivery-limit` = maxDeliveries, DLX → `xweb.workflow.jobs.dlq`), publisher confirms (`spring.rabbitmq.publisher-confirm-type: correlated`, `publish` throws when not confirmed), manual ack, `poll()` via `basicGet` on a **dedicated channel per lease** (ack/nack must use the same channel), `x-delivery-count` → `QueueLease.deliveryCount`, DLQ consumer → `WorkflowWorker.drainDeadLetters`.
* Declarations added next to the existing `QueueConfiguration`; no new infrastructure (RabbitMQ, starter and Testcontainers rabbit already exist).
* Tests: Testcontainers RabbitMQ — publish/confirm, redelivery count, nack(requeue=false) → DLQ, consumer death → redelivery, broker down → publish throws and the run stays PENDING.
* Until then `app.workflow.queue=memory` (single node). The volatile guard keeps refusing LIVE mutating workflows in any multi-node profile.

## 7. Decisions (accepted 2026-10-06 — recorded as D-C0-21)

1. **D1** two migrations: V28 data runtime, V29 run stores; nothing already allocated is renumbered; only unallocated roadmap items moved.
2. **D2** `source_schemas` is in V28 (minimal); sync/webhook infrastructure and advanced history stay deferred.
3. **D3** one `data_source_bindings` table with `mode TEST|LIVE` in the key; TEST execution never writes bindings.
4. **D4** data idempotency retention default 30 days, configurable, minimum 7 days, never earlier than the action-run replay window.
5. **D5** approvals, schedules, notifications out of V28/V29 (tracked, not cancelled); LIVE workflows with an APPROVAL step stay refused.
6. **D6** `action_runs.app_id` nullable; null-safe unique key.
7. **D7** no management API invented; tracked as B-C0-W-03 (OPEN). Related finding B-C0-W-04: production connectors are read-only.

## 8. Implementation order

1. Owner accepts §7 → C0 writes DECISIONS + ledger rows (V28, then V29) and nothing else.
2. V28 file + undo + `DataRuntimeMigrationTests` → Mac Gradle → apply on PG verified.
3. C3 JDBC adapters + `DataGateway` bean + bindings + fixtures; flip `DATA_RUNTIME_UNAVAILABLE` tests to real LIVE query/mutation tests → Mac Gradle.
4. V29 file + undo + migration test → Mac Gradle. **(written 2026-10-06 on `wire/v29-run-persistence`; Mac gate pending)**
5. **(written, Mac gate pending)** `JdbcActionRunStore`/`JdbcWorkflowRunStore` + codec + timers; restart-safety test (kill the runtime mid-run, new context resumes); retire the volatile guard for actions → Mac Gradle.
6. `RabbitWorkflowQueue` + queue profile switch → Mac Gradle with Testcontainers.
7. C5 Phase 3 E2E against the real stack.
