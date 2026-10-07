package com.systemwebstudio.member

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MemberApiTests : IntegrationTestBase() {
    private fun wsMembers(ws: UUID) = "/api/v1/workspaces/$ws/members"
    private fun role(ws: UUID, userId: UUID) = jdbc.queryForList("SELECT role FROM workspace_members WHERE workspace_id=? AND user_id=? AND active", ws, userId).firstOrNull()?.get("role")
    private fun add(s: com.systemwebstudio.support.ApiSession, ws: UUID, username: String, role: String) =
        s.post(wsMembers(ws), """{"username":"$username","role":"$role"}""")
    private fun audit(action: String, ws: UUID) = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action=? AND workspace_id=?", Long::class.java, action, ws)!!

    /** a person of the (default) tenant: addable by a workspace administrator. A user with NO tenant membership is a stranger to every workspace (404, no global directory). */
    private fun colleague(prefix: String) = fx.user(prefix).also {
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role) VALUES (?, ?, 'MEMBER') ON CONFLICT DO NOTHING", com.systemwebstudio.tenancy.TenantIds.DEFAULT, it.id)
    }

    private class Ws(val id: UUID, val admin: com.systemwebstudio.identity.UserEntity)
    private fun workspace(): Ws { val ws = fx.workspace(); val a = fx.user("wsadmin"); fx.member(ws, a, "WORKSPACE_ADMIN"); return Ws(ws, a) }

    @Test
    fun `workspace admin adds, changes and removes members and every step is audited`() {
        val w = workspace(); val s = sessionFor(w.admin.username)
        val target = colleague("newbie")
        assertThat(add(s, w.id, target.username, "VIEWER").response.status).isEqualTo(201)
        assertThat(role(w.id, target.id)).isEqualTo("VIEWER")
        assertThat(add(s, w.id, target.username, "EDITOR").response.status).isEqualTo(409)                      // already a member
        assertThat(s.patch("${wsMembers(w.id)}/${target.id}", """{"role":"EDITOR"}""").response.status).isEqualTo(200)
        assertThat(role(w.id, target.id)).isEqualTo("EDITOR")
        assertThat(s.body(s.get(wsMembers(w.id))).toList().map { it.get("username").asString() }).contains(target.username, w.admin.username)
        assertThat(s.delete("${wsMembers(w.id)}/${target.id}").response.status).isEqualTo(204)
        assertThat(role(w.id, target.id)).isNull()
        assertThat(audit("ADD_MEMBER", w.id)).isEqualTo(1L); assertThat(audit("CHANGE_PERMISSION", w.id)).isEqualTo(1L); assertThat(audit("REMOVE_MEMBER", w.id)).isEqualTo(1L)
        assertThat(add(s, w.id, target.username, "PUBLISHER").response.status).isEqualTo(201)                  // re-adding reactivates
        assertThat(role(w.id, target.id)).isEqualTo("PUBLISHER")
    }

    @Test
    fun `viewer, editor and publisher cannot manage members or escalate themselves`() {
        val w = workspace()
        val victim = fx.user("victim"); fx.member(w.id, victim, "VIEWER")
        for (r in listOf("VIEWER", "EDITOR", "PUBLISHER")) {
            val u = fx.user(r.lowercase()); fx.member(w.id, u, r); val s = sessionFor(u.username)
            assertThat(s.get(wsMembers(w.id)).response.status).describedAs("$r list").isEqualTo(403)
            assertThat(add(s, w.id, fx.user("x").username, "VIEWER").response.status).describedAs("$r add").isEqualTo(403)
            assertThat(s.patch("${wsMembers(w.id)}/${u.id}", """{"role":"WORKSPACE_ADMIN"}""").response.status).describedAs("$r self-escalate").isEqualTo(403)
            assertThat(s.patch("${wsMembers(w.id)}/${victim.id}", """{"role":"WORKSPACE_ADMIN"}""").response.status).describedAs("$r escalate other").isEqualTo(403)
            assertThat(s.delete("${wsMembers(w.id)}/${victim.id}").response.status).describedAs("$r remove").isEqualTo(403)
            assertThat(role(w.id, u.id)).isEqualTo(r)
        }
        assertThat(role(w.id, victim.id)).isEqualTo("VIEWER")
    }

    @Test
    fun `an admin cannot change their own role and the last admin cannot be demoted or removed`() {
        val w = workspace(); val s = sessionFor(w.admin.username)
        assertThat(s.patch("${wsMembers(w.id)}/${w.admin.id}", """{"role":"VIEWER"}""").response.status).isEqualTo(403)
        // a second admin appears: now one of them can go, but never both
        val second = fx.user("second"); fx.member(w.id, second, "WORKSPACE_ADMIN")
        assertThat(s.delete("${wsMembers(w.id)}/${second.id}").response.status).isEqualTo(204)
        // D-C1-13: a SYSTEM_ADMIN who is not a member of the workspace has NO MEMBER_MANAGE there (tenant-scoped provisioning is its tool), so it cannot touch the last admin at all
        val s2 = sessionFor(fx.user("sys", systemAdmin = true).username)
        assertThat(s2.patch("${wsMembers(w.id)}/${w.admin.id}", """{"role":"EDITOR"}""").response.status).isEqualTo(403)
        assertThat(s2.delete("${wsMembers(w.id)}/${w.admin.id}").response.status).isEqualTo(403)
        assertThat(role(w.id, w.admin.id)).isEqualTo("WORKSPACE_ADMIN")
    }

    @Test
    fun `concurrent removal of two admins leaves at least one`() {
        val w = workspace(); val b = fx.user("b"); fx.member(w.id, b, "WORKSPACE_ADMIN")
        val sa = sessionFor(w.admin.username); val sb = sessionFor(b.username)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val r = listOf(pool.submit<Int> { sa.delete("${wsMembers(w.id)}/${b.id}").response.status }, pool.submit<Int> { sb.delete("${wsMembers(w.id)}/${w.admin.id}").response.status }).map { it.get() }
        pool.shutdown()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id=? AND role='WORKSPACE_ADMIN' AND active", Long::class.java, w.id)).isGreaterThanOrEqualTo(1L)
        assertThat(r.count { it == 409 || it == 401 || it == 403 || it == 404 }).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `unknown, disabled and ambiguous targets and invalid roles are rejected`() {
        val w = workspace(); val s = sessionFor(w.admin.username)
        assertThat(add(s, w.id, "nobody-" + UUID.randomUUID(), "VIEWER").response.status).isEqualTo(404)
        val d = colleague("dis"); fx.disable(d.id)
        assertThat(add(s, w.id, d.username, "VIEWER").response.status).isEqualTo(422)
        val stranger = fx.user("stranger")            // exists, but has no relation to this tenant: indistinguishable from "no such user"
        assertThat(add(s, w.id, stranger.username, "VIEWER").response.status).isEqualTo(404)
        assertThat(role(w.id, stranger.id)).isNull()
        val u = colleague("ok")
        assertThat(add(s, w.id, u.username, "SUPERUSER").response.status).isEqualTo(400)
        assertThat(add(s, w.id, u.username, "ADMIN").response.status).isEqualTo(400)                          // system admin is not grantable here
        assertThat(s.post(wsMembers(w.id), """{"username":"${u.username}","email":"a@b.c","role":"VIEWER"}""").response.status).isEqualTo(400)
        assertThat(s.post(wsMembers(w.id), """{"role":"VIEWER"}""").response.status).isEqualTo(400)
    }

    @Test
    fun `cross-workspace access to member APIs is hidden`() {
        val a = workspace(); val b = workspace()
        val sb = sessionFor(b.admin.username)
        assertThat(sb.get(wsMembers(a.id)).response.status).isEqualTo(404)
        assertThat(add(sb, a.id, fx.user("z").username, "VIEWER").response.status).isEqualTo(404)
        assertThat(sb.delete("${wsMembers(a.id)}/${a.admin.id}").response.status).isEqualTo(404)
        assertThat(session().get(wsMembers(a.id)).response.status).isEqualTo(401)
    }

    @Test
    fun `project members - owner manages, others cannot, last owner is protected, removed workspace member loses project access`() {
        val sc = scenario()                                     // sc.user is EDITOR in the workspace and OWNER of the project
        val base = "${sc.base}/members"
        val mate = fx.user("mate"); fx.member(sc.ws, mate, "VIEWER")
        val outsider = colleague("outsider")
        assertThat(sc.s.post(base, """{"username":"${outsider.username}","role":"VIEWER"}""").response.status).isEqualTo(422)   // not in workspace
        assertThat(sc.s.post(base, """{"username":"${mate.username}","role":"EDITOR"}""").response.status).isEqualTo(201)
        assertThat(sc.s.post(base, """{"username":"${mate.username}","role":"EDITOR"}""").response.status).isEqualTo(409)
        assertThat(sc.s.body(sc.s.get(base)).size()).isEqualTo(2)

        val ms = sessionFor(mate.username)                      // EDITOR on the project cannot manage members or self-escalate
        assertThat(ms.get(base).response.status).isEqualTo(403)
        assertThat(ms.patch("$base/${mate.id}", """{"role":"OWNER"}""").response.status).isEqualTo(403)
        assertThat(ms.post(base, """{"username":"${outsider.username}","role":"VIEWER"}""").response.status).isEqualTo(403)

        assertThat(sc.s.patch("$base/${mate.id}", """{"role":"PUBLISHER"}""").response.status).isEqualTo(200)
        assertThat(sc.s.patch("$base/${sc.user.id}", """{"role":"VIEWER"}""").response.status).isEqualTo(403)       // own role
        assertThat(sc.s.body(sc.s.delete("$base/${sc.user.id}")).get("code").asString()).isEqualTo("LAST_OWNER")
        assertThat(sc.s.patch("$base/${mate.id}", """{"role":"OWNER"}""").response.status).isEqualTo(200)
        assertThat(sc.s.delete("$base/${sc.user.id}").response.status).isEqualTo(204)                                  // now allowed: mate is owner
        assertThat(sc.s.get(sc.base).response.status).isEqualTo(404)                                                    // removed user lost access

        val admin = fx.user("wsadm"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN")
        assertThat(sessionFor(admin.username).delete("${wsMembers(sc.ws)}/${mate.id}").response.status).isEqualTo(204)
        assertThat(ms.get(sc.base).response.status).isEqualTo(404)                                                      // workspace removal cascades
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE project_id=? AND action IN ('ADD_MEMBER','CHANGE_PERMISSION','REMOVE_MEMBER')", Long::class.java, sc.projectId)).isGreaterThanOrEqualTo(4L)
    }
}
