-- Stage H: departments/teams, application lifecycle, hosting cost prices.

CREATE TABLE departments (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'DEPARTMENT',
    parent_id UUID REFERENCES departments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT departments_kind_check CHECK (kind IN ('DEPARTMENT', 'TEAM')),
    CONSTRAINT departments_not_self CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX departments_name_unique ON departments (coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
-- organisational grouping only: it grants no permission (access stays workspace/project membership)
ALTER TABLE users ADD COLUMN department_id UUID REFERENCES departments(id);
ALTER TABLE workspaces ADD COLUMN department_id UUID REFERENCES departments(id);

-- ARCHIVED applications are read-only and offline (their site is unpublished); RESTORE makes them editable again.
ALTER TABLE projects ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE projects ADD COLUMN archived_by UUID REFERENCES users(id);
ALTER TABLE projects ADD CONSTRAINT projects_lifecycle_check CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED'));

-- Explicit unit prices for hosting cost estimates (immutable rows; a new price is a new row). Without a price the cost is "unknown".
CREATE TABLE cost_prices (
    id UUID PRIMARY KEY,
    item VARCHAR(32) NOT NULL,
    unit_price NUMERIC(18, 8) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    usd_per_unit NUMERIC(18, 10) NOT NULL DEFAULT 1,
    note VARCHAR(200) NOT NULL DEFAULT '',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cost_prices_item_check CHECK (item IN ('STORAGE_GIB_MONTH', 'BUILD_CPU_HOUR', 'BUILD_MINUTE', 'EGRESS_GIB')),
    CONSTRAINT cost_prices_price_check CHECK (unit_price >= 0 AND usd_per_unit > 0)
);
CREATE INDEX cost_prices_item_idx ON cost_prices (item, effective_from DESC);
