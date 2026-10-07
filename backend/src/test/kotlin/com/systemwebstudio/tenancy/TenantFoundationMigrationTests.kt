package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** C1 · T2 — V26 schema behaviour, checked on a real PostgreSQL (Flyway runs the whole migration chain). */
class TenantFoundationMigrationTests : IntegrationTestBase() {
    private val default = TenantIds.DEFAULT

    private fun newTenant(): UUID { val id = UUID.randomUUID(); jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", id, "t-" + id.toString().take(8)); return id }
    private fun tenantOf(table: String, col: String, id: UUID) = jdbc.queryForObject("SELECT tenant_id FROM $table WHERE $col = ?", UUID::class.java, id)

    @Test
    fun `DEFAULT tenant exists and is ACTIVE`() {
        val row = jdbc.queryForMap("SELECT slug, status FROM tenants WHERE id = ?", default)
        assertThat(row["slug"]).isEqualTo("default"); assertThat(row["status"]).isEqualTo("ACTIVE")
    }

    @Test
    fun `legacy insert paths still work - a workspace created without tenant lands in the DEFAULT tenant`() {
        val ws = fx.workspace()
        assertThat(tenantOf("workspaces", "id", ws)).isEqualTo(default)
    }

    @Test
    fun `no row of the foundation tables is left without a tenant and children always match their workspace`() {
        val sc = scenario()                                            // workspace + member + project + project member through the API
        for (t in listOf("workspaces", "workspace_members", "projects", "project_members"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $t WHERE tenant_id IS NULL", Long::class.java)).describedAs(t).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE p.tenant_id <> w.tenant_id", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members m JOIN workspaces w ON w.id = m.workspace_id WHERE m.tenant_id <> w.tenant_id", Long::class.java)).isZero()
        assertThat(tenantOf("projects", "id", sc.projectId)).isEqualTo(default)
    }

    private fun rejected(block: () -> Unit) = assertThatThrownBy { block() }.hasMessageContaining("does not match the tenant")

    @Test
    fun `legacy INSERT without tenant_id still works on every table (compatibility trigger fills it)`() {
        val u = fx.user(); val ws = fx.workspace()
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'EDITOR')", ws, u.id)
        val pid = UUID.randomUUID()
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id) VALUES (?, ?, 'legacy', ?)", pid, ws, u.id)
        jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role) VALUES (?, ?, ?, 'OWNER')", ws, pid, u.id)
        assertThat(tenantOf("workspace_members", "user_id", u.id)).isEqualTo(default)
        assertThat(tenantOf("projects", "id", pid)).isEqualTo(default)
        assertThat(tenantOf("project_members", "project_id", pid)).isEqualTo(default)
    }

    @Test
    fun `an explicit tenant_id that matches the workspace tenant is accepted`() {
        val u = fx.user(); val ws = fx.workspace(); val pid = UUID.randomUUID()
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, tenant_id) VALUES (?, ?, 'EDITOR', ?)", ws, u.id, default)
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'explicit', ?, ?)", pid, ws, u.id, default)
        jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role, tenant_id) VALUES (?, ?, ?, 'OWNER', ?)", ws, pid, u.id, default)
        assertThat(tenantOf("projects", "id", pid)).isEqualTo(default)
    }

    @Test
    fun `an explicit tenant_id that differs from the workspace tenant is REJECTED on every child table, never overwritten`() {
        val other = newTenant(); val u = fx.user(); val sc = scenario()
        rejected { jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, tenant_id) VALUES (?, ?, 'EDITOR', ?)", sc.ws, u.id, other) }
        rejected { jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'x', ?, ?)", UUID.randomUUID(), sc.ws, u.id, other) }
        rejected { jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role, tenant_id) VALUES (?, ?, ?, 'VIEWER', ?)", sc.ws, sc.projectId, u.id, other) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Long::class.java, sc.ws, u.id)).isZero()   // nothing was inserted
    }

    @Test
    fun `workspace tenant constraint - a row cannot be moved into a workspace of another tenant`() {
        val a = scenario(); val b = scenario(); val other = newTenant()
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", other, b.ws)
        rejected { jdbc.update("UPDATE projects SET workspace_id = ? WHERE id = ?", b.ws, a.projectId) }
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID::class.java, a.projectId)).isEqualTo(a.ws)
        assertThatThrownBy { jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", UUID.randomUUID(), a.ws) }.isInstanceOf(Exception::class.java)   // unknown tenant
    }

    @Test
    fun `moving a workspace to another tenant carries its members and projects along (composite FK ON UPDATE CASCADE)`() {
        val sc = scenario(); val other = newTenant()
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", other, sc.ws)
        assertThat(tenantOf("projects", "id", sc.projectId)).isEqualTo(other)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND tenant_id <> ?", Long::class.java, sc.ws, other)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE workspace_id = ? AND tenant_id <> ?", Long::class.java, sc.ws, other)).isZero()
    }

    @Test
    fun `becoming a workspace member makes the user a MEMBER of the workspace tenant and never a TENANT_ADMIN`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "WORKSPACE_ADMIN")
        val row = jdbc.queryForMap("SELECT role, active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", default, u.id)
        assertThat(row["role"]).isEqualTo("MEMBER"); assertThat(row["active"]).isEqualTo(true)
    }

    @Test
    fun `re-activating a workspace member re-creates the tenant membership, but never re-activates one that was deactivated at tenant level`() {
        val other = newTenant(); val u = fx.user(); val ws = fx.workspace()
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", other, ws)
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active) VALUES (?, ?, 'VIEWER', FALSE)", ws, u.id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Long::class.java, other, u.id)).isZero()   // inactive member: no tenant row
        jdbc.update("UPDATE workspace_members SET active = TRUE WHERE workspace_id = ? AND user_id = ?", ws, u.id)
        assertThat(jdbc.queryForObject("SELECT active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Boolean::class.java, other, u.id)).isTrue()
        jdbc.update("UPDATE tenant_members SET active = FALSE WHERE tenant_id = ? AND user_id = ?", other, u.id)
        jdbc.update("UPDATE workspace_members SET role = 'EDITOR' WHERE workspace_id = ? AND user_id = ?", ws, u.id)
        assertThat(jdbc.queryForObject("SELECT active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Boolean::class.java, other, u.id)).isFalse()
    }

    @Test
    fun `database constraints reject bad tenants, bad roles and missing tenant ids`() {
        assertThatThrownBy { jdbc.update("INSERT INTO tenants (id, slug, name, status) VALUES (?, 'bad-status', 'x', 'WEIRD')", UUID.randomUUID()) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'Bad Slug!', 'x')", UUID.randomUUID()) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'default', 'dup')", UUID.randomUUID()) }.isInstanceOf(Exception::class.java)
        val u = fx.user()
        assertThatThrownBy { jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES (?, ?, 'WORKSPACE_ADMIN')", default, u.id) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, NULL)", UUID.randomUUID(), "n-" + UUID.randomUUID().toString().take(8)) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, ?)", UUID.randomUUID(), "n-" + UUID.randomUUID().toString().take(8), UUID.randomUUID()) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `a user can belong to several tenants`() {
        val u = fx.user(); val a = newTenant(); val b = newTenant()
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES (?, ?, 'TENANT_ADMIN'), (?, ?, 'MEMBER')", a, u.id, b, u.id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE user_id = ?", Long::class.java, u.id)).isEqualTo(2L)
    }
}
