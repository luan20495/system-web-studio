-- Phase 7.1 (ADR 0009): real static runtime for page-schema sites.

-- An immutable, content-addressed build output. Files live in the artifacts bucket under storage_prefix; the manifest lists every
-- file with its size, content type and SHA-256. The same page rendered twice gives the same sha256 and the row is reused.
CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    version_id UUID REFERENCES project_versions(id),
    sha256 CHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL DEFAULT 'STATIC_SITE',
    storage_prefix VARCHAR(300) NOT NULL,
    file_count INTEGER NOT NULL,
    total_bytes BIGINT NOT NULL,
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT artifacts_kind_check CHECK (kind IN ('STATIC_SITE')),
    CONSTRAINT artifacts_project_sha_unique UNIQUE (project_id, sha256)
);

ALTER TABLE deployments ADD COLUMN artifact_id UUID REFERENCES artifacts(id);

-- One public address per project. current_deployment_id is the pointer the gateway serves; switching it is a deploy or a rollback,
-- NULL means the site is offline (unpublished).
CREATE TABLE sites (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    slug VARCHAR(80) NOT NULL UNIQUE,
    current_deployment_id UUID REFERENCES deployments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sites_slug_check CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,79}$')
);
