package com.systemwebstudio.tenancy

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** C1 · H-C1-04: /auth/me mirrors effective project authorization without widening global/workspace authority. */
class ProjectScopedAuthMeTests : IntegrationTestBase() {
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
}
