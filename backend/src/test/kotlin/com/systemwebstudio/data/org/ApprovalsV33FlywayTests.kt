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
 * The REAL Flyway path of `V33__approvals.sql` (D-C0-61) on the repository's own `db/migration` directory: clean V1 -> V33, the upgrade V32 -> V33 executes exactly that one migration,
 * a second migrate is a no-op, and the guarded undo `docs/parallel/c0/undo/U33__approvals.sql` refuses while an approval is PENDING and drops the table when none is.
 */
class ApprovalsV33FlywayTests {
    private fun repoPath(rel: String): Path = listOf(Path.of("..", rel), Path.of(rel)).first { Files.exists(it) }
    private fun migrationDir(): Path = repoPath("backend/src/main/resources/db/migration")
    private fun flyway(db: FlywayHarness.Database, target: String? = null): Flyway =
        Flyway.configure().dataSource(db.ds).locations("filesystem:${migrationDir()}").also { if (target != null) it.target(MigrationVersion.fromVersion(target)) }.outOfOrder(false).load()

    private val tenant = "00000000-0000-0000-0000-000000000001"
    private fun pending(db: FlywayHarness.Database, status: String = "PENDING") = db.jdbc.update(
        "INSERT INTO approvals (id, tenant_id, title, requested_by, requested_at, expires_at, approver_specs, approvers, required_approvals, status, finished_at) " +
            "VALUES (?, ?::uuid, 'v33 test', ?, now(), now() + interval '1 day', '[]'::jsonb, ARRAY[?]::uuid[], 1, ?, CASE WHEN ?::text = 'PENDING' THEN NULL ELSE now() END)",
        UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), status, status
    )

    @Test
    fun `clean database - V1 to V33 applies in order, validates, and the approvals table carries its constraints`() {
        FlywayHarness.newDatabase("v33clean").use { db ->
            val fw = flyway(db); val result = fw.migrate()
            assertThat(result.success).isTrue()
            val info = fw.info()
            assertThat(info.current().version.version).isEqualTo("33"); assertThat(info.pending()).isEmpty()
            assertThat(info.applied().map { it.state }).containsOnly(MigrationState.SUCCESS)
            assertThat(info.applied().last().description).containsIgnoringCase("approvals")
            fw.validate()
            val checks = db.jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'approvals'::regclass AND contype = 'c'", String::class.java)
            assertThat(checks).contains("approvals_status_check", "approvals_required_check", "approvals_decisions_check", "approvals_finished_check")
            // the finished / status check: a PENDING approval cannot carry finished_at, a final one must
            assertThat(pending(db)).isEqualTo(1)
            assertThatThrownBy { db.jdbc.update("UPDATE approvals SET finished_at = now() WHERE status = 'PENDING'") }.hasMessageContaining("approvals_finished_check")
            println("EVIDENCE flyway clean: V1 -> V${info.current().version.version}, ${info.applied().size} migrations, all SUCCESS, validate OK, approvals constraints present")
        }
    }

    @Test
    fun `upgrade - a database at V32 receives ONLY the approvals migration, the table starts empty, a second migrate is a no-op`() {
        FlywayHarness.newDatabase("v33up").use { db ->
            flyway(db, "32").migrate()
            assertThat(flyway(db).info().current().version.version).isEqualTo("32")
            val fw = flyway(db); val result = fw.migrate()
            assertThat(result.success).isTrue(); assertThat(result.migrationsExecuted).isEqualTo(1)
            assertThat(fw.info().current().version.version).isEqualTo("33"); fw.validate()
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM approvals", Long::class.java)).isZero()
            assertThat(fw.migrate().migrationsExecuted).describedAs("a second run applies nothing").isZero()
        }
    }

    @Test
    fun `the guarded undo refuses while an approval is PENDING and drops the table when none is`() {
        val undoSql = Files.readString(repoPath("docs/parallel/c0/undo/U33__approvals.sql"))
        fun runUndo(db: FlywayHarness.Database) = db.ds.connection.use { c -> c.createStatement().use { it.execute(undoSql) } }
        FlywayHarness.newDatabase("v33undo").use { db ->
            flyway(db).migrate()
            pending(db)
            assertThatThrownBy { runUndo(db) }.satisfies({ e -> assertThat(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("U33 refused") == true }).isTrue() })
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'approvals'", Long::class.java)).describedAs("nothing dropped").isEqualTo(1)
            db.jdbc.update("UPDATE approvals SET status = 'CANCELLED', finished_at = now()")    // a final approval is history
            runUndo(db)
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'approvals'", Long::class.java)).isZero()
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'organization_units'", Long::class.java)).describedAs("V32 untouched").isEqualTo(1)
        }
    }
}
