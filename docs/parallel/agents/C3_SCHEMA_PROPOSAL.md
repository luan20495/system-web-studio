# C3 — Schema proposal for the Data foundation (ONE migration request)

Status: PROPOSED · owner: C3 · **no Flyway version is assigned or created here**. C0 gives this request ONE version (see `BOARD.md`, *Migration requests*) and writes the file; until then C3 only has ports (`*Store`, `*Repository`, `*Catalog`). This replaces the earlier T8 row and the T9–T11/Sync/Webhook row (they overlapped and contradicted each other on credentials).

The DDL below is a *proposal sketch in documentation*, not a migration file. Names and types may be adjusted by C0 to the project conventions; the rules marked **MUST** may not be dropped.

## Rules (all tables)

- **MUST** every table has `tenant_id uuid NOT NULL REFERENCES tenants(id)` (C1's tenant table; this request is therefore ordered after C1's tenancy migration).
- **MUST** every workspace-scoped table also carries `workspace_id` with a composite FK `(workspace_id, tenant_id) REFERENCES workspaces (id, tenant_id)` so a row can never point at another tenant's workspace (needs `UNIQUE (id, tenant_id)` on C1's `workspaces`; if C1 did not create it, C0 adds it in the same request).
- **MUST** child tables reference their data source with a composite FK `(data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)`; `data_sources` therefore has `UNIQUE (id, tenant_id)`.
- **MUST** every repository query filters on `tenant_id` (the ports already take it); there is no tenant-less read path. Whether C0 additionally enables row-level security is C0's call.
- Secrets are only ever stored as `SecretsCrypto` ciphertext (`v1:` prefix). No raw credential, no client idempotency key and no row value is stored anywhere in this request.
- Deletion: C0 chooses `ON DELETE` actions. C3 expects RESTRICT for `data_sources` (a source with queries/jobs must be retired, not dropped) and CASCADE from `data_sources` to its derived rows only through an explicit C3 purge that writes an audit record.

## Credential storage decision (resolves the earlier contradiction)

Earlier rows put `credential_enc` on `data_sources` (T8) and a separate `data_credentials` table (T9–T11). The code (`CredentialStore.find/put/remove(tenantId, ref)`, `DataSource.credentialRef`) is the second form, so the request is: **`data_credentials` table + `data_sources.credential_ref`, and no `credential_enc` column**. A credential can then be rotated or purged without touching the source row, and `data_sources` is safe to select into logs and admin lists.

## Tables

```sql
-- 1. data_sources: connection definition without secrets
data_sources (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  workspace_id uuid,                                  -- nullable = tenant-level source; composite FK (workspace_id, tenant_id) when set
  type text NOT NULL,                                 -- 'postgres' | 'rest' | later drivers (closed set enforced in code)
  name text NOT NULL,
  status text NOT NULL,                               -- ACTIVE | DISABLED
  config_nonsecret jsonb NOT NULL,                    -- host/db/ssl mode/base url ... never a secret
  credential_ref text,                                -- key into data_credentials, NULL = none
  created_by uuid, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  version bigint NOT NULL,                            -- bumped on every config/credential/status change; part of every cache key
  UNIQUE (tenant_id, name),
  UNIQUE (id, tenant_id)
);

-- 2. data_credentials: ciphertext only
data_credentials (
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  ref text NOT NULL,
  ciphertext text NOT NULL CHECK (ciphertext LIKE 'v1:%'),
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, ref)
);

-- 3. data_queries / 4. data_mutations: approved operation definitions (the only things a browser can name)
data_queries (
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  data_source_id uuid NOT NULL,
  query_id text NOT NULL,
  kind text NOT NULL,                                 -- 'sql' | 'rest'
  definition jsonb NOT NULL,                          -- sql/pathTemplate, params, maxRows, cacheTtlSeconds ...
  status text NOT NULL,
  version bigint NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, data_source_id, query_id),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
data_mutations (
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  data_source_id uuid NOT NULL,
  mutation_id text NOT NULL,
  kind text NOT NULL,                                 -- CREATE | UPDATE | DELETE | CALL
  definition jsonb NOT NULL,                          -- target, params, entity
  invalidates jsonb NOT NULL DEFAULT '[]',            -- query ids whose cache is dropped on success
  status text NOT NULL, version bigint NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, data_source_id, mutation_id),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

-- 5. source_schemas: discovered structure, ALREADY masked (this is what AiSafeSchema / AiDataCatalog read)
source_schemas (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  data_source_id uuid NOT NULL,
  version int NOT NULL,
  discovered_at timestamptz NOT NULL,
  fingerprint text NOT NULL,
  data_source_version bigint NOT NULL,
  requested_by uuid,
  includes_samples boolean NOT NULL,                  -- samples are off by default
  snapshot jsonb NOT NULL,                            -- masked; no credentials, no raw PII
  UNIQUE (tenant_id, data_source_id, version),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);

-- 6. data_idempotency: C4's derived key (sha256 base64url, 43 chars) — never the raw client key
data_idempotency (
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  data_source_id uuid NOT NULL,
  mutation_id text NOT NULL,
  idem_key text NOT NULL CHECK (idem_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  fingerprint text NOT NULL,                          -- of the params; same key + other params = conflict
  state text NOT NULL CHECK (state IN ('RESERVED','DONE','UNKNOWN')),
  result jsonb,                                       -- only when DONE; no secrets, no raw rows
  lease_until timestamptz NOT NULL,                   -- RESERVED past its lease becomes UNKNOWN, never runnable
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, data_source_id, mutation_id, idem_key),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
-- begin() MUST be one atomic statement (INSERT ... ON CONFLICT DO NOTHING + conditional read of the winner), so two nodes cannot both run.
-- index (expires_at) for the purge job. C0 may implement this port on Redis instead; the semantics (3 states + lease) stay.

-- 7. sync_jobs / sync_state (one-way pull V1)
sync_jobs (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  workspace_id uuid, project_id uuid, app_version_id text,   -- scope of the job; the owner's permission is re-checked there on every run
  data_source_id uuid NOT NULL,
  name text NOT NULL,
  query_id text NOT NULL, mapping_ref text NOT NULL, view_model_ref text NOT NULL,
  key_field text NOT NULL, cursor_field text, cursor_param text, fixed_params jsonb NOT NULL DEFAULT '{}',
  interval_seconds int NOT NULL, page_size int NOT NULL, max_pages_per_run int NOT NULL,
  status text NOT NULL, direction text NOT NULL DEFAULT 'PULL',
  created_by uuid, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  version bigint NOT NULL,
  UNIQUE (tenant_id, name), UNIQUE (id, tenant_id),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
sync_state (
  job_id uuid NOT NULL, tenant_id uuid NOT NULL REFERENCES tenants(id),
  checkpoint text, run_counter bigint NOT NULL DEFAULT 0,
  last_success_at timestamptz, last_error_at timestamptz, last_error_code text, consecutive_failures int NOT NULL DEFAULT 0,
  next_run_at timestamptz NOT NULL,
  lease_owner text, lease_until timestamptz,          -- acquired/released by one conditional UPDATE (compare-and-set)
  last_rows int NOT NULL DEFAULT 0, last_pages int NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, job_id),
  FOREIGN KEY (job_id, tenant_id) REFERENCES sync_jobs (id, tenant_id)
);
-- index (next_run_at) WHERE lease is free, for due().
-- The sync sink (where pulled records land) is NOT part of this request: C0/C2 decide the target store; the SyncSink port stays in-memory in C3 until then.

-- 8. webhook_endpoints (+ optional replay store)
webhook_endpoints (
  id uuid PRIMARY KEY,                                -- the {endpointId} of POST /api/v1/webhooks/data/{endpointId}
  tenant_id uuid NOT NULL REFERENCES tenants(id),     -- tenant is resolved from THIS row, never from the request
  data_source_id uuid NOT NULL,
  name text NOT NULL, status text NOT NULL,
  secret_ref text NOT NULL,                           -- keys into data_credentials
  previous_secret_ref text, previous_valid_until timestamptz,   -- rotation grace (≤ 24 h)
  accepted_events jsonb NOT NULL DEFAULT '[]', event_field text NOT NULL,
  invalidate_query_ids jsonb NOT NULL DEFAULT '[]', entity text, record_key_field text,
  workflow_ref text, rate_per_minute int NOT NULL,
  created_by uuid, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL,
  UNIQUE (tenant_id, name),
  FOREIGN KEY (data_source_id, tenant_id) REFERENCES data_sources (id, tenant_id)
);
webhook_replay (                                      -- only if the replay guard is persisted; Redis SET NX EX is the other option
  endpoint_id uuid NOT NULL, tenant_id uuid NOT NULL REFERENCES tenants(id),
  replay_key text NOT NULL,                           -- 'sig:<sha256 of the signature>' or 'id:<signed delivery id>'
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (endpoint_id, replay_key)               -- first-seen = a successful INSERT; the row is the proof
);
```

## Mapping metadata

**No table is requested.** Mappings and view models are part of the published AppDefinition (C2). `MappingCatalog` is implemented by C0 over the published definition and always returns the `fields[].transforms[]` form (reader normalises legacy `transform`; writer only emits `transforms[]`). C3 stores nothing of its own.

## Not in this request

Flyway version number; the sync sink table; Redis keys for the cache and the realtime bus (infrastructure, B-C3-09); any change to `audit_log` (C3 writes through `AuditService`).
