package com.systemwebstudio.tenancy

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** C1 · H-C1-04: /auth/me exposes exact project-scoped canonical permissions without widening workspace/global authority. */
class ProjectScopedAuthMeTests : IntegrationTestBase() {
    private fun me(s: ApiSession) = s.body(s.get("/api/v1/auth/me"))
    private fun scopes(s: ApiSession) = me(s).get("projectScopes").toList()
    private fun scope(s: ApiSession, p: UUID) = scopes(s).single { it.get("projectId").asString() == p.toString() }
    private fun perms(n: tools.jackson.databind.JsonNode) = n.get("permissions").toList().map { it.asString() }.toSet()

    @Test
    fun `A project VIEWER exposes APP_VIEW and APP_USE, opens the project, but edit is denied`() {
        val owner = fx.user("owner-a"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val u = fx.user("viewer-a"); fx.member(w, u, "VIEWER"); fx.projectRole(p, u, "VIEWER")
        val s = sessionFor(u.username)
        assertThat(perms(scope(s, p.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(s.get(api(w, p.id)).response.status).isEqualTo(200)
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"no"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `B project EDITOR exposes resolved edit permissions and save is allowed`() {
        val owner = fx.user("owner-b"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val u = fx.user("editor-b"); fx.member(w, u, "VIEWER"); fx.projectRole(p, u, "EDITOR")
        val s = sessionFor(u.username)
        assertThat(perms(scope(s, p.id))).contains("APP_VIEW", "APP_USE", "APP_EDIT", "DATA_SOURCE_VIEW", "QUERY_EXECUTE", "ACTION_EXECUTE")
        assertThat(perms(scope(s, p.id))).doesNotContain("APP_PUBLISH", "DATA_MUTATE", "WORKFLOW_MANAGE")
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"edited"}""").response.status).isEqualTo(200)
    }

    @Test
    fun `C project PUBLISHER exposes publish but no edit or mutation permission`() {
        val owner = fx.user("owner-c"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val u = fx.user("publisher-c"); fx.member(w, u, "VIEWER"); fx.projectRole(p, u, "PUBLISHER")
        val s = sessionFor(u.username)
        assertThat(perms(scope(s, p.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE", "APP_PUBLISH")
        assertThat(perms(scope(s, p.id))).doesNotContain("APP_EDIT", "DATA_MUTATE", "QUERY_EXECUTE")
        assertThat(s.patch(api(w, p.id), """{"expectedRevision":0,"name":"no"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `D user with no project membership gets no project scope and no project disclosure`() {
        val owner = fx.user("owner-d"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); val p = fx.project(w, owner)
        val u = fx.user("none-d"); fx.member(w, u, "VIEWER")
        val s = sessionFor(u.username)
        assertThat(scopes(s)).isEmpty()
        assertThat(s.get(api(w, p.id)).response.status).isEqualTo(404)
    }

    @Test
    fun `E foreign project is never disclosed in projectScopes`() {
        val a = fx.user("a-e"); val wa = fx.workspace(); fx.member(wa, a, "EDITOR"); val pa = fx.project(wa, a)
        val b = fx.user("b-e"); val wb = fx.workspace(); fx.member(wb, b, "VIEWER"); val pbOwner = fx.user("pb-owner"); fx.member(wb, pbOwner, "EDITOR"); val pb = fx.project(wb, pbOwner)
        fx.projectRole(pb, b, "VIEWER")
        val s = sessionFor(b.username)
        assertThat(scopes(s).map { it.get("projectId").asString() }).containsExactly(pb.id.toString()).doesNotContain(pa.id.toString())
        assertThat(s.get(api(wa, pa.id)).response.status).isEqualTo(404)
    }

    @Test
    fun `F TENANT_ADMIN without project membership gets no automatic Studio project scope`() {
        val u = fx.user("ta-f")
        jdbc.update("INSERT INTO tenant_members (tenant_id,user_id,role,active) VALUES (?,?, 'TENANT_ADMIN', true)", TenantIds.DEFAULT, u.id)
        val s = sessionFor(u.username)
        assertThat(scopes(s)).isEmpty()
        assertThat(me(s).get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
    }

    @Test
    fun `G SYSTEM_ADMIN without project membership gets no automatic project scope or business permission`() {
        val owner = fx.user("owner-g"); val w = fx.workspace(); fx.member(w, owner, "EDITOR"); fx.project(w, owner)
        val sys = fx.user("sys-g", systemAdmin = true); val s = sessionFor(sys.username)
        assertThat(scopes(s)).isEmpty()
        assertThat(me(s).get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        val wr = me(s).get("workspaces").toList().single { it.get("id").asString() == w.toString() }
        assertThat(wr.get("permissions").toList().map { it.asString() }).doesNotContain("APP_VIEW", "APP_EDIT", "APP_PUBLISH")
    }

    @Test
    fun `H multiple project memberships stay isolated per project`() {
        val owner = fx.user("owner-h"); val w = fx.workspace(); fx.member(w, owner, "EDITOR")
        val p1 = fx.project(w, owner, "Viewer Project"); val p2 = fx.project(w, owner, "Editor Project")
        val u = fx.user("multi-h"); fx.member(w, u, "VIEWER"); fx.projectRole(p1, u, "VIEWER"); fx.projectRole(p2, u, "EDITOR")
        val s = sessionFor(u.username)
        assertThat(scopes(s)).hasSize(2)
        assertThat(perms(scope(s, p1.id))).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(perms(scope(s, p2.id))).contains("APP_VIEW", "APP_USE", "APP_EDIT").doesNotContain("APP_PUBLISH")
    }

    @Test
    fun `real provisioning flow yields project-scoped Studio access and preserves publish denial for EDITOR`() {
        val password = "Project-Pass-2026-x"
        fun activate(link: tools.jackson.databind.JsonNode) {
            val r = session().post("/api/v1/auth/activation/complete", """{"token":"${link.get("token").asString()}","password":"$password"}""")
            assertThat(r.response.status).isEqualTo(200)
        }

        val sysUser = fx.user("flow-sys", systemAdmin = true); val sys = sessionFor(sysUser.username)
        val tr = sys.post("/api/v1/admin/tenants", """{"slug":"flow-${UUID.randomUUID().toString().take(8)}","name":"Flow Tenant"}""")
        assertThat(tr.response.status).isEqualTo(201)
        val tid = UUID.fromString(sys.body(tr).get("id").asString())
        val wr = sys.post("/api/v1/admin/tenants/$tid/workspaces", """{"name":"Flow Workspace"}""")
        assertThat(wr.response.status).isEqualTo(201)
        val wid = UUID.fromString(sys.body(wr).get("id").asString())

        val taName = "flow-ta-" + UUID.randomUUID().toString().take(8)
        val taLink = sys.body(sys.post("/api/v1/admin/tenants/$tid/users",
            """{"username":"$taName","displayName":"Flow TA","tenantRole":"TENANT_ADMIN","workspaceId":"$wid","workspaceRole":"WORKSPACE_ADMIN"}"""))
        activate(taLink)
        val ta = session(); assertThat(ta.login(taName, password).response.status).isEqualTo(200)

        val creatorName = "flow-creator-" + UUID.randomUUID().toString().take(8)
        val creatorLink = ta.body(ta.post("/api/v1/admin/tenants/$tid/users",
            """{"username":"$creatorName","displayName":"Flow Creator","tenantRole":"MEMBER","workspaceId":"$wid","workspaceRole":"VIEWER"}"""))
        activate(creatorLink)
        val creator = session(); assertThat(creator.login(creatorName, password).response.status).isEqualTo(200)

        val created = ta.body(ta.post(api(wid), """{"name":"Flow Project"}"""))
        val pid = UUID.fromString(created.get("id").asString())
        assertThat(ta.post("${api(wid, pid)}/members", """{"username":"$creatorName","role":"EDITOR"}""").response.status).isEqualTo(201)

        val ps = scope(creator, pid)
        assertThat(perms(ps)).contains("APP_VIEW", "APP_EDIT").doesNotContain("APP_PUBLISH")
        assertThat(creator.get(api(wid, pid)).response.status).isEqualTo(200)
        assertThat(creator.patch(api(wid, pid), """{"expectedRevision":0,"name":"Saved by creator"}""").response.status).isEqualTo(200)
        val publish = creator.post("${api(wid, pid)}/publish", """{"visibility":"PRIVATE","expectedRevision":1}""", "Idempotency-Key" to "h-c1-04-editor-denied")
        assertThat(publish.response.status).isEqualTo(403)
    }
}
