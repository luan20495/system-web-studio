package com.systemwebstudio.tenancy

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** C1 · H-C1-04: /auth/me mirrors effective project authorization without widening global/workspace authority. */
class ProjectScopedAuthMeTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService
    private fun me(s: ApiSession) = s.body(s.get("/api/v1/auth/me"))
    private fun scopes(s: ApiSession) = me(s).get("projectScopes").toList()
    private fun scope(s: ApiSession, projectId: UUID) =
        scopes(s).single { it.get("projectId").asString() == projectId.toString() }
    private fun perms(scope: tools.jackson.databind.JsonNode) =
        scope.get("permissions").toList().map { it.asString() }.toSet()

    @Test
    fun `A VIEWER exposes APP_VIEW and APP_USE and edit remains denied`() {
        val owner = fx.user("h04-owner-a"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val user = fx.user("h04-viewer"); fx.member(w, user, "VIEWER"); fx.projectRole(p, user, "VIEWER")
        val s = sessionFor(user.username)

        assertThat(perms(scope(s, p.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(s.get(api(w, p.id)).response.status).isEqualTo(200)
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"forbidden"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `B EDITOR exposes canonical edit scope and save remains allowed`() {
        val owner = fx.user("h04-owner-b"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val user = fx.user("h04-editor"); fx.member(w, user, "VIEWER"); fx.projectRole(p, user, "EDITOR")
        val s = sessionFor(user.username)
        val effective = perms(scope(s, p.id))

        assertThat(effective).contains("APP_VIEW", "APP_USE", "APP_EDIT", "DATA_SOURCE_VIEW", "QUERY_EXECUTE", "ACTION_EXECUTE")
        assertThat(effective).doesNotContain("APP_PUBLISH", "DATA_MUTATE", "WORKFLOW_MANAGE")
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"saved"}""").response.status).isEqualTo(200)
    }

    @Test
    fun `C PUBLISHER exposes publish but not edit or mutation permission`() {
        val owner = fx.user("h04-owner-c"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val user = fx.user("h04-publisher"); fx.member(w, user, "VIEWER"); fx.projectRole(p, user, "PUBLISHER")
        val s = sessionFor(user.username)

        assertThat(perms(scope(s, p.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE", "APP_PUBLISH")
        assertThat(perms(scope(s, p.id))).doesNotContain("APP_EDIT", "DATA_MUTATE", "QUERY_EXECUTE")
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"forbidden"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `D no project membership exposes no project scope and project stays undisclosed`() {
        val owner = fx.user("h04-owner-d"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val user = fx.user("h04-none"); fx.member(w, user, "VIEWER")
        val s = sessionFor(user.username)

        assertThat(scopes(s)).isEmpty()
        assertThat(s.get(api(w, p.id)).response.status).isEqualTo(404)
    }

    @Test
    fun `E foreign project never appears in project scopes`() {
        val a = fx.user("h04-a"); val wa = fx.workspace(); fx.member(wa, a, "EDITOR"); val pa = fx.project(wa, a)
        val bOwner = fx.user("h04-b-owner"); val wb = fx.workspace(); fx.member(wb, bOwner, "EDITOR"); val pb = fx.project(wb, bOwner)
        val b = fx.user("h04-b"); fx.member(wb, b, "VIEWER"); fx.projectRole(pb, b, "VIEWER")
        val s = sessionFor(b.username)

        assertThat(scopes(s).map { it.get("projectId").asString() }).containsExactly(pb.id.toString()).doesNotContain(pa.id.toString())
        assertThat(s.get(api(wa, pa.id)).response.status).isEqualTo(404)
    }

    @Test
    fun `F TENANT_ADMIN without project membership gets no Studio project scope`() {
        val user = fx.user("h04-tenant-admin")
        val tenant = tenants.create("h04-" + UUID.randomUUID().toString().take(8), "H04 Tenant", user.id)
        val s = sessionFor(user.username)
        val m = me(s)

        assertThat(m.get("tenantId").asString()).isEqualTo(tenant.id.toString())
        assertThat(m.get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(scopes(s)).isEmpty()
    }

    @Test
    fun `G SYSTEM_ADMIN without project membership gets no automatic project scope or business permission`() {
        val owner = fx.user("h04-owner-g"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); fx.project(w, owner)
        val sys = fx.user("h04-sys", systemAdmin = true)
        val s = sessionFor(sys.username)
        val m = me(s)

        assertThat(m.get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(scopes(s)).isEmpty()
        val workspace = m.get("workspaces").toList().single { it.get("id").asString() == w.toString() }
        assertThat(workspace.get("permissions").toList().map { it.asString() })
            .doesNotContain("APP_VIEW", "APP_EDIT", "APP_PUBLISH", "MEMBER_MANAGE")
    }

    @Test
    fun `H multiple memberships remain isolated per project`() {
        val owner = fx.user("h04-owner-h"); val w = fx.workspace(); fx.member(w, owner, "EDITOR")
        val viewerProject = fx.project(w, owner, "Viewer")
        val editorProject = fx.project(w, owner, "Editor")
        val user = fx.user("h04-multi"); fx.member(w, user, "VIEWER")
        fx.projectRole(viewerProject, user, "VIEWER")
        fx.projectRole(editorProject, user, "EDITOR")
        val s = sessionFor(user.username)

        assertThat(scopes(s)).hasSize(2)
        assertThat(perms(scope(s, viewerProject.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(perms(scope(s, editorProject.id))).contains("APP_VIEW", "APP_USE", "APP_EDIT").doesNotContain("APP_PUBLISH")
    }

    @Test
    fun `real hierarchy flow lets project EDITOR enter via APP_VIEW while publish remains 403`() {
        val password = "H04-Project-Pass-2026"
        fun activate(link: tools.jackson.databind.JsonNode) {
            val r = session().post(
                "/api/v1/auth/activation/complete",
                """{"token":"${link.get("token").asString()}","password":"$password"}"""
            )
            assertThat(r.response.status).isEqualTo(200)
        }

        val sysUser = fx.user("h04-flow-sys", systemAdmin = true)
        val sys = sessionFor(sysUser.username)

        val tenantResponse = sys.post(
            "/api/v1/admin/tenants",
            """{"slug":"h04-flow-${UUID.randomUUID().toString().take(8)}","name":"H04 Flow Tenant"}"""
        )
        assertThat(tenantResponse.response.status).isEqualTo(201)
        val tenantId = UUID.fromString(sys.body(tenantResponse).get("id").asString())

        val workspaceResponse = sys.post(
            "/api/v1/admin/tenants/$tenantId/workspaces",
            """{"name":"H04 Flow Workspace"}"""
        )
        assertThat(workspaceResponse.response.status).isEqualTo(201)
        val workspaceId = UUID.fromString(sys.body(workspaceResponse).get("id").asString())

        val tenantAdminName = "h04-ta-" + UUID.randomUUID().toString().take(8)
        val tenantAdminLink = sys.body(sys.post(
            "/api/v1/admin/tenants/$tenantId/users",
            """{"username":"$tenantAdminName","displayName":"H04 Tenant Admin","tenantRole":"TENANT_ADMIN","workspaceId":"$workspaceId","workspaceRole":"WORKSPACE_ADMIN"}"""
        ))
        activate(tenantAdminLink)
        val tenantAdmin = session()
        assertThat(tenantAdmin.login(tenantAdminName, password).response.status).isEqualTo(200)

        val creatorName = "h04-creator-" + UUID.randomUUID().toString().take(8)
        val creatorLink = tenantAdmin.body(tenantAdmin.post(
            "/api/v1/admin/tenants/$tenantId/users",
            """{"username":"$creatorName","displayName":"H04 Creator","tenantRole":"MEMBER","workspaceId":"$workspaceId","workspaceRole":"VIEWER"}"""
        ))
        activate(creatorLink)
        val creator = session()
        assertThat(creator.login(creatorName, password).response.status).isEqualTo(200)

        val projectResponse = tenantAdmin.post(api(workspaceId), """{"name":"H04 Project"}""")
        assertThat(projectResponse.response.status).isEqualTo(201)
        val projectId = UUID.fromString(tenantAdmin.body(projectResponse).get("id").asString())

        assertThat(tenantAdmin.post(
            "${api(workspaceId, projectId)}/members",
            """{"username":"$creatorName","role":"EDITOR"}"""
        ).response.status).isEqualTo(201)

        val creatorMe = me(creator)
        val creatorScope = creatorMe.get("projectScopes").toList().single {
            it.get("projectId").asString() == projectId.toString()
        }
        assertThat(perms(creatorScope)).contains("APP_VIEW", "APP_EDIT").doesNotContain("APP_PUBLISH")
        assertThat(creatorMe.get("permissions").toList().map { it.asString() }).doesNotContain("APP_VIEW", "APP_EDIT", "APP_PUBLISH")
        // the point of H-C1-04: a workspace VIEWER holds no Studio permission at workspace level (PermissionMatrix.workspaceRoles), the project role is the ONLY source of APP_VIEW,
        // and it is never flattened into workspaces[].permissions
        val creatorWorkspace = creatorMe.get("workspaces").toList().single { it.get("id").asString() == workspaceId.toString() }
        assertThat(creatorWorkspace.get("permissions").toList().map { it.asString() }).describedAs("workspaces[].permissions of a workspace VIEWER").doesNotContain("APP_VIEW", "APP_EDIT", "APP_PUBLISH", "APP_USE")
        assertThat(creatorMe.get("projectScopes").toList().map { it.get("workspaceId").asString() }).containsExactly(workspaceId.toString())

        assertThat(creator.get(api(workspaceId, projectId)).response.status).isEqualTo(200)
        assertThat(creator.patch(
            api(workspaceId, projectId),
            """{"expectedRevision":0,"name":"Saved by App Creator"}"""
        ).response.status).isEqualTo(200)

        val publish = creator.post(
            "${api(workspaceId, projectId)}/publish",
            """{"visibility":"PRIVATE","expectedRevision":1}""",
            "Idempotency-Key" to "h-c1-04-editor-denied"
        )
        assertThat(publish.response.status).isEqualTo(403)
    }

    @Test
    fun `I stale or removed memberships and deleted projects are not disclosed, and the response carries no secret`() {
        val owner = fx.user("h04-owner-i"); val w = fx.workspace(); fx.member(w, owner, "WORKSPACE_ADMIN"); val ownerSession = sessionFor(owner.username)
        val kept = fx.project(w, owner, "Kept"); val removed = fx.project(w, owner, "Removed"); val deleted = fx.project(w, owner, "Deleted"); val inactive = fx.project(w, owner, "Inactive member")
        val user = fx.user("h04-stale"); fx.member(w, user, "VIEWER")
        for (p in listOf(kept, removed, deleted, inactive)) fx.projectRole(p, user, "EDITOR")
        val s = sessionFor(user.username)
        assertThat(scopes(s).map { it.get("projectId").asString() }).containsExactlyInAnyOrder(kept.id.toString(), removed.id.toString(), deleted.id.toString(), inactive.id.toString())

        assertThat(ownerSession.delete("${api(w, removed.id)}/members/${user.id}").response.status).isEqualTo(204)                      // removed through the API
        jdbc.update("UPDATE project_members SET active = false WHERE project_id = ? AND user_id = ?", inactive.id, user.id)             // deactivated
        jdbc.update("UPDATE projects SET active = false WHERE id = ?", deleted.id)                                                      // project deleted
        assertThat(scopes(s).map { it.get("projectId").asString() }).describedAs("only the live membership remains").containsExactly(kept.id.toString())
        for (gone in listOf(removed, deleted, inactive)) assertThat(s.get(api(w, gone.id)).response.status).describedAs("direct API agrees with /auth/me").isEqualTo(404)

        val raw = s.get("/api/v1/auth/me").response.contentAsString.lowercase()
        for (secret in listOf("password", "argon2", "hash", "token", "secret")) assertThat(raw).describedAs("/auth/me body must not contain '$secret'").doesNotContain(secret)
    }

    @Test
    fun `J a user without any workspace or project membership gets nothing, and one project membership never leaks into another user's response`() {
        val owner = fx.user("h04-owner-j"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val member = fx.user("h04-member-j"); fx.member(w, member, "VIEWER"); fx.projectRole(p, member, "EDITOR")
        val nobody = fx.user("h04-nobody")
        val ns = sessionFor(nobody.username)
        assertThat(me(ns).get("workspaces").size()).isZero(); assertThat(scopes(ns)).isEmpty()
        assertThat(ns.get(api(w, p.id)).response.status).isEqualTo(404)
        assertThat(scopes(sessionFor(member.username)).map { it.get("projectId").asString() }).containsExactly(p.id.toString())
        // the owner's own scope is its own: OWNER keeps the owner set, and a second user never receives it
        val ownerScopes = scopes(sessionFor(owner.username)).filter { it.get("projectId").asString() == p.id.toString() }
        assertThat(ownerScopes).hasSize(1); assertThat(perms(ownerScopes.single())).contains("APP_VIEW", "APP_EDIT", "APP_PUBLISH")
        assertThat(perms(scope(sessionFor(member.username), p.id))).doesNotContain("APP_PUBLISH")
    }

    @Test
    fun `K a project membership whose workspace membership is no longer active is omitted instead of failing the whole response`() {
        val owner = fx.user("h04-owner-k"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val gone = fx.project(w, owner, "Gone"); val live = fx.project(w, owner, "Live")
        val user = fx.user("h04-ws-gone"); fx.member(w, user, "VIEWER"); fx.projectRole(gone, user, "EDITOR")
        val w2 = fx.workspace(); fx.member(w2, owner, "EDITOR"); val other = fx.project(w2, owner, "Other"); fx.member(w2, user, "VIEWER"); fx.projectRole(other, user, "VIEWER")
        val s = sessionFor(user.username)
        assertThat(scopes(s).map { it.get("projectId").asString() }).containsExactlyInAnyOrder(gone.id.toString(), other.id.toString())
        // the project row stays "active", but the person is out of its workspace: AccessService.forProject refuses (404), so /auth/me must not list it - and must still answer 200
        jdbc.update("UPDATE workspace_members SET active = false WHERE workspace_id = ? AND user_id = ?", w, user.id)
        val response = s.get("/api/v1/auth/me")
        assertThat(response.response.status).isEqualTo(200)
        assertThat(s.body(response).get("projectScopes").toList().map { it.get("projectId").asString() }).containsExactly(other.id.toString())
        assertThat(s.get(api(w, gone.id)).response.status).describedAs("the project API agrees").isEqualTo(404)
        assertThat(live.id).isNotEqualTo(gone.id)
    }
}
