-- V30 (C2, docs/parallel/c0/C2_DEPLOY_CONTRACT.md, D-C0-24 / D-C0-27): deployment rollback status and the release scope lease.
-- Additive: nothing is dropped except the status CHECK, which is replaced by the same list plus ROLLING_BACK.
--
-- Scope of serialization = (tenant, app, environment PRODUCTION) = the `sites` row: a project has exactly one site (sites.project_id is the key),
-- the tenant is reached through projects.tenant_id, and no other environment exists in the schema yet.

-- 1. ROLLING_BACK: the decision "undo this deployment" is durable, so a crashed undo is resumed and never rolled forward again.
--    ROLLBACK_FAILED / ROLLBACK_OFFLINE are NOT statuses (they are events, deployment_events.status has no CHECK).
ALTER TABLE deployments DROP CONSTRAINT deployments_status_check;
ALTER TABLE deployments ADD CONSTRAINT deployments_status_check CHECK (status IN
    ('QUEUED', 'POLICY_CHECK', 'SECURITY_CHECK', 'BUILDING', 'DEPLOYING', 'ROLLING_BACK', 'RUNNING', 'FAILED', 'ROLLED_BACK'));
-- deployments_status_idx (partial: NOT IN the three terminal statuses) already covers ROLLING_BACK and stays as it is.

-- 2. Order of intents. A deployment draws its number when the publish request is accepted (the column default); a rollback or unpublish draws one
--    when it takes the lease. sites.active_seq is the number of the last operation that moved the pointer.
CREATE SEQUENCE deployment_activation_seq;
ALTER TABLE deployments ADD COLUMN activation_seq BIGINT;
UPDATE deployments d SET activation_seq = n.rn FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM deployments) n WHERE n.id = d.id;
SELECT setval('deployment_activation_seq', GREATEST((SELECT coalesce(max(activation_seq), 0) FROM deployments), 1), (SELECT count(*) > 0 FROM deployments));
ALTER TABLE deployments ALTER COLUMN activation_seq SET NOT NULL;
ALTER TABLE deployments ALTER COLUMN activation_seq SET DEFAULT nextval('deployment_activation_seq');
ALTER SEQUENCE deployment_activation_seq OWNED BY deployments.activation_seq;
CREATE UNIQUE INDEX deployments_activation_seq_uq ON deployments (activation_seq);

-- 3. The release that was active when this deployment started switching (typed; replaces parsing the text of a SWITCH event).
--    Backfill from the existing SWITCH events, accepting only a deployment of the same project.
ALTER TABLE deployments ADD COLUMN previous_deployment_id UUID REFERENCES deployments(id);
UPDATE deployments d SET previous_deployment_id = e.prev
  FROM (SELECT DISTINCT ON (ev.deployment_id) ev.deployment_id,
               (regexp_match(ev.message, '\[([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\]'))[1]::uuid AS prev
          FROM deployment_events ev WHERE ev.status = 'SWITCH' ORDER BY ev.deployment_id, ev.created_at, ev.id) e
 WHERE e.deployment_id = d.id AND e.prev IS NOT NULL AND e.prev <> d.id
   AND EXISTS (SELECT 1 FROM deployments p WHERE p.id = e.prev AND p.project_id = d.project_id);

-- 4. The scope row. One operation owns it at a time (lease); the active pointer moves only by compare-and-set on pointer_version while the
--    writer still holds the lease with its own fencing token.
--      pointer_version      +1 on every pointer change (the CAS guard)
--      active_seq           activation number of the operation that last moved the pointer
--      active_operation_id  identity of that operation (equality of active_seq only means "the same operation resuming" if this matches)
--      fence_counter        monotonic; every acquisition of the lease (a takeover, or the same operation resuming) draws the next token
--      lease_*              the current owner; NULL = free. lease_fence is the owner's fencing token, a writer with an older one is fenced out
ALTER TABLE sites ADD COLUMN pointer_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sites ADD COLUMN active_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sites ADD COLUMN active_operation_id UUID;
ALTER TABLE sites ADD COLUMN fence_counter BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sites ADD COLUMN lease_operation_id UUID;
ALTER TABLE sites ADD COLUMN lease_kind VARCHAR(16);
ALTER TABLE sites ADD COLUMN lease_deployment_id UUID REFERENCES deployments(id);
ALTER TABLE sites ADD COLUMN lease_seq BIGINT;
ALTER TABLE sites ADD COLUMN lease_fence BIGINT;
ALTER TABLE sites ADD COLUMN lease_holder VARCHAR(64);
ALTER TABLE sites ADD COLUMN lease_started_at TIMESTAMPTZ;
ALTER TABLE sites ADD COLUMN lease_until TIMESTAMPTZ;
UPDATE sites s SET active_seq = d.activation_seq, active_operation_id = d.id FROM deployments d WHERE d.id = s.current_deployment_id;

ALTER TABLE sites ADD CONSTRAINT sites_lease_kind_check CHECK (lease_kind IN ('PUBLISH', 'ROLLBACK', 'UNPUBLISH'));
-- a lease is all or nothing; a PUBLISH lease names its deployment; a token is never ahead of the counter
ALTER TABLE sites ADD CONSTRAINT sites_lease_shape_check CHECK (
    (lease_operation_id IS NULL) = (lease_until IS NULL) AND (lease_operation_id IS NULL) = (lease_kind IS NULL)
    AND (lease_operation_id IS NULL) = (lease_seq IS NULL) AND (lease_operation_id IS NULL) = (lease_fence IS NULL)
    AND (lease_operation_id IS NULL) = (lease_holder IS NULL) AND (lease_operation_id IS NULL) = (lease_started_at IS NULL)
    AND (lease_kind IS DISTINCT FROM 'PUBLISH' OR lease_deployment_id IS NOT NULL)
    AND (lease_operation_id IS NOT NULL OR lease_deployment_id IS NULL));
ALTER TABLE sites ADD CONSTRAINT sites_fence_check CHECK (fence_counter >= 0 AND (lease_fence IS NULL OR lease_fence <= fence_counter));
ALTER TABLE sites ADD CONSTRAINT sites_pointer_check CHECK (pointer_version >= 0 AND active_seq >= 0);

-- diagnostics and the sweep of expired leases
CREATE INDEX sites_lease_until_idx ON sites (lease_until) WHERE lease_operation_id IS NOT NULL;
