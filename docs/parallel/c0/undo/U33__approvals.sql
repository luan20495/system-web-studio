-- U33 - manual undo of V33 (approvals). NOT a Flyway migration (lives outside db/migration). Run by a human, in ONE transaction, after the application is stopped
-- (or with app.workflow.approvals=off, so nothing writes to the table).
-- Guard: refuses while any approval is PENDING: a workflow run WAITING for it would never resume. Final approvals (APPROVED / REJECTED / EXPIRED / CANCELLED) are history
-- and are dropped with the table; export them first if they matter. To go back to V32 properly also delete the flyway_schema_history row of version 33 (a separate,
-- deliberate step). Does not touch V32 or any other table.
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM approvals WHERE status = 'PENDING';
    IF n > 0 THEN
        RAISE EXCEPTION 'U33 refused: % PENDING approval(s); workflow runs wait for them, decide or cancel them deliberately first', n;
    END IF;
END $$;
DROP TABLE approvals;
