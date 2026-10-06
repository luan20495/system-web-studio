-- V28 data_runtime — durable state behind the C3 Data Gateway for real LIVE data (C0, D-C0-21). Allocated in docs/parallel/MIGRATION_LEDGER.md.
-- Scope (reduced from the C3 request in docs/parallel/agents/C3_SCHEMA_PROPOSAL.md, rules kept):
--   data_sources, data_credentials (ciphertext only), source_schemas (minimal, masked), data_queries, data_mutations,
--   data_idempotency (RESERVED / DONE / UNKNOWN + lease + expires_at), data_source_bindings (AppDefinition slot -> source, mode TEST | LIVE).
-- NOT here (still unnumbered): sync_jobs / sync_state, webhook_endpoints / webhook_replay, approvals, schedules, notifications, run stores (V29), RLS.
-- Rules: every table has tenant_id NOT NULL REFERENCES tenants(id); a workspace-scoped row carries the composite FK (workspace_id, tenant_id) ->
-- workspaces(id, tenant_id); children reference their source with (data_source_id, tenant_id) -> data_sources(id, tenant_id), so a row can never
-- point at another tenant's parent. Additive only: no existing table is touched. No secret is stored anywhere except as SecretsCrypto ciphertext
-- ("v1:...") in data_credentials; no client idempotency key and no row value is stored.
-- Undo: docs/parallel/c0/undo/U28__data_runtime.sql (guarded). The code behind it is OFF: app.data-platform.enabled=false.

CREATE TABLE data_sources (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    workspace_id UUID,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL,
    config_nonsecret JSONB NOT NULL DEFAULT '{}'::jsonb,
    credential_ref VARCHAR(80),
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT data_sources_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_sources_config_check CHECK (jsonb_typeof(config_nonsecret) = 'object'),
    CONSTRAINT data_sources_version_check CHECK (version >= 1),
    CONSTRAINT data_sources_tenant_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT data_sources_id_tenant_unique UNIQUE (id, tenant_id),
    CONSTRAINT data_sources_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id)
);
CREATE INDEX data_sources_tenant_idx ON data_sources (tenant_id, created_at);

-- ciphertext only; a list/find of data sources never touches this table. credential_ref on data_sources is a reference, not a FK:
-- webhook secrets (later) share this table and a rotation discards the old reference after the source row moved on.
CREATE TABLE data_credentials (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    ref VARCHAR(80) NOT NULL,
    ciphertext TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, ref),
    CONSTRAINT data_credentials_ciphertext_check CHECK (ciphertext LIKE 'v1:%')
);

-- minimal: exactly what SourceSchemaStore needs (immutable, already-masked snapshots; version grows per data source)
CREATE TABLE source_schemas (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    version INTEGER NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    data_source_version BIGINT NOT NULL,
    requested_by UUID REFERENCES users (id),
    includes_samples BOOLEAN NOT NULL,
    snapshot JSONB NOT NULL,
    CONSTRAINT source_schemas_version_check CHECK (version >= 1),
    CONSTRAINT source_schemas_snapshot_check CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT source_schemas_version_unique UNIQUE (tenant_id, data_source_id, version),
    CONSTRAINT source_schemas_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

-- approved operation definitions: the only things a browser can name. definition is re-validated by the Kotlin definition types on every load.
CREATE TABLE data_queries (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    query_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    definition JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, query_id),
    CONSTRAINT data_queries_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_queries_definition_check CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT data_queries_version_check CHECK (version >= 1),
    CONSTRAINT data_queries_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

CREATE TABLE data_mutations (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    mutation_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    definition JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, mutation_id),
    CONSTRAINT data_mutations_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT data_mutations_definition_check CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT data_mutations_version_check CHECK (version >= 1),
    CONSTRAINT data_mutations_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

-- at-most-once execution of a mutation per (tenant, data source, mutation, key). idem_key is C4's DERIVED key (43-char base64url), never the client's.
-- RESERVED (lease) -> DONE | UNKNOWN; an expired lease is UNKNOWN, never runnable. Rows live until expires_at (retention, D4: default 30 days, min 7).
CREATE TABLE data_idempotency (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    data_source_id UUID NOT NULL,
    mutation_id VARCHAR(64) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    affected BIGINT,
    output_json TEXT,
    completed_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, data_source_id, mutation_id, idem_key),
    CONSTRAINT data_idempotency_state_check CHECK (state IN ('RESERVED', 'DONE', 'UNKNOWN')),
    CONSTRAINT data_idempotency_key_check CHECK (idem_key ~ '^[A-Za-z0-9_-]{8,128}$'),
    CONSTRAINT data_idempotency_output_check CHECK (output_json IS NULL OR octet_length(output_json) <= 1048576),
    CONSTRAINT data_idempotency_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
CREATE INDEX data_idempotency_expiry_idx ON data_idempotency (expires_at);

-- AppDefinition local data-source id (slot) -> registered source, per project and mode. The runtime only READS this table (a TEST run never writes it).
-- Runtime ids are never written into an AppDefinition.
CREATE TABLE data_source_bindings (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    mode VARCHAR(8) NOT NULL,
    slot_id VARCHAR(128) NOT NULL,
    data_source_id UUID NOT NULL,
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, mode, slot_id),
    CONSTRAINT data_source_bindings_mode_check CHECK (mode IN ('LIVE', 'TEST')),
    CONSTRAINT data_source_bindings_workspace_tenant_fk FOREIGN KEY (workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id),
    CONSTRAINT data_source_bindings_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id),
    CONSTRAINT data_source_bindings_source_fk FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
CREATE INDEX data_source_bindings_source_idx ON data_source_bindings (tenant_id, data_source_id);
