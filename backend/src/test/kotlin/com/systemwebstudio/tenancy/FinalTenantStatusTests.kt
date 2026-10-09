package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MvcResult

/** C1 final regression - the company itself: rename (TENANT_MANAGE) and the SUSPENDED / DELETED status gates, for the Tenant Admin, members and a SYSTEM_ADMIN member. */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinalTenantStatusTests : FinalIamTestBase() {
    private fun tenantRow(c: Company) = jdbc.queryForMap("SELECT slug, name, status FROM tenants WHERE id = ?", c.id)
    private fun assertSuspended(r: MvcResult, s: ApiSession, what: String) {
        assertThat(r.response.status).describedAs(what).isEqualTo(403)
        assertThat(errorCode(s, r)).describedAs(what).isEqualTo("TENANT_SUSPENDED")
    }

    // ------------------------------------------------------------------------------------------------ 5. rename
    @Test
    fun `5 tenant rename - Tenant Admin and SYSTEM_ADMIN 200 with audit and immutable slug, member 403, foreign admin 404, invalid names 400, same name writes no audit`() {
        val sys = sysAdmin(); val c = company(sys); val other = company(sys)
        val r = c.admin.patch(base(c), """{"name":"  Renamed Co  "}""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(c.admin.body(r).get("name").asString()).isEqualTo("Renamed Co"); assertThat(c.admin.body(r).get("slug").asString()).isEqualTo(c.slug)
        assertThat(c.admin.body(c.admin.get(base(c))).get("name").asString()).describedAs("GET reflects").isEqualTo("Renamed Co")
        assertThat(tenantRow(c)["slug"]).isEqualTo(c.slug)
        assertThat(auditCount("TENANT_UPDATED", c.id)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_events WHERE action = 'TENANT_UPDATED' AND resource_id = ?", java.util.UUID::class.java, c.id.toString())).isEqualTo(c.adminId)

        // idempotent: the same name (even padded) is 200 and writes no second row
        assertThat(c.admin.patch(base(c), """{"name":"Renamed Co"}""").response.status).isEqualTo(200)
        assertThat(c.admin.patch(base(c), """{"name":" Renamed Co "}""").response.status).isEqualTo(200)
        assertThat(auditCount("TENANT_UPDATED", c.id)).isEqualTo(1L)

        // a plain member of the company: 403; another company's admin: 404 (existence not disclosed); nothing changes
        val e = newEmployee(c); val es = activateAndLogin(e.get("employee").get("username").asString(), e.get("activation").get("token").asString())
        assertThat(es.patch(base(c), """{"name":"By member"}""").response.status).isEqualTo(403)
        val foreign = other.admin.patch(base(c), """{"name":"By stranger"}""")
        assertThat(foreign.response.status).isEqualTo(404); assertThat(errorCode(other.admin, foreign)).isEqualTo("TENANT_NOT_FOUND")
        assertThat(tenantRow(c)["name"]).isEqualTo("Renamed Co")

        // invalid names: 400 TENANT_NAME_INVALID, no audit
        for (bad in listOf("", "   ", "x".repeat(161))) {
            val b = c.admin.patch(base(c), """{"name":"$bad"}""")
            assertThat(b.response.status).describedAs("name of length ${bad.length}").isEqualTo(400); assertThat(errorCode(c.admin, b)).isEqualTo("TENANT_NAME_INVALID")
        }
        assertThat(c.admin.patch(base(c), "{}").response.status).describedAs("missing name").isEqualTo(400)
        assertThat(tenantRow(c)["name"]).isEqualTo("Renamed Co"); assertThat(auditCount("TENANT_UPDATED", c.id)).isEqualTo(1L)
        assertThat(c.admin.patch(base(c), """{"name":"${"y".repeat(160)}"}""").response.status).describedAs("160 characters is the limit").isEqualTo(200)

        // the platform operator
        val bySys = sys.patch(base(c), """{"name":"Operator Named"}""")
        assertThat(bySys.response.status).isEqualTo(200); assertThat(tenantRow(c)["name"]).isEqualTo("Operator Named"); assertThat(tenantRow(c)["slug"]).isEqualTo(c.slug)
        assertThat(auditCount("TENANT_UPDATED", c.id)).isEqualTo(3L)
        assertThat(tenantRow(other)["name"]).isEqualTo("Company ${other.slug}")
    }

    // ------------------------------------------------------------------------------------------------ 6. suspended / deleted
    @Test
    fun `6a SUSPENDED company - Tenant Admin reads 200 and every organization write 403 TENANT_SUSPENDED, members locked out with empty permissions, reactivation restores writes`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val t = type(c, "dept"); val u = unit(c, t, "D1")
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"P1","name":"P"}""")); val gr = c.admin.body(c.admin.post(grades(c), """{"code":"G1","name":"G"}"""))
        val e = newEmployee(c); val eid = uid(e)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val wa = fx.user("fin-wa"); fx.member(ws, wa, "WORKSPACE_ADMIN"); fx.projectRole(p, wa, "EDITOR"); val was = sessionFor(wa.username)
        assertThat(strings(workspaceRow(me(was), ws).get("permissions"))).contains("APP_VIEW", "MEMBER_MANAGE")

        assertThat(sys.patch("${base(c)}/status", """{"status":"SUSPENDED"}""").response.status).isEqualTo(200)

        // Tenant Admin: reads stay 200
        for (path in listOf(units(c), "${units(c)}/${id(u)}", types(c), employees(c), "${employees(c)}/$eid", positions(c), grades(c), base(c)))
            assertThat(c.admin.get(path).response.status).describedAs("read $path").isEqualTo(200)
        // ... every write 403 TENANT_SUSPENDED
        assertSuspended(c.admin.post(types(c), """{"code":"team","name":"Team"}"""), c.admin, "POST type")
        assertSuspended(c.admin.patch("${types(c)}/${id(t)}", """{"name":"Dept2","expectedVersion":${ver(t)}}"""), c.admin, "PATCH type")
        assertSuspended(c.admin.post(units(c), """{"typeId":"${id(t)}","code":"D2","name":"d2"}"""), c.admin, "POST unit")
        assertSuspended(c.admin.patch("${units(c)}/${id(u)}", """{"name":"renamed","expectedVersion":${ver(u)}}"""), c.admin, "PATCH unit")
        assertSuspended(c.admin.post("${units(c)}/${id(u)}/archive", """{"expectedVersion":${ver(u)}}"""), c.admin, "archive unit")
        assertSuspended(c.admin.post(employees(c), """{"username":"${uname("emp")}","displayName":"x"}"""), c.admin, "POST employee")
        assertSuspended(c.admin.post("${employees(c)}/$eid/disable"), c.admin, "disable employee")
        assertSuspended(c.admin.post("${employees(c)}/$eid/organization-memberships", """{"organizationUnitId":"${id(u)}"}"""), c.admin, "assign employee")
        assertSuspended(c.admin.post(positions(c), """{"code":"P2","name":"P2"}"""), c.admin, "POST position")
        assertSuspended(c.admin.patch("${positions(c)}/${id(pos)}", """{"name":"P1b","expectedVersion":${ver(pos)}}"""), c.admin, "PATCH position")
        assertSuspended(c.admin.post(grades(c), """{"code":"G2","name":"G2"}"""), c.admin, "POST grade")
        assertSuspended(c.admin.patch("${grades(c)}/${id(gr)}", """{"name":"G1b","expectedVersion":${ver(gr)}}"""), c.admin, "PATCH grade")
        assertThat(flat(c).map { it.get("code").asString() }).containsExactly("D1"); assertThat(unitNow(c, u).get("name").asString()).isEqualTo("D1")
        assertThat(employee(c, eid).get("active").asBoolean()).isTrue()

        // an ordinary member: workspace API 403 TENANT_SUSPENDED, /auth/me lists the workspace with NO permission and no project scope there
        assertSuspended(was.get(api(ws)), was, "workspace project list")
        assertSuspended(was.get(api(ws, p.id)), was, "project")
        assertSuspended(was.get("/api/v1/workspaces/$ws/members"), was, "workspace members")
        val m = me(was)
        assertThat(strings(workspaceRow(m, ws).get("permissions"))).isEmpty(); assertThat(scopeIds(m)).doesNotContain(p.id.toString())

        // reactivation: writes work again, members get their permissions back
        assertThat(sys.patch("${base(c)}/status", """{"status":"ACTIVE"}""").response.status).isEqualTo(200)
        assertThat(c.admin.post(units(c), """{"typeId":"${id(t)}","code":"D2","name":"d2"}""").response.status).isEqualTo(201)
        assertThat(c.admin.patch("${positions(c)}/${id(pos)}", """{"name":"P1b","expectedVersion":${ver(pos)}}""").response.status).isEqualTo(200)
        assertThat(c.admin.post(grades(c), """{"code":"G2","name":"G2"}""").response.status).isEqualTo(201)
        assertThat(c.admin.post(employees(c), """{"username":"${uname("emp")}","displayName":"x"}""").response.status).isEqualTo(201)
        assertThat(was.get(api(ws, p.id)).response.status).isEqualTo(200)
        val back = me(was)
        assertThat(strings(workspaceRow(back, ws).get("permissions"))).contains("APP_VIEW", "MEMBER_MANAGE"); assertThat(scopeIds(back)).contains(p.id.toString())
    }

    @Test
    fun `6b a SYSTEM_ADMIN that is a workspace member is refused its workspace routes in a SUSPENDED (403) and a DELETED (404) company`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val op = fx.user("fin-op", systemAdmin = true); fx.member(ws, op, "WORKSPACE_ADMIN"); val ops = sessionFor(op.username)
        assertThat(ops.get(api(ws, p.id)).response.status).describedAs("its member role while ACTIVE").isEqualTo(200)
        assertThat(ops.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(200)

        assertThat(sys.patch("${base(c)}/status", """{"status":"SUSPENDED"}""").response.status).isEqualTo(200)
        assertSuspended(ops.get(api(ws)), ops, "SYSTEM_ADMIN member: project list")
        assertSuspended(ops.get(api(ws, p.id)), ops, "SYSTEM_ADMIN member: project")
        assertSuspended(ops.get("/api/v1/workspaces/$ws/members"), ops, "SYSTEM_ADMIN member: members")
        assertSuspended(rename(ops, ws, p.id, 0, "x"), ops, "SYSTEM_ADMIN member: write")

        assertThat(sys.patch("${base(c)}/status", """{"status":"DELETED"}""").response.status).isEqualTo(200)
        assertThat(ops.get(api(ws)).response.status).isEqualTo(404)
        assertThat(ops.get(api(ws, p.id)).response.status).isEqualTo(404)
        assertThat(ops.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(404)
        assertThat(rename(ops, ws, p.id, 0, "x").response.status).isEqualTo(404)
        assertThat(ops.get(units(c)).response.status).describedAs("no organization data of a deleted company either").isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String::class.java, p.id)).isEqualTo("Project")
    }

    @Test
    fun `6c a SYSTEM_ADMIN whose TENANT membership is removed keeps nothing of its workspace membership - platform scope only, in the API and in auth me`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own2"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val op = fx.user("fin-op2", systemAdmin = true); fx.member(ws, op, "WORKSPACE_ADMIN"); val ops = sessionFor(op.username)
        assertThat(ops.get(api(ws, p.id)).response.status).describedAs("member authority while its tenant membership is active").isEqualTo(200)
        assertThat(strings(workspaceRow(me(ops), ws).get("permissions"))).contains("APP_VIEW", "MEMBER_MANAGE")
        // the Tenant Admin of the company cuts the operator off (it is a plain tenant member of this company)
        assertThat(c.admin.delete("${base(c)}/members/${op.id}").response.status).isIn(200, 204)
        assertThat(ops.get(api(ws, p.id)).response.status).describedAs("the workspace membership no longer grants anything").isEqualTo(404)
        assertThat(ops.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(404)
        val m = me(ops)
        assertThat(m.get("projectScopes").toList().map { it.get("projectId").asString() }).doesNotContain(p.id.toString())
        assertThat(strings(workspaceRow(m, ws).get("permissions"))).describedAs("platform scope only").doesNotContain("APP_VIEW", "MEMBER_MANAGE", "PROJECT_CREATE")
        assertThat(m.get("systemAdmin").asBoolean()).isTrue()
    }
}
