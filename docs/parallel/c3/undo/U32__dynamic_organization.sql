-- Undo of the Dynamic Organization migration (C3). GUARDED: refuses while ANY organization row exists (assignments reference real people; there is no destructive rollback of live data -
-- forward-fix instead). Safe only before the feature is switched on. Run manually by C0 / the owner, never by Flyway.
DO $$
DECLARE n BIGINT;
BEGIN
    SELECT (SELECT count(*) FROM employee_positions) + (SELECT count(*) FROM employee_organization_units) + (SELECT count(*) FROM positions)
         + (SELECT count(*) FROM grades) + (SELECT count(*) FROM organization_units) + (SELECT count(*) FROM organization_unit_types) INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'undo of the dynamic organization migration refused: % organization rows exist', n;
    END IF;
END $$;
DROP TABLE employee_positions;
DROP TABLE employee_organization_units;
DROP TABLE grades;
DROP TABLE positions;
DROP TABLE organization_units;
DROP TABLE organization_unit_types;
-- (if the migration was recorded by Flyway: DELETE FROM flyway_schema_history WHERE version = '32';)
