-- V32 Dynamic Organization (C3 persistence; number reserved by C0, architecture D-C0-43; contract: C1 branch feat/c1-final-iam-org-permissions @ db7d6b0,
-- docs/parallel/c1/organization-employee-contract.md + the seams in backend/.../organization/OrganizationRepositories.kt). Persistence follows that contract EXACTLY:
--   * versions START AT 0 (the C1 kit pins it); every versioned write is one `UPDATE ... WHERE tenant_id AND id AND version = ?` that bumps the version;
--   * unit `code` is REQUIRED, CANONICAL UPPER-CASE (CHECK), unique among the NON-ARCHIVED SIBLINGS (roots are siblings of each other); archiving frees it;
--   * type / position / grade codes are unique per tenant case-insensitively, even when disabled; units are the only archivable entity (`active` + `deleted_at`, never contradictory);
--   * unit type rules (allowedParentTypeIds, allowedChildTypeIds, allowRoot, maxDepth) are ONE canonical JSONB document, validated by C1 inside the structural transaction;
--   * links (membership, employee position) end by `active = false` and keep their row (history); a position is held WITHIN a membership; the grade is an attribute of that row;
--   * the employee is `tenant_members(tenant_id, user_id)` (no profile table, no copy of identity); organization data grants NO permission.
-- Conventions of V26 / V27 / V28 are kept: `id UUID PRIMARY KEY` + `UNIQUE (id, tenant_id)` as the target of composite foreign keys that carry tenant_id, `tenant_id NOT NULL REFERENCES tenants`.
-- Additive only: six new tables, no existing table touched (V20 `departments` is NOT used and NOT changed), no data copied. Requires PostgreSQL >= 15 (NULLS NOT DISTINCT); the platform runs 17.6.
-- Undo: docs/parallel/c3/undo/U32__dynamic_organization.sql (refuses while any row exists). Flyway never runs it. Immutable once imported.

-- 1. unit types ------------------------------------------------------------------------------------------------------------------------------------------
CREATE TABLE organization_unit_types (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    icon VARCHAR(40),                                           -- an id of the UI's closed icon list, never a URL
    rules JSONB NOT NULL DEFAULT '{}'::jsonb,                   -- OrgUnitTypeRules {allowedParentTypeIds, allowedChildTypeIds, allowRoot, maxDepth}: applied by C1, stored as one document
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT organization_unit_types_code_check CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
    CONSTRAINT organization_unit_types_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT organization_unit_types_rules_check CHECK (jsonb_typeof(rules) = 'object'),
    CONSTRAINT organization_unit_types_version_check CHECK (version >= 0),
    CONSTRAINT organization_unit_types_id_tenant_unique UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX organization_unit_types_code_unique ON organization_unit_types (tenant_id, lower(code));        -- per tenant, case-insensitive, also when disabled

-- 2. units: the tree (adjacency list) --------------------------------------------------------------------------------------------------------------------
CREATE TABLE organization_units (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    type_id UUID NOT NULL,
    parent_id UUID,                                             -- NULL = a root; several roots per tenant are allowed
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,                                     -- C1 `archivedAt`; ACTIVE = (active, NULL), ARCHIVED = (not active, set)
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT organization_units_code_check CHECK (code ~ '^[A-Z0-9][A-Z0-9._-]{0,59}$'),                        -- canonical upper-case form (C1 uppercases with Locale.ROOT)
    CONSTRAINT organization_units_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT organization_units_metadata_check CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT organization_units_version_check CHECK (version >= 0),
    CONSTRAINT organization_units_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT organization_units_lifecycle_check CHECK (deleted_at IS NULL OR NOT active),                       -- an archived unit is never active: no contradictory row
    CONSTRAINT organization_units_id_tenant_unique UNIQUE (id, tenant_id),
    CONSTRAINT organization_units_type_fk FOREIGN KEY (type_id, tenant_id) REFERENCES organization_unit_types (id, tenant_id) ON DELETE RESTRICT,
    -- parent_id NULL => MATCH SIMPLE skips the check (a root); otherwise the parent must exist IN THE SAME TENANT
    CONSTRAINT organization_units_parent_fk FOREIGN KEY (parent_id, tenant_id) REFERENCES organization_units (id, tenant_id) ON DELETE RESTRICT
);
-- sibling-scoped code among NON-ARCHIVED units; NULLS NOT DISTINCT makes the roots (parent_id NULL) siblings of each other; an archived unit releases its code
CREATE UNIQUE INDEX organization_units_sibling_code_unique ON organization_units (tenant_id, parent_id, code) NULLS NOT DISTINCT WHERE deleted_at IS NULL;
-- children in display order (roots: parent_id IS NULL), the recursive step of every tree query, the parent-FK RESTRICT check
CREATE INDEX organization_units_children_idx ON organization_units (tenant_id, parent_id, sort_order, name, id);
-- the type-FK RESTRICT check, "units of a type"
CREATE INDEX organization_units_type_idx ON organization_units (tenant_id, type_id);

-- 3. positions and grades: independent tenant taxonomies; the GRADE belongs to the employee position, so positions carry no grade --------------------
CREATE TABLE positions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT positions_code_check CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    CONSTRAINT positions_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT positions_version_check CHECK (version >= 0),
    CONSTRAINT positions_id_tenant_unique UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX positions_code_unique ON positions (tenant_id, lower(code));

CREATE TABLE grades (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    "rank" INTEGER,                                             -- ordering hint, nullable, NOT unique (no such rule in D-C0-43 / the C1 contract)
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT grades_code_check CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    CONSTRAINT grades_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT grades_version_check CHECK (version >= 0),
    CONSTRAINT grades_id_tenant_unique UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX grades_code_unique ON grades (tenant_id, lower(code));

-- 4. employee <-> unit membership (multi-membership). The employee is the canonical tenant member --------------------------------------------------------
CREATE TABLE employee_organization_units (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    user_id UUID NOT NULL,
    organization_unit_id UUID NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'MEMBER',        -- business vocabulary only (MEMBER, MANAGER, HEAD, ...): NEVER an authorization input
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,                       -- ending a membership = active FALSE; the row stays (history)
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT employee_organization_units_relation_check CHECK (relation_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT employee_organization_units_primary_active CHECK (NOT is_primary OR active),
    CONSTRAINT employee_organization_units_version_check CHECK (version >= 0),
    -- target of the employee_positions FK: the membership AND its employee, so a position can never be pinned to somebody else's membership
    CONSTRAINT employee_organization_units_id_tenant_user_unique UNIQUE (id, tenant_id, user_id),
    CONSTRAINT employee_organization_units_member_fk FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_members (tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT employee_organization_units_unit_fk FOREIGN KEY (organization_unit_id, tenant_id) REFERENCES organization_units (id, tenant_id) ON DELETE RESTRICT
);
-- one ACTIVE membership per (employee, unit); an ended one does not block a new one
CREATE UNIQUE INDEX employee_organization_units_active_unique ON employee_organization_units (tenant_id, user_id, organization_unit_id) WHERE active;
-- at most ONE active primary membership per employee and tenant (is_primary implies active)
CREATE UNIQUE INDEX employee_organization_units_one_primary_idx ON employee_organization_units (tenant_id, user_id) WHERE is_primary;
-- unit -> its ACTIVE members: activeCountByUnit, member counts, the directory unit filter
CREATE INDEX employee_organization_units_unit_idx ON employee_organization_units (tenant_id, organization_unit_id, user_id) WHERE active;
-- employee -> memberships: the (tenant_id, user_id) lookup of the directory page enrichment
CREATE INDEX employee_organization_units_user_idx ON employee_organization_units (tenant_id, user_id);

-- 5. employee position: held WITHIN one membership; the grade is an attribute of the assignment ----------------------------------------------------------
CREATE TABLE employee_positions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    user_id UUID NOT NULL,
    membership_id UUID NOT NULL,                -- the membership; the unit is DERIVED from it (no second copy to keep in sync)
    position_id UUID NOT NULL,
    grade_id UUID,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT employee_positions_primary_active CHECK (NOT is_primary OR active),
    CONSTRAINT employee_positions_version_check CHECK (version >= 0),
    -- the membership must belong to THE SAME tenant and employee; this also pins the unit to the tenant (the membership already has the unit FK)
    CONSTRAINT employee_positions_membership_fk FOREIGN KEY (membership_id, tenant_id, user_id)
        REFERENCES employee_organization_units (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT employee_positions_position_fk FOREIGN KEY (position_id, tenant_id) REFERENCES positions (id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT employee_positions_grade_fk FOREIGN KEY (grade_id, tenant_id) REFERENCES grades (id, tenant_id) ON DELETE RESTRICT
);
-- one ACTIVE assignment per (membership, position), even with another grade; also the active-positions count of a membership
CREATE UNIQUE INDEX employee_positions_active_unique ON employee_positions (membership_id, position_id) WHERE active;
-- at most ONE active primary position per employee and tenant
CREATE UNIQUE INDEX employee_positions_one_primary_idx ON employee_positions (tenant_id, user_id) WHERE is_primary;
-- employee -> assignments (page enrichment), directory position / grade filters
CREATE INDEX employee_positions_user_idx ON employee_positions (tenant_id, user_id) WHERE active;
CREATE INDEX employee_positions_position_idx ON employee_positions (tenant_id, position_id) WHERE active;
CREATE INDEX employee_positions_grade_idx ON employee_positions (tenant_id, grade_id) WHERE active AND grade_id IS NOT NULL;
