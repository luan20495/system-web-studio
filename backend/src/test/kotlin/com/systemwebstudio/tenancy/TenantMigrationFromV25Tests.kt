package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * C1 · V26 on a database that already holds V25 data: Flyway migrates a scratch database to V25, rows are seeded, V26 is applied, and the
 * result (backfill, no data loss, compatibility, undo guard) is asserted. Uses the same PostgreSQL container as the integration tests.
 * Same scenario as docs/parallel/c1/verification/run-v26-checks.sh (psql), which does not need Gradle.
 */
class TenantMigrationFromV25Tests {
    private val default = "00000000-0000-0000-0000-000000000001"
    private val admin = IntegrationTestBase.postgres

    private fun freshDb(name: String): String {
        val db = "mig_" + name + "_" + UUID.randomUUID().toString().take(8).replace("-", "")
        DriverManager.getConnection(admin.jdbcUrl, admin.username, admin.password).use { it.createStatement().execute("CREATE DATABASE $db") }
        return admin.jdbcUrl.substringBeforeLast("/") + "/" + db
    }
    private fun flyway(url: String, target: String) =
        Flyway.configure().dataSource(url, admin.username, admin.password).locations("classpath:db/migration").target(target).load()
    private fun <T> withDb(url: String, block: (Connection) -> T): T = DriverManager.getConnection(url, admin.username, admin.password).use(block)
    private fun Connection.one(sql: String): Long = createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }
    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private val seed = """
        INSERT INTO users (id, username, password_hash, system_admin) VALUES
          ('11111111-0000-0000-0000-000000000001','alice','x',false),('11111111-0000-0000-0000-000000000002','bob','x',false),
          ('11111111-0000-0000-0000-000000000003','root','x',true),('11111111-0000-0000-0000-000000000004','carol-no-ws','x',false);
        INSERT INTO workspaces (id,name,slug) VALUES ('22222222-0000-0000-0000-000000000001','WS1','ws1'),('22222222-0000-0000-0000-000000000002','WS2','ws2');
        INSERT INTO workspace_members (workspace_id,user_id,role,active) VALUES
          ('22222222-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001','WORKSPACE_ADMIN',true),
          ('22222222-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','EDITOR',true),
          ('22222222-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000002','VIEWER',false);
        INSERT INTO projects (id,workspace_id,name,owner_user_id,active) VALUES
          ('33333333-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000001','P1','11111111-0000-0000-0000-000000000002',true),
          ('33333333-0000-0000-0000-000000000002','22222222-0000-0000-0000-000000000001','P2 inactive','11111111-0000-0000-0000-000000000001',false);
        INSERT INTO project_members (workspace_id,project_id,user_id,role) VALUES
          ('22222222-0000-0000-0000-000000000001','33333333-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','OWNER');
    """.trimIndent()

    private fun v25WithRows(): String {
        val url = freshDb("rows"); flyway(url, "25").migrate()
        withDb(url) { it.exec(seed) }
        return url
    }

    @Test
    fun `V26 on V25 data - every row lands in the DEFAULT tenant, nothing is lost, ids are unchanged, users become plain tenant MEMBERs`() {
        val url = v25WithRows()
        val before = withDb(url) { c -> listOf("workspaces", "workspace_members", "projects", "project_members", "users").map { c.one("SELECT count(*) FROM $it") } }
        flyway(url, "26").migrate()
        withDb(url) { c ->
            assertThat(listOf("workspaces", "workspace_members", "projects", "project_members", "users").map { c.one("SELECT count(*) FROM $it") }).isEqualTo(before)
            assertThat(c.one("SELECT count(*) FROM workspaces WHERE tenant_id <> '$default'")).isZero()
            for (t in listOf("workspace_members", "projects", "project_members")) assertThat(c.one("SELECT count(*) FROM $t WHERE tenant_id IS NULL OR tenant_id <> '$default'")).describedAs(t).isZero()
            assertThat(c.one("SELECT count(*) FROM workspaces WHERE id IN ('22222222-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000002')")).isEqualTo(2L)
            assertThat(c.one("SELECT count(*) FROM tenant_members WHERE tenant_id = '$default' AND role = 'MEMBER' AND active")).isEqualTo(4L)   // all users, incl. one without workspace
            assertThat(c.one("SELECT count(*) FROM tenant_members WHERE role <> 'MEMBER'")).isZero()                                           // nobody is promoted
        }
    }

    @Test
    fun `V26 keeps legacy INSERTs working and rejects a mismatching tenant_id on rows migrated from V25`() {
        val url = v25WithRows(); flyway(url, "26").migrate()
        withDb(url) { c ->
            c.exec("INSERT INTO workspaces (id,name,slug) VALUES ('22222222-0000-0000-0000-000000000009','WS9','ws9')")
            c.exec("INSERT INTO workspace_members (workspace_id,user_id,role) VALUES ('22222222-0000-0000-0000-000000000009','11111111-0000-0000-0000-000000000004','EDITOR')")
            assertThat(c.one("SELECT count(*) FROM workspace_members WHERE workspace_id = '22222222-0000-0000-0000-000000000009' AND tenant_id = '$default'")).isEqualTo(1L)
            c.exec("INSERT INTO tenants (id,slug,name) VALUES ('44444444-0000-0000-0000-000000000001','other','Other')")
            assertThatThrownBy { c.exec("INSERT INTO projects (id,workspace_id,name,owner_user_id,tenant_id) VALUES (gen_random_uuid(),'22222222-0000-0000-0000-000000000001','X','11111111-0000-0000-0000-000000000001','44444444-0000-0000-0000-000000000001')") }
                .hasMessageContaining("does not match the tenant")
        }
    }

    /** the script opens a transaction; after the guard raises, the connection must be rolled back before it is used again */
    private fun refused(c: Connection) {
        assertThatThrownBy { c.exec(undoScript()) }.hasMessageContaining("U26 refused")
        c.exec("ROLLBACK")
    }

    private fun undoScript(): String = listOf("../docs/parallel/c1/undo/U26__tenant_foundation.sql", "docs/parallel/c1/undo/U26__tenant_foundation.sql")
        .map { File(it) }.first { it.exists() }.readText()

    @Test
    fun `undo script refuses (and changes nothing) once V2 tenant data exists`() {
        val url = v25WithRows(); flyway(url, "26").migrate()
        withDb(url) { c ->
            c.exec("INSERT INTO tenants (id,slug,name) VALUES ('44444444-0000-0000-0000-000000000001','other','Other')")
            refused(c)
        }
        withDb(url) { c ->
            assertThat(c.one("SELECT count(*) FROM tenants")).isEqualTo(2L)
            assertThat(c.one("SELECT count(*) FROM information_schema.columns WHERE table_name = 'projects' AND column_name = 'tenant_id'")).isEqualTo(1L)
            c.exec("DELETE FROM tenants WHERE id = '44444444-0000-0000-0000-000000000001'")
            c.exec("UPDATE tenant_members SET role = 'TENANT_ADMIN' WHERE user_id = '11111111-0000-0000-0000-000000000001'")
            refused(c)
            c.exec("UPDATE tenant_members SET role = 'MEMBER'")
            c.exec("CREATE TABLE v27_probe (id uuid primary key, tenant_id uuid references tenants(id))")
            refused(c)
        }
    }

    @Test
    fun `undo script on a database that only holds the V26 backfill removes the scaffolding and keeps all business rows`() {
        val url = v25WithRows(); flyway(url, "26").migrate()
        val before = withDb(url) { c -> listOf("workspaces", "workspace_members", "projects", "project_members", "users").map { c.one("SELECT count(*) FROM $it") } }
        withDb(url) { c -> c.exec(undoScript()) }
        withDb(url) { c ->
            assertThat(listOf("workspaces", "workspace_members", "projects", "project_members", "users").map { c.one("SELECT count(*) FROM $it") }).isEqualTo(before)
            assertThat(c.one("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('tenants','tenant_members')")).isZero()
            assertThat(c.one("SELECT count(*) FROM information_schema.columns WHERE column_name = 'tenant_id' AND table_name IN ('workspaces','projects','workspace_members','project_members')")).isZero()
        }
    }
}
