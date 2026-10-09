package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * C1 final regression - live authority over the account / membership lifecycle: a change made by an administrator takes effect on the NEXT request of a session
 * that is already open (global disable, tenant membership removed / deactivated, project membership removed, project role downgraded, SYSTEM_ADMIN revoked).
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)          // same reason as OrganizationApiContractTests: never keep this context alive next to the publish tests
class FinalIamLifecycleTests : FinalIamTestBase() {

    // ------------------------------------------------------------------------------------------------ 1. global account disable
    @Test
    fun `1a global disable revokes every live session at once, re-enable never resurrects an old session cookie, a Tenant Admin cannot use the global route`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val u = fx.user("fin-dis"); fx.member(ws, u, "VIEWER")
        val first = sessionFor(u.username); val second = sessionFor(u.username)
        assertThat(first.get("/api/v1/auth/me").response.status).isEqualTo(200); assertThat(second.get(api(ws)).response.status).isEqualTo(200)
        val secondCookie = second.cookie(SESSION_COOKIE)!!
        assertThat(replay(secondCookie)).describedAs("positive control: the bare cookie authenticates while the account is enabled").isEqualTo(200)

        // the company's Tenant Admin holds no platform authority: the global status route is 403 and nothing changes
        val byTenantAdmin = c.admin.patch("/api/v1/admin/users/${u.id}/status", """{"enabled":false}""")
        assertThat(byTenantAdmin.response.status).isEqualTo(403)
        assertThat(enabled(u.id)).isTrue()
        assertThat(first.get("/api/v1/auth/me").response.status).describedAs("the refused call left the session alive").isEqualTo(200)

        assertThat(sys.patch("/api/v1/admin/users/${u.id}/status", """{"enabled":false}""").response.status).isEqualTo(200)
        assertThat(enabled(u.id)).describedAs("users.enabled").isFalse()
        assertThat(first.get("/api/v1/auth/me").response.status).describedAs("next request of a live session").isEqualTo(401)
        assertThat(first.get(api(ws)).response.status).isEqualTo(401)
        assertThat(session().login(u.username).response.status).describedAs("a disabled account cannot sign in").isEqualTo(401)

        // re-enable: the second session was never used after the disable - its cookie must still be dead (revoked server-side, not just refused by a filter)
        assertThat(sys.patch("/api/v1/admin/users/${u.id}/status", """{"enabled":true}""").response.status).isEqualTo(200)
        assertThat(enabled(u.id)).isTrue()
        assertThat(replay(secondCookie)).describedAs("old session cookie after re-enable").isEqualTo(401)
        assertThat(replay(secondCookie, api(ws))).isEqualTo(401)
        assertThat(second.get("/api/v1/auth/me").response.status).isEqualTo(401)
        assertThat(first.get("/api/v1/auth/me").response.status).isEqualTo(401)
        val fresh = session()
        assertThat(fresh.login(u.username).response.status).describedAs("only a fresh login works").isEqualTo(200)
        assertThat(fresh.get(api(ws)).response.status).isEqualTo(200)
    }

    @Test
    fun `1b a Tenant Admin disabling an employee ends the company membership only - the account stays enabled and keeps working in another company`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val wsA = wsIn(a, "A"); val wsB = wsIn(b, "B")
        val e = newEmployee(a, extra = ""","workspaceId":"$wsA","workspaceRole":"VIEWER""""); val eid = uid(e); val username = e.get("employee").get("username").asString()
        fx.member(wsB, entity(eid), "VIEWER")                                                              // the same person also works in company B
        val es = activateAndLogin(username, e.get("activation").get("token").asString())
        assertThat(es.get(api(wsA)).response.status).isEqualTo(200); assertThat(es.get(api(wsB)).response.status).isEqualTo(200)
        assertThat(workspaceIds(me(es))).containsExactlyInAnyOrder(wsA.toString(), wsB.toString())

        assertThat(a.admin.post("${employees(a)}/$eid/disable").response.status).isEqualTo(200)
        assertThat(enabled(eid)).describedAs("an employee disable is not a global account disable").isTrue()
        val m = me(es)                                                                                      // still authenticated
        assertThat(workspaceIds(m)).containsExactly(wsB.toString())
        assertThat(tenantIds(m)).contains(b.id.toString()).doesNotContain(a.id.toString())
        assertThat(es.get(api(wsA)).response.status).describedAs("company A is closed at once").isEqualTo(404)
        assertThat(es.get(api(wsB)).response.status).describedAs("company B is untouched").isEqualTo(200)
        assertThat(session().login(username, PASSWORD).response.status).describedAs("the account can still sign in").isEqualTo(200)

        // and company B's Tenant Admin cannot reach the global switch either
        assertThat(b.admin.patch("/api/v1/admin/users/$eid/status", """{"enabled":false}""").response.status).isEqualTo(403)
        assertThat(enabled(eid)).isTrue()
        assertThat(es.get(api(wsB)).response.status).isEqualTo(200)
    }

    // ------------------------------------------------------------------------------------------------ 2. memberships changed under a live session
    @Test
    fun `2a tenant membership REMOVED while logged in - still authenticated, but every tenant-scoped route of that company fails on the next request`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val x = fx.user("fin-x"); fx.member(ws, x, "VIEWER"); fx.projectRole(p, x, "EDITOR")
        assertThat(c.admin.put("${base(c)}/members/${x.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        val xs = sessionFor(x.username)
        assertThat(xs.get(units(c)).response.status).isEqualTo(200); assertThat(xs.get(api(ws, p.id)).response.status).isEqualTo(200)
        val before = me(xs)
        assertThat(workspaceIds(before)).contains(ws.toString()); assertThat(scopeIds(before)).contains(p.id.toString()); assertThat(tenantIds(before)).contains(c.id.toString())
        assertThat(strings(before.get("permissions"))).contains("ORG_STRUCTURE_VIEW", "TENANT_MANAGE")

        assertThat(c.admin.delete("${base(c)}/members/${x.id}").response.status).isEqualTo(204)
        val after = me(xs)
        assertThat(workspaceIds(after)).describedAs("workspaces[]").doesNotContain(ws.toString())
        assertThat(scopeIds(after)).describedAs("projectScopes").doesNotContain(p.id.toString())
        assertThat(tenantIds(after)).doesNotContain(c.id.toString())
        assertThat(strings(after.get("permissions"))).describedAs("no stale tenant permission").isEmpty()
        assertThat(xs.get(api(ws, p.id)).response.status).isEqualTo(404)
        assertThat(xs.get(api(ws)).response.status).isEqualTo(404)
        assertThat(rename(xs, ws, p.id, 0, "stale").response.status).isEqualTo(404)
        assertThat(xs.get(units(c)).response.status).describedAs("organization routes").isEqualTo(404)
        assertThat(xs.get(employees(c)).response.status).isEqualTo(404)
        assertThat(xs.get(base(c)).response.status).isEqualTo(404)
    }

    @Test
    fun `2b tenant membership DEACTIVATED (employee disable) while logged in - workspace and project closed at once, reopened by enable`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val y = fx.user("fin-y"); fx.member(ws, y, "WORKSPACE_ADMIN"); fx.projectRole(p, y, "VIEWER")
        val ys = sessionFor(y.username)
        assertThat(ys.get(api(ws, p.id)).response.status).isEqualTo(200)
        assertThat(scopeIds(me(ys))).containsExactly(p.id.toString())

        assertThat(c.admin.post("${employees(c)}/${y.id}/disable").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Boolean::class.java, c.id, y.id)).isFalse()
        val m = me(ys)
        assertThat(workspaceIds(m)).doesNotContain(ws.toString()); assertThat(scopeIds(m)).isEmpty(); assertThat(tenantIds(m)).doesNotContain(c.id.toString())
        assertThat(ys.get(api(ws, p.id)).response.status).isEqualTo(404)
        assertThat(ys.get(api(ws)).response.status).isEqualTo(404)
        assertThat(ys.get("/api/v1/workspaces/$ws/members").response.status).describedAs("WORKSPACE_ADMIN authority is gone too").isEqualTo(404)

        assertThat(c.admin.post("${employees(c)}/${y.id}/enable").response.status).isEqualTo(200)
        assertThat(ys.get(api(ws, p.id)).response.status).isEqualTo(200)
        assertThat(scopeIds(me(ys))).containsExactly(p.id.toString())
    }

    @Test
    fun `2c project membership removed while logged in - project API 404 and the scope disappears from auth me`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner); val kept = fx.project(ws, owner, "Kept")
        val u = fx.user("fin-pm"); fx.member(ws, u, "VIEWER"); fx.projectRole(p, u, "EDITOR"); fx.projectRole(kept, u, "VIEWER")
        val us = sessionFor(u.username); val os = sessionFor(owner.username)
        assertThat(us.get(api(ws, p.id)).response.status).isEqualTo(200)
        assertThat(scopeIds(me(us))).containsExactlyInAnyOrder(p.id.toString(), kept.id.toString())

        assertThat(os.delete("${api(ws, p.id)}/members/${u.id}").response.status).isEqualTo(204)
        assertThat(us.get(api(ws, p.id)).response.status).isEqualTo(404)
        assertThat(rename(us, ws, p.id, 0, "stale").response.status).isEqualTo(404)
        assertThat(scopeIds(me(us))).containsExactly(kept.id.toString())
        assertThat(us.get(api(ws, kept.id)).response.status).isEqualTo(200)
    }

    @Test
    fun `2d project role downgraded EDITOR to VIEWER while logged in - the very next write is 403 and auth me carries no stale edit scope`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("fin-own"); fx.member(ws, owner, "EDITOR"); val p = fx.project(ws, owner)
        val u = fx.user("fin-dg"); fx.member(ws, u, "VIEWER"); fx.projectRole(p, u, "EDITOR")
        val us = sessionFor(u.username); val os = sessionFor(owner.username)
        assertThat(scopePerms(me(us), p.id)).contains("APP_EDIT")
        assertThat(rename(us, ws, p.id, revision(us, ws, p.id), "as editor").response.status).isEqualTo(200)

        val change = os.patch("${api(ws, p.id)}/members/${u.id}", """{"role":"VIEWER"}""")
        assertThat(change.response.status).isEqualTo(200)
        val rev = revision(us, ws, p.id)
        assertThat(rename(us, ws, p.id, rev, "as viewer").response.status).describedAs("write after downgrade").isEqualTo(403)
        assertThat(scopePerms(me(us), p.id)).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        assertThat(us.body(us.get(api(ws, p.id))).get("name").asString()).isEqualTo("as editor")
        assertThat(revision(us, ws, p.id)).isEqualTo(rev)
    }

    // ------------------------------------------------------------------------------------------------ 7. /auth/me never serves a stale SYSTEM_ADMIN snapshot
    @Test
    fun `7 after SYSTEM_ADMIN is revoked from a live session, auth me shows systemAdmin false, roles exactly USER and only real memberships`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c, "Mine"); val other = wsIn(c, "Not mine")
        val target = fx.user("fin-op", systemAdmin = true); fx.member(ws, target, "VIEWER")
        val ts = sessionFor(target.username)
        val before = me(ts)
        assertThat(before.get("systemAdmin").asBoolean()).isTrue(); assertThat(strings(before.get("roles"))).containsExactlyInAnyOrder("USER", "ADMIN")
        assertThat(workspaceIds(before)).describedAs("a SYSTEM_ADMIN sees every workspace").contains(ws.toString(), other.toString())
        assertThat(ts.get("/api/v1/admin/tenants").response.status).isEqualTo(200)

        assertThat(sys.post("/api/v1/admin/users/${target.id}/system-admin", """{"grant":false,"confirm":true}""").response.status).isEqualTo(200)
        assertThat(systemAdminFlag(target.id)).isFalse()
        val after = me(ts)                                                                                     // same session, no re-login
        assertThat(after.get("systemAdmin").asBoolean()).isFalse()
        assertThat(strings(after.get("roles"))).containsExactly("USER")
        assertThat(workspaceIds(after)).containsExactly(ws.toString())
        assertThat(after.get("platformScope").asBoolean()).isFalse(); assertThat(after.get("businessAccess").asBoolean()).isFalse()
        assertThat(strings(after.get("permissions"))).doesNotContain("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(ts.get("/api/v1/admin/tenants").response.status).describedAs("the platform routes agree with /auth/me").isEqualTo(403)
        assertThat(ts.get("/api/v1/admin/users").response.status).isEqualTo(403)
    }
}
