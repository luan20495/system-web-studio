package com.systemwebstudio.data.hardening

import com.systemwebstudio.data.org.FlywayHarness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * DATABASE audit of what the data platform and the organization persistence rely on, on a database migrated by the REAL Flyway migrations (V1..V32):
 *  - structure: tenant_id mandatory, composite tenant FKs, every foreign key backed by an index, no destructive statement in the data / organization migrations;
 *  - plans: the statements the production adapters run, EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) on a set-based seed of 200 tenants / 20,000 data sources / 200,000 queries /
 *    100,000 mutations / 500,000 idempotency rows / 10,000 bindings / 1,000,000 audit events. Plans are asserted to use an index (no sequential scan of a big table) and are
 *    written, with their timings, to build/reports/data-plan-audit.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSchemaAndPlanAuditTests {
    private lateinit var db: FlywayHarness.Database
    private lateinit var jdbc: JdbcTemplate
    private val json = JsonMapper.builder().build()
    private val report = StringBuilder()
    private val notes = StringBuilder()
    private val dataTables = listOf("data_sources", "data_credentials", "source_schemas", "data_queries", "data_mutations", "data_idempotency", "data_source_bindings")
    private val orgTables = listOf("organization_unit_types", "organization_units", "employee_organization_units", "positions", "grades", "employee_positions")

    @BeforeAll fun migrateAndSeed() {
        db = FlywayHarness.newDatabase("planaudit"); jdbc = db.jdbc
        FlywayHarness.flyway(db).migrate()
        val seedFile = """
            CREATE TEMP TABLE tt AS SELECT n, gen_random_uuid() AS id FROM generate_series(1, 200) n;
            INSERT INTO tenants (id, slug, name) SELECT id, 'pa-' || n, 'Plan ' || n FROM tt;
            CREATE TEMP TABLE ww AS SELECT t.id AS tenant_id, gen_random_uuid() AS id, w AS k FROM tt t, generate_series(1, 5) w;
            INSERT INTO workspaces (id, name, slug, tenant_id) SELECT id, 'ws', 'w-' || replace(id::text, '-', ''), tenant_id FROM ww;
            INSERT INTO users (id, username, password_hash, display_name, email) VALUES ('11111111-1111-1111-1111-111111111111', 'plan-owner', 'x', 'Owner', 'plan-owner@example.test');
            INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) SELECT gen_random_uuid(), w.id, 'p' || p, '11111111-1111-1111-1111-111111111111', w.tenant_id FROM ww w, generate_series(1, 10) p;
            INSERT INTO data_sources (id, tenant_id, workspace_id, type, name, status, created_at)
                SELECT gen_random_uuid(), w.tenant_id, w.id, 'rest', 'src-' || w.k || '-' || s, 'ACTIVE', now() - (s || ' minutes')::interval FROM ww w, generate_series(1, 20) s;
            INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) SELECT tenant_id, id, 'q' || q, 'SQL', '{"sql":"SELECT 1"}'::jsonb FROM data_sources, generate_series(1, 10) q;
            INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition) SELECT tenant_id, id, 'm' || q, 'CREATE', '{"target":"t"}'::jsonb FROM data_sources, generate_series(1, 5) q;
            INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at)
                SELECT s.tenant_id, s.id, 'm' || (1 + k % 5), 'key' || lpad(k::text, 12, '0'), 'fp', CASE WHEN k % 7 = 0 THEN 'UNKNOWN' WHEN k % 3 = 0 THEN 'RESERVED' ELSE 'DONE' END, now() + interval '5 minutes',
                       CASE WHEN k = 25 THEN now() - ((s.id::text < '8')::int + 1 || ' hours')::interval ELSE now() + interval '20 days' END
                FROM data_sources s, generate_series(1, 25) k;
            INSERT INTO source_schemas (id, tenant_id, data_source_id, version, discovered_at, fingerprint, data_source_version, includes_samples, snapshot)
                SELECT gen_random_uuid(), s.tenant_id, s.id, v, now(), 'fp' || v, 1, false, '{"entities":[]}'::jsonb FROM data_sources s, generate_series(1, 3) v WHERE s.id::text < '4';
            INSERT INTO data_source_bindings (tenant_id, workspace_id, project_id, mode, slot_id, data_source_id)
                SELECT s.tenant_id, s.workspace_id, p.id, 'LIVE', 'slot-' || s.rn, s.id
                FROM (SELECT id, tenant_id, workspace_id, row_number() OVER (PARTITION BY workspace_id ORDER BY id) rn FROM data_sources) s
                JOIN (SELECT id, workspace_id, row_number() OVER (PARTITION BY workspace_id ORDER BY id) rn FROM projects) p ON p.workspace_id = s.workspace_id AND p.rn = s.rn WHERE s.rn <= 10;
            INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, new_value, created_at)
                SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', CASE WHEN rn % 2 = 0 THEN 'DATASOURCE_CREATED' ELSE 'DATASOURCE_CREDENTIAL_ROTATED' END, 'DATA_SOURCE', id::text,
                       jsonb_build_object('tenantId', tenant_id::text, 'hasCredential', 'true'), now() - (rn || ' minutes')::interval
                FROM (SELECT id, tenant_id, row_number() OVER (ORDER BY id) rn FROM data_sources LIMIT 4000) x;
            INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, new_value, created_at)
                SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', CASE WHEN g % 3 = 0 THEN 'QUERY_SERVED' WHEN g % 3 = 1 THEN 'LOGIN' ELSE 'PROJECT_UPDATED' END, 'PROJECT', gen_random_uuid()::text,
                       jsonb_build_object('tenantId', gen_random_uuid()::text), now() - (g || ' seconds')::interval
                FROM generate_series(1, 996000) g;
            ANALYZE;"""
        db.ds.connection.use { c -> c.createStatement().use { it.execute(seedFile) } }
        report.append("")
    }

    @AfterAll fun write() {
        val dir = Path.of("build", "reports"); Files.createDirectories(dir); val text = "# Data / organization database audit - plans on a seeded schema\n\n| statement | plan nodes | exec ms | planning ms | shared hit / read | rows |\n|---|---|---|---|---|---|\n$report\n$notes"; Files.writeString(dir.resolve("data-plan-audit.md"), text); println(text); db.close()
    }

    // ------------------------------------------------------------------------------------------------ structure

    @Test
    fun `tenant_id is mandatory and references tenants on every data and organization table, and every cross-table reference carries the tenant`() {
        for (t in dataTables + orgTables) {
            assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id'", String::class.java, t)).describedAs("$t.tenant_id").isEqualTo("NO")
            assertThat(jdbc.queryForObject("""SELECT count(*) FROM pg_constraint c WHERE c.conrelid = ?::regclass AND c.contype = 'f' AND c.confrelid = 'tenants'::regclass
                AND (SELECT attname FROM pg_attribute WHERE attrelid = c.conrelid AND attnum = c.conkey[1]) = 'tenant_id'""", Long::class.java, t)).describedAs("$t has an FK tenant_id -> tenants").isEqualTo(1)
        }
        // foreign keys between tenant-owned tables of these two modules must be composite and include tenant_id on BOTH sides
        val weak = jdbc.queryForList("""
            SELECT c.conrelid::regclass::text || '.' || c.conname AS fk FROM pg_constraint c
            WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (?) AND c.confrelid::regclass::text = ANY (?)
              AND NOT ((SELECT array_agg(attname) FROM pg_attribute WHERE attrelid = c.conrelid AND attnum = ANY (c.conkey)) @> ARRAY['tenant_id']::name[])""",
            String::class.java, (dataTables + orgTables).toTypedArray(), (dataTables + orgTables).toTypedArray())
        assertThat(weak).describedAs("FKs between tenant-owned tables that do not carry tenant_id").isEmpty()
        notes.append("\n_structure_: ${dataTables.size + orgTables.size} tables, tenant_id NOT NULL + FK to tenants on all; 0 cross-table FKs without tenant_id.\n\n")
    }

    @Test
    fun `every foreign key of the data and organization tables has an index that leads with one of its columns, and the ones served only by a partial index are listed`() {
        val sql = """
            SELECT c.conrelid::regclass::text || '.' || c.conname AS fk,
                   EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indisvalid AND (i.indkey::int2[])[0] = ANY (c.conkey)) AS any_index,
                   EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indpred IS NULL AND (i.indkey::int2[])[0] = ANY (c.conkey)) AS full_index
            FROM pg_constraint c WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (?) AND c.confrelid <> 'users'::regclass ORDER BY 1"""
        val rows = jdbc.queryForList(sql, (dataTables + orgTables).toTypedArray())
        assertThat(rows.filter { it["any_index"] == false }.map { it["fk"] }).describedAs("foreign keys with no index at all").isEmpty()
        val partialOnly = rows.filter { it["full_index"] == false }.map { it["fk"].toString() }
        notes.append("\n_foreign keys served only by a PARTIAL index_ (a hard delete of the parent would scan the child; the application never hard-deletes them): ${partialOnly.ifEmpty { listOf("none") }.joinToString(", ")}\n\n")
        assertThat(partialOnly.filter { it.startsWith("data_") || it.startsWith("source_") }).describedAs("the data runtime tables have full indexes").isEmpty()
    }

    @Test
    fun `no destructive statement in the data runtime and organization migrations, and no migration is out of order`() {
        val dir = listOf(Path.of("..", "backend", "src", "main", "resources", "db", "migration"), Path.of("src", "main", "resources", "db", "migration")).first { Files.exists(it) }
        val destructive = Regex("(?im)^\\s*(DROP\\s+(TABLE|COLUMN|SCHEMA|INDEX|TYPE|CONSTRAINT)|TRUNCATE|DELETE\\s+FROM|UPDATE\\s+\\w+\\s+SET|ALTER\\s+TABLE\\s+\\w+\\s+(DROP|ALTER\\s+COLUMN\\s+\\w+\\s+TYPE|RENAME))")
        for (name in listOf("V28__data_runtime.sql", "V29__workflow_run_persistence.sql", "V32__dynamic_organization.sql"))
            assertThat(destructive.findAll(Files.readString(dir.resolve(name))).map { it.value.trim() }.toList()).describedAs("$name is additive only").isEmpty()
        val applied = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank", String::class.java).map { it.toInt() }
        assertThat(applied).isSorted(); assertThat(applied.last()).isEqualTo(32)
    }

    // ------------------------------------------------------------------------------------------------ plans

    private class Plan(val nodes: List<String>, val execMs: Double, val planMs: Double, val hit: Long, val read: Long, val rows: Long)

    private fun explain(label: String, sql: String, vararg args: Any?, rollback: Boolean = false): Plan {
        fun run(): String = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql", String::class.java, *args)!!
        val text = if (rollback) { var out = ""; val tx = TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(db.ds)); tx.execute { st -> out = run(); st.setRollbackOnly() }; out } else run()
        val root: JsonNode = json.readTree(text).get(0); val plan = root.get("Plan"); val nodes = ArrayList<String>()
        fun walk(n: JsonNode) { nodes += n.get("Node Type").asString() + (n.get("Relation Name")?.asString()?.let { " on $it" } ?: "") + (n.get("Index Name")?.asString()?.let { " using $it" } ?: ""); n.get("Plans")?.forEach { walk(it) } }
        walk(plan)
        val p = Plan(nodes, root.get("Execution Time").asDouble(), root.get("Planning Time").asDouble(), plan.get("Shared Hit Blocks").asLong(), plan.get("Shared Read Blocks").asLong(), plan.get("Actual Rows").asLong())
        report.append("| $label | ${p.nodes.joinToString("; ")} | ${"%.2f".format(p.execMs)} | ${"%.2f".format(p.planMs)} | ${p.hit}/${p.read} | ${p.rows} |\n")
        return p
    }
    private fun noSeqScan(p: Plan, vararg tables: String) = tables.forEach { t -> assertThat(p.nodes).describedAs("sequential scan of $t").noneMatch { it == "Seq Scan on $t" } }

    private val tenant get() = jdbc.queryForObject("SELECT tenant_id FROM data_sources ORDER BY id LIMIT 1", UUID::class.java)!!
    private val source get() = jdbc.queryForMap("SELECT id, workspace_id FROM data_sources WHERE tenant_id = ? ORDER BY id LIMIT 1", tenant)

    @Test
    fun `data source, definition and binding statements use their indexes on a 20,000-source schema`() {
        val t = tenant; val s = source; val sid = s["id"] as UUID; val ws = s["workspace_id"] as UUID
        val project = jdbc.queryForObject("SELECT project_id FROM data_source_bindings WHERE tenant_id = ? LIMIT 1", UUID::class.java, t)
        val cols = "id, tenant_id, workspace_id, type, name, status, config_nonsecret, credential_ref, created_by, created_at, updated_at, version"
        noSeqScan(explain("find data source (tenant, id)", "SELECT $cols FROM data_sources WHERE tenant_id = ? AND id = ?", t, sid), "data_sources")
        noSeqScan(explain("find data source (tenant, workspace, id)", "SELECT $cols FROM data_sources WHERE tenant_id = ? AND workspace_id = ? AND id = ?", t, ws, sid), "data_sources")
        noSeqScan(explain("list data sources of a workspace (LIMIT 1000)", "SELECT $cols FROM data_sources WHERE tenant_id = ? AND workspace_id = ? ORDER BY created_at, id LIMIT 1000", t, ws), "data_sources")
        noSeqScan(explain("list data sources of a tenant (LIMIT 1000)", "SELECT $cols FROM data_sources WHERE tenant_id = ? ORDER BY created_at, id LIMIT 1000", t), "data_sources")
        noSeqScan(explain("find query (tenant, source, id)", "SELECT kind, query_id, definition::text, version FROM data_queries WHERE tenant_id = ? AND data_source_id = ? AND status = 'ACTIVE' AND query_id = ?", t, sid, "q3"), "data_queries")
        noSeqScan(explain("list queries of a source", "SELECT kind, query_id, definition::text, version FROM data_queries WHERE tenant_id = ? AND data_source_id = ? AND status = 'ACTIVE' ORDER BY query_id LIMIT 1000", t, sid), "data_queries")
        noSeqScan(explain("find mutation (tenant, source, id)", "SELECT kind, mutation_id, definition::text, version FROM data_mutations WHERE tenant_id = ? AND data_source_id = ? AND status = 'ACTIVE' AND mutation_id = ?", t, sid, "m2"), "data_mutations")
        noSeqScan(explain("runtime bindings of a project and mode (ownership joins)", """SELECT b.slot_id, b.data_source_id FROM data_source_bindings b
            JOIN projects p ON p.id = b.project_id AND p.workspace_id = b.workspace_id AND p.tenant_id = b.tenant_id
            JOIN data_sources s ON s.id = b.data_source_id AND s.tenant_id = b.tenant_id AND s.workspace_id = b.workspace_id WHERE b.tenant_id = ? AND b.project_id = ? AND b.mode = ?""", t, project, "LIVE"), "data_source_bindings", "data_sources", "projects")
        noSeqScan(explain("delete guard - bindings of a source", "SELECT count(*) FROM data_source_bindings WHERE tenant_id = ? AND data_source_id = ?", t, sid), "data_source_bindings")
        noSeqScan(explain("delete guard - unfinished idempotency of a source", "SELECT count(*) FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? AND state IN ('RESERVED', 'UNKNOWN')", t, sid), "data_idempotency")
        noSeqScan(explain("latest schema snapshot of a source", "SELECT id, version FROM source_schemas WHERE tenant_id = ? AND data_source_id = ? ORDER BY version DESC LIMIT 1", t, sid), "source_schemas")
    }

    @Test
    fun `idempotency statements - the key lookup is a primary-key probe and the retention purge walks the expiry index, on 500,000 rows`() {
        val t = tenant; val sid = source["id"] as UUID
        val key = jdbc.queryForObject("SELECT idem_key FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? LIMIT 1", String::class.java, t, sid)
        val p = explain("idempotency key lookup (PK)", "SELECT fingerprint, state, affected, output_json, completed_at, expires_at FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ?", t, sid, "m1", key)
        noSeqScan(p, "data_idempotency"); assertThat(p.execMs).isLessThan(50.0)
        val sel = explain("purge candidates (LIMIT 1000)", "SELECT ctid FROM data_idempotency WHERE expires_at <= now() LIMIT 1000")
        assertThat(sel.nodes.joinToString()).describedAs("the purge must use data_idempotency_expiry_idx").contains("data_idempotency_expiry_idx"); assertThat(sel.rows).isEqualTo(1000)
        val purge = explain("purge DELETE (rolled back)", "DELETE FROM data_idempotency WHERE ctid IN (SELECT ctid FROM data_idempotency WHERE expires_at <= now() LIMIT 1000)", rollback = true)
        assertThat(purge.nodes.joinToString()).contains("data_idempotency_expiry_idx")
        val del = explain("delete a source's idempotency rows (rolled back)", "DELETE FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ?", t, sid, rollback = true)
        noSeqScan(del, "data_idempotency")
    }

    @Test
    fun `credential-author lookup reads the audit trail - measured on 1,000,000 audit events`() {
        val t = tenant; val sid = source["id"] as UUID
        val p = explain("credential actor lookup over audit_events (1M rows)", """SELECT actor_id FROM audit_events WHERE resource_type = 'DATA_SOURCE' AND resource_id = ? AND new_value ->> 'tenantId' = ? AND actor_id IS NOT NULL
            AND (action = 'DATASOURCE_CREDENTIAL_ROTATED' OR (action = 'DATASOURCE_CREATED' AND new_value ->> 'hasCredential' = 'true')) ORDER BY created_at DESC, id DESC LIMIT 1""", sid.toString(), t.toString())
        notes.append("\n_audit_events lookup_: plan `${p.nodes.joinToString("; ")}` in ${"%.1f".format(p.execMs)} ms (finding F-3 if a sequential scan).\n\n")
        assertThat(p.execMs).describedAs("must stay interactive on a 1M-row trail").isLessThan(2_000.0)
    }
}
