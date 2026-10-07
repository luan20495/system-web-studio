-- U28 — manual undo of V28 (data_runtime). NOT a Flyway migration (lives outside db/migration). Run by a human, in ONE transaction, after the app is stopped.
-- Guard: refuses when any data source or credential exists (queries, mutations, bindings, snapshots and idempotency records all hang off a data source), so
-- nothing a person registered after V28 is silently dropped. To go back to V27 properly also delete the flyway_schema_history row of version 28
-- (flyway repair/delete is a separate, deliberate step).
DO $$
DECLARE n bigint;
BEGIN
    SELECT (SELECT count(*) FROM data_sources) + (SELECT count(*) FROM data_credentials) INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'U28 refused: % data source / credential row(s) exist; remove them deliberately first', n;
    END IF;
END $$;
DROP TABLE data_source_bindings;
DROP TABLE data_idempotency;
DROP TABLE data_mutations;
DROP TABLE data_queries;
DROP TABLE source_schemas;
DROP TABLE data_credentials;
DROP TABLE data_sources;
