package com.systemwebstudio.migration

import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.tenancy.TenantIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** C0 · V27 `publish_configs` schema behaviour on a real PostgreSQL (Flyway runs V1..V27). The API behind the table stays OFF. */
class PublishConfigsMigrationTests : IntegrationTestBase() {
    private fun project(ws: UUID): UUID {
        val owner = fx.user(); val id = UUID.randomUUID()
        // tenant_id is written explicitly, read from the parent workspace (never guessed): TenantInsertPathsGrepTest forbids new inserts that rely on the V26 compatibility default
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, ws)
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'pc', ?, ?)", id, ws, owner.id, tenant)
        return id
    }

    @Test
    fun `tenant_id is mandatory and defaults keep a new project private and static`() {
        val nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'publish_configs' AND column_name = 'tenant_id'", String::class.java)
        assertThat(nullable).isEqualTo("NO")
        val ws = fx.workspace(); val p = project(ws)
        jdbc.update("INSERT INTO publish_configs (project_id, workspace_id, tenant_id) VALUES (?, ?, ?)", p, ws, TenantIds.DEFAULT)
        val row = jdbc.queryForMap("SELECT mode, visibility, requires_auth, public_data_approved, revision FROM publish_configs WHERE project_id = ?", p)
        assertThat(row["mode"]).isEqualTo("STATIC"); assertThat(row["visibility"]).isEqualTo("PRIVATE")
        assertThat(row["requires_auth"]).isEqualTo(false); assertThat(row["public_data_approved"]).isEqualTo(false); assertThat((row["revision"] as Number).toLong()).isEqualTo(1L)
    }

    @Test
    fun `a row whose tenant differs from its workspace tenant is rejected`() {
        val other = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", other, "t-" + other.toString().take(8))
        val ws = fx.workspace(); val p = project(ws)
        assertThatThrownBy { jdbc.update("INSERT INTO publish_configs (project_id, workspace_id, tenant_id) VALUES (?, ?, ?)", p, ws, other) }
            .hasMessageContaining("publish_configs_workspace_tenant_fk")
    }

    @Test
    fun `PRIVATE_LINK needs a token hash and PUBLIC cannot require auth`() {
        val ws = fx.workspace(); val a = project(ws); val b = project(ws)
        assertThatThrownBy { jdbc.update("INSERT INTO publish_configs (project_id, workspace_id, tenant_id, visibility) VALUES (?, ?, ?, 'PRIVATE_LINK')", a, ws, TenantIds.DEFAULT) }
            .hasMessageContaining("publish_configs_link_check")
        assertThatThrownBy { jdbc.update("INSERT INTO publish_configs (project_id, workspace_id, tenant_id, visibility, requires_auth) VALUES (?, ?, ?, 'PUBLIC', TRUE)", b, ws, TenantIds.DEFAULT) }
            .hasMessageContaining("publish_configs_public_auth_check")
    }
}
