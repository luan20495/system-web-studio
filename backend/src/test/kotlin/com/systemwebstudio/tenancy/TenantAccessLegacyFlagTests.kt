package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.PermissionMatrix
import com.systemwebstudio.access.adapters.allowed
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/** C1 · T2 — with app.tenancy.system-admin-business-access=true the legacy SYSTEM_ADMIN bypass is restored exactly (D-C1-11). */
@TestPropertySource(properties = ["app.tenancy.system-admin-business-access=true"])
class TenantAccessLegacyFlagTests : IntegrationTestBase() {
    @Autowired lateinit var access: AccessService

    @Test
    fun `flag on - a system admin that is not a member has every permission and sees all projects, as before T2`() {
        val sc = scenario(); val sys = fx.user("legacy-sys", systemAdmin = true); val s = sessionFor(sys.username)
        val ctx = access.forProject(sys.id, sc.ws, sc.projectId)
        assertThat(ctx.permissions).isEqualTo(PermissionMatrix.systemAdmin)
        assertThat(ctx.seesAllProjects).isTrue(); assertThat(ctx.systemAdminBypass).isTrue(); assertThat(ctx.platformScope).isFalse()
        assertThat(s.get(sc.base).response.status).isEqualTo(200)
        assertThat(s.get("${sc.base}/schema").response.status).isEqualTo(200)
        assertThat(s.body(s.get(api(sc.ws))).size()).isEqualTo(1)
        assertThat(s.get("/api/v1/workspaces/${sc.ws}/audit-events").response.status).isEqualTo(200)
    }

    @Test
    fun `flag on - ordinary users are unaffected (still 404 for foreign workspaces)`() {
        val sc = scenario(); val other = fx.user("outsider")
        assertThat(sessionFor(other.username).get(sc.base).response.status).isEqualTo(404)
    }

    @Autowired lateinit var gateway: com.systemwebstudio.access.adapters.GatewayAuthorizer
    @Autowired lateinit var port: com.systemwebstudio.access.adapters.AccessPort

    @Test
    fun `flag on - the adapters follow the same policy (system admin may query, but only as a USER actor)`() {
        val sc = scenario(); val sys = fx.user("legacy-gw", systemAdmin = true)
        val req = { kind: com.systemwebstudio.tenancy.ActorKind, op: String -> com.systemwebstudio.access.adapters.GatewayAuthRequest(com.systemwebstudio.access.adapters.Principal(kind, sys.id), TenantIds.DEFAULT, sc.ws, sc.projectId, null, op, null) }
        assertThat(gateway.authorize(req(com.systemwebstudio.tenancy.ActorKind.USER, "MUTATION_EXECUTE")).allowed).isTrue()
        assertThat(gateway.authorize(req(com.systemwebstudio.tenancy.ActorKind.SERVICE, "MUTATION_EXECUTE")).allowed).isFalse()
        assertThat(port.check(com.systemwebstudio.access.adapters.AppAccessRequest(com.systemwebstudio.access.adapters.Principal(com.systemwebstudio.tenancy.ActorKind.USER, sys.id), TenantIds.DEFAULT, sc.ws, sc.projectId, "WORKFLOW_MANAGE")).allowed).isTrue()
    }

    @Test
    fun `flag on - me exposes businessAccess and the full permission set for non-member workspaces`() {
        val sc = scenario(); val sys = fx.user("legacy-me", systemAdmin = true); val s = sessionFor(sys.username)
        val m = s.body(s.get("/api/v1/auth/me"))
        assertThat(m.get("businessAccess").asBoolean()).isTrue()
        val perms = m.get("workspaces").toList().single { it.get("id").asString() == sc.ws.toString() }.get("permissions").toList().map { it.asString() }
        assertThat(perms).contains("APP_VIEW", "DATA_MUTATE", "WORKFLOW_MANAGE", "TENANT_MANAGE")
        assertThat(perms).isSubsetOf(com.systemwebstudio.access.PermissionCodes.CANONICAL)
    }

    @Test
    fun `flag on - self grant is still rejected for a system admin`() {
        val sc = scenario(); val sys = fx.user("legacy-self", systemAdmin = true); val s = sessionFor(sys.username)
        assertThat(s.post("/api/v1/workspaces/${sc.ws}/members", """{"username":"${sys.username}","role":"WORKSPACE_ADMIN"}""").response.status).isEqualTo(403)
    }
}
