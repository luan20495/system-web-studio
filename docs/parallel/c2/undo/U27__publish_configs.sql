-- U27 — manual undo of V27 (publish_configs). NOT a Flyway migration (lives outside db/migration). Run by a human, in ONE transaction, after the app is stopped.
-- Guard: refuses when any row carries policy a person set after the backfill (so nothing a user chose is silently dropped).
-- To go back to V26 properly also delete the flyway_schema_history row of version 27 (flyway repair/delete is a separate, deliberate step).
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM publish_configs
     WHERE revision > 1 OR public_data_approved OR link_token_hash IS NOT NULL OR visibility NOT IN ('PRIVATE', 'PUBLIC')
        OR cache_seconds IS NOT NULL OR updated_by IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'U27 refused: % publish_configs row(s) hold policy set after the V27 backfill', n;
    END IF;
END $$;
DROP TABLE publish_configs;
