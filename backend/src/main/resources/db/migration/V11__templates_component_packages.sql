-- Phase 5: template library and component contribution.
-- A template is a PAGE SCHEMA (JSON validated against the registry), never application source code.
CREATE TABLE templates (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    schema JSONB NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 1,
    author_id UUID NOT NULL REFERENCES users(id),
    source_project_id UUID REFERENCES projects(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT templates_visibility_check CHECK (visibility IN ('PRIVATE', 'COMPANY')),
    CONSTRAINT templates_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE INDEX templates_listing_idx ON templates (visibility, status, updated_at DESC);
CREATE INDEX templates_author_idx ON templates (author_id, updated_at DESC);

-- A contributed component ("block") is a named, reviewed preset of an APPROVED registry component: its props are data validated
-- against that component's props schema and rendered by the existing renderer. No HTML, CSS or JavaScript is stored or executed,
-- so the registry's security guarantee is unchanged.
CREATE TABLE component_packages (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    base_component VARCHAR(64) NOT NULL REFERENCES components(id),
    owner_id UUID NOT NULL REFERENCES users(id),
    -- state of the latest version (PRIVATE = draft or rejected), or DEPRECATED. SUBMITTED/VALIDATING are reserved for an
    -- asynchronous validation step; today validation runs synchronously during submit and is recorded in component_reviews.
    status VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    latest_version INTEGER NOT NULL DEFAULT 1,
    approved_version INTEGER,                 -- the version offered in the company library (null until first approval)
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT component_packages_status_check CHECK (status IN ('PRIVATE', 'SUBMITTED', 'VALIDATING', 'REVIEW', 'APPROVED', 'DEPRECATED'))
);
CREATE INDEX component_packages_status_idx ON component_packages (status, updated_at DESC);
CREATE INDEX component_packages_owner_idx ON component_packages (owner_id, updated_at DESC);

CREATE TABLE component_package_versions (
    package_id UUID NOT NULL REFERENCES component_packages(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    base_component VARCHAR(64) NOT NULL,
    base_component_version VARCHAR(16) NOT NULL,
    props JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    validation JSONB,
    source_project_id UUID REFERENCES projects(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    PRIMARY KEY (package_id, version),
    FOREIGN KEY (base_component, base_component_version) REFERENCES component_versions(component_id, version),
    CONSTRAINT component_package_versions_status_check CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'REJECTED', 'SUPERSEDED'))
);

-- Append-only history of the workflow (submissions, automated validation, decisions).
CREATE TABLE component_reviews (
    id UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES component_packages(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    actor_id UUID REFERENCES users(id),
    decision VARCHAR(24) NOT NULL,
    comment VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT component_reviews_decision_check CHECK (decision IN ('SUBMIT', 'VALIDATION_PASSED', 'VALIDATION_FAILED', 'WITHDRAW', 'APPROVE', 'REJECT', 'DEPRECATE', 'RESTORE'))
);
CREATE INDEX component_reviews_package_idx ON component_reviews (package_id, created_at);
