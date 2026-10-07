-- U29 — manual undo of V29 (workflow_run_persistence). NOT a Flyway migration (lives outside db/migration). Run by a human, in ONE transaction, after the
-- application is stopped (or with app.workflow.enabled=false AND app.workflow.run-store=memory, so nothing writes to these tables).
-- Guard: refuses while any run is NOT finished (an action run that is RUNNING, a workflow run that is PENDING / RUNNING / WAITING or whose compensation is
-- IN_PROGRESS): dropping the tables would silently abandon work in flight, and the replay guarantee of every recorded idempotency key (action_runs) would end.
-- Finished runs are history and are dropped with the tables; export them first if they matter. To go back to V28 properly also delete the
-- flyway_schema_history row of version 29 (flyway repair/delete is a separate, deliberate step). Does not touch V28 or any other table.
DO $$
DECLARE n bigint;
BEGIN
    SELECT (SELECT count(*) FROM action_runs WHERE status = 'RUNNING')
         + (SELECT count(*) FROM workflow_runs WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS')
      INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'U29 refused: % run(s) are still in flight; let them finish or fail them deliberately first', n;
    END IF;
END $$;
DROP TABLE workflow_run_steps;
DROP TABLE workflow_runs;
DROP TABLE action_runs;
