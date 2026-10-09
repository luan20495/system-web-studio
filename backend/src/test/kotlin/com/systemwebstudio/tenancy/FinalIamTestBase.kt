package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.OrganizationTestBase
import com.systemwebstudio.support.ApiSession
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Shared fixtures of the C1 final IAM regression classes. Everything a test makes lives in its OWN companies (tenants created through the real API), so the
 * snapshot / purge of [OrganizationTestBase] removes the tenants, workspaces, memberships and accounts afterwards. Projects are the one thing that base does not
 * know about: they are removed here first (a subclass @AfterEach runs before the superclass one), together with their memberships.
 */
abstract class FinalIamTestBase : OrganizationTestBase() {
    @Autowired lateinit var tenantService: TenantService

    private var tenantsAtStart: Set<UUID> = emptySet()
    @BeforeEach fun snapshotTenantsForProjects() { tenantsAtStart = jdbc.queryForList("SELECT id FROM tenants", UUID::class.java).toSet() }
    @AfterEach fun purgeProjectsOfTheTestTenants() {
        val fresh = (jdbc.queryForList("SELECT id FROM tenants", UUID::class.java).toSet() - tenantsAtStart).joinToString(",") { "'$it'" }
        if (fresh.isEmpty()) return
        val projects = "SELECT p.id FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE w.tenant_id IN ($fresh)"
        jdbc.execute("DELETE FROM project_members WHERE project_id IN ($projects)")
        jdbc.execute("DELETE FROM projects WHERE id IN ($projects)")
    }

    protected val SESSION_COOKIE = "STUDIO_SESSION"

    /** a workspace of the company [c] (real service, same path as POST /admin/tenants/{id}/workspaces) */
    protected fun wsIn(c: Company, name: String = "WS"): UUID = tenantService.createWorkspace(c.id, name)["id"] as UUID
    protected fun entity(userId: UUID) = fx.users.findById(userId).get()

    protected fun me(s: ApiSession): JsonNode { val r = s.get("/api/v1/auth/me"); org.assertj.core.api.Assertions.assertThat(r.response.status).describedAs("/auth/me").isEqualTo(200); return s.body(r) }
    protected fun strings(n: JsonNode?): List<String> = n?.toList()?.map { it.asString() } ?: emptyList()
    protected fun workspaceIds(m: JsonNode) = m.get("workspaces").toList().map { it.get("id").asString() }
    protected fun workspaceRow(m: JsonNode, ws: UUID) = m.get("workspaces").toList().single { it.get("id").asString() == ws.toString() }
    protected fun scopeIds(m: JsonNode) = m.get("projectScopes").toList().map { it.get("projectId").asString() }
    protected fun scopePerms(m: JsonNode, p: UUID) = strings(m.get("projectScopes").toList().single { it.get("projectId").asString() == p.toString() }.get("permissions")).toSet()
    protected fun tenantIds(m: JsonNode) = m.get("tenants").toList().map { it.get("id").asString() }

    protected fun enabled(userId: UUID) = jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean::class.java, userId)!!
    protected fun systemAdminFlag(userId: UUID) = jdbc.queryForObject("SELECT system_admin FROM users WHERE id = ?", Boolean::class.java, userId)!!

    /** a request carrying ONLY [cookie] (no CSRF, nothing else): what an attacker replaying an old session cookie would send */
    protected fun replay(cookie: Cookie, path: String = "/api/v1/auth/me"): Int = mvc.perform(MockMvcRequestBuilders.get(path).cookie(cookie)).andReturn().response.status

    protected fun revision(s: ApiSession, ws: UUID, p: UUID) = s.body(s.get(api(ws, p))).get("revision").asLong()
    protected fun rename(s: ApiSession, ws: UUID, p: UUID, rev: Long, name: String) = s.patch(api(ws, p), """{"expectedRevision":$rev,"name":"$name"}""")
    protected fun publish(s: ApiSession, ws: UUID, p: UUID, rev: Long, key: String) =
        s.post("${api(ws, p)}/publish", """{"visibility":"PRIVATE","expectedRevision":$rev}""", "Idempotency-Key" to key)
    protected fun errorCode(s: ApiSession, r: org.springframework.test.web.servlet.MvcResult): String = s.body(r).get("code").asString()
}
