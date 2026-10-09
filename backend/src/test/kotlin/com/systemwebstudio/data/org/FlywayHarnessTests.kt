package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.api.MigrationState
import org.junit.jupiter.api.Test
import java.util.UUID

/** Clean install and upgrade of the REAL migrations (whatever the classpath holds today). Becomes the V1 -> V32 / pre-V32 -> V32 proof by itself once V32 exists. */
class FlywayHarnessTests {
    @Test
    fun `clean database - every migration of the classpath applies, in order, successfully`() {
        val ceiling = FlywayHarness.ceiling(); val all = FlywayHarness.versions()
        System.getProperty("c3.expected.ceiling")?.let { assertThat(ceiling.version).isEqualTo(it) }
        FlywayHarness.newDatabase("clean").use { db ->
            val result = FlywayHarness.flyway(db).migrate()
            assertThat(result.success).isTrue(); assertThat(result.migrationsExecuted).isEqualTo(all.size)
            val info = FlywayHarness.flyway(db).info()
            assertThat(info.current().version).isEqualTo(ceiling); assertThat(info.pending()).isEmpty()
            assertThat(info.applied().map { it.state }).containsOnly(MigrationState.SUCCESS)
            assertThat(info.applied().map { it.version }).containsExactlyElementsOf(all)                                   // no gap, no out-of-order
            FlywayHarness.flyway(db).validate()                                                                             // checksums match the files
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = '00000000-0000-0000-0000-000000000001'", Long::class.java)).isEqualTo(1)   // the V26 DEFAULT tenant
            println("clean install: V${all.first()} -> V$ceiling, ${all.size} migrations, all SUCCESS")
        }
    }

    @Test
    fun `upgrade - a database at the version below the ceiling gets the last migration, and the legacy data is byte-identical afterwards`() {
        val pre = FlywayHarness.preCeiling(); val ceiling = FlywayHarness.ceiling()
        FlywayHarness.newDatabase("upgrade").use { db ->
            FlywayHarness.flyway(db, pre).migrate()
            assertThat(FlywayHarness.flyway(db).info().current().version).isEqualTo(pre)
            // fixture at the pre-ceiling state: legacy departments (V20, global), users, a tenant member (V26), a workspace
            val dept = UUID.randomUUID(); val team = UUID.randomUUID(); val user = UUID.randomUUID(); val ws = UUID.randomUUID()
            db.jdbc.update("INSERT INTO departments (id, name, kind) VALUES (?, 'Engineering', 'DEPARTMENT')", dept)
            db.jdbc.update("INSERT INTO departments (id, name, kind, parent_id) VALUES (?, 'Platform', 'TEAM', ?)", team, dept)
            db.jdbc.update("INSERT INTO users (id, username, password_hash, display_name, email) VALUES (?, 'legacy-user', 'x', 'Legacy User', 'legacy@corp.example')", user)
            db.jdbc.update("UPDATE users SET department_id = ? WHERE id = ?", team, user)
            db.jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES ('00000000-0000-0000-0000-000000000001', ?, 'TENANT_ADMIN')", user)
            db.jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id, department_id) VALUES (?, 'Legacy WS', 'legacy-ws', '00000000-0000-0000-0000-000000000001', ?)", ws, dept)
            val tables = mapOf("departments" to "id, name, kind, parent_id", "users" to "id, username, display_name, email, enabled, department_id", "tenant_members" to "tenant_id, user_id, role, active", "workspaces" to "id, name, slug, tenant_id, department_id")
            val before = FlywayHarness.snapshot(db.jdbc, tables)

            val result = FlywayHarness.flyway(db).migrate()                                                                 // the normal Flyway path, no manual SQL
            assertThat(result.success).isTrue(); assertThat(result.migrationsExecuted).isEqualTo(1)
            val info = FlywayHarness.flyway(db).info()
            assertThat(info.current().version).isEqualTo(ceiling); assertThat(info.pending()).isEmpty()
            FlywayHarness.flyway(db).validate()
            assertThat(FlywayHarness.snapshot(db.jdbc, tables)).describedAs("legacy departments / users / tenant_members / workspaces unchanged by the last migration").isEqualTo(before)
            assertThat(db.jdbc.queryForObject("SELECT department_id FROM users WHERE id = ?", UUID::class.java, user)).isEqualTo(team)
            println("upgrade: V$pre -> V$ceiling executed ${result.migrationsExecuted} migration, legacy tables unchanged: ${before.perTable.mapValues { it.value.first }}")
        }
    }
}
