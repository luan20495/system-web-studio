package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The REAL Flyway path of `V32__dynamic_organization.sql`, run on the repository's own `db/migration` directory (no copy, no pending script): clean V1 -> V32 and upgrade V30 -> V32,
 * the checksum is stable across both paths, and the duplicate-migration guard (exactly one V32, V33 not consumed, no duplicate version).
 */
class OrgV32FlywayTests {
    private fun repoPath(rel: String): Path = listOf(Path.of("..", rel), Path.of(rel)).first { Files.exists(it) }

    /** the repository's real migration directory */
    private fun migrationDir(): Path = repoPath("backend/src/main/resources/db/migration")

    private fun flyway(db: FlywayHarness.Database, dir: Path, target: String? = null): Flyway =
        Flyway.configure().dataSource(db.ds).locations("filesystem:$dir").also { if (target != null) it.target(MigrationVersion.fromVersion(target)) }.outOfOrder(false).load()

    @Test
    fun `clean database - V1 to V32 applies in order successfully and validates, the absent V31 of C2 is only a gap`() {
        val dir = migrationDir()
        FlywayHarness.newDatabase("v32clean").use { db ->
            val fw = flyway(db, dir); val result = fw.migrate()
            assertThat(result.success).isTrue()
            val info = fw.info()
            assertThat(info.current().version.version).isEqualTo("32"); assertThat(info.pending()).isEmpty()
            assertThat(info.applied().map { it.state }).containsOnly(MigrationState.SUCCESS)
            assertThat(info.applied().map { it.version.version }.map { it.toInt() }).isSorted(); assertThat(info.applied().last().description).containsIgnoringCase("dynamic organization")
            fw.validate()
            val tables = db.jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('organization_unit_types','organization_units','employee_organization_units','positions','grades','employee_positions')", String::class.java)
            assertThat(tables).hasSize(6)
            // the schema works as migrated: a tenant, a type, a root, a child, a member, a membership
            val t = OrgTestDb.newTenant(jdbc = db.jdbc); val ty = UUID.randomUUID(); val root = UUID.randomUUID(); val child = UUID.randomUUID()
            db.jdbc.update("INSERT INTO organization_unit_types (id, tenant_id, code, name) VALUES (?, ?, 'x', 'X')", ty, t)
            db.jdbc.update("INSERT INTO organization_units (id, tenant_id, type_id, code, name) VALUES (?, ?, ?, 'ROOT', 'Root')", root, t, ty)
            db.jdbc.update("INSERT INTO organization_units (id, tenant_id, type_id, parent_id, code, name) VALUES (?, ?, ?, ?, 'KID', 'Kid')", child, t, ty, root)
            val u = OrgTestDb.newMember(t, jdbc = db.jdbc); db.jdbc.update("INSERT INTO employee_organization_units (id, tenant_id, user_id, organization_unit_id) VALUES (?, ?, ?, ?)", UUID.randomUUID(), t, u, child)
            println("EVIDENCE flyway clean: V1 -> V${info.current().version.version} on the repository db/migration, ${info.applied().size} migrations, all SUCCESS, validate OK")
        }
    }

    @Test
    fun `upgrade - a database at V30 receives ONLY the organization migration through Flyway, legacy departments users tenant_members workspaces are byte-identical, no organization row appears`() {
        val dir = migrationDir()
        FlywayHarness.newDatabase("v32up").use { db ->
            flyway(db, dir, "30").migrate()
            assertThat(flyway(db, dir).info().current().version.version).isEqualTo("30")
            val dept = UUID.randomUUID(); val team = UUID.randomUUID(); val user = UUID.randomUUID(); val ws = UUID.randomUUID()
            db.jdbc.update("INSERT INTO departments (id, name, kind) VALUES (?, 'Engineering', 'DEPARTMENT')", dept)
            db.jdbc.update("INSERT INTO departments (id, name, kind, parent_id) VALUES (?, 'Platform', 'TEAM', ?)", team, dept)
            db.jdbc.update("INSERT INTO users (id, username, password_hash, display_name, email) VALUES (?, 'legacy-user', 'x', 'Legacy User', 'legacy@corp.example')", user)
            db.jdbc.update("UPDATE users SET department_id = ? WHERE id = ?", team, user)
            db.jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES ('00000000-0000-0000-0000-000000000001', ?, 'TENANT_ADMIN')", user)
            db.jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id, department_id) VALUES (?, 'Legacy WS', 'legacy-ws', '00000000-0000-0000-0000-000000000001', ?)", ws, dept)
            val tables = mapOf("departments" to "id, name, kind, parent_id", "users" to "id, username, display_name, email, enabled, department_id", "tenant_members" to "tenant_id, user_id, role, active", "workspaces" to "id, name, slug, tenant_id, department_id", "tenants" to "id, slug, name, status")
            val before = FlywayHarness.snapshot(db.jdbc, tables)

            val fw = flyway(db, dir); val result = fw.migrate()
            assertThat(result.success).isTrue(); assertThat(result.migrationsExecuted).isEqualTo(1)
            assertThat(fw.info().current().version.version).isEqualTo("32"); fw.validate()
            assertThat(FlywayHarness.snapshot(db.jdbc, tables)).describedAs("nothing of the existing data changed").isEqualTo(before)
            assertThat(db.jdbc.queryForObject("SELECT department_id FROM users WHERE id = ?", UUID::class.java, user)).isEqualTo(team)         // V20 departments untouched, still wired
            listOf("organization_unit_types", "organization_units", "employee_organization_units", "positions", "grades", "employee_positions").forEach {
                assertThat(db.jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)).describedAs("$it starts empty (no backfill, no data copied)").isZero()
            }
            println("EVIDENCE flyway upgrade: V30 -> V32 executed ${result.migrationsExecuted} migration; legacy ${before.perTable.mapValues { it.value.first }} unchanged; six organization tables empty")
        }
    }

    @Test
    fun `the guarded undo script drops the six tables on an empty database and REFUSES while any organization row exists`() {
        val dir = migrationDir(); val undoSql = Files.readString(repoPath("docs/parallel/c3/undo/U32__dynamic_organization.sql"))
        fun runUndo(db: FlywayHarness.Database) = db.ds.connection.use { c -> c.createStatement().use { it.execute(undoSql) } }                    // psql-like: the whole script, dollar-quoted DO block included
        FlywayHarness.newDatabase("v32undo").use { db ->
            flyway(db, dir).migrate()
            val t = OrgTestDb.newTenant(jdbc = db.jdbc); db.jdbc.update("INSERT INTO organization_unit_types (id, tenant_id, code, name) VALUES (?, ?, 'x', 'X')", UUID.randomUUID(), t)
            assertThatThrownBy { runUndo(db) }.satisfies({ e -> assertThat(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("undo of the dynamic organization migration refused") == true }).isTrue() })
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'organization_units'", Long::class.java)).isEqualTo(1)   // nothing dropped
            db.jdbc.update("DELETE FROM organization_unit_types")
            runUndo(db)
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('organization_unit_types','organization_units','employee_organization_units','positions','grades','employee_positions')", Long::class.java)).isZero()
        }
    }

    private fun versions(): Map<Int, List<String>> =
        Files.list(migrationDir()).use { st -> st.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList() }
            .map { Regex("^V(\\d+)__.+\\.sql$").find(it)?.groupValues?.get(1)?.toInt() to it }.also { l -> assertThat(l.filter { it.first == null }).describedAs("every .sql in db/migration is a versioned migration").isEmpty() }
            .groupBy({ it.first!! }, { it.second })

    @Test
    fun `duplicate migration guard - exactly one V32, named for the organization, V33 and above not consumed, no version used twice`() {
        val v = versions()
        assertThat(v.filterValues { it.size > 1 }).describedAs("no duplicate migration version").isEmpty()
        assertThat(v[32]).describedAs("exactly one V32").containsExactly("V32__dynamic_organization.sql")
        assertThat(v.keys.filter { it > 32 }).describedAs("V33 and above are not consumed").isEmpty()
        assertThat(v.keys).describedAs("V31 belongs to C2: not taken here").doesNotContain(31)
        assertThat(v.keys.max()).isEqualTo(32)
        println("EVIDENCE migration guard: ${v.size} files, highest V${v.keys.max()}, V32 = ${v[32]}, no V33, no duplicate version")
    }

    @Test
    fun `checksum is stable - the clean run and the V30 upgrade record the same V32 checksum, it equals the file's, a second migrate is a no-op and validate passes`() {
        val dir = migrationDir()
        fun v32Checksum(db: FlywayHarness.Database) = db.jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '32' AND success", Int::class.java)
        FlywayHarness.newDatabase("v32suma").use { a -> FlywayHarness.newDatabase("v32sumb").use { b ->
            flyway(a, dir).migrate()
            flyway(b, dir, "30").migrate(); flyway(b, dir).migrate()
            val ca = v32Checksum(a); val cb = v32Checksum(b)
            assertThat(ca).describedAs("clean and upgrade record the same checksum").isEqualTo(cb)
            val fw = flyway(a, dir)
            assertThat(fw.info().applied().last().checksum).isEqualTo(ca)                                    // = Flyway's checksum of the file on disk
            assertThat(fw.migrate().migrationsExecuted).describedAs("a second run applies nothing").isZero(); fw.validate()
            val flywayHistory = a.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long::class.java)
            assertThat(flywayHistory).isEqualTo(a.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL", Long::class.java))
            println("EVIDENCE checksum: V32 checksum $ca on the clean run and on the V30 upgrade, equal to the file; second migrate executed 0; validate OK")
        } }
    }
}
