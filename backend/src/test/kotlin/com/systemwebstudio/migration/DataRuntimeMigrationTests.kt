package com.systemwebstudio.migration

import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.tenancy.TenantIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/** C0 · V28 `data_runtime` schema behaviour on a real PostgreSQL (Flyway runs V1..V28). The code behind the tables stays OFF (`app.data-platform.enabled`). */
class DataRuntimeMigrationTests : IntegrationTestBase() {
    private val tables = listOf("data_sources", "data_credentials", "source_schemas", "data_queries", "data_mutations", "data_idempotency", "data_source_bindings")

    private fun newTenant(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", id, "t-" + id.toString().take(8))
        return id
    }

    private fun workspaceOf(tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, ?)", id, "w-" + id.toString().take(8), tenant)
        return id
    }

    private fun projectIn(ws: UUID, tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'p', ?, ?)", id, ws, fx.user().id, tenant)
        return id
    }

    private fun source(tenant: UUID, ws: UUID? = null, status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO data_sources (id, tenant_id, workspace_id, type, name, status) VALUES (?, ?, ?, 'rest', ?, ?)", id, tenant, ws, "s-" + id.toString().take(8), status)
        return id
    }

    @Test
    fun `every table exists and tenant_id is mandatory on all of them`() {
        for (t in tables) {
            val nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id'", String::class.java, t)
            assertThat(nullable).describedAs(t).isEqualTo("NO")
        }
    }

    @Test
    fun `a data source cannot point at a workspace of another tenant`() {
        val other = newTenant()
        val foreign = workspaceOf(other)
        assertThatThrownBy { source(TenantIds.DEFAULT, foreign) }.hasMessageContaining("data_sources_workspace_tenant_fk")
        source(other, foreign)                       // the same workspace under its own tenant is fine
        source(TenantIds.DEFAULT)                    // tenant-level source (no workspace)
    }

    @Test
    fun `status, config shape and name uniqueness per tenant are enforced`() {
        assertThatThrownBy { source(TenantIds.DEFAULT, status = "BROKEN") }.hasMessageContaining("data_sources_status_check")
        assertThatThrownBy { jdbc.update("INSERT INTO data_sources (id, tenant_id, type, name, status, config_nonsecret) VALUES (?, ?, 'rest', 'x', 'ACTIVE', '[]'::jsonb)", UUID.randomUUID(), TenantIds.DEFAULT) }
            .hasMessageContaining("data_sources_config_check")
        val name = "dup-" + UUID.randomUUID().toString().take(8)
        jdbc.update("INSERT INTO data_sources (id, tenant_id, type, name, status) VALUES (?, ?, 'rest', ?, 'ACTIVE')", UUID.randomUUID(), TenantIds.DEFAULT, name)
        assertThatThrownBy { jdbc.update("INSERT INTO data_sources (id, tenant_id, type, name, status) VALUES (?, ?, 'rest', ?, 'ACTIVE')", UUID.randomUUID(), TenantIds.DEFAULT, name) }
            .hasMessageContaining("data_sources_tenant_name_unique")
        val other = newTenant()
        jdbc.update("INSERT INTO data_sources (id, tenant_id, type, name, status) VALUES (?, ?, 'rest', ?, 'ACTIVE')", UUID.randomUUID(), other, name)   // another tenant may reuse the name
    }

    @Test
    fun `credentials are stored as ciphertext only`() {
        val ref = UUID.randomUUID().toString()
        assertThatThrownBy { jdbc.update("INSERT INTO data_credentials (tenant_id, ref, ciphertext) VALUES (?, ?, 'sk-plain-secret')", TenantIds.DEFAULT, ref) }
            .hasMessageContaining("data_credentials_ciphertext_check")
        jdbc.update("INSERT INTO data_credentials (tenant_id, ref, ciphertext) VALUES (?, ?, 'v1:abcdef')", TenantIds.DEFAULT, ref)
        assertThatThrownBy { jdbc.update("INSERT INTO data_credentials (tenant_id, ref, ciphertext) VALUES (?, ?, 'v1:other')", TenantIds.DEFAULT, ref) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `child rows cannot reference a data source of another tenant`() {
        val a = TenantIds.DEFAULT; val b = newTenant()
        val sourceOfA = source(a)
        assertThatThrownBy {
            jdbc.update("INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) VALUES (?, ?, 'q1', 'sql', '{}'::jsonb)", b, sourceOfA)
        }.hasMessageContaining("data_queries_source_fk")
        assertThatThrownBy {
            jdbc.update("INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition) VALUES (?, ?, 'm1', 'CREATE', '{}'::jsonb)", b, sourceOfA)
        }.hasMessageContaining("data_mutations_source_fk")
        jdbc.update("INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) VALUES (?, ?, 'q1', 'sql', '{}'::jsonb)", a, sourceOfA)
        jdbc.update("INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition) VALUES (?, ?, 'm1', 'CREATE', '{}'::jsonb)", a, sourceOfA)
        assertThatThrownBy { jdbc.update("INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) VALUES (?, ?, 'q2', 'sql', '[]'::jsonb)", a, sourceOfA) }
            .hasMessageContaining("data_queries_definition_check")
    }

    @Test
    fun `schema snapshots are unique per data source and version and tenant bound`() {
        val a = TenantIds.DEFAULT; val s = source(a)
        val insert = "INSERT INTO source_schemas (id, tenant_id, data_source_id, version, discovered_at, fingerprint, data_source_version, includes_samples, snapshot) VALUES (?, ?, ?, ?, now(), 'f', 1, FALSE, '{}'::jsonb)"
        jdbc.update(insert, UUID.randomUUID(), a, s, 1)
        jdbc.update(insert, UUID.randomUUID(), a, s, 2)
        assertThatThrownBy { jdbc.update(insert, UUID.randomUUID(), a, s, 2) }.hasMessageContaining("source_schemas_version_unique")
        assertThatThrownBy { jdbc.update(insert, UUID.randomUUID(), newTenant(), s, 1) }.hasMessageContaining("source_schemas_source_fk")
    }

    @Test
    fun `idempotency rows validate state and key shape and are tenant bound`() {
        val a = TenantIds.DEFAULT; val s = source(a)
        val insert = "INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at) VALUES (?, ?, 'm1', ?, 'fp', ?, now(), now() + interval '30 days')"
        val key = "k".repeat(43)
        jdbc.update(insert, a, s, key, "RESERVED")
        assertThatThrownBy { jdbc.update(insert, a, s, key, "RESERVED") }.isInstanceOf(Exception::class.java)                                // primary key
        assertThatThrownBy { jdbc.update(insert, a, s, "k".repeat(42) + "x", "DONE?") }.hasMessageContaining("data_idempotency_state_check")
        assertThatThrownBy { jdbc.update(insert, a, s, "short", "RESERVED") }.hasMessageContaining("data_idempotency_key_check")
        assertThatThrownBy { jdbc.update(insert, a, s, "bad key with spaces " + "x".repeat(10), "RESERVED") }.hasMessageContaining("data_idempotency_key_check")
        assertThatThrownBy { jdbc.update(insert, newTenant(), s, "z".repeat(43), "RESERVED") }.hasMessageContaining("data_idempotency_source_fk")
    }

    @Test
    fun `a binding is per project and mode, never crosses tenants or workspaces`() {
        val ws = fx.workspace(); val p = projectIn(ws, TenantIds.DEFAULT); val s = source(TenantIds.DEFAULT)
        val insert = "INSERT INTO data_source_bindings (tenant_id, workspace_id, project_id, mode, slot_id, data_source_id) VALUES (?, ?, ?, ?, ?, ?)"
        jdbc.update(insert, TenantIds.DEFAULT, ws, p, "LIVE", "erp-db", s)
        jdbc.update(insert, TenantIds.DEFAULT, ws, p, "TEST", "erp-db", s)                                                                    // the same slot may be bound once per mode
        assertThatThrownBy { jdbc.update(insert, TenantIds.DEFAULT, ws, p, "LIVE", "erp-db", s) }.isInstanceOf(Exception::class.java)       // primary key includes the mode
        assertThatThrownBy { jdbc.update(insert, TenantIds.DEFAULT, ws, p, "STAGING", "erp-db", s) }.hasMessageContaining("data_source_bindings_mode_check")
        val other = newTenant()
        assertThatThrownBy { jdbc.update(insert, TenantIds.DEFAULT, ws, p, "LIVE", "crm", source(other)) }.hasMessageContaining("data_source_bindings_source_fk")
        val otherWs = fx.workspace()
        assertThatThrownBy { jdbc.update(insert, TenantIds.DEFAULT, otherWs, p, "LIVE", "crm", s) }.hasMessageContaining("data_source_bindings_project_fk")
        val foreignWs = workspaceOf(other); val foreignProject = projectIn(foreignWs, other)          // the project really is in that workspace: only the tenant link is wrong
        assertThatThrownBy { jdbc.update(insert, TenantIds.DEFAULT, foreignWs, foreignProject, "LIVE", "crm", s) }.hasMessageContaining("data_source_bindings_workspace_tenant_fk")
    }

    @Test
    fun `the undo script refuses while data sources exist and changes nothing`() {
        source(TenantIds.DEFAULT)
        val root = listOf(File("."), File("backend")).first { File(it, "src/main/kotlin").isDirectory }
        val script = File(root, "../docs/parallel/c0/undo/U28__data_runtime.sql").readText()
        val c = jdbc.dataSource!!.connection
        try {
            c.autoCommit = false
            assertThatThrownBy { c.createStatement().use { it.execute(script) } }.hasMessageContaining("U28 refused")
            c.rollback()
        } finally {
            c.autoCommit = true
            c.close()
        }
        for (t in tables) assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t)).describedAs(t).isTrue()
    }
}
