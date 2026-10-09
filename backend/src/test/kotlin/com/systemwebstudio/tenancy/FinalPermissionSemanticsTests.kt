package com.systemwebstudio.tenancy

import com.systemwebstudio.access.PermissionCodes
import com.systemwebstudio.access.PermissionMatrix
import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * C1 final regression - every authority is scoped and independent: Tenant Admin / Workspace Admin / SYSTEM_ADMIN reach only their own scope; project roles are
 * separate capabilities (view, edit, publish); organization facts (MANAGER / HEAD relation, position, grade) grant nothing in the Studio.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinalPermissionSemanticsTests : FinalIamTestBase() {
    private fun orgReads(c: Company) = listOf(units(c), types(c), employees(c), positions(c), grades(c), base(c) + "/members")
    private fun orgWrites(c: Company) = listOf(
        types(c) to """{"code":"x${UUID.randomUUID().toString().take(6)}","name":"X"}""", positions(c) to """{"code":"P1","name":"P"}""",
        grades(c) to """{"code":"G1","name":"G"}""", employees(c) to """{"username":"${uname("nope")}","displayName":"Nope"}""")

    // ------------------------------------------------------------------------------------------------ 3. authority never crosses its scope
    @Test
    fun `3a a Tenant Admin has no cross-tenant authority (foreign tenant 404) and a same-scope permission it lacks is 403`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val wsB = wsIn(b)
        for (path in orgReads(b) + base(b)) assertThat(a.admin.get(path).response.status).describedAs("A's admin GET $path").isEqualTo(404)
        for ((path, body) in orgWrites(b)) assertThat(a.admin.post(path, body).response.status).describedAs("A's admin POST $path").isEqualTo(404)
        val foreignRename = a.admin.patch(base(b), """{"name":"Hijacked"}""")
        assertThat(foreignRename.response.status).isEqualTo(404); assertThat(errorCode(a.admin, foreignRename)).isEqualTo("TENANT_NOT_FOUND")
        assertThat(a.admin.post("${base(b)}/users", """{"username":"${uname("x")}","displayName":"x"}""").response.status).isEqualTo(404)
        assertThat(a.admin.get(api(wsB)).response.status).describedAs("B's workspace").isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String::class.java, b.id)).isEqualTo("Company ${b.slug}")
        // same scope, missing permission: platform operations on its OWN tenant
        assertThat(a.admin.patch("${base(a)}/status", """{"status":"SUSPENDED"}""").response.status).isEqualTo(403)
        assertThat(a.admin.get("/api/v1/admin/tenants").response.status).isEqualTo(403)
        assertThat(a.admin.post("/api/v1/admin/tenants", """{"slug":"${uname("t")}","name":"x"}""").response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String::class.java, a.id)).isEqualTo("ACTIVE")
        // and a plain member of the SAME tenant lacks the organization capabilities: 403, not 404
        val e = newEmployee(a); val es = activateAndLogin(e.get("employee").get("username").asString(), e.get("activation").get("token").asString())
        for (path in orgReads(a)) assertThat(es.get(path).response.status).describedAs("plain member GET $path").isEqualTo(403)
        assertThat(es.get(base(a)).response.status).describedAs("its own tenant is visible").isEqualTo(200)
    }

    @Test
    fun `3b a Workspace Admin has no organization authority, a SYSTEM_ADMIN has only the platform scope`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val wa = fx.user("fin-wa"); fx.member(ws, wa, "WORKSPACE_ADMIN"); val was = sessionFor(wa.username)
        assertThat(was.get("/api/v1/workspaces/$ws/members").response.status).describedAs("its own scope works").isEqualTo(200)
        for (path in orgReads(c)) assertThat(was.get(path).response.status).describedAs("WORKSPACE_ADMIN GET $path").isEqualTo(403)
        for ((path, body) in orgWrites(c)) assertThat(was.post(path, body).response.status).describedAs("WORKSPACE_ADMIN POST $path").isEqualTo(403)
        assertThat(was.patch(base(c), """{"name":"Taken over"}""").response.status).isEqualTo(403)
        assertThat(strings(me(was).get("permissions"))).isEmpty()

        for (path in listOf(units(c), types(c), employees(c), positions(c), grades(c))) assertThat(sys.get(path).response.status).describedAs("SYSTEM_ADMIN GET $path").isEqualTo(403)
        for ((path, body) in orgWrites(c)) assertThat(sys.post(path, body).response.status).describedAs("SYSTEM_ADMIN POST $path").isEqualTo(403)
        val m = me(sys)
        assertThat(strings(m.get("permissions"))).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(m.get("platformScope").asBoolean()).isTrue(); assertThat(m.get("businessAccess").asBoolean()).isFalse()
        assertThat(scopeIds(m)).isEmpty()
        assertThat(strings(workspaceRow(m, ws).get("permissions"))).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(sys.get(base(c) + "/members").response.status).describedAs("the platform operation itself").isEqualTo(200)
    }

    // ------------------------------------------------------------------------------------------------ 4. permission independence
    @Test
    fun `4a the real role matrix keeps view, use, edit, publish and mutate independent`() {
        fun codes(role: String) = PermissionCodes.canonicalCodesOf(PermissionMatrix.projectRoles.getValue(role)).toSet()
        assertThat(codes("VIEWER")).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(codes("EDITOR")).contains("APP_VIEW", "APP_USE", "APP_EDIT").doesNotContain("APP_PUBLISH", "DATA_MUTATE")
        assertThat(codes("PUBLISHER")).contains("APP_VIEW", "APP_USE", "APP_PUBLISH").doesNotContain("APP_EDIT", "DATA_MUTATE")
        // no organization relation / position / grade is a role of the matrix: only the Tenant Admin role carries organization capabilities, and it carries no APP_*
        assertThat(PermissionCodes.canonicalCodesOf(PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN"))).noneMatch { it.startsWith("APP_") }
        assertThat(PermissionMatrix.tenantRoles.getValue("MEMBER")).isEmpty()
        assertThat(PermissionMatrix.tenantRoles.keys).containsExactlyInAnyOrder("TENANT_ADMIN", "MEMBER")
    }

    @Test
    fun `4b project roles over HTTP - VIEWER cannot edit, EDITOR edits but cannot publish, PUBLISHER cannot edit`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        fun member(role: String): ApiSession { val u = fx.user("fin-${role.lowercase()}"); fx.member(ws, u, "VIEWER"); fx.projectRole(p, u, role); return sessionFor(u.username) }
        val viewer = member("VIEWER"); val editor = member("EDITOR"); val publisher = member("PUBLISHER")

        assertThat(scopePerms(me(viewer), p.id)).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(scopePerms(me(editor), p.id)).contains("APP_VIEW", "APP_USE", "APP_EDIT").doesNotContain("APP_PUBLISH", "DATA_MUTATE")
        assertThat(scopePerms(me(publisher), p.id)).contains("APP_VIEW", "APP_USE", "APP_PUBLISH").doesNotContain("APP_EDIT", "DATA_MUTATE")
        for (s in listOf(viewer, editor, publisher)) {
            assertThat(s.get(api(ws, p.id)).response.status).isEqualTo(200)
            val m = me(s)
            assertThat(strings(m.get("permissions"))).describedAs("project roles never reach the top level").isEmpty()
            assertThat(strings(workspaceRow(m, ws).get("permissions"))).describedAs("nor the workspace row").isEmpty()
        }

        assertThat(rename(viewer, ws, p.id, revision(viewer, ws, p.id), "viewer").response.status).isEqualTo(403)
        assertThat(rename(publisher, ws, p.id, revision(publisher, ws, p.id), "publisher").response.status).isEqualTo(403)
        assertThat(rename(editor, ws, p.id, revision(editor, ws, p.id), "editor").response.status).isEqualTo(200)
        assertThat(publish(editor, ws, p.id, revision(editor, ws, p.id), "fin-editor-" + UUID.randomUUID()).response.status).describedAs("EDITOR publish").isEqualTo(403)
        assertThat(publish(viewer, ws, p.id, revision(viewer, ws, p.id), "fin-viewer-" + UUID.randomUUID()).response.status).describedAs("VIEWER publish").isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Long::class.java, p.id)).isZero()
        assertThat(editor.body(editor.get(api(ws, p.id))).get("name").asString()).isEqualTo("editor")
    }

    @Test
    fun `4c MANAGER and HEAD relations, a position and a grade give no APP permission, no project visibility and no organization authority`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val t = type(c, "dept"); val u1 = unit(c, t, "D1"); val u2 = unit(c, t, "D2")
        val e = newEmployee(c, extra = ""","workspaceId":"$ws","workspaceRole":"VIEWER""""); val eid = uid(e)
        val es = activateAndLogin(e.get("employee").get("username").asString(), e.get("activation").get("token").asString())
        val before = me(es)
        assertThat(strings(before.get("permissions"))).isEmpty(); assertThat(scopeIds(before)).isEmpty()

        val manager = c.admin.post("${employees(c)}/$eid/organization-memberships", """{"organizationUnitId":"${id(u1)}","relationType":"MANAGER"}""")
        assertThat(manager.response.status).isEqualTo(201)
        assertThat(c.admin.post("${employees(c)}/$eid/organization-memberships", """{"organizationUnitId":"${id(u2)}","relationType":"HEAD"}""").response.status).isEqualTo(201)
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"CEO","name":"Chief"}""")); val gr = c.admin.body(c.admin.post(grades(c), """{"code":"G10","name":"Top","rank":100}"""))
        assertThat(c.admin.post("${employees(c)}/$eid/positions", """{"membershipId":"${id(c.admin.body(manager))}","positionId":"${id(pos)}","gradeId":"${id(gr)}"}""").response.status).isEqualTo(201)
        val held = employee(c, eid)
        assertThat(held.get("organizationMemberships").toList().map { it.get("relationType").asString() }).containsExactlyInAnyOrder("MANAGER", "HEAD")
        assertThat(held.get("positions").size()).isEqualTo(1)

        val after = me(es)
        assertThat(strings(after.get("permissions"))).describedAs("top-level permissions").isEmpty()
        assertThat(after.get("workspaces")).describedAs("workspaces[] unchanged").isEqualTo(before.get("workspaces"))
        assertThat(after.get("projectScopes")).describedAs("projectScopes unchanged").isEqualTo(before.get("projectScopes"))
        assertThat(strings(workspaceRow(after, ws).get("permissions"))).noneMatch { it.startsWith("APP_") }
        assertThat(es.get(api(ws, p.id)).response.status).describedAs("project of a workspace in the company").isEqualTo(404)
        assertThat(es.body(es.get(api(ws))).size()).describedAs("no project listed").isZero()
        for (path in orgReads(c)) assertThat(es.get(path).response.status).describedAs("org route $path").isEqualTo(403)
        for ((path, body) in orgWrites(c)) assertThat(es.post(path, body).response.status).describedAs("org write $path").isEqualTo(403)
    }
}
