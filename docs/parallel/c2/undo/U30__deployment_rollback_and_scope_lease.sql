-- U30 — manual undo of V30 (deployment rollback status + release scope lease). NOT a Flyway migration (lives outside db/migration). Run by a human, in ONE
-- transaction, after the app is stopped.
-- Guard: refuses while a deployment is ROLLING_BACK (the status would no longer be valid) or any scope lease is held (an operation is in flight).
-- To go back to V29 properly also delete the flyway_schema_history row of version 30 (flyway repair/delete is a separate, deliberate step).
DO $$
DECLARE rolling bigint; leased bigint;
BEGIN
    SELECT count(*) INTO rolling FROM deployments WHERE status = 'ROLLING_BACK';
    SELECT count(*) INTO leased FROM sites WHERE lease_operation_id IS NOT NULL;
    IF rolling > 0 THEN RAISE EXCEPTION 'U30 refused: % deployment(s) are ROLLING_BACK', rolling; END IF;
    IF leased > 0 THEN RAISE EXCEPTION 'U30 refused: % site scope lease(s) are held', leased; END IF;
END $$;
DROP INDEX sites_lease_until_idx;
ALTER TABLE sites DROP CONSTRAINT sites_pointer_check;
ALTER TABLE sites DROP CONSTRAINT sites_fence_check;
ALTER TABLE sites DROP CONSTRAINT sites_lease_shape_check;
ALTER TABLE sites DROP CONSTRAINT sites_lease_kind_check;
ALTER TABLE sites DROP COLUMN lease_until, DROP COLUMN lease_started_at, DROP COLUMN lease_holder, DROP COLUMN lease_fence, DROP COLUMN lease_seq,
    DROP COLUMN lease_deployment_id, DROP COLUMN lease_kind, DROP COLUMN lease_operation_id, DROP COLUMN fence_counter,
    DROP COLUMN active_operation_id, DROP COLUMN active_seq, DROP COLUMN pointer_version;
ALTER TABLE deployments DROP COLUMN previous_deployment_id;
DROP INDEX deployments_activation_seq_uq;
ALTER TABLE deployments DROP COLUMN activation_seq;
DROP SEQUENCE IF EXISTS deployment_activation_seq;
ALTER TABLE deployments DROP CONSTRAINT deployments_status_check;
ALTER TABLE deployments ADD CONSTRAINT deployments_status_check CHECK (status IN
    ('QUEUED', 'POLICY_CHECK', 'SECURITY_CHECK', 'BUILDING', 'DEPLOYING', 'RUNNING', 'FAILED', 'ROLLED_BACK'));
