package com.systemwebstudio.identity.authme

import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Fixtures for the /auth/me project-scope harness (query complexity, benchmark, differential equivalence).
 * Everything is written with plain SQL in tenants this class creates; [purge] removes exactly those tenants / workspaces / projects / memberships and every user created
 * while the class ran, because the shared test database's sign-up cap (app.signup.max-users = 500) counts every account ever created.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AuthMeFixtureBase : IntegrationTestBase() {
    @Autowired lateinit var counter: SqlStatementCounter

    private var usersBefore: Set<UUID> = emptySet()
    private var tenantsBefore: Set<UUID> = emptySet()
    private var workspacesBefore: Set<UUID> = emptySet()
    private fun idsOf(table: String): Set<UUID> = jdbc.queryForList("SELECT id FROM $table", UUID::class.java).toSet()

    @BeforeAll fun snapshotBeforeClass() { usersBefore = idsOf("users"); tenantsBefore = idsOf("tenants"); workspacesBefore = idsOf("workspaces") }

    @AfterAll fun purge() {
        val tenants = (idsOf("tenants") - tenantsBefore).joinToString(",") { "'$it'" }
        val workspaces = (idsOf("workspaces") - workspacesBefore).joinToString(",") { "'$it'" }
        val users = (idsOf("users") - usersBefore).joinToString(",") { "'$it'" }
        if (workspaces.isNotEmpty()) {
            jdbc.execute("DELETE FROM project_members WHERE workspace_id IN ($workspaces)")
            jdbc.execute("DELETE FROM projects WHERE workspace_id IN ($workspaces)")
            jdbc.execute("DELETE FROM workspace_members WHERE workspace_id IN ($workspaces)")
            jdbc.execute("DELETE FROM workspaces WHERE id IN ($workspaces)")
        }
        if (tenants.isNotEmpty()) {
            jdbc.execute("DELETE FROM tenant_members WHERE tenant_id IN ($tenants)")
            jdbc.execute("DELETE FROM tenants WHERE id IN ($tenants)")
        }
        if (users.isNotEmpty()) {
            jdbc.execute("DELETE FROM account_tokens WHERE user_id IN ($users) OR created_by IN ($users)")
            jdbc.execute("DELETE FROM project_members WHERE user_id IN ($users)")
            jdbc.execute("DELETE FROM workspace_members WHERE user_id IN ($users)")
            jdbc.execute("DELETE FROM tenant_members WHERE user_id IN ($users)")
            jdbc.execute("DELETE FROM users WHERE id IN ($users)")
        }
    }

    // ---- primitive fixtures (plain SQL; tenant_id is always passed explicitly) -------------------------------------------------------------------------------
    fun newTenant(status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name, status) VALUES (?, ?, ?, ?)", id, "am-" + id.toString().take(12), "AuthMe $id", status)
        return id
    }
    fun tenantStatus(tenant: UUID, status: String) { jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", status, tenant) }

    fun newWorkspace(tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, ?, ?, ?)", id, "AM " + id.toString().take(6), "am-" + id, tenant)
        return id
    }
    private fun tenantOf(ws: UUID): UUID = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, ws)!!

    /** the V26 trigger adds the tenant_members row (MEMBER) when an ACTIVE workspace membership is inserted */
    fun wsMember(ws: UUID, user: UserEntity, role: String, active: Boolean = true) {
        jdbc.update("""INSERT INTO workspace_members (workspace_id, user_id, role, active, tenant_id) VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role, active = EXCLUDED.active""", ws, user.id, role, active, tenantOf(ws))
    }
    fun tenantMember(tenant: UUID, user: UserEntity, role: String = "MEMBER", active: Boolean = true) {
        jdbc.update("""INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?, ?, ?, ?)
                       ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role, active = EXCLUDED.active""", tenant, user.id, role, active)
    }
    fun setTenantMemberActive(tenant: UUID, user: UserEntity, active: Boolean) { jdbc.update("UPDATE tenant_members SET active = ? WHERE tenant_id = ? AND user_id = ?", active, tenant, user.id) }
    fun setWsMemberActive(ws: UUID, user: UserEntity, active: Boolean) { jdbc.update("UPDATE workspace_members SET active = ? WHERE workspace_id = ? AND user_id = ?", active, ws, user.id) }

    fun newProject(ws: UUID, owner: UserEntity, archived: Boolean = false, active: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO projects (id, workspace_id, tenant_id, name, owner_user_id, lifecycle, archived_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            id, ws, tenantOf(ws), "AM project", owner.id, if (archived) "ARCHIVED" else "ACTIVE", if (archived) java.sql.Timestamp(System.currentTimeMillis()) else null, active)
        return id
    }
    /** project_members needs a workspace_members row of the same (workspace, user) (FK project_members_workspace_user_fk) */
    fun pm(project: UUID, user: UserEntity, role: String, active: Boolean = true) {
        jdbc.update("""INSERT INTO project_members (workspace_id, project_id, user_id, role, active, tenant_id)
                       SELECT p.workspace_id, p.id, ?, ?, ?, p.tenant_id FROM projects p WHERE p.id = ?""", user.id, role, active, project)
    }

    fun login(user: UserEntity): ApiSession {
        val s = session()
        val r = s.login(user.username)
        assertThat(r.response.status).describedAs("login of ${user.username}").isEqualTo(200)
        return s
    }

    fun me(s: ApiSession): JsonNode {
        val r = s.get("/api/v1/auth/me")
        assertThat(r.response.status).describedAs("/auth/me").isEqualTo(200)
        return s.body(r)
    }

    data class Scope(val projectId: UUID, val workspaceId: UUID, val role: String?, val permissions: List<String>)

    fun scopesOf(me: JsonNode): List<Scope> = me.get("projectScopes").toList().map { n ->
        Scope(UUID.fromString(n.get("projectId").asString()), UUID.fromString(n.get("workspaceId").asString()),
            n.get("role")?.takeIf { !it.isNull }?.asString(), n.get("permissions").toList().map { it.asString() })
    }

    // ---- bulk fixture: ONE user with N visible project memberships -------------------------------------------------------------------------------------------
    /**
     * One tenant, four workspaces in which the user holds EDITOR / VIEWER / PUBLISHER / WORKSPACE_ADMIN (round-robin by g % 4), N ACTIVE project memberships with project
     * roles VIEWER / EDITOR / PUBLISHER / OWNER (by (g / 4) % 4), every 10th project ARCHIVED. Noise that must NOT appear: N/20 memberships of inactive projects
     * (projects.active = false) and N/10 inactive memberships (project_members.active = false). Inserted with INSERT ... SELECT generate_series (4 statements).
     */
    class Bulk(val n: Int, val user: UserEntity, val tenant: UUID, val workspaceRoles: Map<UUID, String>, val expected: Map<UUID, Expected>)
    data class Expected(val workspaceId: UUID, val role: String, val archived: Boolean)

    val WS_ROLES = listOf("EDITOR", "VIEWER", "PUBLISHER", "WORKSPACE_ADMIN")

    fun bulk(n: Int): Bulk {
        val user = fx.user("am-bulk-$n")
        val tenant = newTenant()
        val ws = WS_ROLES.map { r -> newWorkspace(tenant).also { wsMember(it, user, r) } }
        val inactiveProjects = n / 20
        val inactiveMemberships = n / 10
        val total = n + inactiveProjects + inactiveMemberships
        jdbc.update("""INSERT INTO projects (id, workspace_id, tenant_id, name, owner_user_id, lifecycle, archived_at, active)
            SELECT gen_random_uuid(), (CASE g % 4 WHEN 0 THEN ?::uuid WHEN 1 THEN ?::uuid WHEN 2 THEN ?::uuid ELSE ?::uuid END), ?, 'am-' || g, ?,
                   CASE WHEN g % 10 = 0 THEN 'ARCHIVED' ELSE 'ACTIVE' END, CASE WHEN g % 10 = 0 THEN now() END, g < ? OR g >= ?
            FROM generate_series(0, ? - 1) AS g""",
            ws[0], ws[1], ws[2], ws[3], tenant, user.id, n, n + inactiveProjects, total)
        jdbc.update("""INSERT INTO project_members (workspace_id, project_id, user_id, role, active, tenant_id)
            SELECT p.workspace_id, p.id, ?, (ARRAY['VIEWER','EDITOR','PUBLISHER','OWNER'])[((split_part(p.name, '-', 2)::int / 4) % 4) + 1],
                   split_part(p.name, '-', 2)::int < ?, p.tenant_id
            FROM projects p WHERE p.tenant_id = ?""", user.id, n + inactiveProjects, tenant)
        val expected = jdbc.query("""SELECT p.id, p.workspace_id, pm.role, p.lifecycle FROM projects p JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = ?
                                     WHERE p.tenant_id = ? AND p.active AND pm.active""", { rs, _ ->
            rs.getObject(1, UUID::class.java) to Expected(rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4) == "ARCHIVED")
        }, user.id, tenant).toMap()
        assertThat(expected).describedAs("bulk fixture visible memberships").hasSize(n)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE user_id = ?", Long::class.java, user.id)).isEqualTo(total.toLong())
        return Bulk(n, user, tenant, ws.zip(WS_ROLES).toMap(), expected)
    }

    class Measurement(val statements: Long, val statementsAllThreads: Long, val millis: Double, val bytes: Int, val body: JsonNode)

    fun measureMe(s: ApiSession): Measurement {
        counter.reset()
        val t0 = System.nanoTime()
        val r = s.get("/api/v1/auth/me")
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val q = counter.countOnCurrentThread(); val all = counter.count()
        assertThat(r.response.status).describedAs("/auth/me").isEqualTo(200)
        return Measurement(q, all, ms, r.response.contentAsByteArray.size, s.body(r))
    }

    companion object {
        val SIZES = listOf(25, 100, 500, 2000)
    }
}
